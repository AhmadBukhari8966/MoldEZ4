package edu.truman.moldez.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import edu.truman.moldez.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: SessionStore

    @Before fun prepare() {
        File(context.filesDir, "moldez").deleteRecursively()
        store = SessionStore(context)
    }

    @Test fun savedAnalysisSurvivesSourceRemovalAndLibraryRestart() {
        val source = imageFile("original.jpg", 8, 6)
        val asset = store.importImage(Uri.fromFile(source))
        val record = store.saveAnalysis(asset, detection(), AnalysisSettings(diameterMm = 90.0, useClahe = true), true)
        val session = Session("session-1", "Growth study", 1_700_000_000_000L, listOf(record))
        store.save(listOf(session))
        source.delete()
        File(asset.imagePath).delete()
        val loaded = SessionStore(context).load().single()
        assertEquals(session, loaded)
        assertTrue(File(loaded.analyses.single().imagePath).isFile)
        val result = store.loadDetection(loaded.analyses.single())!!
        assertArrayEquals(detection().dish.pixels, result.dish.pixels)
        assertArrayEquals(detection().culture.pixels, result.culture.pixels)
        assertTrue(loaded.analyses.single().edited)
        assertEquals(93.0, loaded.analyses.single().measurement.effectiveDiameterMm, 0.0)
    }

    @Test fun portableArchiveRestoresImagesMasksCalibrationAndDates() {
        val asset = store.importImage(Uri.fromFile(imageFile("petri.jpg", 8, 6)))
        val record = store.saveAnalysis(asset.copy(capturedAt = 1_650_000_000_000L), detection(),
            AnalysisSettings(diameterMm = 85.0, cultureConfidence = .55f, calibration = Calibration.WINDOWS), true)
        val session = Session("portable", "A, B & cultures", 1_690_000_000_000L, listOf(record))
        val output = ByteArrayOutputStream()
        store.exportSession(session, output)
        File(record.imagePath).parentFile!!.deleteRecursively()
        val imported = store.importSession(output.toByteArray().inputStream())
        assertNotEquals(session.id, imported.id)
        assertEquals(session.name, imported.name)
        assertEquals(session.createdAt, imported.createdAt)
        val restored = imported.analyses.single()
        assertNotEquals(record.id, restored.id)
        assertEquals(record.timestamp, restored.timestamp)
        assertEquals(record.capturedAt, restored.capturedAt)
        assertEquals(record.settings, restored.settings)
        assertEquals(record.measurement, restored.measurement)
        assertTrue(restored.edited)
        assertArrayEquals(detection().culture.pixels, store.loadDetection(restored)!!.culture.pixels)
        store.save(listOf(imported))
        assertEquals(imported, store.load().single())
    }

    @Test fun importAppliesExifOrientationAndPreservesCaptureTime() {
        val source = imageFile("rotated.jpg", 80, 40)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:03:02 01:02:03")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-06:00")
            saveAttributes()
        }
        val asset = store.importImage(Uri.fromFile(source))
        val bitmap = BitmapFactory.decodeFile(asset.imagePath)
        assertEquals(40, bitmap.width)
        assertEquals(80, bitmap.height)
        bitmap.recycle()
        assertEquals(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse("2024-03-02T01:02:03-06:00")!!.time, asset.capturedAt)
        assertTrue(source.exists())
    }

    @Test fun importedImageIsLimitedTo1600Pixels() {
        val asset = store.importImage(Uri.fromFile(imageFile("large.jpg", 2400, 1200)))
        val bitmap = BitmapFactory.decodeFile(asset.imagePath)
        assertEquals(1600, bitmap.width)
        assertEquals(800, bitmap.height)
        bitmap.recycle()
    }

    @Test fun traversalArchiveIsRejectedWithoutWritingOutsideStaging() {
        val zip = zipOf(mapOf("../escaped.txt" to "unsafe".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { store.importSession(zip.inputStream()) }
        assertFalse(File(context.filesDir, "moldez/escaped.txt").exists())
        assertTrue(File(context.filesDir, "moldez").listFiles()!!.none { it.name.startsWith("incoming-session-") })
    }

    @Test fun highlyCompressedOversizedEntryIsRejectedDuringExpansion() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("assets/oversized.png"))
            val block = ByteArray(64 * 1024)
            repeat(513) { zip.write(block) } // 32 MiB + 64 KiB from a small compressed payload.
            zip.closeEntry()
        }
        assertTrue(bytes.size() < 100_000)
        val error = assertThrows(IllegalArgumentException::class.java) { store.importSession(bytes.toByteArray().inputStream()) }
        assertTrue(error.message!!.contains("size limit") || error.message!!.contains("too large"))
        assertTrue(File(context.filesDir, "moldez").listFiles()!!.none { it.name.startsWith("incoming-session-") })
    }

    @Test fun archiveWithMeasurementsThatDisagreeWithMasksIsRejected() {
        val record = store.saveAnalysis(store.importImage(Uri.fromFile(imageFile("dish.jpg", 8, 6))), detection(), AnalysisSettings(), false)
        val output = ByteArrayOutputStream()
        store.exportSession(Session("test", "Integrity", 1_700_000_000_000L, listOf(record)), output)
        val entries = unzip(output.toByteArray())
        val manifest = JSONObject(String(entries.getValue("session.json"), Charsets.UTF_8))
        manifest.getJSONObject("session").getJSONArray("analyses").getJSONObject(0).put("culturePixels", 1)
        entries["session.json"] = manifest.toString().toByteArray()
        val error = assertThrows(IllegalArgumentException::class.java) { store.importSession(zipOf(entries).inputStream()) }
        assertTrue(error.message!!.contains("measurements do not match"))
        assertTrue(File(record.imagePath).exists())
    }

    @Test fun corruptLibraryIsReportedAndKept() {
        val file = File(context.filesDir, "moldez/sessions.json")
        file.writeText("not valid JSON")
        assertThrows(IllegalStateException::class.java) { store.load() }
        assertEquals("not valid JSON", file.readText())
    }

    @Test fun explicitRecoveryKeepsUnreadableLibraryBackupBeforeSaving() {
        val file = File(context.filesDir, "moldez/sessions.json")
        file.writeText("unreadable saved library")
        assertThrows(IllegalStateException::class.java) { store.load() }
        store.save(listOf(Session("recovered", "New session", 1_700_000_000_000L, emptyList())))
        assertEquals("New session", store.load().single().name)
        val backups = File(context.filesDir, "moldez/recovery").listFiles()!!.toList()
        assertEquals(1, backups.size)
        assertEquals("unreadable saved library", backups.single().readText())
    }

    private fun imageFile(name: String, width: Int, height: Int): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(195, 180, 146)) }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        return file
    }

    private fun detection() = DetectionResult(BinaryMask(8, 6, ByteArray(48) { 1 }), BinaryMask(8, 6, ByteArray(48) { if (it < 12) 1 else 0 }))

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
        } }
        bytes.toByteArray()
    }

    private fun unzip(bytes: ByteArray): MutableMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
                zip.closeEntry()
            }
        }
    }
}
