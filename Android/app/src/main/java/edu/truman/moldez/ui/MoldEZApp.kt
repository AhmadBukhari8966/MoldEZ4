@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package edu.truman.moldez.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.truman.moldez.R
import edu.truman.moldez.BuildConfig
import edu.truman.moldez.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

private val Navy=Color(0xFF20364E)
private val Teal=Color(0xFF16877F)
private val Mint=Color(0xFF2DB99C)
private val LightScheme=lightColorScheme(primary=Teal,secondary=Navy,tertiary=Color(0xFF5D76BB),background=Color(0xFFF3F6FA),surface=Color.White,
    surfaceVariant=Color(0xFFEAF0F5),surfaceTint=Teal,primaryContainer=Color(0xFFDDF5EE),onPrimaryContainer=Color(0xFF073E36),
    secondaryContainer=Color(0xFFDCEDEB),onSecondaryContainer=Navy,surfaceContainer=Color(0xFFEAF0F5),surfaceContainerLow=Color(0xFFF0F4F8),
    surfaceContainerHigh=Color(0xFFE3EBF1),surfaceContainerHighest=Color(0xFFDDE6EE),onSurface=Color(0xFF1B2B3C),onBackground=Color(0xFF1B2B3C))
private val DarkScheme=darkColorScheme(primary=Color(0xFF69D9C8),secondary=Color(0xFFADC7E7),tertiary=Color(0xFFB7C5FF),background=Color(0xFF101B28),surface=Color(0xFF192838),
    surfaceVariant=Color(0xFF26384B),surfaceTint=Mint,primaryContainer=Color(0xFF164C43),onPrimaryContainer=Color(0xFFB9F4E8),
    secondaryContainer=Color(0xFF254942),onSecondaryContainer=Color(0xFFCEF3EA),surfaceContainer=Color(0xFF1C2D3E),surfaceContainerLow=Color(0xFF172534),
    surfaceContainerHigh=Color(0xFF25384A),surfaceContainerHighest=Color(0xFF2C4154),onSurface=Color(0xFFE0EAF3),onBackground=Color(0xFFE0EAF3))
private enum class Destination(val label:String,val icon:ImageVector) {
    ANALYZE("Analyze",Icons.Default.Science),SESSIONS("Sessions",Icons.Default.FolderOpen),
    CAMERA("Capture",Icons.Default.PhotoCamera),SETTINGS("Settings",Icons.Default.Settings)
}
fun number(value:Double,digits:Int=2)=String.format(Locale.US,"%.${digits}f",value)
fun date(value:Long)=SimpleDateFormat("MMM d, yyyy · HH:mm",Locale.getDefault()).format(Date(value))

