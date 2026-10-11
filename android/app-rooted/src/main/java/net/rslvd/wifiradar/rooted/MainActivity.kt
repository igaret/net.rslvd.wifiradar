package net.rslvd.wifiradar.rooted

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
import net.rslvd.wifiradar.rooted.databinding.ActivityMainBinding
import net.rslvd.wifiradar.rooted.nexmon.NexmonChip
import net.rslvd.wifiradar.rooted.nexmon.NexmonCsiReceiver
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

        val s = vm.settings.value
        b.chipSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, NexmonChip.values().map { it.label })
        b.chipSpinner.setSelection(s.chip.ordinal)
        b.chipSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { vm.updateSettings { it.copy(chip = NexmonChip.values()[pos]) } }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        b.editIface.setText(s.iface)
        b.editChannel.setText(s.channel.toString())
        b.editMac.setText(s.macFilter)
        (if (s.mode == MainViewModel.CaptureMode.PASSIVE) b.modePassive else b.modeConnected).isChecked = true
        when (s.bandwidthMhz) { 40 -> b.bw40; 80 -> b.bw80; else -> b.bw20 }.isChecked = true
        (if (s.source == NexmonCsiReceiver.Source.TCPDUMP) b.srcTcpdump else b.srcUdp).isChecked = true
        b.firstByteFilter.isChecked = s.firstByteFilter
        b.rateSlider.value = s.rateHz.toFloat().coerceIn(b.rateSlider.valueFrom, b.rateSlider.valueTo)
        b.rateLabel.text = "Ping rate: ${b.rateSlider.value.toInt()} Hz"

        b.modeGroup.setOnCheckedChangeListener { _, id ->
            vm.updateSettings { it.copy(mode = if (id == R.id.modePassive) MainViewModel.CaptureMode.PASSIVE else MainViewModel.CaptureMode.CONNECTED) }
            updateModeHint()
        }
        b.bwGroup.setOnCheckedChangeListener { _, id ->
            vm.updateSettings { it.copy(bandwidthMhz = when (id) { R.id.bw40 -> 40; R.id.bw80 -> 80; else -> 20 }) }
        }
        b.sourceGroup.setOnCheckedChangeListener { _, id ->
            vm.updateSettings { it.copy(source = if (id == R.id.srcTcpdump) NexmonCsiReceiver.Source.TCPDUMP else NexmonCsiReceiver.Source.UDP_SOCKET) }
        }
        b.firstByteFilter.setOnCheckedChangeListener { _, checked -> vm.updateSettings { it.copy(firstByteFilter = checked) } }
        b.rateSlider.addOnChangeListener { _, v, _ -> b.rateLabel.text = "Ping rate: ${v.toInt()} Hz" }
        b.rateSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) { vm.updateSettings { it.copy(rateHz = slider.value.toInt()) } }
        })
        b.btnUseCurrentAp.setOnClickListener {
            toast(vm.useCurrentAccessPoint())
            val n = vm.settings.value
            b.editChannel.setText(n.channel.toString()); b.editMac.setText(n.macFilter); b.bw20.isChecked = true
        }
        b.btnPreflight.setOnClickListener { vm.runPreflight() }
        b.btnStart.setOnClickListener {
            if (vm.running.value) { vm.stop(); return@setOnClickListener }
            val channel = b.editChannel.text.toString().toIntOrNull()
            if (channel == null || channel !in 1..196) { toast("Enter a valid channel"); return@setOnClickListener }
            vm.updateSettings { it.copy(iface = b.editIface.text.toString().trim().ifEmpty { "wlan0" }, channel = channel, macFilter = b.editMac.text.toString().trim()) }
            vm.start()
        }
        b.btnDemo.setOnClickListener { if (vm.demo.value) vm.stopDemo() else vm.startDemo() }

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
        b.btnRecord.setOnClickListener { vm.toggleRecording(if (b.formatJsonl.isChecked) CsiRecorder.Format.JSONL else CsiRecorder.Format.CSV) }
        b.btnExport.setOnClickListener { vm.lastCapture.value?.let { CsiRecorder.share(this, it) } ?: toast("No finished capture yet") }

        updateModeHint()
        observe()
    }

    private fun updateModeHint() {
        b.modeHint.text = if (vm.settings.value.mode == MainViewModel.CaptureMode.CONNECTED)
            "Stay connected to your AP; the phone pings the gateway as root and the firmware extracts CSI from the AP's replies (filter = BSSID). Use “Use current AP” to fill channel + BSSID."
        else
            "Monitor mode: sniff every frame on the chosen channel/bandwidth (Wi-Fi connectivity is lost while active). Another device must generate traffic, e.g. the ESP32 TX from the companion firmware."
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.frames.collect { b.lineView.update(it); b.heatmapView.update(it) } }
                launch { vm.preflight.collect { b.preflightText.text = it } }
                launch {
                    vm.running.collect { r ->
                        b.btnStart.text = if (r) "Stop capture" else "Start capture"
                        b.statusText.text = if (r) "Capturing…" else "Idle"
                    }
                }
                launch { vm.stats.collect { b.statsText.text = it } }
                launch { vm.fps.collect { b.fpsLabel.text = "$it frames/s" } }
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
                launch {
                    vm.recording.collect { f ->
                        b.btnRecord.text = if (f != null) "Stop recording" else "Start recording"
                        b.recordStatus.text = f?.let { "Recording → ${it.name}" } ?: (vm.lastCapture.value?.let { "Last capture: ${it.name}" } ?: "No capture yet")
                    }
                }
                launch { vm.lastCapture.collect { f -> if (vm.recording.value == null) b.recordStatus.text = f?.let { "Last capture: ${it.name}" } ?: "No capture yet" } }
                launch {
                    vm.log.collect { line ->
                        logLines.addLast(line)
                        while (logLines.size > 14) logLines.removeFirst()
                        b.logText.text = logLines.joinToString("\n")
                    }
                }
                launch { vm.demo.collect { b.btnDemo.text = if (it) "Stop demo" else "Demo (synthetic CSI)" } }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.NEARBY_WIFI_DEVICES
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
