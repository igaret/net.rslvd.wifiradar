package net.rslvd.wifiradar.esp32.flasher

import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Minimal implementation of the esptool serial protocol against the **ESP32 ROM
 * bootloader** (no stub loader). Supports: reset-into-bootloader, SYNC, chip
 * detection, baud change, SPI attach/params, and uncompressed flash writes.
 *
 * Reference: esptool "Serial Protocol" documentation and esptool/loader.py.
 */
class EspFlasher(private val port: UsbSerialPort, private val log: (String) -> Unit) {

    class FlashException(message: String) : IOException(message)

    private class Response(val value: Int, val data: ByteArray)

    // ESP32 ROM replies carry 4 status bytes (the stub and ESP8266 use 2); learned from SYNC.
    private var statusBytesLength = 4
    private val readBuf = ByteArray(4096)
    private var readPos = 0
    private var readLen = 0

    // ------------------------------------------------------------------ transport

    private fun write(data: ByteArray) {
        port.write(data, 2000)
    }

    private fun flushInput() {
        try { port.purgeHwBuffers(false, true) } catch (_: Exception) {}
        readPos = 0; readLen = 0
        val tmp = ByteArray(1024)
        var n: Int
        do { n = try { port.read(tmp, 20) } catch (_: IOException) { 0 } } while (n > 0)
    }

