package edu.truman.moldez.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Messages in this exception are safe to show; server bodies and request secrets are never included. */
class AnalysisException(message: String) : IOException(message)

class AnalysisEngine {
    suspend fun detect(
        image: Bitmap,
        settings: AnalysisSettings,
        apiKey: String,
        onStatus: (String) -> Unit = {},
    ): DetectionResult {
        settings.validate()
        require(!image.isRecycled && image.width <= MAX_IMAGE_SIDE && image.height <= MAX_IMAGE_SIDE) {
            "Choose an image using the photo picker so it can be resized safely."
        }
        val key = apiKey.trim()
        require(key.isNotEmpty() && key.length <= 512 && key.all { it.code in 33..126 }) {
            "MoldEZ detection is not configured correctly. Install an updated app from the project maintainer."
        }
        onStatus(if (settings.useClahe) "Enhancing image contrast…" else "Preparing image…")
        val encoded = withContext(Dispatchers.Default) {
            ensureActive()
            val prepared = ImageProcessing.preprocess(image, settings)
            try {
                val bytes = ByteArrayOutputStream()
                check(prepared.compress(Bitmap.CompressFormat.JPEG, 95, bytes)) { "Could not prepare this photo." }
                ensureActive()
                Base64.encode(bytes.toByteArray(), Base64.NO_WRAP)
            } finally {
                if (prepared !== image) prepared.recycle()
            }
        }
        onStatus("Detecting the dish…")
        val dishResponse = infer(settings.dishModel, settings.dishConfidence, encoded, key)
        val dish = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            RoboflowMasks.parse(dishResponse, image.width, image.height, settings.dishConfidence, firstOnly = true) { context.ensureActive() }
        }
        if (dish.count() == 0L) throw AnalysisException("No dish detected. Choose a clearer photo with the entire dish visible.")
        currentCoroutineContext().ensureActive()
        onStatus("Detecting culture…")
        val cultureResponse = infer(settings.cultureModel, settings.cultureConfidence, encoded, key)
        val result = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val culture = RoboflowMasks.parse(cultureResponse, image.width, image.height, settings.cultureConfidence) { context.ensureActive() }
            for (i in culture.pixels.indices) {
                if (i % 65536 == 0) ensureActive()
                if (dish.pixels[i].toInt() == 0) culture.pixels[i] = 0
            }
            DetectionResult(dish, culture)
        }
        onStatus("Analysis complete")
        return result
    }

    private suspend fun infer(model: String, confidence: Float, payload: ByteArray, key: String): String = try {
        withTimeout(120_000) { withContext(Dispatchers.IO) {
            // Explicitly disable redirects so an Authorization header never reaches another host.
            val connection = URL("https://serverless.roboflow.com/$model?confidence=${wireConfidence(confidence)}&format=json" +
                "&image_type=base64&response_mask_format=polygon&disable_active_learning=true")
                .openConnection() as HttpsURLConnection
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { connection.disconnect() }
                try {
                    connection.requestMethod = "POST"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 90_000
                    connection.doOutput = true
                    connection.setRequestProperty("Authorization", "Bearer $key")
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setFixedLengthStreamingMode(payload.size)
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    connection.outputStream.use { output ->
                        var offset = 0
                        while (offset < payload.size && continuation.isActive) {
                            val size = min(16_384, payload.size - offset)
                            output.write(payload, offset, size)
                            offset += size
                        }
                    }
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    val status = connection.responseCode
                    if (status !in 200..299) throw AnalysisException(httpError(status))
                    if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw AnalysisException("The model returned too much data. Try a smaller photo.")
                    val body = connection.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(16_384)
                        while (continuation.isActive) {
                            val size = input.read(buffer)
                            if (size == -1) break
                            if (out.size().toLong() + size > MAX_RESPONSE_BYTES) throw AnalysisException("The model returned too much data. Try a smaller photo.")
                            out.write(buffer, 0, size)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(safeNetworkError(error))
                } finally {
                    connection.disconnect()
                }
            }
        } }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw AnalysisException("Roboflow took too long to respond. Check your connection and try again.")
    }

    internal companion object {
        const val MAX_IMAGE_SIDE = 1600
        const val MAX_RESPONSE_BYTES = 20L * 1024 * 1024

        internal fun wireConfidence(confidence: Float): String {
            require(confidence in 0f..1f)
            // This project/version route divides every value >= 1 by 100 for SDK compatibility.
            // Preserve fractional values below 1, but encode a 100% threshold as 100 rather than 1.
            // https://github.com/roboflow/inference/blob/main/inference/core/interfaces/http/http_api.py#L4465
            return if (confidence == 1f) "100.0" else confidence.toString()
        }

        fun httpError(status: Int) = when (status) {
            401, 403 -> "The MoldEZ detection service could not authorize this request. Contact the project maintainer."
            404 -> "The detection model is unavailable. Please contact the MoldEZ project maintainer."
            413 -> "This photo is too large for Roboflow. Choose a smaller photo."
            422 -> "Roboflow could not process this photo. Try another photo or contact the MoldEZ project maintainer."
            429 -> "The MoldEZ detection service's usage limit was reached. Try again later."
            in 500..599 -> "Roboflow is temporarily unavailable. Try again in a moment."
            in 300..399 -> "Roboflow redirected this request. Update the app before trying again."
            else -> "Roboflow could not complete the analysis (HTTP $status). Try again."
        }

        fun safeNetworkError(error: Exception): AnalysisException = when (error) {
            is AnalysisException -> error
            is SocketTimeoutException -> AnalysisException("Roboflow took too long to respond. Check your connection and try again.")
            is UnknownHostException -> AnalysisException("Cannot reach Roboflow. Check your internet connection.")
            is SSLException -> AnalysisException("A secure connection to Roboflow could not be established. Check your device's date and network.")
            else -> AnalysisException("The connection to Roboflow failed. Check your internet connection and try again.")
        }
    }
}

