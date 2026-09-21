package edu.truman.moldez.data

import edu.truman.moldez.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportExporterTest {
    @Test fun csvQuotesNamesEscapesFormulasAndIncludesBothCoverageValues() {
        val settings = AnalysisSettings(calibration = Calibration.WINDOWS)
        val record = AnalysisRecord("1", "=SUM(A1:A2),\"culture\".jpg", 1_700_000_000_000L, 1_699_000_000_000L,
            "", "", "", "", settings, Measurements.calculate(100, 100, settings), true)
        val output = ByteArrayOutputStream()
        ReportExporter.csv(Session("session", "A\nB", 1_700_000_000_000L, listOf(record)), output)
        val csv = output.toString("UTF-8")
        assertTrue(csv.startsWith("\uFEFFSession,Photograph,"))
        assertTrue(csv.contains("\"A\nB\""))
        assertTrue(csv.contains("\"'=SUM(A1:A2),\"\"culture\"\".jpg\""))
        assertTrue(csv.contains(",WINDOWS,true,100,103,100,100,100,105,"))
        assertTrue(csv.endsWith("\r\n"))
    }

}
