package net.rslvd.wifiradar.esp32

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.slider.Slider
import net.rslvd.wifiradar.core.record.CsiRecorder
import net.rslvd.wifiradar.core.ui.CsiLineView
import net.rslvd.wifiradar.esp32.databinding.ActivityMainBinding
import net.rslvd.wifiradar.esp32.flasher.FlasherActivity
import net.rslvd.wifiradar.esp32.net.Esp32Client
import net.rslvd.wifiradar.esp32.net.Esp32Discovery
import net.rslvd.wifiradar.esp32.net.Esp32Protocol
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private val logLines = ArrayDeque<String>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestRuntimePermissions()

        b.editHost.setText(Esp32Protocol.DEFAULT_AP_IP)
        b.btnJoinAp.setOnClickListener { vm.wifi.connectToAccessPoint(Esp32Protocol.DEFAULT_SSID, Esp32Protocol.DEFAULT_PASSWORD) }
        b.btnWifiSettings.setOnClickListener { vm.wifi.openWifiSettings(this) }
        b.btnBindWifi.setOnClickListener { vm.wifi.bindToWifi() }
        b.btnConnect.setOnClickListener {
            when (vm.client.state.value) {
                is Esp32Client.State.Connected, is Esp32Client.State.Connecting -> vm.disconnect()
                else -> connectTo(b.editHost.text.toString())
            }
        }

        (if (vm.mode.value == MainViewModel.Mode.DUAL) b.modeDual else b.modeSingle).isChecked = true
        b.modeGroup.setOnCheckedChangeListener { _, id ->
            vm.setMode(if (id == R.id.modeDual) MainViewModel.Mode.DUAL else MainViewModel.Mode.SINGLE)
        }
        b.rateSlider.value = vm.rateHz.value.toFloat().coerceIn(b.rateSlider.valueFrom, b.rateSlider.valueTo)
        b.rateLabel.text = "Sample rate: ${b.rateSlider.value.toInt()} Hz"
        b.rateSlider.addOnChangeListener { _, v, _ -> b.rateLabel.text = "Sample rate: ${v.toInt()} Hz" }
        b.rateSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) { vm.setRate(slider.value.toInt()) }
        })

        b.plotModeGroup.setOnCheckedChangeListener { _, id ->
            b.lineView.mode = when (id) {
                R.id.plotLinear -> CsiLineView.Mode.AMPLITUDE_LINEAR
                R.id.plotPhase -> CsiLineView.Mode.PHASE
                else -> CsiLineView.Mode.AMPLITUDE_DB
            }
        }
        b.thresholdSlider.addOnChangeListener { _, v, _ ->
            val t = v / 100f
            b.thresholdLabel.text = "Motion threshold: %.2f".format(t)
            vm.setThreshold(t)
        }
        b.btnRecord.setOnClickListener {
            vm.toggleRecording(if (b.formatJsonl.isChecked) CsiRecorder.Format.JSONL else CsiRecorder.Format.CSV)
        }
        b.btnExport.setOnClickListener {
            vm.lastCapture.value?.let { CsiRecorder.share(this, it) } ?: toast("No finished capture yet")
        }
        b.btnSend.setOnClickListener {
            vm.sendCommand(b.editCommand.text.toString().trim().ifEmpty { "INFO" })
        }
        b.btnFlasher.setOnClickListener { startActivity(Intent(this, FlasherActivity::class.java)) }

        observe()
    }

    private fun connectTo(text: String) {
        val host = text.substringBefore(':').trim()
        val port = text.substringAfter(':', "").toIntOrNull() ?: Esp32Protocol.DEFAULT_TCP_PORT
        if (host.isEmpty()) { toast("Enter the ESP32 IP address"); return }
        vm.connect(host, port)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.frames.collect { b.lineView.update(it); b.heatmapView.update(it) } }
                launch {
                    vm.client.state.collect { st ->
                        b.connectionStatus.text = when (st) {
                            is Esp32Client.State.Disconnected -> "Not connected"
                            is Esp32Client.State.Connecting -> "Connecting to ${st.host}:${st.port}…"
                            is Esp32Client.State.Connected -> "Connected to ${st.host}:${st.port}"
                            is Esp32Client.State.Error -> "Error: ${st.message}"
                        }
                        b.btnConnect.text = if (st is Esp32Client.State.Connected || st is Esp32Client.State.Connecting) "Disconnect" else "Connect"
                    }
                }
                launch { vm.wifi.status.collect { b.wifiStatus.text = "Wi-Fi: $it" } }
                launch { vm.discovery.devices.collect { renderDevices(it) } }
                launch {
                    vm.motion.collect { m ->
                        b.motionLabel.text = when {
                            m.windowFrames < 5 -> "MOTION: —  (collecting)"
                            m.moving -> "MOTION  (score %.2f)".format(m.score)
                            m.presence -> "STILL — presence likely  (score %.2f)".format(m.score)
                            else -> "STILL  (score %.2f)".format(m.score)
                        }
                        b.motionBar.progress = (m.score * 100).toInt().coerceIn(0, b.motionBar.max)
                    }
                }
                launch { vm.fps.collect { b.fpsLabel.text = "$it frames/s" } }
                launch {
                    vm.recording.collect { f ->
                        b.btnRecord.text = if (f != null) "Stop recording" else "Start recording"
                        b.recordStatus.text = f?.let { "Recording → ${it.name}" } ?: (vm.lastCapture.value?.let { "Last capture: ${it.name}" } ?: "No capture yet")
                    }
                }
                launch { vm.lastCapture.collect { f -> if (vm.recording.value == null) b.recordStatus.text = f?.let { "Last capture: ${it.name}" } ?: "No capture yet" } }
                launch {
                    vm.client.status.collect { s ->
                        b.deviceStatus.text = s?.let {
                            "role=${it.roleName} ch=${it.channel} up=${it.uptimeS}s heap=${it.freeHeap / 1024}K\n" +
                            "csi total=${it.csiTotal} dropped=${it.csiDropped} filtered=${it.csiFiltered}\n" +
                            "pings_rx=${it.pingsRx} tx_count=${it.txCount} tx_rate=${it.txRateHz}Hz " +
                            (if (it.role == 2) "ap_rssi=${it.rssi}" else "stations=${it.staCount}") +
                            "   phone→esp pings=${vm.traffic.sent.get()}"
                        } ?: "no status yet"
                    }
                }
                launch {
                    vm.log.collect { line ->
                        logLines.addLast(line)
                        while (logLines.size > 12) logLines.removeFirst()
                        b.deviceLog.text = logLines.joinToString("\n")
                    }
                }
                launch {
                    vm.mode.collect { m ->
                        b.modeHint.text = if (m == MainViewModel.Mode.SINGLE)
                            "Phone joins the ESP32's AP and sends UDP pings at the sample rate; the CSI of every ping is streamed back."
                        else
                            "TX ESP32 is the AP and broadcasts pings at the sample rate. Phone and RX both join it; connect to the RX (role \"rx\") to stream."
                    }
                }
            }
        }
    }

    private fun renderDevices(devices: List<Esp32Discovery.Device>) {
        b.devicesContainer.removeAllViews()
        if (devices.isEmpty()) {
            b.devicesContainer.addView(TextView(this).apply {
                text = "none yet — join the \"${Esp32Protocol.DEFAULT_SSID}\" network, or type the IP below"
                alpha = 0.7f
            })
            return
        }
        for (d in devices) {
            b.devicesContainer.addView(TextView(this).apply {
                text = "▸ ${d.role.uppercase()}  ${d.ip}:${d.port}   ${d.mac}   fw ${d.fw}"
                textSize = 15f
                setPadding(8, 12, 8, 12)
                setOnClickListener {
                    if (vm.mode.value == MainViewModel.Mode.DUAL && d.role == "tx") {
                        toast("Dual mode streams from the RX device; the TX is configured automatically")
                    }
                    b.editHost.setText("${d.ip}:${d.port}")
                    vm.connect(d.ip, d.port)
                }
            })
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.NEARBY_WIFI_DEVICES
        val missing = wanted.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @Suppress("unused")
    private fun View.show(visible: Boolean) { visibility = if (visible) View.VISIBLE else View.GONE }
}