/** Parses only segmentation data. A missing or unsupported payload must never become a zero result. */
internal object RoboflowMasks {
    private const val MAX_MASK_PIXELS = 16_777_216L
    private const val INVALID = "The model returned invalid segmentation data. Check that both model IDs are segmentation models."

    fun parse(json: String, width: Int, height: Int, threshold: Float, firstOnly: Boolean = false, checkCancelled: () -> Unit = {}): BinaryMask {
        require(width in 1..1600 && height in 1..1600 && threshold in 0f..1f)
        try {
            val tokener = JSONTokener(json)
            val root = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') invalid()
            val result = BinaryMask(width, height, ByteArray(width * height))
            var accepted = false
            var visited = 0
            fun visit(node: Any?, sourceWidth: Int, sourceHeight: Int, depth: Int) {
                checkCancelled()
                if (++visited > 2048 || depth > 16) invalid()
                when (node) {
                    is JSONArray -> {
                        if (node.length() > 1024) invalid()
                        for (i in 0 until node.length()) visit(node.opt(i), sourceWidth, sourceHeight, depth + 1)
                    }
                    is JSONObject -> {
                        if (node.has("error") || node.has("detail") || node.optBoolean("is_stub")) invalid()
                        var sw = sourceWidth
                        var sh = sourceHeight
                        val dimensions = when (val value = node.opt("image")) {
                            is JSONObject -> value
                            is JSONArray -> if (value.length() == 1) value.optJSONObject(0) else null
                            else -> null
                        }
                        if (dimensions != null) {
                            sw = dimensions.optInt("width", 0)
                            sh = dimensions.optInt("height", 0)
                            if (sw !in 1..32768 || sh !in 1..32768) invalid()
                        }
                        if (node.has("segmentation_mask") || node.has("points")) {
                            var confidence = 1.0
                            for (key in listOf("confidence", "score", "probability")) {
                                if (node.has(key)) {
                                    confidence = node.optDouble(key, Double.NaN)
                                    if (!confidence.isFinite() || confidence !in 0.0..1.0) invalid()
                                    break
                                }
                            }
                            // Validate every leaf even when it is below threshold or follows the first dish.
                            val mask = if (node.has("segmentation_mask")) semantic(node, width, height, threshold)
                                else polygon(node.getJSONArray("points"), sw, sh, width, height, checkCancelled)
                            if (confidence >= threshold && (!firstOnly || !accepted)) {
                                for (i in result.pixels.indices) if (mask.pixels[i].toInt() != 0) result.pixels[i] = 1
                                accepted = true
                            }
                        } else if (node.has("predictions")) {
                            visit(node.opt("predictions"), sw, sh, depth + 1)
                        } else invalid()
                    }
                    else -> invalid()
                }
            }
            visit(root, width, height, 0)
            return result
        } catch (error: AnalysisException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw AnalysisException(INVALID)
        }
    }

