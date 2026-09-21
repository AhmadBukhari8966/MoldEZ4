package edu.truman.moldez.core

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real responses to a generated image; replayed locally without network or credentials. */
@RunWith(AndroidJUnit4::class)
class LiveResponseFixtureTest {
    @Test fun androidDecodesLiveSemanticResponsesAndClipsCultureToDish() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val source = assets.open("live-fixture-source.jpg").use { BitmapFactory.decodeStream(it)!! }
        try {
            assertEquals(768, source.width)
            assertEquals(768, source.height)
            val dish = RoboflowMasks.parse(assets.open("live-fixture-dish.json").bufferedReader().use { it.readText() },
                source.width, source.height, 0.3f, firstOnly = true)
            val culture = RoboflowMasks.parse(assets.open("live-fixture-culture.json").bufferedReader().use { it.readText() },
                source.width, source.height, 0.4f)

            // Counts independently derived from the original PNG bytes, not Android BitmapFactory.
            assertEquals(280_916L, dish.count())
            assertEquals(104_863L, culture.count())
            for (i in culture.pixels.indices) if (dish.pixels[i].toInt() == 0) culture.pixels[i] = 0
            assertEquals(104_518L, culture.count())
            assertTrue(culture.count() in 1..dish.count())

            val measurement = Measurements.calculate(dish.count(), culture.count(), AnalysisSettings())
            assertEquals(103.0, measurement.effectiveDiameterMm, 0.0)
            assertEquals(104_518.0 / 280_916 * 105, measurement.coveragePercent, 0.000001)
            val overlay = ImageProcessing.overlay(source, DetectionResult(dish, culture))
            assertEquals(source.width, overlay.width)
            assertEquals(source.height, overlay.height)
            overlay.recycle()
        } finally { source.recycle() }
    }
}
