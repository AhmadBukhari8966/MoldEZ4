package edu.truman.moldez.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.truman.moldez.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs against real Android PDF natives; Robolectric does not implement PdfDocument. */
@RunWith(AndroidJUnit4::class)
class PdfExportTest {
    @Test fun pdfIsReadableAndIncludesAnImagePageForEveryAnalysis() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photograph = File(context.cacheDir, "report-source.jpg")
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        photograph.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val store = SessionStore(context)
        val asset = store.importImage(Uri.fromFile(photograph))
        val detection = DetectionResult(BinaryMask(320, 240, ByteArray(76_800) { 1 }), BinaryMask(320, 240, ByteArray(76_800) { if (it < 20_000) 1 else 0 }))
        val saved = store.saveAnalysis(asset, detection, AnalysisSettings(), false)
        val records = (1..21).map { index -> saved.copy(id = "report-$index", fileName = "Image $index, a long photograph name.jpg") }
        val output = File(context.cacheDir, "analysis-report.pdf")
        output.outputStream().use { ReportExporter.pdf(context, Session("report", "Growth study", System.currentTimeMillis(), records), it) }
        assertTrue(output.length() > 1000)
        val signature = ByteArray(4)
        output.inputStream().use { assertEquals(4, it.read(signature)) }
        assertEquals("%PDF", String(signature))
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                // At least two summary pages, plus a page for every analysis.
                assertTrue(renderer.pageCount >= records.size + 2)
                (0 until renderer.pageCount).forEach { pageIndex ->
                    renderer.openPage(pageIndex).use { page ->
                        assertEquals(595, page.width)
                        assertEquals(842, page.height)
                        val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertEquals("Reports must have an opaque white paper background", Color.WHITE, rendered.getPixel(0, 0))
                            val pixels = IntArray(rendered.width * rendered.height)
                            rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
                            assertTrue("Page ${pageIndex + 1} must contain visible content", pixels.count { Color.alpha(it) > 0 && Color.red(it) < 220 } > 100)
                            if (pageIndex == 0 || pageIndex == renderer.pageCount - 1) {
                                File(context.cacheDir, "report-page-${pageIndex + 1}.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            }
                        } finally { rendered.recycle() }
                    }
                }
            }
        }
    }
}