    private fun semantic(node: JSONObject, width: Int, height: Int, threshold: Float): BinaryMask {
        val bitmap = decodePng(node.opt("segmentation_mask"))
        try {
            val source = ImageProcessing.bitmapToMask(bitmap)
            val result = resize(source, width, height)
            val confidenceValue = node.opt("confidence_mask")
            if (confidenceValue != null && confidenceValue !== JSONObject.NULL) {
                val confidence = decodePng(confidenceValue)
                try {
                    if (confidence.width != bitmap.width || confidence.height != bitmap.height) invalid()
                    val row = IntArray(confidence.width)
                    var previousY = -1
                    for (y in 0 until height) {
                        val sy = ((y + 0.5) * confidence.height / height).toInt().coerceAtMost(confidence.height - 1)
                        if (sy != previousY) {
                            confidence.getPixels(row, 0, row.size, 0, sy, row.size, 1)
                            previousY = sy
                        }
                        for (x in 0 until width) {
                            val sx = ((x + 0.5) * confidence.width / width).toInt().coerceAtMost(confidence.width - 1)
                            if (Color.red(row[sx]) / 255f < threshold || Color.alpha(row[sx]) == 0) result.pixels[y * width + x] = 0
                        }
                    }
                } finally { confidence.recycle() }
            }
            return result
        } finally { bitmap.recycle() }
    }

    private fun decodePng(value: Any?): Bitmap {
        if (value !is String || value.isBlank() || value.length > AnalysisEngine.MAX_RESPONSE_BYTES) invalid()
        val encoded = if (value.startsWith("data:image/", ignoreCase = true)) {
            val comma = value.indexOf(',')
            if (comma < 0 || !value.substring(0, comma).endsWith(";base64", ignoreCase = true)) invalid()
            value.substring(comma + 1)
        } else value
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        // Restrict masks to lossless PNG. JPEG artifacts would create false positive pixels.
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) invalid()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_MASK_PIXELS) invalid()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }) ?: invalid()
    }

    private fun polygon(points: JSONArray, sourceWidth: Int, sourceHeight: Int, width: Int, height: Int, checkCancelled: () -> Unit): BinaryMask {
        if (points.length() !in 3..16_384) invalid()
        val xs = DoubleArray(points.length())
        val ys = DoubleArray(points.length())
        for (i in xs.indices) {
            val point = points.optJSONObject(i) ?: invalid()
            val x = point.optDouble("x", Double.NaN)
            val y = point.optDouble("y", Double.NaN)
            if (!x.isFinite() || !y.isFinite() || kotlin.math.abs(x) > 1_000_000 || kotlin.math.abs(y) > 1_000_000) invalid()
            xs[i] = x * width / sourceWidth
            ys[i] = y * height / sourceHeight
        }
        val mask = BinaryMask(width, height, ByteArray(width * height))
        val intersections = DoubleArray(xs.size)
        // Scan-line fill at pixel centers is deterministic and never includes antialiased fringe pixels.
        for (y in max(0, floor(ys.min()).toInt()) until min(height, ceil(ys.max()).toInt())) {
            if (y % 32 == 0) checkCancelled()
            val py = y + 0.5
            var count = 0
            var j = xs.lastIndex
            for (i in xs.indices) {
                if ((ys[i] > py) != (ys[j] > py)) {
                    intersections[count++] = xs[i] + (py - ys[i]) * (xs[j] - xs[i]) / (ys[j] - ys[i])
                }
                j = i
            }
            java.util.Arrays.sort(intersections, 0, count)
            for (i in 0 until count - 1 step 2) {
                val start = ceil(intersections[i] - 0.5).toInt().coerceIn(0, width)
                val end = ceil(intersections[i + 1] - 0.5).toInt().coerceIn(0, width)
                for (x in start until end) mask.pixels[y * width + x] = 1
            }
        }
        return mask
    }

    internal fun resize(mask: BinaryMask, width: Int, height: Int): BinaryMask {
        if (mask.width == width && mask.height == height) return mask
        val result = BinaryMask(width, height, ByteArray(width * height))
        for (y in 0 until height) {
            val sy = ((y + 0.5) * mask.height / height).toInt().coerceAtMost(mask.height - 1)
            for (x in 0 until width) {
                val sx = ((x + 0.5) * mask.width / width).toInt().coerceAtMost(mask.width - 1)
                result.pixels[y * width + x] = mask.pixels[sy * mask.width + sx]
            }
        }
        return result
    }

    private fun invalid(): Nothing = throw AnalysisException(INVALID)
}

