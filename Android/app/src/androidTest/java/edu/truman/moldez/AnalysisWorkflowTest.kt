package edu.truman.moldez

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.truman.moldez.core.*
import edu.truman.moldez.data.SessionStore
import edu.truman.moldez.ui.MoldEZViewModel
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A synthetic mask exercises real image/session/edit/export code without claiming model accuracy. */
@RunWith(AndroidJUnit4::class)
class AnalysisWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun savedMaskCanBeRestoredEditedUndoneAndSaved() {
        lateinit var vm:MoldEZViewModel
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[MoldEZViewModel::class.java]}
        compose.waitUntil(15_000) {!vm.state.loading}
        val context=compose.activity
        val photo=File(context.cacheDir,"synthetic-workflow-photo.png")
        val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(225,215,180))}
        photo.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        val store=SessionStore(context)
        val asset=store.importImage(Uri.fromFile(photo))
        val dish=BinaryMask(128,128,ByteArray(128*128){1})
        val culture=BinaryMask(128,128,ByteArray(128*128)).apply {
            for(y in 50..70) for(x in 50..70) pixels[y*128+x]=1
        }
        val record=store.saveAnalysis(asset,DetectionResult(dish,culture),AnalysisSettings(diameterMm=90.0),false)
        compose.runOnIdle {vm.openRecord(record)}
        compose.waitUntil(15_000) {!vm.state.busy && vm.state.detection!=null}
        assertEquals(441L,vm.state.measurement!!.culturePixels)
        assertEquals(93.0,vm.state.measurement!!.effectiveDiameterMm,0.0)
        assertEquals("Restored controls must match saved calibration",90.0,vm.state.settings.diameterMm,0.0)
        compose.onNodeWithText("Edit mask").performScrollTo().performClick()
        compose.onNodeWithText("Draw").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {vm.paint(listOf(20f to 20f,25f to 25f),6f,false)}
        compose.waitUntil(15_000) {!vm.state.busy && vm.state.edited}
        assertTrue(vm.state.measurement!!.culturePixels>441)
        assertEquals(1,vm.state.undoCount)
        compose.runOnIdle {vm.undo()}
        compose.waitUntil(15_000) {!vm.state.busy && vm.state.redoCount==1}
        assertEquals(441L,vm.state.measurement!!.culturePixels)
        compose.runOnIdle {vm.redo()}
        compose.waitUntil(15_000) {!vm.state.busy && vm.state.redoCount==0}
        assertTrue(vm.state.measurement!!.culturePixels>441)
        compose.runOnIdle {vm.saveCurrent()}
        compose.waitUntil(15_000) {!vm.state.busy && vm.state.savedCurrent}
        assertTrue(vm.state.activeSession!!.analyses.last().edited)
        assertNotNull(store.loadDetection(vm.state.activeSession!!.analyses.last()))
        assertTrue("Gallery source must survive every operation",photo.isFile)
    }
}
