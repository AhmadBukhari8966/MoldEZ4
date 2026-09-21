package edu.truman.moldez.core

import kotlin.math.PI

enum class Calibration { WINDOWS, STANDARD }

data class AnalysisSettings(
    val diameterMm: Double = 100.0,
    val dishConfidence: Float = 0.30f,
    val cultureConfidence: Float = 0.40f,
    val useClahe: Boolean = false,
    val claheClip: Double = 2.0,
    val claheTiles: Int = 8,
    val calibration: Calibration = Calibration.WINDOWS,
    val dishModel: String = "moldez_dish_finder/4",
    val cultureModel: String = "moldez_segmentation/6",
    val intervalSeconds: Int = 600,
) {
    fun validate() {
        require(diameterMm.isFinite() && diameterMm > 0 && diameterMm <= 10000) { "Enter a dish diameter between 0 and 10,000 mm." }
        require(dishConfidence in 0f..1f && cultureConfidence in 0f..1f) { "Confidence must be between 0 and 100%." }
        require(claheClip in 0.1..40.0 && claheTiles in 2..32) { "Invalid contrast settings." }
        require(Regex("[A-Za-z0-9_-]+/[0-9]+").matches(dishModel) && Regex("[A-Za-z0-9_-]+/[0-9]+").matches(cultureModel)) { "Model IDs must use project/version format." }
        require(intervalSeconds in 10..86400) { "Capture interval must be between 10 seconds and 24 hours." }
    }
}

data class BinaryMask(val width: Int, val height: Int, val pixels: ByteArray) {
    init { require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) }
    fun count(): Long = pixels.count { it.toInt() != 0 }.toLong()
    fun deepCopy() = BinaryMask(width, height, pixels.copyOf())
}

data class DetectionResult(val dish: BinaryMask, val culture: BinaryMask) {
    init { require(dish.width == culture.width && dish.height == culture.height) }
    fun deepCopy() = DetectionResult(dish.deepCopy(), culture.deepCopy())
}

data class ImageAsset(val id: String, val fileName: String, val imagePath: String, val capturedAt: Long)

data class Measurement(
    val platePixels: Long,
    val culturePixels: Long,
    val enteredDiameterMm: Double,
    val effectiveDiameterMm: Double,
    val plateAreaMm2: Double,
    val cultureAreaMm2: Double,
    val rawCoveragePercent: Double,
    val coveragePercent: Double,
)

object Measurements {
    fun calculate(dishPixels: Long, culturePixels: Long, settings: AnalysisSettings): Measurement {
        settings.validate()
        require(dishPixels > 0) { "No dish detected. Adjust confidence or choose another photo." }
        require(culturePixels in 0..dishPixels) { "Culture pixels must be inside the dish." }
        val diameter = settings.diameterMm + if (settings.calibration == Calibration.WINDOWS) 3.0 else 0.0
        // Windows uses this pi approximation; retain it for numerical parity.
        val pi = if (settings.calibration == Calibration.WINDOWS) 3.14159 else PI
        val area = pi * (diameter / 2) * (diameter / 2)
        val ratio = culturePixels.toDouble() / dishPixels
        return Measurement(dishPixels, culturePixels, settings.diameterMm, diameter, area, area * ratio,
            ratio * 100.0, ratio * if (settings.calibration == Calibration.WINDOWS) 105.0 else 100.0)
    }
}

data class AnalysisRecord(
    val id: String,
    val fileName: String,
    val timestamp: Long,
    val capturedAt: Long,
    val imagePath: String,
    val overlayPath: String,
    val dishMaskPath: String,
    val cultureMaskPath: String,
    val settings: AnalysisSettings,
    val measurement: Measurement,
    val edited: Boolean = false,
)

data class Session(val id: String, val name: String, val createdAt: Long, val analyses: List<AnalysisRecord>)

data class GrowthComparison(val hours: Double, val coverageChange: Double, val areaChange: Double, val areaPerHour: Double, val radialMmPerHour: Double)

fun compareAnalyses(first: AnalysisRecord, second: AnalysisRecord, manualHours: Double? = null): GrowthComparison {
    val ordered = listOf(first, second).sortedBy { it.timestamp }
    val a = ordered[0]; val b = ordered[1]
    require(a.settings.calibration == b.settings.calibration) { "Compare analyses using the same calibration." }
    require(kotlin.math.abs(a.measurement.enteredDiameterMm - b.measurement.enteredDiameterMm) < 0.001) { "Compare analyses with the same dish diameter." }
    val hours = manualHours ?: (b.timestamp - a.timestamp).toDouble() / 3_600_000
    require(hours.isFinite() && hours > 0) { "Enter an elapsed time greater than zero." }
    val areaChange = b.measurement.cultureAreaMm2 - a.measurement.cultureAreaMm2
    return GrowthComparison(hours, b.measurement.coveragePercent-a.measurement.coveragePercent, areaChange,
        areaChange/hours, (kotlin.math.sqrt(b.measurement.cultureAreaMm2/PI)-kotlin.math.sqrt(a.measurement.cultureAreaMm2/PI))/hours)
}