object ImageProcessing {
    fun preprocess(bitmap: Bitmap, settings: AnalysisSettings): Bitmap {
        settings.validate()
        if (!settings.useClahe) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luminance = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val y = 0.2126729 * linear[Color.red(pixel)] + 0.7151522 * linear[Color.green(pixel)] + 0.0721750 * linear[Color.blue(pixel)]
            luminance[i] = (((116 * labF(y) - 16) * 255 / 100).roundToInt().coerceIn(0, 255)).toByte()
        }
        val enhanced = clahe(luminance, width, height, settings.claheTiles, settings.claheClip)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = linear[Color.red(pixel)]; val g = linear[Color.green(pixel)]; val b = linear[Color.blue(pixel)]
            val fx = labF((0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047)
            val fy = labF(0.2126729 * r + 0.7151522 * g + 0.0721750 * b)
            val fz = labF((0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883)
            val newY = ((enhanced[i].toInt() and 255) * 100.0 / 255 + 16) / 116
            val x = 0.95047 * labInverse(newY + fx - fy)
            val y = labInverse(newY)
            val z = 1.08883 * labInverse(newY - fy + fz)
            pixels[i] = Color.argb(Color.alpha(pixel), srgb(3.2404542 * x - 1.5371385 * y - 0.4985314 * z),
                srgb(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z), srgb(0.0556434 * x - 0.2040259 * y + 1.0572252 * z))
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** CLAHE on CIELAB L*: clipped tile histograms, redistributed mass, bilinear tile interpolation. */
    internal fun clahe(input: ByteArray, width: Int, height: Int, tileCount: Int, clipLimit: Double): ByteArray {
        require(width > 0 && height > 0 && input.size == width * height && tileCount > 0 && clipLimit > 0)
        val nx = min(tileCount, width); val ny = min(tileCount, height)
        val tileWidth = (width + nx - 1) / nx; val tileHeight = (height + ny - 1) / ny
        val area = tileWidth * tileHeight
        val limit = max(1, (clipLimit * area / 256).toInt())
        val mappings = Array(nx * ny) { IntArray(256) }
        for (ty in 0 until ny) for (tx in 0 until nx) {
            val histogram = IntArray(256)
            for (y in 0 until tileHeight) for (x in 0 until tileWidth) {
                val sx = reflect(tx * tileWidth + x, width)
                val sy = reflect(ty * tileHeight + y, height)
                histogram[input[sy * width + sx].toInt() and 255]++
            }
            var excess = 0
            for (i in histogram.indices) if (histogram[i] > limit) {
                excess += histogram[i] - limit
                histogram[i] = limit
            }
            val batch = excess / 256
            var remainder = excess % 256
            for (i in histogram.indices) histogram[i] += batch
            if (remainder > 0) {
                val step = max(256 / remainder, 1)
                var i = 0
                while (i < 256 && remainder > 0) { histogram[i]++; i += step; remainder-- }
            }
            var total = 0
            for (i in histogram.indices) {
                total += histogram[i]
                mappings[ty * nx + tx][i] = (total * 255.0 / area).roundToInt().coerceIn(0, 255)
            }
        }
        val result = ByteArray(input.size)
        for (y in 0 until height) {
            val gy = y.toDouble() / tileHeight - 0.5
            val iy = floor(gy).toInt(); val dy = gy - iy
            val y0 = iy.coerceIn(0, ny - 1); val y1 = (iy + 1).coerceIn(0, ny - 1)
            for (x in 0 until width) {
                val gx = x.toDouble() / tileWidth - 0.5
                val ix = floor(gx).toInt(); val dx = gx - ix
                val x0 = ix.coerceIn(0, nx - 1); val x1 = (ix + 1).coerceIn(0, nx - 1)
                val value = input[y * width + x].toInt() and 255
                val top = mappings[y0 * nx + x0][value] * (1 - dx) + mappings[y0 * nx + x1][value] * dx
                val bottom = mappings[y1 * nx + x0][value] * (1 - dx) + mappings[y1 * nx + x1][value] * dx
                result[y * width + x] = (top * (1 - dy) + bottom * dy).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return result
    }

    fun overlay(bitmap: Bitmap, result: DetectionResult): Bitmap {
        require(bitmap.width == result.dish.width && bitmap.height == result.dish.height) { "Photo and mask dimensions differ." }
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val dish = result.dish.pixels; val culture = result.culture.pixels
        val radius = max(1, min(w, h) / 500)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (dish[i].toInt() == 0) continue
            var boundary = false
            for (d in 1..radius) {
                if (x - d < 0 || y - d < 0 || x + d >= w || y + d >= h ||
                    dish[y * w + (x - d)].toInt() == 0 || dish[y * w + x + d].toInt() == 0 ||
                    dish[(y - d) * w + x].toInt() == 0 || dish[(y + d) * w + x].toInt() == 0) {
                    boundary = true; break
                }
            }
            if (culture[i].toInt() != 0) pixels[i] = blend(pixels[i], 255, 50, 50, 160)
            if (boundary) pixels[i] = blend(pixels[i], 50, 255, 50, 220)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun maskToBitmap(mask: BinaryMask): Bitmap = Bitmap.createBitmap(
        IntArray(mask.pixels.size) { if (mask.pixels[it].toInt() == 0) Color.BLACK else Color.WHITE },
        mask.width, mask.height, Bitmap.Config.ARGB_8888,
    )

    fun bitmapToMask(bitmap: Bitmap): BinaryMask {
        val row = IntArray(bitmap.width)
        val mask = BinaryMask(bitmap.width, bitmap.height, ByteArray(bitmap.width * bitmap.height))
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            for (x in row.indices) if (Color.alpha(row[x]) > 0 && Color.red(row[x]) > 0) mask.pixels[y * row.size + x] = 1
        }
        return mask
    }

    private fun blend(pixel: Int, r: Int, g: Int, b: Int, alpha: Int): Int = Color.argb(Color.alpha(pixel),
        (Color.red(pixel) * (255 - alpha) + r * alpha) / 255,
        (Color.green(pixel) * (255 - alpha) + g * alpha) / 255,
        (Color.blue(pixel) * (255 - alpha) + b * alpha) / 255)

    private val linear = DoubleArray(256) { value ->
        val v = value / 255.0
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    private fun labF(v: Double) = if (v > 216.0 / 24389) Math.cbrt(v) else v * 841 / 108 + 4.0 / 29
    private fun labInverse(v: Double) = if (v > 6.0 / 29) v * v * v else 108.0 / 841 * (v - 4.0 / 29)
    private fun srgb(v: Double): Int = ((if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055) * 255).roundToInt().coerceIn(0, 255)
    private fun reflect(v: Int, length: Int): Int {
        if (length == 1) return 0
        val period = 2 * (length - 1)
        val r = v % period
        return if (r < length) r else period - r
    }
}
