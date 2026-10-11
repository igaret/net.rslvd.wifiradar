package net.rslvd.wifiradar.core.record

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import net.rslvd.wifiradar.core.model.CsiFrame
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes CSI frames to CSV or JSON Lines in the app's external "captures" directory. */
class CsiRecorder(private val dir: File) {

    enum class Format(val extension: String) { CSV("csv"), JSONL("jsonl") }

    private var writer: BufferedWriter? = null
    private var format = Format.CSV
    private var headerWritten = false
    var file: File? = null
        private set
    var frameCount = 0L
        private set
    val isRecording: Boolean get() = writer != null

    @Synchronized
    fun start(format: Format, meta: Map<String, String> = emptyMap()): File {
        stop()
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "csi_$stamp.${format.extension}")
        writer = BufferedWriter(FileWriter(f), 1 shl 16)
        this.format = format
        headerWritten = false
        frameCount = 0
        file = f
        if (format == Format.JSONL && meta.isNotEmpty()) {
            writer!!.write(JSONObject(meta).put("type", "meta").toString())
            writer!!.newLine()
        }
        return f
    }

    @Synchronized
    fun write(frame: CsiFrame) {
        val w = writer ?: return
        when (format) {
            Format.CSV -> {
                if (!headerWritten) {
                    val n = frame.subcarrierCount
                    val sb = StringBuilder("recv_time_ms,seq,device_ts_us,source,mac,rssi,noise,channel,bw_mhz,sig_mode,mcs,n_sub,valid_mask")
                    for (i in 0 until n) sb.append(",re_").append(i)
                    for (i in 0 until n) sb.append(",im_").append(i)
                    w.write(sb.toString()); w.newLine()
                    headerWritten = true
                }
                val sb = StringBuilder(frame.subcarrierCount * 12 + 128)
                sb.append(frame.receivedAtMs).append(',').append(frame.seq).append(',')
                    .append(frame.deviceTimestampUs).append(',').append(frame.source.name).append(',')
                    .append(frame.sourceMac).append(',').append(frame.rssi).append(',')
                    .append(frame.noiseFloor).append(',').append(frame.channel).append(',')
                    .append(frame.bandwidthMhz).append(',').append(frame.sigMode).append(',')
                    .append(frame.mcs).append(',').append(frame.subcarrierCount).append(',')
                for (v in frame.validMask) sb.append(if (v) '1' else '0')
                for (v in frame.re) sb.append(',').append(v)
                for (v in frame.im) sb.append(',').append(v)
                w.write(sb.toString()); w.newLine()
            }
            Format.JSONL -> {
                val o = JSONObject()
                    .put("type", "csi")
                    .put("recv_time_ms", frame.receivedAtMs)
                    .put("seq", frame.seq)
                    .put("device_ts_us", frame.deviceTimestampUs)
                    .put("source", frame.source.name)
                    .put("mac", frame.sourceMac)
                    .put("rssi", frame.rssi)
                    .put("noise", frame.noiseFloor)
                    .put("channel", frame.channel)
                    .put("bw_mhz", frame.bandwidthMhz)
                    .put("sig_mode", frame.sigMode)
                    .put("mcs", frame.mcs)
                    .put("re", JSONArray().also { a -> frame.re.forEach { a.put(it.toDouble()) } })
                    .put("im", JSONArray().also { a -> frame.im.forEach { a.put(it.toDouble()) } })
                    .put("valid", JSONArray().also { a -> frame.validMask.forEach { a.put(it) } })
                frame.raw?.let { raw -> o.put("raw", JSONArray().also { a -> raw.forEach { a.put(it.toInt()) } }) }
                w.write(o.toString()); w.newLine()
            }
        }
        frameCount++
    }

    @Synchronized
    fun stop(): File? {
        writer?.flush()
        writer?.close()
        writer = null
        return file
    }

    companion object {
        fun defaultDir(context: Context): File = File(context.getExternalFilesDir(null), "captures")

        /** Opens the system share sheet for a capture file via FileProvider. */
        fun share(context: Context, file: File) {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val intent = Intent(Intent.ACTION_SEND)
                .setType(if (file.extension == "csv") "text/csv" else "application/json")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Export ${file.name}"))
        }
    }
}
