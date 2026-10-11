package net.rslvd.wifiradar.esp32.flasher

import java.io.ByteArrayOutputStream

/** SLIP framing used by the Espressif ROM serial bootloader (RFC 1055 style). */
object SlipCodec {
    const val END = 0xC0
    const val ESC = 0xDB
    const val ESC_END = 0xDC
    const val ESC_ESC = 0xDD

    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 16)
        out.write(END)
        for (b in payload) {
            when (b.toInt() and 0xff) {
                END -> { out.write(ESC); out.write(ESC_END) }
                ESC -> { out.write(ESC); out.write(ESC_ESC) }
                else -> out.write(b.toInt())
            }
        }
        out.write(END)
        return out.toByteArray()
    }

    /** Byte-at-a-time decoder; returns a complete frame when the closing END arrives. */
    class Decoder {
        private val buf = ByteArrayOutputStream()
        private var inFrame = false
        private var escaped = false

        fun feed(b: Int): ByteArray? {
            if (!inFrame) {
                if (b == END) { inFrame = true; buf.reset(); escaped = false }
                return null
            }
            if (escaped) {
                escaped = false
                buf.write(when (b) { ESC_END -> END; ESC_ESC -> ESC; else -> b })
                return null
            }
            return when (b) {
                ESC -> { escaped = true; null }
                END -> if (buf.size() == 0) null else { inFrame = false; buf.toByteArray() }
                else -> { buf.write(b); null }
            }
        }
    }
}
