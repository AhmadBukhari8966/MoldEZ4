package edu.truman.moldez.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import edu.truman.moldez.core.AnalysisRecord
import edu.truman.moldez.core.Calibration
import edu.truman.moldez.core.Session
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Generates complete reports on a worker thread; the caller owns the output stream. */
object ReportExporter {
    fun csv(session: Session, output: OutputStream) {
        // UTF-8 BOM makes non-ASCII photograph/session names portable to spreadsheet applications.
        output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val header = listOf("Session", "Photograph", "Analysis time", "Capture time", "Calibration", "Manually edited",
            "Entered diameter (mm)", "Effective diameter (mm)", "Dish pixels", "Culture pixels", "Raw coverage (%)",
            "Reported coverage (%)", "Dish area (mm^2)", "Culture area (mm^2)", "Dish confidence", "Culture confidence",
            "CLAHE enabled", "CLAHE clip", "CLAHE tiles", "Dish model", "Culture model")
        fun row(values: List<String>) {
            output.write((values.joinToString(",") { escapeCsv(it) } + "\r\n").toByteArray(Charsets.UTF_8))
        }
        row(header)
        session.analyses.forEach { r ->
            val m = r.measurement
            row(listOf(session.name, r.fileName, isoDate(r.timestamp), isoDate(r.capturedAt), r.settings.calibration.name,
                r.edited.toString(), number(m.enteredDiameterMm), number(m.effectiveDiameterMm), m.platePixels.toString(),
                m.culturePixels.toString(), number(m.rawCoveragePercent), number(m.coveragePercent), number(m.plateAreaMm2),
                number(m.cultureAreaMm2), number(r.settings.dishConfidence.toDouble()), number(r.settings.cultureConfidence.toDouble()),
                r.settings.useClahe.toString(), number(r.settings.claheClip), r.settings.claheTiles.toString(),
                r.settings.dishModel, r.settings.cultureModel))
        }
        output.flush()
    }

    fun pdf(context: Context, session: Session, output: OutputStream) {
        val document = PdfDocument()
        val layout = PdfLayout(document, session.name)
        try {
            layout.startPage()
            layout.heading("MoldEZ", 27f)
            layout.heading("Culture analysis report", 17f)
            layout.paragraph(session.name, 13f)
            layout.paragraph("Created ${date(session.createdAt)} | Exported ${date(System.currentTimeMillis())}", 9f, Color.DKGRAY)
            layout.paragraph("${session.analyses.size} saved ${if (session.analyses.size == 1) "analysis" else "analyses"}", 10f)
            layout.gap(8f)
            if (session.analyses.any { it.settings.calibration == Calibration.WINDOWS }) {
                layout.paragraph("Windows calibration: effective dish diameter = entered diameter + 3 mm; reported coverage = culture/dish pixel ratio x 105. Coverage can reach 105%. Areas use the unadjusted pixel ratio and pi = 3.14159.", 9f, Color.DKGRAY)
            }
            if (session.analyses.any { it.settings.calibration == Calibration.STANDARD }) {
                layout.paragraph("Standard calibration: the entered diameter sets dish area; reported coverage = culture/dish pixel ratio x 100 (0-100%).", 9f, Color.DKGRAY)
            }
            layout.paragraph("Measurements depend on the selected dish diameter and saved segmentation. Capture time comes from photograph metadata when available, otherwise import time. All displayed times include the local time-zone offset.", 9f, Color.DKGRAY)
            layout.gap(10f)
            layout.heading("Measurements", 15f)
            if (session.analyses.isEmpty()) layout.paragraph("This session has no saved analyses.", 11f)
            else {
                layout.tableHeader()
                session.analyses.forEachIndexed { index, r -> layout.tableRow(index + 1, r) }
            }
            session.analyses.forEachIndexed { index, record ->
                layout.startPage()
                layout.heading("Analysis ${index + 1}", 19f)
                layout.paragraph(record.fileName, 13f)
                layout.paragraph("Analyzed: ${date(record.timestamp)}", 9f)
                layout.paragraph("Captured: ${date(record.capturedAt)}", 9f)
                layout.paragraph("${if (record.edited) "Manually edited masks" else "Model detection"} | ${calibrationName(record)} calibration", 10f)
                val m = record.measurement
                layout.gap(6f)
                layout.paragraph("Reported coverage: ${fixed(m.coveragePercent)}%   |   Raw coverage: ${fixed(m.rawCoveragePercent)}%", 12f)
                layout.paragraph("Culture area: ${fixed(m.cultureAreaMm2)} mm²   |   Dish area: ${fixed(m.plateAreaMm2)} mm²", 11f)
                layout.paragraph("Entered diameter: ${fixed(m.enteredDiameterMm)} mm   |   Effective diameter: ${fixed(m.effectiveDiameterMm)} mm", 10f)
                layout.paragraph("Pixels: ${m.culturePixels} culture / ${m.platePixels} dish", 9f)
                layout.gap(8f)
                layout.drawPhotograph(context, record.overlayPath, "Saved segmentation overlay", 300f)
                layout.paragraph("Dish model: ${record.settings.dishModel} | Confidence: ${fixed(record.settings.dishConfidence * 100.0)}%", 9f)
                layout.paragraph("Culture model: ${record.settings.cultureModel} | Confidence: ${fixed(record.settings.cultureConfidence * 100.0)}%", 9f)
                layout.paragraph(if (record.settings.useClahe) "Contrast enhancement: CLAHE, clip ${fixed(record.settings.claheClip)}, ${record.settings.claheTiles} x ${record.settings.claheTiles} tiles" else "Contrast enhancement: off", 9f)
            }
            layout.finish()
            document.writeTo(output)
            output.flush()
        } finally { layout.finish(); document.close() }
    }

