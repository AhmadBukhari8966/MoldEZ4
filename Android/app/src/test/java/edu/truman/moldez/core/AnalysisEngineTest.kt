package edu.truman.moldez.core

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AnalysisEngineTest {
    @Test fun semanticMasksAcceptNestedShapesAndNormalizeDimensions() {
        val encoded = png(intArrayOf(Color.BLACK, Color.WHITE, Color.WHITE, Color.BLACK), 2, 2)
        val shapes = listOf(
            JSONObject().put("segmentation_mask", encoded),
            JSONObject().put("predictions", JSONObject().put("segmentation_mask", encoded)),
            JSONObject().put("predictions", JSONArray().put(JSONObject().put("predictions",
                JSONArray().put(JSONObject().put("segmentation_mask", "data:image/png;base64,$encoded"))))),
        )
        for (shape in shapes) {
            val mask = RoboflowMasks.parse(shape.toString(), 4, 4, 0.3f)
            assertEquals(8L, mask.count())
            assertEquals(0, mask.pixels[0].toInt())
            assertEquals(1, mask.pixels[3].toInt())
            assertEquals(1, mask.pixels[12].toInt())
            assertEquals(0, mask.pixels[15].toInt())
        }
    }

    @Test fun semanticConfidenceMaskUsesFractionalThreshold() {
        val mask = png(intArrayOf(Color.WHITE, Color.WHITE), 2, 1)
        val confidence = png(intArrayOf(Color.rgb(20, 20, 20), Color.rgb(230, 230, 230)), 2, 1)
        val payload = JSONObject().put("predictions", JSONObject().put("segmentation_mask", mask).put("confidence_mask", confidence))
        val result = RoboflowMasks.parse(payload.toString(), 2, 1, 0.4f)
        assertArrayEquals(byteArrayOf(0, 1), result.pixels)
    }

    @Test fun firstDishAndCultureUnionAreDifferent() {
        val predictions = JSONArray().put(rectangle(0, 0, 2, 4)).put(rectangle(2, 0, 4, 4))
        val payload = JSONObject().put("image", JSONObject().put("width", 4).put("height", 4)).put("predictions", predictions).toString()
        assertEquals(8L, RoboflowMasks.parse(payload, 4, 4, 0.4f, firstOnly = true).count())
        assertEquals(16L, RoboflowMasks.parse(payload, 4, 4, 0.4f).count())
    }

    @Test fun polygonsScaleFromResponseImageAndClipToBounds() {
        val payload = JSONObject().put("image", JSONObject().put("width", 8).put("height", 8))
            .put("predictions", JSONArray().put(rectangle(-2, -2, 4, 4))).toString()
        assertEquals(4L, RoboflowMasks.parse(payload, 4, 4, 0.3f).count())
    }

    @Test fun confidenceFilteringAllowsLegitimateEmptyResults() {
        val payload = JSONObject().put("predictions", JSONArray().put(rectangle(0, 0, 4, 4).put("confidence", 0.2)))
        assertEquals(0L, RoboflowMasks.parse(payload.toString(), 4, 4, 0.4f).count())
        assertEquals(0L, RoboflowMasks.parse("{\"predictions\":[]}", 4, 4, 0.4f).count())
    }

    @Test fun malformedResponsesNeverBecomeEmptyMeasurements() {
        val invalid = listOf("{}", "null", "{\"predictions\":null}", "{\"predictions\":[{}]}",
            "{\"predictions\":[{\"class\":\"culture\",\"confidence\":0.8}]}",
            "{\"predictions\":[{\"segmentation_mask\":\"corrupted\"}]}",
            "{\"predictions\":[{\"points\":[]}]}", "{\"predictions\":[]} trailing data")
        for (payload in invalid) {
            try {
                RoboflowMasks.parse(payload, 4, 4, 0.4f)
                fail("Expected invalid segmentation response")
            } catch (expected: AnalysisException) {
                assertTrue(expected.message!!.contains("segmentation"))
            }
        }
    }

    @Test fun malformedLaterCultureCannotProducePartialMeasurement() {
        val payload = JSONObject().put("predictions", JSONArray().put(rectangle(0, 0, 4, 4)).put(JSONObject()))
        assertThrows(AnalysisException::class.java) { RoboflowMasks.parse(payload.toString(), 4, 4, 0.4f) }
    }

    @Test fun masksSurvivePngRoundtripIncludingLowClassIds() {
        val original = BinaryMask(2, 2, byteArrayOf(0, 1, 1, 0))
        val bitmap = ImageProcessing.maskToBitmap(original)
        assertArrayEquals(original.pixels, ImageProcessing.bitmapToMask(bitmap).pixels)
        val encoded = png(intArrayOf(Color.BLACK, Color.rgb(1, 1, 1), Color.WHITE, Color.TRANSPARENT), 2, 2)
        assertArrayEquals(original.pixels, RoboflowMasks.parse(JSONObject().put("segmentation_mask", encoded).toString(), 2, 2, 0.4f).pixels)
    }

    @Test fun overlayLeavesSourceUntouchedAndExcludesOutsideCulture() {
        val source = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val dish = RoboflowMasks.parse(JSONObject().put("predictions", JSONArray().put(rectangle(1, 1, 4, 4))).toString(), 5, 5, 0f)
        val result = ImageProcessing.overlay(source, DetectionResult(dish, BinaryMask(5, 5, ByteArray(25) { 1 })))
        assertEquals(Color.BLUE, result.getPixel(0, 0))
        assertNotEquals(Color.BLUE, result.getPixel(2, 2))
        assertEquals(Color.BLUE, source.getPixel(2, 2))
    }

    @Test fun claheProducesFiniteLuminanceAndPreservesInput() {
        val input = ByteArray(64 * 64) { (70 + (it % 64) / 4).toByte() }
        val copy = input.copyOf()
        val enhanced = ImageProcessing.clahe(input, 64, 64, 4, 2.0)
        assertArrayEquals(copy, input)
        assertEquals(input.size, enhanced.size)
        assertFalse(input.contentEquals(enhanced))
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(80, 80, 80)) }
        assertSame(source, ImageProcessing.preprocess(source, AnalysisSettings(useClahe = false)))
        val output = ImageProcessing.preprocess(source, AnalysisSettings(useClahe = true))
        assertEquals(source.width, output.width)
        assertEquals(source.height, output.height)
        assertEquals(Color.rgb(80, 80, 80), source.getPixel(0, 0))
        val p = output.getPixel(0, 0)
        assertTrue(kotlin.math.abs(Color.red(p) - Color.green(p)) <= 1)
        assertTrue(kotlin.math.abs(Color.green(p) - Color.blue(p)) <= 1)
    }

    @Test fun claheClipsAndRedistributesHistogramBeforeCumulativeMapping() {
        // For 256 pixels at 128 and clip limit 1, 255 excess samples spread across bins 0..254.
        // At bin 128 the cumulative count is 130; round(130 * 255 / 256) is 129.
        val output = ImageProcessing.clahe(ByteArray(256) { 128.toByte() }, 16, 16, 1, 1.0)
        assertTrue(output.all { (it.toInt() and 255) == 129 })
    }

    @Test fun errorsNeverExposeNetworkExceptionSecrets() {
        val error = AnalysisEngine.safeNetworkError(IOException("token SECRET at https://example.invalid?api_key=SECRET"))
        assertFalse(error.toString().contains("SECRET"))
        assertNull(error.cause)
        assertTrue(AnalysisEngine.httpError(401).contains("project maintainer"))
        assertFalse(AnalysisEngine.httpError(401).contains("API key"))
        assertTrue(AnalysisEngine.httpError(429).contains("usage limit"))
    }

    @Test fun fullConfidenceUsesLegacyRoutePercentageWithoutChangingFractionalValues() {
        assertEquals("0.0", AnalysisEngine.wireConfidence(0f))
        assertEquals("0.3", AnalysisEngine.wireConfidence(0.3f))
        assertEquals("0.99", AnalysisEngine.wireConfidence(0.99f))
        assertEquals("100.0", AnalysisEngine.wireConfidence(1f))
        assertThrows(IllegalArgumentException::class.java) { AnalysisEngine.wireConfidence(Float.NaN) }
    }

    @Test fun cancellationIsNeverReportedAsInvalidSegmentation() {
        assertThrows(CancellationException::class.java) {
            RoboflowMasks.parse("{\"predictions\":[]}", 4, 4, 0.4f) { throw CancellationException("cancelled") }
        }
    }

    private fun rectangle(x0: Int, y0: Int, x1: Int, y1: Int) = JSONObject().put("confidence", 0.9).put("points", JSONArray()
        .put(JSONObject().put("x", x0).put("y", y0)).put(JSONObject().put("x", x1).put("y", y0))
        .put(JSONObject().put("x", x1).put("y", y1)).put(JSONObject().put("x", x0).put("y", y1)))

    private fun png(pixels: IntArray, width: Int, height: Int): String {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }
}
