package net.rslvd.wifiradar.rooted

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.rslvd.wifiradar.core.demo.SyntheticCsiSource
import net.rslvd.wifiradar.core.dsp.MotionDetector
import net.rslvd.wifiradar.core.model.CsiFrame
import net.rslvd.wifiradar.core.record.CsiRecorder
import net.rslvd.wifiradar.rooted.nexmon.CsiParams
import net.rslvd.wifiradar.rooted.nexmon.DeviceCompat
import net.rslvd.wifiradar.rooted.nexmon.NexmonChip
import net.rslvd.wifiradar.rooted.nexmon.NexmonController
import net.rslvd.wifiradar.rooted.nexmon.NexmonCsiReceiver
import net.rslvd.wifiradar.rooted.nexmon.PingGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {

    /** CONNECTED: stay associated, ping the AP, filter its BSSID. PASSIVE: monitor mode, sniff everything on the channel. */
    enum class CaptureMode { CONNECTED, PASSIVE }

    data class Settings(
        val chip: NexmonChip = DeviceCompat.detect() ?: NexmonChip.BCM4339,
        val iface: String = "wlan0",
        val mode: CaptureMode = CaptureMode.CONNECTED,
        val channel: Int = 36,
        val bandwidthMhz: Int = 20,
        val macFilter: String = "",
        val rateHz: Int = 50,
        val source: NexmonCsiReceiver.Source = NexmonCsiReceiver.Source.UDP_SOCKET,
        val firstByteFilter: Boolean = false,
    )

    private val prefs = app.getSharedPreferences("wifiradar-root", Context.MODE_PRIVATE)
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val receiver = NexmonCsiReceiver(viewModelScope)
    private val controller = NexmonController()
    private val ping = PingGenerator()
    val recorder = CsiRecorder(CsiRecorder.defaultDir(app))
    private val detector = MotionDetector()

    private val _settings = MutableStateFlow(loadSettings())
    val settings = _settings.asStateFlow()
    private val _preflight = MutableStateFlow("Tap “Check device” to verify root, nexutil and chip.")
    val preflight = _preflight.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _motion = MutableStateFlow(MotionDetector.Result(0f, false, false, 0))
    val motion = _motion.asStateFlow()
    private val _fps = MutableStateFlow(0)
    val fps = _fps.asStateFlow()
    private val _stats = MutableStateFlow("")
    val stats = _stats.asStateFlow()
    private val _recording = MutableStateFlow<File?>(null)
    val recording = _recording.asStateFlow()
    val lastCapture = MutableStateFlow<File?>(null)
    private val _demo = MutableStateFlow(false)
    val demo = _demo.asStateFlow()
    private val _log = MutableSharedFlow<String>(replay = 14, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val log = _log.asSharedFlow()
    private val _frames = MutableSharedFlow<CsiFrame>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames = _frames.asSharedFlow()

    private var demoJob: Job? = null
    private var activeConfig: CsiParams.Config? = null
    private var fpsCounter = 0

    init {
        viewModelScope.launch { receiver.frames.collect { onFrame(it) } }
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _fps.value = fpsCounter; fpsCounter = 0
                if (_running.value) _stats.value = "udp packets=${receiver.packetCount.get()}  parse errors=${receiver.parseErrors.get()}  ping=${if (ping.isRunning) "running" else "off"}"
            }
        }
    }

    private fun onFrame(frame: CsiFrame) {
        fpsCounter++
        _motion.value = detector.push(frame)
        if (recorder.isRecording) recorder.write(frame)
        _frames.tryEmit(frame)
    }

    private fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            chip = runCatching { NexmonChip.valueOf(prefs.getString("chip", d.chip.name)!!) }.getOrDefault(d.chip),
            iface = prefs.getString("iface", d.iface) ?: d.iface,
            mode = runCatching { CaptureMode.valueOf(prefs.getString("mode", d.mode.name)!!) }.getOrDefault(d.mode),
            channel = prefs.getInt("channel", d.channel),
            bandwidthMhz = prefs.getInt("bw", d.bandwidthMhz),
            macFilter = prefs.getString("mac", d.macFilter) ?: "",
            rateHz = prefs.getInt("rate", d.rateHz),
            source = runCatching { NexmonCsiReceiver.Source.valueOf(prefs.getString("source", d.source.name)!!) }.getOrDefault(d.source),
            firstByteFilter = prefs.getBoolean("fbf", d.firstByteFilter),
        )
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit()
            .putString("chip", s.chip.name).putString("iface", s.iface).putString("mode", s.mode.name)
            .putInt("channel", s.channel).putInt("bw", s.bandwidthMhz).putString("mac", s.macFilter)
            .putInt("rate", s.rateHz).putString("source", s.source.name).putBoolean("fbf", s.firstByteFilter)
            .apply()
    }

    /** Fills channel/bandwidth/BSSID from the current Wi-Fi association (needs location permission for the BSSID). */
    @Suppress("DEPRECATION")
    fun useCurrentAccessPoint(): String {
        val info = wifi.connectionInfo ?: return "not connected to Wi-Fi"
        val channel = CsiParams.frequencyToChannel(info.frequency)
        val bssid = info.bssid?.takeIf { it != "02:00:00:00:00:00" && it.isNotEmpty() }
        if (channel == 0) return "not connected to Wi-Fi"
        updateSettings { it.copy(channel = channel, bandwidthMhz = 20, macFilter = bssid ?: it.macFilter) }
        return "channel $channel (${info.frequency} MHz), BSSID ${bssid ?: "unknown — grant location permission"}"
    }

    fun runPreflight() {
        _preflight.value = "checking…"
        viewModelScope.launch(Dispatchers.IO) {
            val p = controller.preflight()
            withContext(Dispatchers.Main) {
                _preflight.value = p.report
                p.detectedChip?.let { chip -> updateSettings { it.copy(chip = chip) } }
            }
        }
    }

    fun start() {
        if (_running.value) return
        stopDemo()
        val s = _settings.value
        val macs = s.macFilter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (macs.any { runCatching { CsiParams.parseMac(it) }.isFailure }) { log("invalid MAC filter: ${s.macFilter}"); return }
        val cfg = try {
            CsiParams.Config(
                channel = s.channel, bandwidthMhz = s.bandwidthMhz, macFilters = macs,
                firstPacketByte = if (s.firstByteFilter) 0x88 else null,
            ).also { CsiParams.encode(it) }      // validates channel/bandwidth combination and filter count
        } catch (e: IllegalArgumentException) {
            log("invalid capture config: ${e.message}"); return
        }
        controller.iface = s.iface
        receiver.chip = s.chip
        detector.reset()
        _running.value = true
        viewModelScope.launch(Dispatchers.IO) {
            // Start listening first so the first frames are not lost
            receiver.start(s.source, s.iface)
            val r = runCatching { controller.start(cfg, monitorMode = s.mode == CaptureMode.PASSIVE) }
                .getOrElse { Result.failure(it) }
            controller.drain().forEach { log(it) }
            r.onSuccess { msg ->
                log(msg)
                activeConfig = cfg
                if (s.mode == CaptureMode.CONNECTED) {
                    val gw = gatewayAddress()
                    if (gw != null) { ping.start(gw, s.rateHz); log("pinging $gw at ${s.rateHz} Hz as root") }
                    else log("no gateway: connect to Wi-Fi first (ping not started)")
                }
            }.onFailure { e ->
                log("start failed: ${e.message}")
                receiver.stop()
                withContext(Dispatchers.Main) { _running.value = false }
            }
        }
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        viewModelScope.launch(Dispatchers.IO) {
            ping.stop()
            receiver.stop()
            activeConfig?.let { controller.stop(it, monitorMode = _settings.value.mode == CaptureMode.PASSIVE) }
            activeConfig = null
            controller.drain().forEach { log(it) }
            log("stopped")
        }
    }

    @Suppress("DEPRECATION")
    private fun gatewayAddress(): String? {
        val gw = wifi.dhcpInfo?.gateway ?: return null
        if (gw == 0) return null
        return String.format(java.util.Locale.US, "%d.%d.%d.%d", gw and 0xff, (gw shr 8) and 0xff, (gw shr 16) and 0xff, (gw shr 24) and 0xff)
    }

    fun setThreshold(t: Float) { detector.threshold = t }

    fun toggleRecording(format: CsiRecorder.Format) {
        if (recorder.isRecording) {
            val f = recorder.stop()
            _recording.value = null
            lastCapture.value = f
            log("saved ${f?.name} (${recorder.frameCount} frames)")
        } else {
            val s = _settings.value
            val f = recorder.start(format, mapOf("app" to "wifi-radar-rooted", "chip" to s.chip.name,
                "channel" to s.channel.toString(), "bw" to s.bandwidthMhz.toString(), "mode" to s.mode.name))
            _recording.value = f
            log("recording to ${f.name}")
        }
    }

    fun startDemo() {
        stop()
        detector.reset()
        _demo.value = true
        demoJob = viewModelScope.launch { SyntheticCsiSource(rateHz = 50).frames().collect { onFrame(it) } }
        log("demo source started")
    }

    fun stopDemo() {
        demoJob?.cancel(); demoJob = null
        _demo.value = false
    }

    private fun log(line: String) { _log.tryEmit(line) }

    override fun onCleared() {
        stopDemo()
        ping.stop()
        receiver.stop()
        recorder.stop()
    }
}
