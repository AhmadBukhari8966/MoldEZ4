package edu.truman.moldez.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun CaptureScreen(state:AppUiState,vm:MoldEZViewModel) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val view=LocalView.current
    var permission by remember {mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {permission=it}
    val controller=remember {LifecycleCameraController(context).apply {
        setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        imageCaptureMode=ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
    }}
    var cameraReady by remember {mutableStateOf(false)}
    var interval by rememberSaveable(state.settings.intervalSeconds) {mutableStateOf((state.settings.intervalSeconds/60.0).toString().removeSuffix(".0"))}
    DisposableEffect(permission,owner) {
        if(permission) runCatching {controller.bindToLifecycle(owner);cameraReady=true}.onFailure {vm.error("Camera unavailable: ${it.message}")}
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_STOP) vm.stopAutomation()
            if(event==Lifecycle.Event.ON_RESUME) permission=ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer);controller.unbind();vm.stopAutomation()}
    }
    DisposableEffect(state.automationRunning) {
        val old=view.keepScreenOn;view.keepScreenOn=state.automationRunning
        onDispose {view.keepScreenOn=old}
    }
    LaunchedEffect(state.automationRunning,permission,cameraReady) {
        if(state.automationRunning && permission && cameraReady) {
            try {
                while(vm.state.automationRunning) {
                    val captured=captureImage(context,controller)
                    try {if(!vm.analyzeCapture(captured.first)) break} finally {captured.second.delete()}
                    for(seconds in vm.state.settings.intervalSeconds downTo 1) {
                        if(!vm.state.automationRunning) break
                        vm.countdown(seconds);delay(1000)
                    }
                }
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {vm.error("Timed capture stopped: ${e.message ?: "camera unavailable"}");vm.stopAutomation()}
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Watch your culture grow",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text("Capture, analyze and save a time series automatically.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            if(permission) AndroidView(factory={ctx->PreviewView(ctx).apply {this.controller=controller;scaleType=PreviewView.ScaleType.FIT_CENTER}},
                modifier=Modifier.fillMaxWidth().height(330.dp).background(Color.Black))
            else Column(Modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.PhotoCamera,null,Modifier.size(48.dp))
                Text("Allow camera access to start a time series.")
                Button(onClick={request.launch(Manifest.permission.CAMERA)}) {Text("Allow camera")}
                TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}) {Text("Open app permissions")}
            }
        }
        Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("Capture schedule",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                OutlinedTextField(interval,{interval=it},label={Text("Interval between analyses (minutes)")},singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!state.automationRunning && !state.busy,modifier=Modifier.fillMaxWidth())
                Text("The first photo is taken immediately. The next interval begins after each analysis finishes, so requests never overlap.",style=MaterialTheme.typography.bodySmall)
                Text("Saving to ${state.activeSession?.name ?: "session"} · ${state.settings.diameterMm} mm entered diameter",style=MaterialTheme.typography.bodySmall)
                if(state.automationRunning) {
                    Text(if(state.busy) "Analyzing capture…" else "Next capture in ${state.countdown/60}:${(state.countdown%60).toString().padStart(2,'0')}",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                    Text("${state.automationCount} captures saved")
                    Button(onClick=vm::stopAutomation,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) {Icon(Icons.Default.Stop,null);Text("Stop capture")}
                } else Button(onClick={
                    val minutes=interval.toDoubleOrNull()
                    if(minutes==null || !minutes.isFinite() || minutes<1.0/6 || minutes>1440) vm.error("Enter an interval between 0.167 and 1,440 minutes.")
                    else if(vm.updateSettings(state.settings.copy(intervalSeconds=(minutes*60).toInt().coerceAtLeast(10)))) vm.startAutomation()
                },enabled=permission && cameraReady && !state.busy,modifier=Modifier.fillMaxWidth()) {Icon(Icons.Default.PlayArrow,null);Text("Start time series")}
            }
        }
        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
            Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Info,null)
                Text("Keep this screen open and the phone positioned above the dish. MoldEZ keeps the display awake during capture and stops the schedule when you leave the app or this screen. Each analysis requires internet.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private suspend fun captureImage(context:Context,controller:LifecycleCameraController):Pair<Uri,File> = suspendCancellableCoroutine {continuation->
    val directory=File(context.cacheDir,"captures").apply {mkdirs()}
    val file=File.createTempFile("series_",".jpg",directory)
    val options=ImageCapture.OutputFileOptions.Builder(file).build()
    try {
        controller.takePicture(options,ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result:ImageCapture.OutputFileResults) {
                if(continuation.isActive) continuation.resume(FileProvider.getUriForFile(context,"${context.packageName}.files",file) to file)
                else file.delete()
            }
            override fun onError(exception:ImageCaptureException) {file.delete();if(continuation.isActive) continuation.resumeWithException(exception)}
        })
    } catch(e:Exception) {file.delete();if(continuation.isActive) continuation.resumeWithException(e)}
}