    private fun escapeCsv(value: String): String {
        // Prevent names imported from files or archives from becoming spreadsheet formulas.
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
    }

    private fun calibrationName(record: AnalysisRecord) = if (record.settings.calibration == Calibration.WINDOWS) "Windows" else "Standard"
    private fun number(value: Double) = String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
    private fun fixed(value: Double) = String.format(Locale.US, "%.2f", value)
    private fun date(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.US).format(Date(millis))
    private fun isoDate(millis: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(millis))

    private class PdfLayout(private val document: PdfDocument, private val sessionName: String) {
        private val pageWidth = 595
        private val pageHeight = 842
        private val margin = 38f
        private val contentWidth = pageWidth - margin * 2
        private val bottom = pageHeight - 48f
        private val ink = Color.rgb(30, 41, 59)
        private val accent = Color.rgb(18, 112, 89)
        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = margin
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val canvas: Canvas get() = checkNotNull(page).canvas

        fun startPage() {
            finish()
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas.drawColor(Color.WHITE)
            y = margin
        }

        fun finish() {
            val current = page ?: return
            style(8f, Color.GRAY)
            current.canvas.drawLine(margin, bottom + 12f, pageWidth - margin, bottom + 12f, paint)
            current.canvas.drawText("MoldEZ | Page $pageNumber", margin, pageHeight - 25f, paint)
            val footer = ellipsize(sessionName.replace('\n', ' '), 310f)
            current.canvas.drawText(footer, pageWidth - margin - paint.measureText(footer), pageHeight - 25f, paint)
            document.finishPage(current)
            page = null
        }

        private fun style(size: Float, color: Int = ink, bold: Boolean = false) {
            paint.color = color
            paint.textSize = size
            paint.typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            paint.style = Paint.Style.FILL
        }

        private fun ensure(height: Float): Boolean {
            if (y + height <= bottom) return false
            startPage()
            return true
        }

        fun heading(text: String, size: Float) {
            paragraph(text, size, accent, true)
            gap(4f)
        }

        fun gap(height: Float) { ensure(height); y += height }

