package net.rslvd.wifiradar.esp32.flasher

import android.content.Context
import org.json.JSONObject

/** A set of (offset, image) pairs to flash, loaded from assets/firmware/manifest.json or a user file. */
class FirmwareBundle(val name: String, val version: String, val chip: String, val flashSizeBytes: Int, val parts: List<Part>) {

    class Part(val file: String, val offset: Int, val data: ByteArray)

    val totalBytes: Int get() = parts.sumOf { it.data.size }

    override fun toString(): String =
        "$name $version — ${parts.size} part(s), ${totalBytes / 1024} KB: " +
            parts.joinToString(", ") { "${it.file}@0x%X".format(it.offset) }

    companion object {
        fun fromAssets(context: Context, dir: String = "firmware"): FirmwareBundle {
            val json = JSONObject(context.assets.open("$dir/manifest.json").bufferedReader().use { it.readText() })
            val arr = json.getJSONArray("parts")
            val parts = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val file = o.getString("file")
                Part(file, o.getInt("offset"), context.assets.open("$dir/$file").use { it.readBytes() })
            }
            val mb = json.optString("flash_size", "4MB").removeSuffix("MB").toIntOrNull() ?: 4
            return FirmwareBundle(json.optString("name", "firmware"), json.optString("version", "?"),
                json.optString("chip", "esp32"), mb * 1024 * 1024, parts)
        }

        /** A single merged image (bootloader + partition table + app), normally flashed at 0x0. */
        fun single(name: String, data: ByteArray, offset: Int): FirmwareBundle =
            FirmwareBundle(name, "custom", "esp32", 4 * 1024 * 1024, listOf(Part(name, offset, data)))
    }
}
