package id.rekaminaja.app.recorder

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/** Status perekaman yang dibagikan antar service & UI (satu proses). */
object RecorderState {

    enum class State { IDLE, COUNTDOWN, RECORDING, PAUSED }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var autoPausedBySensitiveApp: Boolean = false

    @Volatile
    var facecamVisible: Boolean = false

    /** True bila rekaman aktif melewati jalur codec+DSP sehingga Deck Efek
     *  Suara (preset/volume mic) berlaku langsung, bukan rekaman berikutnya. */
    @Volatile
    var fxLive: Boolean = false

    /**
     * True bila sesi MediaProjection masih hidup (izin tangkapan sudah
     * diberikan) sehingga rekaman berikutnya bisa mulai langsung tanpa
     * dialog izin lagi. Sesi berakhir saat bola melayang disembunyikan,
     * izin dicabut sistem, atau proses mati.
     */
    @Volatile
    var projectionReady: Boolean = false

    private var startRealtime = 0L
    private var pausedAccumulated = 0L
    private var pauseStart = 0L

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: (State) -> Unit) = listeners.add(l)
    fun removeListener(l: (State) -> Unit) = listeners.remove(l)

    fun set(newState: State) {
        val now = SystemClock.elapsedRealtime()
        when (newState) {
            State.RECORDING -> {
                if (state == State.PAUSED) {
                    pausedAccumulated += now - pauseStart
                } else if (state != State.RECORDING) {
                    startRealtime = now
                    pausedAccumulated = 0L
                }
            }
            State.PAUSED -> if (state == State.RECORDING) pauseStart = now
            State.IDLE -> {
                startRealtime = 0L; pausedAccumulated = 0L; pauseStart = 0L
                autoPausedBySensitiveApp = false
                facecamVisible = false
                fxLive = false
            }
            State.COUNTDOWN -> Unit
        }
        state = newState
        main.post { listeners.forEach { it(newState) } }
    }

    fun elapsedMs(): Long {
        if (startRealtime == 0L) return 0L
        val now = SystemClock.elapsedRealtime()
        val pausedExtra = if (state == State.PAUSED) now - pauseStart else 0L
        return (now - startRealtime - pausedAccumulated - pausedExtra).coerceAtLeast(0L)
    }

    val isRecording: Boolean get() = state == State.RECORDING || state == State.PAUSED
}