        fun paragraph(text: String, size: Float, color: Int = ink, bold: Boolean = false) {
            style(size, color, bold)
            val lines = wrap(text, contentWidth)
            val lineHeight = size * 1.4f
            lines.forEach {
                ensure(lineHeight)
                style(size, color, bold)
                canvas.drawText(it, margin, y + size, paint)
                y += lineHeight
            }
            y += 4f
        }

        fun tableHeader() {
            ensure(28f)
            style(10f, accent)
            canvas.drawRect(margin, y, pageWidth - margin, y + 25f, paint)
            style(8f, Color.WHITE, true)
            canvas.drawText("Photograph / analyzed", margin + 6, y + 16, paint)
            canvas.drawText("Coverage", margin + 233, y + 16, paint)
            canvas.drawText("Culture mm²", margin + 306, y + 16, paint)
            canvas.drawText("Calibration", margin + 409, y + 16, paint)
            y += 28
        }

        fun tableRow(index: Int, record: AnalysisRecord) {
            style(9f)
            val lines = wrap("$index. ${record.fileName}", 218f)
            val rowHeight = maxOf(42f, (lines.size * 12 + 24).toFloat())
            if (ensure(rowHeight)) tableHeader()
            style(9f)
            lines.forEachIndexed { line, text -> canvas.drawText(text, margin + 6f, y + 12f + line * 12f, paint) }
            style(8f, Color.DKGRAY)
            canvas.drawText(date(record.timestamp), margin + 6f, y + lines.size * 12f + 12f, paint)
            style(9f)
            canvas.drawText(fixed(record.measurement.coveragePercent) + "%", margin + 233f, y + 12f, paint)
            canvas.drawText(fixed(record.measurement.cultureAreaMm2), margin + 306f, y + 12f, paint)
            canvas.drawText(calibrationName(record), margin + 409f, y + 12f, paint)
            style(8f, Color.DKGRAY)
            canvas.drawText("${fixed(record.measurement.effectiveDiameterMm)} mm", margin + 409f, y + 27f, paint)
            y += rowHeight
            style(1f, Color.LTGRAY)
            canvas.drawLine(margin, y - 3f, pageWidth - margin, y - 3f, paint)
        }

        fun drawPhotograph(context: Context, path: String, caption: String, maxHeight: Float) {
            var bitmap: Bitmap? = null
            try {
                val file = File(path).canonicalFile
                require(file.path.startsWith(File(context.filesDir, "moldez").canonicalPath + File.separator))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 1..1600 && bounds.outHeight in 1..1600)
                bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                    ?: error("Missing photograph")
                val scale = min(contentWidth / bitmap.width, maxHeight / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                ensure(height + 31f)
                val left = margin + (contentWidth - width) / 2
                canvas.drawBitmap(bitmap, null, RectF(left, y, left + width, y + height), paint)
                y += height + 5f
                paragraph(caption, 8f, Color.DKGRAY)
                gap(8f)
            } catch (_: Exception) {
                paragraph("The saved overlay is unavailable for this analysis.", 9f, Color.DKGRAY)
            } finally { bitmap?.recycle() }
        }

        private fun ellipsize(text: String, width: Float): String {
            if (paint.measureText(text) <= width) return text
            val end = paint.breakText(text, true, width - paint.measureText("..."), null)
            return text.take(end) + "..."
        }

        /** Breaks long tokens too, so filenames and user text can never run out of a column. */
        private fun wrap(text: String, width: Float): List<String> = buildList {
            text.replace('\r', ' ').split('\n').forEach { paragraph ->
                var remaining = paragraph
                if (remaining.isEmpty()) add("")
                while (remaining.isNotEmpty()) {
                    val fit = paint.breakText(remaining, true, width, null).coerceAtLeast(1)
                    if (fit >= remaining.length) { add(remaining); break }
                    val boundary = remaining.lastIndexOf(' ', fit).takeIf { it > 0 } ?: fit
                    add(remaining.take(boundary).trimEnd())
                    remaining = remaining.drop(boundary).trimStart()
                }
            }
        }
    }
}
