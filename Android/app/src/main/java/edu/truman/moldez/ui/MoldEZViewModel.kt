package edu.truman.moldez.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.truman.moldez.BuildConfig
import edu.truman.moldez.core.*
import edu.truman.moldez.data.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.math.*

data class AppUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val status: String = "Ready for your first culture",
    val message: String? = null,
    val error: String? = null,
    val settings: AnalysisSettings = AnalysisSettings(),
    val darkMode: Boolean = false,
    val asset: ImageAsset? = null,
    val image: Bitmap? = null,
    val overlay: Bitmap? = null,
    val detection: DetectionResult? = null,
    val detectionSettings: AnalysisSettings? = null,
    val edited: Boolean = false,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    val savedCurrent: Boolean = false,
    val sessions: List<Session> = emptyList(),
    val activeSessionId: String = "",
    val selectedRecords: Set<String> = emptySet(),
    val automationRunning: Boolean = false,
    val countdown: Int = 0,
    val automationCount: Int = 0,
) {
    val activeSession: Session? get() = sessions.find { it.id == activeSessionId }
    val measurement: Measurement? get() = detection?.let {
        runCatching { Measurements.calculate(it.dish.count(), it.culture.count(), detectionSettings ?: settings) }.getOrNull()
    }
}

class MoldEZViewModel(app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf(AppUiState()); private set
    private val preferences = AppPreferences(app)
    private val store = SessionStore(app)
    private val engine = AnalysisEngine()
    private var operation: Job? = null
    private val undo = ArrayDeque<BinaryMask>()
    private val redo = ArrayDeque<BinaryMask>()
    private val apiKey = BuildConfig.ROBOFLOW_API_KEY
    private var exportSnapshot: Session? = null

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { store.load() }
                val sessions = loaded.ifEmpty { listOf(newSession("Session 1")) }
                val active=sessions.find {it.id==preferences.activeSessionId()} ?: sessions.first()
                state = state.copy(loading = false, settings = preferences.settings(), darkMode = preferences.darkMode(),
                    sessions = sessions, activeSessionId = active.id)
            } catch (e: Exception) {
                state = state.copy(loading = false, error = "Could not load saved sessions: ${e.message}")
            }
        }
    }
    private fun newSession(name: String) = Session(UUID.randomUUID().toString(), name, System.currentTimeMillis(), emptyList())
    fun dismissMessage() { state = state.copy(message = null) }
    fun dismissError() { state = state.copy(error = null) }
    fun notify(message: String) { state = state.copy(message = message) }
    fun error(message: String) { state = state.copy(error = message) }
    fun setDarkMode(dark: Boolean) { preferences.setDarkMode(dark); state = state.copy(darkMode = dark) }
    fun updateSettings(value: AnalysisSettings): Boolean {
        if (state.busy || state.automationRunning) { error("Finish the current operation before changing settings."); return false }
        return runCatching {
            preferences.setSettings(value)
            val old = state.settings
            // Diameter and calibration can be recalculated without another remote prediction.
            val inferenceChanged = old.copy(diameterMm=value.diameterMm, calibration=value.calibration, intervalSeconds=value.intervalSeconds) != value
            state = if (inferenceChanged) state.copy(settings=value, detection=null, overlay=null, detectionSettings=null, edited=false, savedCurrent=false)
                else state.copy(settings=value, detectionSettings=state.detectionSettings?.copy(diameterMm=value.diameterMm, calibration=value.calibration), savedCurrent=false)
            if (inferenceChanged) clearUndo()
            true
        }.getOrElse { error(it.message ?: "Invalid settings"); false }
    }
    private fun clearUndo() { undo.clear(); redo.clear(); state=state.copy(undoCount=0, redoCount=0) }
    private fun launchOperation(status: String, block: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy=true, status=status, error=null)
        operation = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { state=state.copy(status="Cancelled"); throw e }
            catch (e: Exception) { state=state.copy(error=safeError(e), status="Action needs attention") }
            finally { state=state.copy(busy=false) }
        }
    }
    private fun safeError(e: Exception): String {
        val text = e.message ?: "Something went wrong. Please try again."
        return if (apiKey.isNotEmpty()) text.replace(apiKey, "[redacted]") else text
    }
    fun cancel() { operation?.cancel(); stopAutomation() }
    fun selectImage(uri: Uri) = launchOperation("Opening photo…") {
        val asset = withContext(Dispatchers.IO) { store.importImage(uri) }
        showAsset(asset)
        state=state.copy(status="Photo ready — run detection")
    }
    private suspend fun showAsset(asset: ImageAsset) {
        val bitmap=withContext(Dispatchers.IO) { BitmapFactory.decodeFile(asset.imagePath) ?: throw IllegalArgumentException("Unable to decode photo") }
        clearUndo()
        state=state.copy(asset=asset, image=bitmap, detection=null, detectionSettings=null, overlay=null, edited=false, savedCurrent=false)
    }
    fun detect() {
        val bitmap=state.image ?: return
        val settings=state.settings
        launchOperation("Finding the dish…") {
            val result=engine.detect(bitmap, settings, apiKey) { status -> viewModelScope.launch { state=state.copy(status=status) } }
            val overlay=withContext(Dispatchers.Default) { ImageProcessing.overlay(bitmap, result) }
            clearUndo()
            state=state.copy(detection=result, detectionSettings=settings, overlay=overlay, edited=false, savedCurrent=false,
                status="Detection complete — review the mask, then save")
        }
    }
    fun saveCurrent() {
        if(state.activeSession==null) {error("Create or import a session in Sessions before saving an analysis.");return}
        val asset=state.asset ?: return; val detection=state.detection?.deepCopy() ?: return
        val settings=state.detectionSettings ?: state.settings; val edited=state.edited
        launchOperation("Saving analysis…") {
            val record=withContext(Dispatchers.IO) { store.saveAnalysis(asset, detection, settings, edited) }
            appendRecord(record)
            state=state.copy(savedCurrent=true, status="Analysis saved", message="Added to ${state.activeSession?.name}")
        }
    }
    private suspend fun appendRecord(record: AnalysisRecord) {
        check(state.activeSession!=null) { "Create a session before saving an analysis." }
        val sessions=state.sessions.map { if(it.id==state.activeSessionId) it.copy(analyses=it.analyses+record) else it }
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { store.save(sessions) }
            state=state.copy(sessions=sessions)
        }
    }
    fun batch(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if(state.activeSession==null) {error("Create or import a session in Sessions before starting a batch.");return}
        val settings=state.settings
        launchOperation("Starting batch…") {
            val failures=mutableListOf<String>(); var success=0
            for ((index,uri) in uris.withIndex()) {
                currentCoroutineContext().ensureActive()
                state=state.copy(status="Image ${index+1} of ${uris.size}")
                try {
                    val asset=withContext(Dispatchers.IO) { store.importImage(uri) }
                    val bitmap=withContext(Dispatchers.IO) { BitmapFactory.decodeFile(asset.imagePath) ?: throw IllegalArgumentException("Cannot read image") }
                    val result=engine.detect(bitmap,settings,apiKey)
                    val record=withContext(Dispatchers.IO) { store.saveAnalysis(asset,result,settings,false) }
                    appendRecord(record); success++
                    val overlay=withContext(Dispatchers.Default) { ImageProcessing.overlay(bitmap,result) }
                    clearUndo()
                    state=state.copy(asset=asset,image=bitmap,detection=result,detectionSettings=settings,overlay=overlay,edited=false,savedCurrent=true)
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { failures += "Image ${index+1}: ${safeError(e)}" }
            }
            clearUndo()
            state=state.copy(status="Batch finished: $success saved, ${failures.size} failed", message=if(failures.isEmpty()) "$success analyses saved" else null,
                error=if(failures.isNotEmpty()) "$success saved; ${failures.size} failed.\n\n"+failures.joinToString("\n") else null)
        }
    }
    /** Image coordinates; one undo snapshot per completed brush stroke. */
    fun paint(points: List<Pair<Float,Float>>, radius: Float, erase: Boolean) {
        if(state.busy || points.isEmpty()) return
        val old=state.detection ?: return
        val bitmap=state.image ?: return
        launchOperation("Updating mask…") {
        val (mask,overlay)=withContext(Dispatchers.Default) {
        val mask=old.culture.deepCopy()
        val w=mask.width; val h=mask.height; val r=radius.coerceIn(1f,200f)
        fun dab(x: Float,y:Float) {
            for(yy in max(0,floor(y-r).toInt())..min(h-1,ceil(y+r).toInt()))
                for(xx in max(0,floor(x-r).toInt())..min(w-1,ceil(x+r).toInt()))
                    if((xx-x)*(xx-x)+(yy-y)*(yy-y)<=r*r) {
                        val i=yy*w+xx
                        if(old.dish.pixels[i].toInt()!=0) mask.pixels[i]=if(erase) 0 else 1
                    }
        }
        dab(points[0].first,points[0].second)
        for(i in 1 until points.size) {
            ensureActive()
            val a=points[i-1]; val b=points[i]
            val steps=max(1,ceil(hypot(b.first-a.first,b.second-a.second)/max(1f,r/2)).toInt())
            for(j in 1..steps) { val t=j.toFloat()/steps; dab(a.first+(b.first-a.first)*t,a.second+(b.second-a.second)*t) }
        }
        mask to ImageProcessing.overlay(bitmap,old.copy(culture=mask))
        }
        undo.addLast(old.culture.deepCopy()); if(undo.size>12) undo.removeFirst(); redo.clear()
        state=state.copy(detection=old.copy(culture=mask),overlay=overlay,edited=true,savedCurrent=false,undoCount=undo.size,redoCount=0,status="Mask updated — save to keep this revision")
        }
    }
    fun undo() { if(undo.isNotEmpty() && !state.busy) { state.detection?.let { redo.addLast(it.culture.deepCopy()) }; replaceMask(undo.removeLast()) } }
    fun redo() { if(redo.isNotEmpty() && !state.busy) { state.detection?.let { undo.addLast(it.culture.deepCopy()) }; replaceMask(redo.removeLast()) } }
    private fun replaceMask(mask: BinaryMask) {
        val detection=state.detection?.copy(culture=mask) ?: return; val bitmap=state.image ?: return
        launchOperation("Updating mask…") {
            try {
                val overlay=withContext(Dispatchers.Default) { ImageProcessing.overlay(bitmap,detection) }
                state=state.copy(detection=detection,overlay=overlay,edited=true,savedCurrent=false,undoCount=undo.size,redoCount=redo.size,status="Mask updated — save to keep this revision")
            } catch(e:CancellationException) {clearUndo();throw e}
        }
    }
    fun createSession(name:String) {
        if(name.isBlank()) { error("Enter a session name."); return }
        launchOperation("Creating session…") {
            val session=newSession(name.trim().take(100)); val sessions=state.sessions+session
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { store.save(sessions) }
                preferences.setActiveSessionId(session.id)
                state=state.copy(sessions=sessions,activeSessionId=session.id,selectedRecords=emptySet(),status="Session created")
            }
        }
    }
    fun renameSession(name:String) {
        if(name.isBlank()) return
        launchOperation("Renaming session…") {
            val sessions=state.sessions.map { if(it.id==state.activeSessionId) it.copy(name=name.trim().take(100)) else it }
            withContext(NonCancellable) {withContext(Dispatchers.IO) { store.save(sessions) }; state=state.copy(sessions=sessions,status="Session renamed")}
        }
    }
    fun selectSession(id:String) { if(!state.busy && state.sessions.any {it.id==id}) {preferences.setActiveSessionId(id);state=state.copy(activeSessionId=id,selectedRecords=emptySet())} }
    fun toggleRecord(id:String) { state=state.copy(selectedRecords=if(id in state.selectedRecords) state.selectedRecords-id else state.selectedRecords+id) }
    fun deleteSelected() = launchOperation("Removing selected analyses…") {
        val sessions=state.sessions.map { if(it.id==state.activeSessionId) it.copy(analyses=it.analyses.filterNot { a->a.id in state.selectedRecords }) else it }
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { store.save(sessions) }
            state=state.copy(sessions=sessions,selectedRecords=emptySet(),status="Selected analyses removed")
        }
    }
    fun openRecord(record:AnalysisRecord) = launchOperation("Opening saved analysis…") {
        val asset=ImageAsset(record.id,record.fileName,record.imagePath,record.capturedAt)
        showAsset(asset)
        val result=withContext(Dispatchers.IO) { store.loadDetection(record) }
        val overlay=result?.let { withContext(Dispatchers.Default) { ImageProcessing.overlay(state.image!!,it) } }
        state=state.copy(settings=record.settings,detection=result,detectionSettings=record.settings,overlay=overlay,edited=record.edited,savedCurrent=true,
            status=if(result==null) "Photo opened; saved masks unavailable" else "Saved analysis restored")
    }
    fun prepareExport(): Boolean {
        exportSnapshot=state.activeSession
        if(exportSnapshot==null || exportSnapshot!!.analyses.isEmpty()) { error("Save an analysis before exporting."); return false }
        return true
    }
    fun export(uri:Uri, kind:String) {
        val session=exportSnapshot ?: state.activeSession ?: return
        launchOperation("Exporting $kind…") {
            withContext(Dispatchers.IO) {
                val resolver=getApplication<Application>().contentResolver
                resolver.openOutputStream(uri,"wt")?.use { output -> when(kind) {
                    "PDF"->ReportExporter.pdf(getApplication(),session,output)
                    "CSV"->ReportExporter.csv(session,output)
                    else->store.exportSession(session,output)
                } } ?: throw IllegalArgumentException("Cannot write to the selected location.")
            }
            state=state.copy(status="Export complete",message="$kind saved")
        }
    }
    fun exportOverlay(uri:Uri) {
        val bitmap=state.overlay ?: return
        launchOperation("Saving detection image…") {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) {"Unable to write detection image."}
                } ?: throw IllegalArgumentException("Cannot write to that location.")
            }
            state=state.copy(message="Detection image saved",status="Image exported")
        }
    }
    fun importSession(uri:Uri) = launchOperation("Importing session…") {
        val session=withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { store.importSession(it) }
                ?: throw IllegalArgumentException("Cannot read the selected session.")
        }
        val imported=session.copy(id=UUID.randomUUID().toString())
        val sessions=state.sessions+imported
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { store.save(sessions) }
            preferences.setActiveSessionId(imported.id)
            state=state.copy(sessions=sessions,activeSessionId=imported.id,selectedRecords=emptySet(),status="Session imported")
        }
    }
    fun startAutomation() {
        if(state.busy) return
        if(state.activeSession==null) {error("Create or import a session in Sessions before timed capture.");return}
        state=state.copy(automationRunning=true,countdown=0,automationCount=0,status="Camera session running")
    }
    fun stopAutomation() { state=state.copy(automationRunning=false,countdown=0) }
    fun countdown(seconds:Int) { state=state.copy(countdown=seconds) }
    suspend fun analyzeCapture(uri:Uri): Boolean {
        if(!state.automationRunning || state.busy) return false
        state=state.copy(busy=true,status="Analyzing camera capture…")
        try {
            val settings=state.settings
            val asset=withContext(Dispatchers.IO) { store.importImage(uri) }
            val bitmap=withContext(Dispatchers.IO) { BitmapFactory.decodeFile(asset.imagePath) ?: throw IllegalArgumentException("Cannot read capture") }
            val result=engine.detect(bitmap,settings,apiKey)
            val record=withContext(Dispatchers.IO) { store.saveAnalysis(asset,result,settings,false) }
            appendRecord(record)
            val overlay=withContext(Dispatchers.Default) { ImageProcessing.overlay(bitmap,result) }
            clearUndo()
            state=state.copy(asset=asset,image=bitmap,detection=result,detectionSettings=settings,overlay=overlay,savedCurrent=true,edited=false,
                automationCount=state.automationCount+1,status="Capture saved")
            return true
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { error(safeError(e)); stopAutomation(); return false }
        finally { state=state.copy(busy=false) }
    }
}