@Composable
fun MoldEZApp(vm:MoldEZViewModel=viewModel()) {
    val state=vm.state
    var screen by rememberSaveable { mutableStateOf(Destination.ANALYZE) }
    BackHandler(enabled=screen!=Destination.ANALYZE) {vm.stopAutomation();screen=Destination.ANALYZE}
    val context=LocalContext.current
    SideEffect {
        (context as? Activity)?.let { activity->
            WindowCompat.getInsetsController(activity.window,activity.window.decorView).apply {
                isAppearanceLightStatusBars=false
                isAppearanceLightNavigationBars=!state.darkMode
            }
        }
    }
    val snackbar=remember { SnackbarHostState() }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->uri?.let(vm::selectImage) }
    val batchPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.batch(it) }
    val capture=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok->
        cameraUri?.let { if(ok) vm.selectImage(Uri.parse(it)) }; cameraUri=null
    }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importSession) }
    val exportSession=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { uri->vm.export(uri,"Session") } }
    val exportPdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let { uri->vm.export(uri,"PDF") } }
    val exportCsv=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { uri->vm.export(uri,"CSV") } }
    val exportImage=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {it?.let(vm::exportOverlay)}
    fun launchCamera() {
        runCatching {
            val dir=File(context.cacheDir,"captures").apply { mkdirs() }
            val file=File.createTempFile("photo_",".jpg",dir)
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
            cameraUri=uri.toString(); capture.launch(uri)
        }.onFailure { vm.error("No camera app is available. Try choosing a photo instead.") }
    }
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted->
        if(granted) launchCamera() else vm.error("Camera permission is needed to take a photo. You can still choose one from your gallery.")
    }
    fun takePhoto() {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) launchCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    MaterialTheme(colorScheme=if(state.darkMode) DarkScheme else LightScheme,shapes=Shapes(medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(22.dp))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide=maxWidth>=900.dp
            Scaffold(
                containerColor=MaterialTheme.colorScheme.background,
                topBar={ TopAppBar(title={ Row(verticalAlignment=Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.mascot),contentDescription=null,modifier=Modifier.size(42.dp))
                    Spacer(Modifier.width(10.dp)); Column {
                        Text("MoldEZ",fontWeight=FontWeight.ExtraBold,fontSize=25.sp)
                        Text("CULTURE ANALYSIS",fontSize=9.sp,letterSpacing=2.sp)
                    }
                } },actions={
                    IconButton(onClick={vm.setDarkMode(!state.darkMode)}) { Icon(if(state.darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,"Toggle appearance") }
                },colors=TopAppBarDefaults.topAppBarColors(containerColor=Navy,titleContentColor=Color.White,actionIconContentColor=Color.White)) },
                bottomBar={ if(!wide) NavigationBar { Destination.entries.forEach { destination->
                    NavigationBarItem(selected=screen==destination,onClick={ if(screen!=destination) vm.stopAutomation(); screen=destination },
                        icon={Icon(destination.icon,destination.label)},label={Text(destination.label)})
                } } },snackbarHost={ SnackbarHost(snackbar) }
            ) { padding->
                Row(Modifier.padding(padding).fillMaxSize()) {
                    if(wide) NavigationRail(Modifier.fillMaxHeight()) { Spacer(Modifier.height(16.dp)); Destination.entries.forEach { destination->
                        NavigationRailItem(selected=screen==destination,onClick={if(screen!=destination) vm.stopAutomation(); screen=destination},
                            icon={Icon(destination.icon,destination.label)},label={Text(destination.label)})
                    } }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if(state.loading) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
                        else when(screen) {
                            Destination.ANALYZE->AnalyzeScreen(state,vm,wide,
                                onPick={photoPicker.launch(arrayOf("image/*"))},onCamera={takePhoto()},
                                onBatch={batchPicker.launch(arrayOf("image/*"))},onSaveImage={exportImage.launch("MoldEZ_detection.png")})
                            Destination.SESSIONS->SessionsScreen(state,vm,
                                onOpen={vm.openRecord(it); screen=Destination.ANALYZE},
                                onImport={import.launch(arrayOf("application/zip","application/octet-stream","application/json"))},
                                onExport={type->if(vm.prepareExport()) {
                                    val name=(state.activeSession?.name ?: "MoldEZ").replace(Regex("[^A-Za-z0-9_-]"),"_")
                                    when(type) { "PDF"->exportPdf.launch("${name}_report.pdf"); "CSV"->exportCsv.launch("${name}_results.csv"); else->exportSession.launch("$name.moldez.zip") }
                                }})
                            Destination.CAMERA->CaptureScreen(state,vm)
                            Destination.SETTINGS->SettingsScreen(state,vm)
                        }
                        if(state.busy) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(state.status,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick=vm::cancel) { Text("Cancel") }
                        }
                    }
                }
            }
        }
        state.error?.let { message->AlertDialog(onDismissRequest=vm::dismissError,
            icon={Icon(Icons.Default.Info,null)},title={Text("MoldEZ needs your attention")},
            text={Text(message,Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()))},
            confirmButton={TextButton(onClick=vm::dismissError){Text("OK")}}) }
    }
}

