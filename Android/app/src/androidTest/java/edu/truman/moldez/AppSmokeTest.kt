package edu.truman.moldez

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.truman.moldez.data.AppPreferences
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun appLaunchesWithBundledModelAccessAndNoCredentialEntry() {
        compose.waitUntil(15_000) {compose.onAllNodesWithText("From culture to clarity").fetchSemanticsNodes().isNotEmpty()}
        assertTrue("Bundled model access must be available without entering a credential", BuildConfig.ROBOFLOW_API_KEY.isNotBlank())
        compose.onNodeWithText("From culture to clarity").assertIsDisplayed()
        compose.onNodeWithText("Set up").assertDoesNotExist()
        compose.onNodeWithText("Connect your Roboflow account to start analyzing cultures.").assertDoesNotExist()
        if (AppPreferences(compose.activity).darkMode()) {
            compose.onNodeWithContentDescription("Toggle appearance").performClick()
        }
        captureUi("analyze")
        compose.onNodeWithContentDescription("Toggle appearance").performClick()
        captureUi("dark")
        compose.onNodeWithContentDescription("Toggle appearance").performClick()
        compose.onNodeWithText("Settings",useUnmergedTree=true).assertDoesNotExist()
        listOf("API key", "Replacement API key", "Save key", "Remove key", "Open Roboflow API settings").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("Sessions",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Your research, organized").assertIsDisplayed()
        captureUi("sessions")
        compose.onNodeWithText("Capture",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Watch your culture grow").assertIsDisplayed()
        captureUi("capture")
    }

    private fun captureUi(screen: String) {
        compose.waitForIdle()
        val form = if (compose.activity.resources.configuration.screenWidthDp >= 900) "tablet" else "phone"
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(compose.activity.cacheDir, "ui-$form-$screen.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
