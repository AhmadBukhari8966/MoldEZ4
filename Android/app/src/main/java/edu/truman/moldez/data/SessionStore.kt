package edu.truman.moldez.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.exifinterface.media.ExifInterface
import edu.truman.moldez.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Blocking storage operations: callers must use a background dispatcher. */
class SessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "moldez").apply { mkdirs() }.canonicalFile
    private val library = AtomicFile(File(root, "sessions.json"))
    private var preservedLibrary: Triple<Long, Long, File>? = null

    @Synchronized
    fun load(): List<Session> {
        if (!library.baseFile.exists() && !File(library.baseFile.path + ".bak").exists()) return emptyList()
        return try {
            readLibrary()
        } catch (e: Exception) {
            // Keep the original even if a device storage problem prevents an additional recovery copy.
            runCatching { preserveUnreadableLibrary() }
            throw IllegalStateException("Could not read saved sessions. Your saved files have been kept. ${e.message.orEmpty()}", e)
        }
    }

    private fun readLibrary(): List<Session> {
        val json = library.openRead().use { JSONObject(String(readBounded(it, MAX_JSON_BYTES), Charsets.UTF_8)) }
        require(json.getInt("version") == VERSION) { "This session library needs a newer version of MoldEZ." }
        val sessions = json.getJSONArray("sessions")
        require(sessions.length() <= MAX_RECORDS) { "The session library has too many sessions." }
        return List(sessions.length()) { sessionFromJson(sessions.getJSONObject(it), root) }
    }

    private fun preserveUnreadableLibrary() {
        val source = library.baseFile
        require(source.isFile) { "The previous session library could not be preserved." }
        val previous = preservedLibrary
        if (previous != null && previous.first == source.lastModified() && previous.second == source.length() && previous.third.isFile) return
        val copy = File(root, "recovery/sessions-${System.currentTimeMillis()}-${UUID.randomUUID()}.json").apply { parentFile?.mkdirs() }
        try {
            source.inputStream().use { input -> copy.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            preservedLibrary = Triple(source.lastModified(), source.length(), copy)
        } catch (e: Exception) {
            copy.delete()
            throw IllegalStateException("Could not preserve the previous session library. Free some storage before creating a new session.", e)
        }
    }

    @Synchronized
    fun save(sessions: List<Session>) {
        require(sessions.size <= MAX_RECORDS) { "Too many sessions." }
        require(sessions.map { it.id }.distinct().size == sessions.size) { "Session IDs must be unique." }
        val json = JSONObject().put("version", VERSION).put("sessions", JSONArray().apply {
            sessions.forEach { put(sessionToJson(it, ::localRelativePath)) }
        })
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "The session library is too large." }
        if (library.baseFile.exists() || File(library.baseFile.path + ".bak").exists()) {
            // Recovery is allowed, but never replace unreadable user data without keeping a copy.
            if (runCatching { readLibrary() }.isFailure) preserveUnreadableLibrary()
        }
        val stream = library.startWrite()
        try {
            stream.write(bytes)
            library.finishWrite(stream)
        } catch (e: Exception) {
            library.failWrite(stream)
            throw e
        }
    }

    /** Copies the selected photograph, applies EXIF orientation, and stores at most 1600 pixels per side. */
    fun importImage(uri: Uri): ImageAsset {
        val id = UUID.randomUUID().toString()
        val incoming = File(root, "incoming-$id")
        val target = File(root, "images/$id.jpg").apply { parentFile?.mkdirs() }
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                incoming.outputStream().use { copyBounded(input, it, MAX_SOURCE_BYTES) }
            } ?: error("The selected photograph could not be opened.")
            val exif = runCatching { ExifInterface(incoming) }.getOrNull()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(incoming.path, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "This file is not a supported photograph." }
            require(options.outWidth.toLong() * options.outHeight <= MAX_SOURCE_PIXELS) { "The photograph is too large to import safely." }
            var sample = 1
            while (max(options.outWidth, options.outHeight) / sample > MAX_DIMENSION * 2) sample *= 2
            decoded = BitmapFactory.decodeFile(incoming.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("The photograph could not be decoded.")
            val matrix = orientationMatrix(exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL)
            oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val scale = MAX_DIMENSION.toFloat() / max(oriented.width, oriented.height)
            val normalized = if (scale < 1f) Bitmap.createScaledBitmap(oriented,
                (oriented.width * scale).roundToInt().coerceAtLeast(1),
                (oriented.height * scale).roundToInt().coerceAtLeast(1), true) else oriented
            try { writeBitmap(normalized, target, Bitmap.CompressFormat.JPEG) }
            finally { if (normalized !== oriented) normalized.recycle() }
            return ImageAsset(id, imageName(uri), target.absolutePath, captureTime(exif, uri))
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            incoming.delete()
            if (oriented !== decoded) oriented?.recycle()
            decoded?.recycle()
        }
    }

    /** A saved analysis has its own immutable copy of every asset, including manually edited masks. */
    fun saveAnalysis(asset: ImageAsset, detection: DetectionResult, settings: AnalysisSettings, edited: Boolean): AnalysisRecord {
        settings.validate()
        val savedDetection = detection.deepCopy()
        validateDetection(savedDetection)
        val source = localFile(asset.imagePath)
        require(source.isFile) { "The source photograph is no longer available." }
        val image = decodeBoundedImage(source)
        val id = UUID.randomUUID().toString()
        val directory = File(root, "analyses/$id").apply { mkdirs() }
        try {
            require(image.width == savedDetection.dish.width && image.height == savedDetection.dish.height) { "The masks do not match the photograph." }
            val imageFile = File(directory, "source.jpg")
            val dishFile = File(directory, "dish.png")
            val cultureFile = File(directory, "culture.png")
            val overlayFile = File(directory, "overlay.jpg")
            source.inputStream().use { input -> imageFile.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            ImageProcessing.maskToBitmap(savedDetection.dish).let { mask ->
                try { writeBitmap(mask, dishFile, Bitmap.CompressFormat.PNG) } finally { mask.recycle() }
            }
            ImageProcessing.maskToBitmap(savedDetection.culture).let { mask ->
                try { writeBitmap(mask, cultureFile, Bitmap.CompressFormat.PNG) } finally { mask.recycle() }
            }
            ImageProcessing.overlay(image, savedDetection).let { overlay ->
                try { writeBitmap(overlay, overlayFile, Bitmap.CompressFormat.JPEG) }
                finally { if (overlay !== image) overlay.recycle() }
            }
            return AnalysisRecord(id, asset.fileName.take(MAX_TEXT_LENGTH).ifBlank { "Photograph" }, System.currentTimeMillis(), asset.capturedAt,
                imageFile.absolutePath, overlayFile.absolutePath, dishFile.absolutePath, cultureFile.absolutePath,
                settings, Measurements.calculate(savedDetection.dish.count(), savedDetection.culture.count(), settings), edited)
        } catch (e: Exception) {
            directory.deleteRecursively()
            throw e
        } finally { image.recycle() }
    }

    fun loadDetection(record: AnalysisRecord): DetectionResult? = runCatching {
        val dish = readMask(localFile(record.dishMaskPath))
        val culture = readMask(localFile(record.cultureMaskPath))
        DetectionResult(dish, culture).also {
            validateDetection(it)
            require(it.dish.count() == record.measurement.platePixels && it.culture.count() == record.measurement.culturePixels)
        }
    }.getOrNull()

    /** Portable, versioned JSON + image/mask ZIP; never serializes executable objects. */
    fun exportSession(session: Session, output: OutputStream) {
        require(session.analyses.size <= MAX_RECORDS) { "The session has too many analyses to export." }
        val entries = linkedMapOf<String, File>()
        val json = sessionToJson(session) { path ->
            val file = localFile(path)
            require(file.isFile) { "A saved photograph or mask is missing. Export could not finish." }
            require(file.length() <= MAX_ENTRY_BYTES) { "A saved image is too large to export." }
            entries.entries.firstOrNull { it.value == file }?.key ?: "assets/${entries.size}-${file.name}".also { entries[it] = file }
        }
        val manifest = JSONObject().put("format", FORMAT).put("version", VERSION).put("session", json).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_JSON_BYTES && entries.values.sumOf { it.length() } + manifest.size <= MAX_BUNDLE_BYTES) { "The session is too large to export." }
        ZipOutputStream(object : FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
            override fun close() { flush() }
        }).use { zip ->
            zip.putNextEntry(ZipEntry("session.json"))
            zip.write(manifest)
            zip.closeEntry()
            entries.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
        }
    }

    /** Validates the complete archive before making any imported assets visible to sessions. */
    fun importSession(input: InputStream): Session {
        val bundleId = UUID.randomUUID().toString()
        val staging = File(root, "incoming-session-$bundleId").apply { mkdirs() }
        val destination = File(root, "imports/$bundleId")
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(names.add(entry.name) && names.size <= MAX_RECORDS * 4 + 1) { "The session archive has duplicate files or too many files." }
                    val file = safeRelativeFile(staging, entry.name)
                    require(!entry.isDirectory) { "Unexpected folder in the session archive." }
                    require(entry.name == "session.json" || entry.name.startsWith("assets/")) { "Unexpected file in the session archive." }
                    val entryLimit = if (entry.name == "session.json") MAX_JSON_BYTES.toLong() else MAX_ENTRY_BYTES
                    require(entry.size < 0 || entry.size <= entryLimit) { "A file in the session archive is too large." }
                    file.parentFile?.mkdirs()
                    val copied = file.outputStream().use { output ->
                        val size = copyBounded(zip, output, minOf(entryLimit, MAX_BUNDLE_BYTES - total))
                        output.fd.sync()
                        size
                    }
                    total += copied
                    zip.closeEntry()
                }
            }
            val manifest = File(staging, "session.json")
            require(manifest.isFile) { "This is not a MoldEZ Android session archive." }
            val json = JSONObject(manifest.readText(Charsets.UTF_8))
            require(json.getString("format") == FORMAT && json.getInt("version") == VERSION) { "This session archive format is not supported." }
            val session = sessionFromJson(json.getJSONObject("session"), staging)
            require(session.analyses.size <= MAX_RECORDS) { "The session has too many analyses." }
            val referencedAssets = session.analyses.flatMap { listOf(it.imagePath, it.overlayPath, it.dishMaskPath, it.cultureMaskPath) }
            require(referencedAssets.distinct().size == referencedAssets.size) { "The session archive reuses asset paths." }
            val expectedNames = referencedAssets.map { File(it).relativeTo(staging).invariantSeparatorsPath }.toSet()
            require(expectedNames.all { it.startsWith("assets/") } && names == expectedNames + "session.json") { "The archive has missing or unexpected session assets." }
            session.analyses.forEach { record ->
                val image = decodeBoundedImage(File(record.imagePath))
                try {
                    val detection = DetectionResult(readMask(File(record.dishMaskPath)), readMask(File(record.cultureMaskPath)))
                    validateDetection(detection)
                    require(detection.dish.width == image.width && detection.dish.height == image.height) { "An imported mask does not match its photograph." }
                    require(detection.dish.count() == record.measurement.platePixels && detection.culture.count() == record.measurement.culturePixels) { "The session measurements do not match its saved masks." }
                    // Recreate overlays from verified masks instead of trusting a possibly unrelated image.
                    val overlayFile = File(record.overlayPath)
                    require(overlayFile != File(record.imagePath) && overlayFile != File(record.dishMaskPath) && overlayFile != File(record.cultureMaskPath)) { "The session archive reuses incompatible asset paths." }
                    val overlay = ImageProcessing.overlay(image, detection)
                    try { writeBitmap(overlay, overlayFile, Bitmap.CompressFormat.JPEG) }
                    finally { if (overlay !== image) overlay.recycle() }
                } finally { image.recycle() }
            }
            destination.parentFile?.mkdirs()
            require(staging.renameTo(destination)) { "There is not enough storage to import this session." }
            fun move(path: String) = File(destination, File(path).relativeTo(staging).path).absolutePath
            return session.copy(id = UUID.randomUUID().toString(), analyses = session.analyses.map {
                it.copy(id = UUID.randomUUID().toString(), imagePath = move(it.imagePath), overlayPath = move(it.overlayPath),
                    dishMaskPath = move(it.dishMaskPath), cultureMaskPath = move(it.cultureMaskPath))
            })
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw IllegalArgumentException("Could not import this session. ${e.message.orEmpty()}", e)
        }
    }

    private fun imageName(uri: Uri): String {
        val displayName = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return (displayName ?: uri.lastPathSegment ?: "Photograph").substringAfterLast('/').take(MAX_TEXT_LENGTH).ifBlank { "Photograph" }
    }

    private fun captureTime(exif: ExifInterface?, uri: Uri): Long {
        val exifText = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
        if (exifText != null) {
            val offset = exif?.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            val parsed = runCatching {
                val format = SimpleDateFormat(if (offset == null) "yyyy:MM:dd HH:mm:ss" else "yyyy:MM:dd HH:mm:ssXXX", Locale.US).apply { isLenient = false }
                format.parse(exifText + (offset ?: ""))?.time
            }.getOrNull()
            if (parsed != null && parsed in 1..MAX_DATE) return parsed
        }
        val mediaTime = runCatching {
            appContext.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { date -> date in 1..MAX_DATE } else null
            }
        }.getOrNull()
        return mediaTime ?: System.currentTimeMillis()
    }

    private fun localRelativePath(path: String): String = localFile(path).relativeTo(root.canonicalFile).invariantSeparatorsPath

    private fun localFile(path: String): File {
        val file = File(path).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "The saved asset is outside MoldEZ storage." }
        return file
    }

    companion object {
        private const val VERSION = 1
        private const val FORMAT = "MoldEZ-Android-Session"
        private const val MAX_DIMENSION = 1600
        private const val MAX_SOURCE_PIXELS = 150_000_000L
        private const val MAX_SOURCE_BYTES = 128L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
        private const val MAX_BUNDLE_BYTES = 512L * 1024 * 1024
        private const val MAX_JSON_BYTES = 8 * 1024 * 1024
        private const val MAX_RECORDS = 1000
        private const val MAX_TEXT_LENGTH = 512
        private const val MAX_DATE = 32_503_680_000_000L

        private fun safeRelativeFile(base: File, path: String): File {
            require(path.isNotBlank() && path.length <= MAX_TEXT_LENGTH && path.split('/').size <= 8 && !path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000') && !path.contains(':') && path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Unsafe path in the session archive." }
            val file = File(base, path).canonicalFile
            require(file.path.startsWith(base.canonicalPath + File.separator)) { "Unsafe path in the session archive." }
            return file
        }

        private fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
            require(limit >= 0) { "The session archive is too large." }
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                require(total <= limit) { "This file exceeds the supported size limit." }
                output.write(buffer, 0, count)
            }
            return total
        }

        private fun readBounded(input: InputStream, limit: Int): ByteArray = java.io.ByteArrayOutputStream().use {
            copyBounded(input, it, limit.toLong())
            it.toByteArray()
        }

        private fun orientationMatrix(orientation: Int) = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }

        private fun writeBitmap(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat) {
            file.outputStream().use {
                require(bitmap.compress(format, 94, it)) { "Could not save the photograph. Check available storage." }
                it.fd.sync()
            }
        }

        private fun decodeBoundedImage(file: File): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth in 1..MAX_DIMENSION && bounds.outHeight in 1..MAX_DIMENSION) { "A saved photograph or mask is missing, invalid, or larger than 1600 pixels." }
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: error("A saved photograph or mask could not be opened.")
        }

        private fun readMask(file: File): BinaryMask {
            val image = decodeBoundedImage(file)
            return try { ImageProcessing.bitmapToMask(image) } finally { image.recycle() }
        }

        private fun validateDetection(detection: DetectionResult) {
            require(detection.dish.width <= MAX_DIMENSION && detection.dish.height <= MAX_DIMENSION) { "The analysis exceeds the supported image size." }
            require(detection.dish.count() > 0) { "No dish was detected." }
            require(detection.dish.pixels.indices.all { detection.culture.pixels[it].toInt() == 0 || detection.dish.pixels[it].toInt() != 0 }) { "Culture pixels must be inside the dish." }
        }

        private fun settingsToJson(s: AnalysisSettings) = JSONObject()
            .put("diameterMm", s.diameterMm).put("dishConfidence", s.dishConfidence.toDouble())
            .put("cultureConfidence", s.cultureConfidence.toDouble()).put("useClahe", s.useClahe)
            .put("claheClip", s.claheClip).put("claheTiles", s.claheTiles).put("calibration", s.calibration.name)
            .put("dishModel", s.dishModel).put("cultureModel", s.cultureModel).put("intervalSeconds", s.intervalSeconds)

        private fun settingsFromJson(j: JSONObject): AnalysisSettings = AnalysisSettings(
            diameterMm = j.getDouble("diameterMm"), dishConfidence = j.getDouble("dishConfidence").toFloat(),
            cultureConfidence = j.getDouble("cultureConfidence").toFloat(), useClahe = j.getBoolean("useClahe"),
            claheClip = j.getDouble("claheClip"), claheTiles = j.getInt("claheTiles"), calibration = Calibration.valueOf(j.getString("calibration")),
            dishModel = j.getString("dishModel"), cultureModel = j.getString("cultureModel"), intervalSeconds = j.getInt("intervalSeconds")
        ).also { it.validate() }

        private fun sessionToJson(session: Session, path: (String) -> String): JSONObject {
            require(session.analyses.size <= MAX_RECORDS && session.analyses.map { it.id }.distinct().size == session.analyses.size) { "Invalid number of analyses or duplicate analysis IDs." }
            require(session.name.isNotBlank() && session.name.length <= MAX_TEXT_LENGTH && session.id.isNotBlank() && session.id.length <= MAX_TEXT_LENGTH) { "The session name or ID is empty or too long." }
            require(session.createdAt in 1..MAX_DATE) { "Invalid session date." }
            return JSONObject().put("id", session.id).put("name", session.name).put("createdAt", session.createdAt)
                .put("analyses", JSONArray().apply {
                    session.analyses.forEach { r ->
                        r.settings.validate()
                        require(r.id.isNotBlank() && r.id.length <= MAX_TEXT_LENGTH && r.fileName.isNotBlank() && r.fileName.length <= MAX_TEXT_LENGTH) { "Invalid analysis name or ID." }
                        require(r.timestamp in 1..MAX_DATE && r.capturedAt in 1..MAX_DATE) { "Invalid analysis date." }
                        require(r.measurement.platePixels <= MAX_DIMENSION.toLong() * MAX_DIMENSION) { "Invalid mask size." }
                        Measurements.calculate(r.measurement.platePixels, r.measurement.culturePixels, r.settings)
                        put(JSONObject().put("id", r.id).put("fileName", r.fileName.take(MAX_TEXT_LENGTH))
                            .put("timestamp", r.timestamp).put("capturedAt", r.capturedAt).put("image", path(r.imagePath))
                            .put("overlay", path(r.overlayPath)).put("dishMask", path(r.dishMaskPath)).put("cultureMask", path(r.cultureMaskPath))
                            .put("settings", settingsToJson(r.settings)).put("edited", r.edited)
                            .put("platePixels", r.measurement.platePixels).put("culturePixels", r.measurement.culturePixels))
                    }
                })
        }

        private fun sessionFromJson(json: JSONObject, base: File): Session {
            fun text(key: String, obj: JSONObject = json): String = obj.getString(key).also {
                require(it.isNotBlank() && it.length <= MAX_TEXT_LENGTH) { "Invalid session text." }
            }
            fun time(key: String, obj: JSONObject = json): Long = obj.getLong(key).also {
                require(it in 1..MAX_DATE) { "Invalid session date." }
            }
            val entries = json.getJSONArray("analyses")
            require(entries.length() <= MAX_RECORDS) { "Too many analyses in this session." }
            val records = List(entries.length()) { index ->
                val r = entries.getJSONObject(index)
                val settings = settingsFromJson(r.getJSONObject("settings"))
                val measurement = Measurements.calculate(r.getLong("platePixels"), r.getLong("culturePixels"), settings)
                require(measurement.platePixels <= MAX_DIMENSION.toLong() * MAX_DIMENSION) { "Invalid mask size." }
                fun asset(key: String): String = safeRelativeFile(base, text(key, r)).absolutePath
                AnalysisRecord(text("id", r), text("fileName", r), time("timestamp", r), time("capturedAt", r),
                    asset("image"), asset("overlay"), asset("dishMask"), asset("cultureMask"), settings, measurement, r.getBoolean("edited"))
            }
            require(records.map { it.id }.distinct().size == records.size) { "Duplicate analysis IDs in session." }
            return Session(text("id"), text("name"), time("createdAt"), records)
        }
    }
}