@Composable
private fun SectionCard(title:String,subtitle:String?=null,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Card(modifier,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            subtitle?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}
@Composable
private fun PageHeading(title:String,subtitle:String) {
    Column(Modifier.padding(bottom=8.dp)) {
        Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(4.dp)); Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnalyzeScreen(state:AppUiState,vm:MoldEZViewModel,wide:Boolean,onPick:()->Unit,onCamera:()->Unit,onBatch:()->Unit,onSaveImage:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if(wide) 24.dp else 16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        PageHeading("From culture to clarity","Photograph. Analyze. Measure. Track growth.")
        if(wide) Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
            Column(Modifier.width(270.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                ImageInputCard(state,vm,onPick,onCamera,onBatch)
                ResultsCard(state,vm)
            }
            ImageCard(state,vm,onSaveImage,Modifier.weight(1f))
        } else {
            ImageInputCard(state,vm,onPick,onCamera,onBatch)
            ImageCard(state,vm,onSaveImage)
            ResultsCard(state,vm)
        }
        Text(state.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}
@Composable
private fun ImageInputCard(state:AppUiState,vm:MoldEZViewModel,onPick:()->Unit,onCamera:()->Unit,onBatch:()->Unit) {
    var diameter by remember(state.settings.diameterMm) { mutableStateOf(state.settings.diameterMm.toString().removeSuffix(".0")) }
    val valid=diameter.toDoubleOrNull()?.let { it.isFinite() && it>0 && it<=10000 }==true
    SectionCard("01  Prepare your sample") {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick=onPick,enabled=!state.busy) { Icon(Icons.Default.AddPhotoAlternate,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Choose photo") }
            OutlinedButton(onClick=onCamera,enabled=!state.busy) { Icon(Icons.Default.PhotoCamera,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Camera") }
            TextButton(onClick=onBatch,enabled=!state.busy) { Icon(Icons.Default.Collections,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Batch photos") }
        }
        state.asset?.let { Text(it.fileName,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis) }
        OutlinedTextField(value=diameter,onValueChange={diameter=it},label={Text("Dish diameter (mm)")},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),isError=!valid,enabled=!state.busy,modifier=Modifier.fillMaxWidth(),
            supportingText={Text(if(state.settings.calibration==Calibration.WINDOWS) "Windows mode adds 3 mm to this value." else "Enter the measured dish diameter.")})
        if(valid && diameter.toDouble()!=state.settings.diameterMm) TextButton(onClick={vm.updateSettings(state.settings.copy(diameterMm=diameter.toDouble()))}) { Text("Apply diameter") }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Enhance contrast"); Text("CLAHE preprocessing",style=MaterialTheme.typography.bodySmall) }
            Switch(checked=state.settings.useClahe,onCheckedChange={vm.updateSettings(state.settings.copy(useClahe=it))},enabled=!state.busy)
        }
        Button(onClick={if(valid && vm.updateSettings(state.settings.copy(diameterMm=diameter.toDouble()))) vm.detect()},
            enabled=state.image!=null && !state.busy && valid,modifier=Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Science,null,Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Run detection")
        }
        Text("Detection sends this photo to Roboflow. Results are stored on your device.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImageCard(state:AppUiState,vm:MoldEZViewModel,onSaveImage:()->Unit,modifier:Modifier=Modifier) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var tool by rememberSaveable { mutableStateOf("Draw") }
    var radius by rememberSaveable { mutableFloatStateOf(24f) }
    var showOriginal by rememberSaveable { mutableStateOf(false) }
    var zoom by remember(state.asset?.id) { mutableFloatStateOf(1f) }
    var pan by remember(state.asset?.id) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var stroke by remember { mutableStateOf(emptyList<Offset>()) }
    val bitmap=if(showOriginal) state.image else state.overlay ?: state.image
    SectionCard("02  Review your culture","Red marks culture; green outlines the dish.",modifier.fillMaxWidth()) {
        if(bitmap==null) Box(Modifier.fillMaxWidth().height(260.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center) {
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Biotech,null,Modifier.size(54.dp),tint=MaterialTheme.colorScheme.primary)
                Text("Your culture image appears here",style=MaterialTheme.typography.bodyMedium)
                Text("Choose a photo to begin",style=MaterialTheme.typography.bodySmall)
            }
        } else {
            val fit=min(boxSize.width.toFloat()/bitmap.width,boxSize.height.toFloat()/bitmap.height)
            fun toImage(p:Offset):Pair<Float,Float> {
                val center=Offset(boxSize.width/2f,boxSize.height/2f)
                val unscaled=(p-center-pan)/zoom+center
                return Pair((unscaled.x-(boxSize.width-bitmap.width*fit)/2)/fit,(unscaled.y-(boxSize.height-bitmap.height*fit)/2)/fit)
            }
            Box(Modifier.fillMaxWidth().heightIn(min=240.dp,max=480.dp).aspectRatio(1.3f)
                .background(Color(0xFF13202C),RoundedCornerShape(12.dp)).onSizeChanged {boxSize=it}
                .pointerInput(editing,tool,state.asset?.id,boxSize,radius,state.busy,showOriginal) {
                    if(!state.busy && (tool=="Move" || !editing)) detectTransformGestures { _,p,z,_->
                        zoom=(zoom*z).coerceIn(1f,6f); pan=if(zoom==1f) Offset.Zero else pan+p
                    }
                    else if(!state.busy && editing && !showOriginal) awaitEachGesture {
                        val down=awaitFirstDown();down.consume();stroke=listOf(down.position)
                        try {
                            var pressed=true
                            while(pressed) {
                                val event=awaitPointerEvent()
                                val change=event.changes.firstOrNull {it.id==down.id}
                                if(change!=null) {stroke=stroke+change.position;change.consume();pressed=change.pressed} else pressed=false
                            }
                            if(stroke.isNotEmpty()) vm.paint(stroke.map(::toImage),radius,tool=="Erase")
                        } finally {stroke=emptyList()}
                    }
                }) {
                Image(bitmap.asImageBitmap(),contentDescription=if(state.detection!=null) "Analyzed culture image" else "Photo preview",
                    modifier=Modifier.fillMaxSize().graphicsLayer {scaleX=zoom;scaleY=zoom;translationX=pan.x;translationY=pan.y;clip=true},contentScale=ContentScale.Fit)
                if(stroke.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                    val path=Path().apply {moveTo(stroke[0].x,stroke[0].y);stroke.drop(1).forEach {lineTo(it.x,it.y)}}
                    drawPath(path,if(tool=="Erase") Color(0x99EF706B) else Color(0x994EDCAA),style=Stroke(width=radius*fit*zoom*2,cap=androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                FilterChip(selected=showOriginal,onClick={showOriginal=!showOriginal},label={Text("Original")},enabled=state.detection!=null)
                FilterChip(selected=editing,onClick={editing=!editing;showOriginal=false},label={Text("Edit mask")},leadingIcon={Icon(Icons.Default.Edit,null,Modifier.size(16.dp))},enabled=state.detection!=null && !state.busy)
                TextButton(onClick={zoom=1f;pan=Offset.Zero}) {Text("Reset view")}
                TextButton(onClick=onSaveImage,enabled=state.overlay!=null && !state.busy) {Text("Save image")}
            }
            if(editing && state.detection!=null) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("Draw","Erase","Move").forEach {name->FilterChip(selected=tool==name,onClick={tool=name},label={Text(name)})}
                    IconButton(onClick=vm::undo,enabled=state.undoCount>0 && !state.busy) {Icon(Icons.AutoMirrored.Filled.Undo,"Undo mask edit")}
                    IconButton(onClick=vm::redo,enabled=state.redoCount>0 && !state.busy) {Icon(Icons.AutoMirrored.Filled.Redo,"Redo mask edit")}
                }
                Text("Brush size: ${radius.toInt()} px",style=MaterialTheme.typography.bodySmall)
                Slider(value=radius,onValueChange={radius=it},valueRange=4f..100f,modifier=Modifier.semantics {contentDescription="Mask brush size"})
                Text("Draw or erase inside the dish. Choose Move to pinch and pan.",style=MaterialTheme.typography.bodySmall)
            } else Text("Pinch to zoom; drag to inspect details.",style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ResultsCard(state:AppUiState,vm:MoldEZViewModel) {
    val m=state.measurement
    SectionCard("03  Measurements") {
        if(m==null) Text("Run detection to measure culture coverage and area.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Text("${number(m.coveragePercent)}%",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
            Text("Culture coverage",style=MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress={ (m.rawCoveragePercent/100).toFloat().coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth().height(7.dp),color=Mint)
            Metric("Culture area","${number(m.cultureAreaMm2)} mm²")
            Metric("Dish area","${number(m.plateAreaMm2)} mm²")
            Metric("Effective diameter","${number(m.effectiveDiameterMm,1)} mm")
            Metric("Culture / dish pixels","${m.culturePixels} / ${m.platePixels}")
            if((state.detectionSettings ?: state.settings).calibration==Calibration.WINDOWS) Text(
                "Windows calibration: +3 mm diameter, ×105 coverage. Raw coverage is ${number(m.rawCoveragePercent)}%; adjusted coverage may exceed 100%.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.edited) AssistChip(onClick={},label={Text("Manually edited mask")},leadingIcon={Icon(Icons.Default.Edit,null,Modifier.size(14.dp))})
            Button(onClick=vm::saveCurrent,enabled=!state.busy && !state.savedCurrent,modifier=Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Save,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(state.savedCurrent) "Saved to session" else "Save analysis")
            }
            Text("Session: ${state.activeSession?.name ?: "—"}",style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
private fun Metric(label:String,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.Top) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp));Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold)
    }
}

@Composable
private fun SessionsScreen(state:AppUiState,vm:MoldEZViewModel,onOpen:(AnalysisRecord)->Unit,onImport:()->Unit,onExport:(String)->Unit) {
    var dropdown by remember {mutableStateOf(false)}
    var nameDialog by remember {mutableStateOf<String?>(null)}
    var name by remember {mutableStateOf("")}
    var deleteDialog by remember {mutableStateOf(false)}
    var compareDialog by remember {mutableStateOf(false)}
    val session=state.activeSession
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {PageHeading("Your research, organized","Save complete analyses and compare growth over time.")}
        item {SectionCard("Sessions") {
            Box {
                OutlinedButton(onClick={dropdown=true},enabled=!state.busy) {Icon(Icons.Default.FolderOpen,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(session?.name ?: "Choose session");Icon(Icons.Default.ArrowDropDown,null)}
                DropdownMenu(expanded=dropdown,onDismissRequest={dropdown=false}) {state.sessions.forEach {s->
                    DropdownMenuItem(text={Text("${s.name} (${s.analyses.size})")},onClick={vm.selectSession(s.id);dropdown=false})
                }}
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={name="Session ${state.sessions.size+1}";nameDialog="New session"},enabled=!state.busy) {Icon(Icons.Default.Add,null);Text("New")}
                TextButton(onClick={name=session?.name ?: "";nameDialog="Rename session"},enabled=!state.busy) {Text("Rename")}
                TextButton(onClick=onImport,enabled=!state.busy) {Icon(Icons.Default.FileOpen,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Import")}
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("PDF","CSV","Session").forEach {kind->OutlinedButton(onClick={onExport(kind)},enabled=session?.analyses?.isNotEmpty()==true && !state.busy) {Text("Export $kind")}}
            }
            Text("Session bundles include photos, masks, calibration and results.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        if(session?.analyses?.isNotEmpty()==true) {
            item {SectionCard("Growth overview","${session.analyses.size} analyses · analysis time on horizontal axis") {HistoryChart(session.analyses)}}
            item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick={compareDialog=true},enabled=state.selectedRecords.size==2) {Icon(Icons.Default.CompareArrows,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Compare two")}
                OutlinedButton(onClick={deleteDialog=true},enabled=state.selectedRecords.isNotEmpty() && !state.busy) {Icon(Icons.Default.DeleteOutline,null,Modifier.size(18.dp));Text("Remove selected")}
                Text("${state.selectedRecords.size} selected",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
            }}
            items(session.analyses.reversed(),key={it.id}) {record->
                Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=record.id in state.selectedRecords,onCheckedChange={vm.toggleRecord(record.id)},modifier=Modifier.semantics {contentDescription="Select ${record.fileName}"})
                        Column(Modifier.weight(1f).clickable(enabled=!state.busy){onOpen(record)}.padding(vertical=8.dp)) {
                            Text(record.fileName,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(date(record.timestamp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${number(record.measurement.coveragePercent)}%  ·  ${number(record.measurement.cultureAreaMm2)} mm²",color=MaterialTheme.colorScheme.primary)
                            if(record.edited) Text("Edited mask",style=MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick={onOpen(record)},enabled=!state.busy) {Icon(Icons.Default.OpenInNew,"Open analysis")}
                    }
                }
            }
        } else item {SectionCard("Your session is ready") {Text("Save an analysis from the Analyze tab, or import a MoldEZ Android session bundle.")}}
        item {Spacer(Modifier.height(16.dp))}
    }
    nameDialog?.let { title->AlertDialog(onDismissRequest={nameDialog=null},title={Text(title)},text={OutlinedTextField(name,{name=it},label={Text("Session name")},singleLine=true)},
        confirmButton={TextButton(onClick={if(title=="New session") vm.createSession(name) else vm.renameSession(name);nameDialog=null},enabled=name.isNotBlank()){Text("Save")}},
        dismissButton={TextButton(onClick={nameDialog=null}){Text("Cancel")}})}
    if(deleteDialog) AlertDialog(onDismissRequest={deleteDialog=false},title={Text("Remove selected analyses?")},text={Text("This removes ${state.selectedRecords.size} entries from this session. Previously exported files are kept.")},
        confirmButton={TextButton(onClick={vm.deleteSelected();deleteDialog=false}){Text("Remove")}},dismissButton={TextButton(onClick={deleteDialog=false}){Text("Cancel")}})
    if(compareDialog) {
        val records=session?.analyses?.filter {it.id in state.selectedRecords}.orEmpty()
        if(records.size==2) ComparisonDialog(records[0],records[1]) {compareDialog=false} else compareDialog=false
    }
}

@Composable
private fun HistoryChart(records:List<AnalysisRecord>) {
    val sorted=records.sortedBy {it.timestamp}
    val line=MaterialTheme.colorScheme.primary; val grid=MaterialTheme.colorScheme.outlineVariant
    val maxValue=max(1.0,sorted.maxOfOrNull {it.measurement.cultureAreaMm2} ?: 1.0)
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics {contentDescription="Culture area over time chart with ${records.size} analyses"}) {
        val left=12f;val bottom=size.height-12f;val span=max(1L,sorted.last().timestamp-sorted.first().timestamp)
        for(i in 0..3) {val y=bottom-(bottom-8)*i/3;drawLine(grid,Offset(left,y),Offset(size.width-8,y),1.dp.toPx())}
        val pts=sorted.map {r->Offset(left+(size.width-left-8)*(r.timestamp-sorted.first().timestamp).toFloat()/span,
            bottom-(bottom-8)*(r.measurement.cultureAreaMm2/maxValue).toFloat())}
        for(i in 1 until pts.size) drawLine(line,pts[i-1],pts[i],3.dp.toPx())
        pts.forEach {drawCircle(line,4.dp.toPx(),it)}
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("0–${number(maxValue)} mm²",style=MaterialTheme.typography.bodySmall)
        Text("${sorted.size} samples",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ComparisonDialog(first:AnalysisRecord,second:AnalysisRecord,onDismiss:()->Unit) {
    var manual by remember {mutableStateOf(false)};var hours by remember {mutableStateOf("24")}
    val comparison=runCatching {compareAnalyses(first,second,if(manual) hours.toDoubleOrNull() ?: Double.NaN else null)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Growth comparison")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("${first.fileName}\n${second.fileName}",style=MaterialTheme.typography.bodySmall)
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(manual,{manual=it});Text("Set elapsed time manually")}
        if(manual) OutlinedTextField(hours,{hours=it},label={Text("Elapsed hours")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
        else Text("Uses the times the analyses were saved, not the photograph capture times. Use manual time for historical samples.",style=MaterialTheme.typography.bodySmall)
        comparison.onSuccess {c->
            Metric("Elapsed time","${number(c.hours,3)} hours")
            Metric("Coverage change","${number(c.coverageChange)} percentage points")
            Metric("Area change","${number(c.areaChange)} mm²")
            Metric("Areal growth","${number(c.areaPerHour,4)} mm²/hour")
            Metric("Equivalent radial growth","${number(c.radialMmPerHour,4)} mm/hour")
        }.onFailure {Text(it.message ?: "Unable to compare",color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick=onDismiss){Text("Done")}})
}

@Composable
private fun SettingsScreen(state:AppUiState,vm:MoldEZViewModel) {
    val context=LocalContext.current
    var dish by remember(state.settings.dishConfidence) {mutableFloatStateOf(state.settings.dishConfidence)}
    var culture by remember(state.settings.cultureConfidence) {mutableFloatStateOf(state.settings.cultureConfidence)}
    var dishModel by remember(state.settings.dishModel) {mutableStateOf(state.settings.dishModel)}
    var cultureModel by remember(state.settings.cultureModel) {mutableStateOf(state.settings.cultureModel)}
    var clip by remember(state.settings.claheClip) {mutableStateOf(state.settings.claheClip.toString())}
    var tiles by remember(state.settings.claheTiles) {mutableStateOf(state.settings.claheTiles.toString())}
    var windows by remember(state.settings.calibration) {mutableStateOf(state.settings.calibration==Calibration.WINDOWS)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        PageHeading("Make MoldEZ yours","Analysis preferences and appearance.")
        SectionCard("Detection service","MoldEZ is ready to analyze photos. No account setup is needed.") {
            Text("Detection uses the MoldEZ project's Roboflow service and requires internet. Photos are sent over HTTPS; saved results stay on this device unless you export them.",style=MaterialTheme.typography.bodySmall)
        }
        SectionCard("Detection preferences","Changing a model or threshold requires another detection.") {
            Text("Dish confidence: ${(dish*100).roundToInt()}%")
            Slider(dish,{dish=it},valueRange=.01f..1f,enabled=!state.busy,modifier=Modifier.semantics {contentDescription="Dish confidence"})
            Text("Culture confidence: ${(culture*100).roundToInt()}%")
            Slider(culture,{culture=it},valueRange=.01f..1f,enabled=!state.busy,modifier=Modifier.semantics {contentDescription="Culture confidence"})
            OutlinedTextField(dishModel,{dishModel=it},label={Text("Dish model · project/version")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(cultureModel,{cultureModel=it},label={Text("Culture model · project/version")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(clip,{clip=it},label={Text("CLAHE clip limit")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(tiles,{tiles=it},label={Text("CLAHE tiles per axis")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(verticalAlignment=Alignment.CenterVertically) {Switch(windows,{windows=it});Spacer(Modifier.width(12.dp));Column {Text("Match Windows measurements");Text("+3 mm diameter · ×105 coverage",style=MaterialTheme.typography.bodySmall)}}
            Text(if(windows) "Applied consistently to manual, batch and timed analyses. Adjusted coverage can exceed 100%." else "Standard mode uses the entered diameter and 0–100% coverage.",style=MaterialTheme.typography.bodySmall)
            Button(onClick={
                val parsedClip=clip.toDoubleOrNull();val parsedTiles=tiles.toIntOrNull()
                if(parsedClip==null || parsedTiles==null) vm.error("Enter valid contrast settings.")
                else if(vm.updateSettings(state.settings.copy(dishConfidence=dish,cultureConfidence=culture,dishModel=dishModel.trim(),cultureModel=cultureModel.trim(),
                    claheClip=parsedClip,claheTiles=parsedTiles,calibration=if(windows) Calibration.WINDOWS else Calibration.STANDARD))) vm.notify("Analysis preferences saved")
            },enabled=!state.busy && !state.automationRunning) {Text("Save preferences")}
        }
        SectionCard("About MoldEZ") {
            Text("Culture analysis · Android ${BuildConfig.VERSION_NAME}",fontWeight=FontWeight.SemiBold)
            Text("Developed from MoldEZ Mark IV, Truman State University. Original project: Mohammed Ayan Mahmood, Dr. Kafi R. Rahman, Dr. Hajeewaka C. Mendis, and contributors.",style=MaterialTheme.typography.bodySmall)
            Text("Research measurements depend on the photograph, model predictions and calibration. Review masks before saving. Desktop pickle sessions require conversion; Android uses portable, data-only session bundles.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/AhmadBukhari8966/MoldEZ4")))}}) {Text("Project and license")}
        }
    }
}
