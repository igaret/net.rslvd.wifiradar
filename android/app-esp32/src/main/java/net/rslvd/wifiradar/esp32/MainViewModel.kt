package net.rslvd.wifiradar.esp32

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.rslvd.wifiradar.core.dsp.MotionDetector
import net.rslvd.wifiradar.core.model.CsiFrame
import net.rslvd.wifiradar.core.record.CsiRecorder
import net.rslvd.wifiradar.esp32.net.Esp32Client
import net.rslvd.wifiradar.esp32.net.Esp32Discovery
import net.rslvd.wifiradar.esp32.net.Esp32Protocol
import net.rslvd.wifiradar.esp32.net.TrafficGenerator
import net.rslvd.wifiradar.esp32.net.WifiConnector
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Mode { SINGLE, DUAL }

    private val prefs = app.getSharedPreferences("wifiradar", Context.MODE_PRIVATE)

    val client = Esp32Client(viewModelScope)
    val discovery = Esp32Discovery(app, viewModelScope)
    val traffic = TrafficGenerator(viewModelScope)
    val wifi = WifiConnector(app)
    val recorder = CsiRecorder(CsiRecorder.defaultDir(app))
    private val detector = MotionDetector()

    private val _mode = MutableStateFlow(runCatching { Mode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(Mode.SINGLE))
    val mode = _mode.asStateFlow()
    private val _rateHz = MutableStateFlow(prefs.getInt("rate_hz", 100))
    val rateHz = _rateHz.asStateFlow()
    private val _motion = MutableStateFlow(MotionDetector.Result(0f, false, false, 0))
    val motion = _motion.asStateFlow()
    private val _fps = MutableStateFlow(0)
    val fps = _fps.asStateFlow()
    private val _recording = MutableStateFlow<File?>(null)
    val recording = _recording.asStateFlow()
    val lastCapture = MutableStateFlow<File?>(null)
    private val _log = MutableSharedFlow<String>(replay = 12, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val log = _log.asSharedFlow()
    /** Frames for the UI, from whichever source is active (ESP32). */
    private val _frames = MutableSharedFlow<CsiFrame>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames = _frames.asSharedFlow()

    private var fpsCounter = 0

    init {
        viewModelScope.launch { client.frames.collect { onFrame(it) } }
        viewModelScope.launch { client.textLines.collect { log("← $it") } }
        viewModelScope.launch {
            client.state.collect { st ->
                when (st) {
                    is Esp32Client.State.Connected -> {
                        log("connected to ${st.host}:${st.port}")
                        applyTraffic(st.host)
                    }
                    is Esp32Client.State.Error -> { log("connection error: ${st.message}"); traffic.stop() }
                    else -> traffic.stop()
                }
            }
        }
        viewModelScope.launch {
            while (true) { delay(1000); _fps.value = fpsCounter; fpsCounter = 0 }
        }
        discovery.start()
    }

    private fun onFrame(frame: CsiFrame) {
        fpsCounter++
        _motion.value = detector.push(frame)
        if (recorder.isRecording) recorder.write(frame)
        _frames.tryEmit(frame)
    }

    /** Single mode: the phone illuminates the channel. Dual mode: the TX ESP32 does, so stay quiet. */
    private fun applyTraffic(host: String) {
        if (_mode.value == Mode.SINGLE) traffic.start(host, Esp32Protocol.PING_PORT, _rateHz.value) else traffic.stop()
    }

    fun connect(host: String, port: Int) {
        detector.reset()
        client.connect(host, port)
    }

    fun disconnect() {
        client.disconnect()
        traffic.stop()
    }

    fun setMode(m: Mode) {
        if (_mode.value == m) return
        _mode.value = m
        prefs.edit().putString("mode", m.name).apply()
        (client.state.value as? Esp32Client.State.Connected)?.let { applyTraffic(it.host) }
        if (m == Mode.DUAL) pushTxRate()
    }

    fun setRate(hz: Int) {
        _rateHz.value = hz
        prefs.edit().putInt("rate_hz", hz).apply()
        (client.state.value as? Esp32Client.State.Connected)?.let { applyTraffic(it.host) }
        if (_mode.value == Mode.DUAL) pushTxRate()
    }

    /** Dual mode: the TX paces sampling. It is the AP (192.168.4.1) unless discovery says otherwise. */
    private fun pushTxRate() {
        val tx = discovery.devices.value.firstOrNull { it.role == "tx" }
        val host = tx?.ip ?: Esp32Protocol.DEFAULT_AP_IP
        val port = tx?.port ?: Esp32Protocol.DEFAULT_TCP_PORT
        viewModelScope.launch {
            val reply = Esp32Client.oneShot(host, port, "SET_RATE ${_rateHz.value}")
            log("TX $host: ${reply ?: "no reply (is the TX reachable?)"}")
        }
    }

    fun sendCommand(command: String) {
        log("→ $command")
        client.send(command)
    }

    fun setThreshold(t: Float) { detector.threshold = t }

    fun toggleRecording(format: CsiRecorder.Format) {
        if (recorder.isRecording) {
            val f = recorder.stop()
            _recording.value = null
            lastCapture.value = f
            log("saved ${f?.name} (${recorder.frameCount} frames)")
        } else {
            val f = recorder.start(format, mapOf("app" to "wifi-radar-esp32", "mode" to _mode.value.name, "rate_hz" to _rateHz.value.toString()))
            _recording.value = f
            log("recording to ${f.name}")
        }
    }

    private fun log(line: String) { _log.tryEmit(line) }

    override fun onCleared() {
        client.disconnect()
        traffic.stop()
        discovery.stop()
        wifi.release()
        recorder.stop()
    }
}
