package net.rslvd.wifiradar.esp32.flasher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import net.rslvd.wifiradar.esp32.databinding.ActivityFlasherBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class FlasherActivity : AppCompatActivity() {

    private lateinit var b: ActivityFlasherBinding
    private lateinit var usbManager: UsbManager
    private var drivers: List<UsbSerialDriver> = emptyList()
    private var selected: UsbSerialDriver? = null
    private var customBundle: FirmwareBundle? = null
    private var flashing = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                log(if (granted) "USB permission granted" else "USB permission denied")
                refreshDevices()
            }
        }
    }

    private val pickBin = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            customBundle = FirmwareBundle.single(uri.lastPathSegment?.substringAfterLast('/') ?: "custom.bin", bytes, 0)
            b.firmwareLabel.text = "Custom merged image: ${bytes.size / 1024} KB → flashed at 0x0"
            log("Loaded custom image (${bytes.size} bytes). It must be a merged image (bootloader@0x1000 + table@0x8000 + app@0x10000).")
        }.onFailure { log("Could not read file: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFlasherBinding.inflate(layoutInflater)
        setContentView(b.root)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)

        b.baudSpinner.setSelection(2)   // 460800
        b.btnRefresh.setOnClickListener { refreshDevices() }
        b.btnBundled.setOnClickListener { customBundle = null; showBundledInfo() }
        b.btnPickBin.setOnClickListener { pickBin.launch(arrayOf("application/octet-stream", "*/*")) }
        b.btnFlash.setOnClickListener { startFlash() }

        showBundledInfo()
        refreshDevices()
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) log("USB device attached")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refreshDevices()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun showBundledInfo() {
        runCatching { FirmwareBundle.fromAssets(this) }
            .onSuccess { b.firmwareLabel.text = "Bundled: $it" }
            .onFailure { b.firmwareLabel.text = "Bundled firmware missing from assets (${it.message})" }
    }

    private fun refreshDevices() {
        drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        selected = drivers.firstOrNull()
        val d = selected
        if (d == null) {
            b.deviceLabel.text = "No USB-serial adapter found. Plug the ESP32 in via an OTG cable/adapter."
            return
        }
        val dev = d.device
        val perm = usbManager.hasPermission(dev)
        b.deviceLabel.text = "%s  (%04x:%04x)  %s  — %s".format(
            d.javaClass.simpleName.removeSuffix("SerialDriver"), dev.vendorId, dev.productId,
            dev.productName ?: "", if (perm) "permission OK" else "needs permission")
        if (!perm) requestPermission(dev)
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
        usbManager.requestPermission(device, pi)
    }

    private fun startFlash() {
        if (flashing) return
        val driver = selected ?: run { toast("No USB device"); return }
        if (!usbManager.hasPermission(driver.device)) { requestPermission(driver.device); return }
        val bundle = customBundle ?: runCatching { FirmwareBundle.fromAssets(this) }.getOrElse {
            toast("Bundled firmware not found"); return
        }
        val baud = b.baudSpinner.selectedItem.toString().toInt()
        flashing = true
        b.btnFlash.isEnabled = false
        b.progress.progress = 0
        b.log.text = ""
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                flash(driver, bundle, baud)
            } catch (e: Exception) {
                log("FAILED: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { flashing = false; b.btnFlash.isEnabled = true }
            }
        }
    }

    private fun flash(driver: UsbSerialDriver, bundle: FirmwareBundle, baud: Int) {
        val connection = usbManager.openDevice(driver.device) ?: throw IOException("could not open USB device (permission?)")
        val port = driver.ports[0]
        port.open(connection)
        try {
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val flasher = EspFlasher(port) { log(it) }
            if (!flasher.connect()) {
                throw IOException("Could not sync with the ESP32 bootloader. If your board lacks auto-reset, hold BOOT, tap EN/RST, release BOOT, then press Flash again.")
            }
            val chip = flasher.detectChip()
            log("Detected: $chip")
            if (chip != "ESP32") throw IOException("This firmware targets the original ESP32 (WROOM-32); refusing to flash a $chip")
            if (baud != 115200) {
                try { flasher.changeBaud(baud); log("Baud rate → $baud") }
                catch (e: Exception) { log("Baud change failed (${e.message}); continuing at 115200") }
            }
            flasher.attachFlash()
            flasher.setFlashParams(bundle.flashSizeBytes)
            val total = bundle.totalBytes
            var done = 0
            for (part in bundle.parts) {
                log("Writing ${part.file}: ${part.data.size} bytes at 0x%06X".format(part.offset))
                val base = done
                flasher.writeFlash(part.offset, part.data) { written, _ -> progress(base + written, total) }
                done += part.data.size
            }
            log("All ${bundle.parts.size} part(s) written (${total / 1024} KB). Resetting…")
            flasher.hardReset()
            log("Done. The board boots the Wi-Fi Radar firmware: default role 'single', AP \"WiFiRadar\" / \"wifiradar123\".\n" +
                "Hold BOOT 3 s to cycle role (LED blinks: 1=single 2=tx 3=rx), or send SET_ROLE from the main screen.")
        } finally {
            runCatching { port.close() }
        }
    }

    private fun log(line: String) = runOnUiThread {
        b.log.append(line + "\n")
        b.logScroll.post { b.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun progress(done: Int, total: Int) = runOnUiThread {
        b.progress.progress = if (total > 0) (done * 100L / total).toInt() else 0
        b.progressLabel.text = "${done / 1024} / ${total / 1024} KB"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object { private const val ACTION_USB_PERMISSION = "net.rslvd.wifiradar.esp32.USB_PERMISSION" }
}
