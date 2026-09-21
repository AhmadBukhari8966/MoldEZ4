package edu.truman.moldez

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the installed app locally without making a cloud inference request. */
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun awaitStartup() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Choose photo").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun navigationAndWindowsCalibrationAreAvailable() {
        awaitStartup()
        compose.onNodeWithText("Choose photo").assertIsDisplayed()
        compose.onNodeWithText("Run detection").assertIsNotEnabled()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Make MoldEZ yours").assertIsDisplayed()
        compose.onNodeWithText("Match Windows measurements").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+3 mm diameter · ×105 coverage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Capture", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Watch your culture grow").assertIsDisplayed()
        compose.onNodeWithText("Sessions", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your research, organized").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("From culture to clarity").assertIsDisplayed()
    }

    @Test fun sessionCreationSurvivesActivityRecreation() {
        awaitStartup()
        compose.onNodeWithText("Sessions", useUnmergedTree = true).performClick()
        compose.onNodeWithText("New").performClick()
        val sessionName = "Device test ${System.currentTimeMillis()}"
        compose.onNode(hasSetTextAction()).performTextReplacement(sessionName)
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(sessionName).fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(sessionName).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(sessionName).assertIsDisplayed()
    }
}
