package io.github.tux4us.miriquidihub.files

import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Führt Dateivorgänge (Kopieren, Verschieben, Löschen, Hochladen, Packen) unabhängig von der
 * Activity aus. Der Vorgang überlebt Drehen und Sprachwechsel; der [TransferService] hält den
 * Prozess im Hintergrund am Leben. Es läuft immer nur ein Vorgang.
 */
object TransferManager {

    sealed class State {
        object Idle : State()
        data class Running(val title: String, val progress: TransferProgress?) : State()
        data class Finished(val title: String, val result: TransferResult?, val error: String?) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile
    private var job: Job? = null

    val isBusy: Boolean get() = _state.value is State.Running

    /** Startet [block]. false, wenn bereits ein Vorgang läuft. */
    fun start(context: Context, title: String, block: (TransferEngine) -> TransferResult): Boolean {
        synchronized(this) {
            if (isBusy) return false
            _state.value = State.Running(title, null)
        }
        val appContext = context.applicationContext
        ContextCompat.startForegroundService(appContext, TransferService.intent(appContext))
        job = scope.launch {
            val engine = TransferEngine(
                isCancelled = { !isActive },
                onProgress = { p -> _state.value = State.Running(title, p) }
            )
            _state.value = try {
                State.Finished(title, block(engine), null)
            } catch (e: Exception) {
                State.Finished(title, null, e.message ?: e.javaClass.simpleName)
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /** Setzt nach der Anzeige des Ergebnisses zurück auf [State.Idle]. */
    fun acknowledge() {
        if (_state.value is State.Finished) _state.value = State.Idle
    }
}

/** App-interne Zwischenablage für Kopieren/Ausschneiden, auch zwischen Speicherorten. */
object FileClipboard {
    var source: FileSource? = null
        private set
    var entries: List<FileEntry> = emptyList()
        private set
    var move: Boolean = false
        private set

    val isEmpty: Boolean get() = entries.isEmpty()

    fun set(source: FileSource, entries: List<FileEntry>, move: Boolean) {
        this.source = source
        this.entries = entries
        this.move = move
    }

    fun clear() {
        source = null
        entries = emptyList()
        move = false
    }
}
