package edu.truman.moldez.data

import android.content.Context
import edu.truman.moldez.core.AnalysisSettings
import edu.truman.moldez.core.Calibration

/** User preferences; detection credentials are supplied by the application build. */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("moldez_preferences", Context.MODE_PRIVATE)
    fun darkMode() = prefs.getBoolean("dark", false)
    fun activeSessionId() = prefs.getString("active_session", "") ?: ""
    fun setActiveSessionId(id: String) { prefs.edit().putString("active_session", id).apply() }
    fun setDarkMode(value: Boolean) { prefs.edit().putBoolean("dark", value).apply() }
    fun settings() = AnalysisSettings(
        diameterMm = prefs.getString("diameter", "100")!!.toDoubleOrNull() ?: 100.0,
        dishConfidence = prefs.getFloat("dish_conf", .30f), cultureConfidence = prefs.getFloat("culture_conf", .40f),
        useClahe = prefs.getBoolean("clahe", false), claheClip = prefs.getString("clip", "2")!!.toDoubleOrNull() ?: 2.0,
        claheTiles = prefs.getInt("tiles", 8),
        calibration = runCatching { Calibration.valueOf(prefs.getString("calibration", "WINDOWS")!!) }.getOrDefault(Calibration.WINDOWS),
        dishModel = prefs.getString("dish_model", "moldez_dish_finder/4")!!,
        cultureModel = prefs.getString("culture_model", "moldez_segmentation/6")!!,
        intervalSeconds = prefs.getInt("interval", 600),
    )
    fun setSettings(value: AnalysisSettings) {
        value.validate()
        prefs.edit().putString("diameter", value.diameterMm.toString()).putFloat("dish_conf", value.dishConfidence)
            .putFloat("culture_conf", value.cultureConfidence).putBoolean("clahe", value.useClahe)
            .putString("clip", value.claheClip.toString()).putInt("tiles", value.claheTiles)
            .putString("calibration", value.calibration.name).putString("dish_model", value.dishModel)
            .putString("culture_model", value.cultureModel).putInt("interval", value.intervalSeconds).apply()
    }
}