    private fun readByte(deadline: Long): Int {
        while (readPos >= readLen) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return -1
            readLen = port.read(readBuf, remaining.coerceAtMost(500).toInt())
            readPos = 0
        }
        return readBuf[readPos++].toInt() and 0xff
    }

    private fun readFrame(deadline: Long): ByteArray? {
        val decoder = SlipCodec.Decoder()
        while (true) {
            val b = readByte(deadline)
            if (b < 0) return null
            decoder.feed(b)?.let { return it }
        }
    }

    // ------------------------------------------------------------------ protocol core

    /** Request: 0x00 | op | u16 len | u32 checksum | data.  Reply: 0x01 | op | u16 len | u32 value | data+status */
    private fun command(op: Int, data: ByteArray, checksum: Int = 0, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Response {
        val pkt = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(0).put(op.toByte()).putShort(data.size.toShort()).putInt(checksum).put(data).array()
        write(SlipCodec.encode(pkt))
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val frame = readFrame(deadline) ?: throw FlashException("timeout waiting for reply to command 0x%02x".format(op))
            if (frame.size < 8 || frame[0].toInt() != 1) continue
            if ((frame[1].toInt() and 0xff) != op) continue              // stale reply to an earlier command
            val b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            b.position(2)
            val len = b.short.toInt() and 0xffff
            val value = b.int
            return Response(value, frame.copyOfRange(8, minOf(frame.size, 8 + len)))
        }
    }

    private fun checkCommand(desc: String, op: Int, data: ByteArray, checksum: Int = 0, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Response {
        val r = command(op, data, checksum, timeoutMs)
        if (r.data.size < statusBytesLength) throw FlashException("$desc: short reply (${r.data.size} bytes)")
        val idx = r.data.size - statusBytesLength
        val status = r.data[idx].toInt() and 0xff
        if (status != 0) {
            val err = r.data[idx + 1].toInt() and 0xff
            throw FlashException("$desc failed: error 0x%02x (%s)".format(err, ROM_ERRORS[err] ?: "unknown"))
        }
        return r
    }

    private fun le(vararg values: Int): ByteArray =
        ByteBuffer.allocate(4 * values.size).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()

    /** XOR of all data bytes seeded with 0xEF — required for FLASH_DATA / MEM_DATA. */
    fun checksum(data: ByteArray): Int {
        var c = CHECKSUM_SEED
        for (b in data) c = c xor (b.toInt() and 0xff)
        return c
    }

    // ------------------------------------------------------------------ reset

    /**
     * esptool "ClassicReset": on DevKit boards DTR drives GPIO0 and RTS drives EN
     * through a two-transistor circuit. Pull EN low with GPIO0 high, then release EN
     * while GPIO0 is low → ROM download mode.
     */
    fun enterBootloader() {
        port.setDTR(false); port.setRTS(true)      // IO0 = high, EN = low (reset)
        SystemClock.sleep(100)
        port.setDTR(true); port.setRTS(false)      // IO0 = low, EN = high (boot into download mode)
        SystemClock.sleep(50)
        port.setDTR(false)                         // release IO0
    }

    fun hardReset() {
        port.setDTR(false)
        port.setRTS(true)
        SystemClock.sleep(100)
        port.setRTS(false)
    }

    // ------------------------------------------------------------------ high level

    fun connect(resetAttempts: Int = 3): Boolean {
        for (attempt in 1..resetAttempts) {
            log("Resetting into bootloader (attempt $attempt/$resetAttempts)…")
            enterBootloader()
            SystemClock.sleep(50)
            flushInput()
            for (i in 1..7) {
                if (trySync()) { log("Synced with ROM bootloader"); return true }
                SystemClock.sleep(50)
            }
        }
        return false
    }

    private fun trySync(): Boolean {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        return try {
            val r = command(ESP_SYNC, payload, 0, SYNC_TIMEOUT_MS)
            statusBytesLength = r.data.size.coerceIn(2, 4)
            // The ROM answers one SYNC with a burst of identical replies; drain them.
            val deadline = SystemClock.elapsedRealtime() + 150
            while (readFrame(deadline) != null) { /* discard */ }
            true
        } catch (_: FlashException) {
            false
        }
    }

    fun readReg(addr: Int): Int = checkCommand("read register 0x%08x".format(addr), ESP_READ_REG, le(addr)).value

    fun detectChip(): String {
        val magic = readReg(CHIP_DETECT_MAGIC_REG)
        return KNOWN_CHIPS[magic] ?: "unknown chip (magic 0x%08x)".format(magic)
    }

    /** The ESP32 ROM supports baud changes (second argument 0 = "I am the ROM", not the stub). */
    fun changeBaud(baud: Int) {
        checkCommand("change baud rate", ESP_CHANGE_BAUDRATE, le(baud, 0))
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        SystemClock.sleep(50)
        flushInput()
    }

    /** ROM loader needs the SPI flash pins attached explicitly (arg 0 = default pins; extra u32 is a legacy ROM param). */
    fun attachFlash() {
        checkCommand("attach SPI flash", ESP_SPI_ATTACH, le(0, 0))
    }

    fun setFlashParams(totalSizeBytes: Int) {
        checkCommand("set SPI flash parameters", ESP_SPI_SET_PARAMS,
            le(0 /*id*/, totalSizeBytes, 64 * 1024 /*block*/, 4 * 1024 /*sector*/, 256 /*page*/, 0xffff /*status mask*/))
    }

    /**
     * Writes [image] at [offset]. FLASH_BEGIN makes the ROM erase the region (slow: ~30 s/MB
     * budget), then 1 KB FLASH_DATA blocks follow. FLASH_END is deliberately NOT sent: on the
     * ROM loader it exits to user code regardless of its argument; we hard-reset at the end instead.
     */
    fun writeFlash(offset: Int, image: ByteArray, progress: (written: Int, total: Int) -> Unit) {
        val blocks = (image.size + ROM_FLASH_WRITE_SIZE - 1) / ROM_FLASH_WRITE_SIZE
        val eraseTimeout = max(DEFAULT_TIMEOUT_MS, ERASE_TIMEOUT_PER_MB_MS * image.size / 1_000_000L)
        log("Erasing %d KB at 0x%06x…".format(image.size / 1024, offset))
        checkCommand("enter flash mode", ESP_FLASH_BEGIN, le(image.size, blocks, ROM_FLASH_WRITE_SIZE, offset), 0, eraseTimeout)
        val block = ByteArray(ROM_FLASH_WRITE_SIZE)
        for (seq in 0 until blocks) {
            val start = seq * ROM_FLASH_WRITE_SIZE
            val n = minOf(ROM_FLASH_WRITE_SIZE, image.size - start)
            block.fill(0xFF.toByte())
            System.arraycopy(image, start, block, 0, n)
            checkCommand("write block $seq/$blocks", ESP_FLASH_DATA, le(block.size, seq, 0, 0) + block, checksum(block))
            progress(start + n, image.size)
        }
    }

    companion object {
        const val ESP_FLASH_BEGIN = 0x02
        const val ESP_FLASH_DATA = 0x03
        const val ESP_FLASH_END = 0x04
        const val ESP_SYNC = 0x08
        const val ESP_READ_REG = 0x0A
        const val ESP_SPI_SET_PARAMS = 0x0B
        const val ESP_SPI_ATTACH = 0x0D
        const val ESP_CHANGE_BAUDRATE = 0x0F

        const val ROM_FLASH_WRITE_SIZE = 0x400
        const val CHECKSUM_SEED = 0xEF
        const val CHIP_DETECT_MAGIC_REG = 0x40001000

        const val DEFAULT_TIMEOUT_MS = 3000L
        const val SYNC_TIMEOUT_MS = 100L
        const val ERASE_TIMEOUT_PER_MB_MS = 30_000L

        val KNOWN_CHIPS: Map<Int, String> = mapOf(
            0x00f01d83 to "ESP32",
            0x000007c6 to "ESP32-S2",
            0x00000009 to "ESP32-S3",
            0x6921506f to "ESP32-C3",
            0x1b31506f to "ESP32-C3",
            0x2ce0806f to "ESP32-C6",
            0xd7b73e80.toInt() to "ESP32-H2",
            0xfff0c101.toInt() to "ESP8266",
        )
        private val ROM_ERRORS = mapOf(
            0x05 to "received message is invalid", 0x06 to "failed to act on received message",
            0x07 to "invalid CRC in message", 0x08 to "flash write error", 0x09 to "flash read error",
            0x0a to "flash read length error", 0x0b to "deflate error",
        )
    }
}
