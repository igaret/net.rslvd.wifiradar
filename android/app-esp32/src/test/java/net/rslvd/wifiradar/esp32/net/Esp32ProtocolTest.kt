package net.rslvd.wifiradar.esp32.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors were produced by compiling the firmware's own packed structs (csi_collector.h / net_server.h) on the host. */
class Esp32ProtocolTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val csiVector = "04030201ddccbbaa246f28abcdefc7a106000107000b00010013" + "8000" +
        (0 until 64).joinToString("") { k -> "%02x%02x".format((-k) and 0xff, k and 0xff) }

    @Test fun parsesCsiSampleFromFirmwareStruct() {
        val f = Esp32Protocol.parseCsi(hex(csiVector), 1_000L)
        assertNotNull(f); f!!
        assertEquals(0x01020304L, f.seq)
        assertEquals(0xAABBCCDDL, f.deviceTimestampUs)
        assertEquals("24:6f:28:ab:cd:ef", f.sourceMac)
        assertEquals(-57, f.rssi); assertEquals(-95, f.noiseFloor)
        assertEquals(6, f.channel); assertEquals(1, f.sigMode); assertEquals(7, f.mcs); assertEquals(20, f.bandwidthMhz)
        assertEquals(64, f.subcarrierCount)
        assertEquals(128, f.raw!!.size)
        // natural index i <-> FFT index (i+32)%64 ; firmware wrote imag=-k, real=k
        for (i in 0 until 64) {
            val k = (i + 32) % 64
            if (k < 2) { assertEquals(0f, f.re[i], 0f); assertEquals(0f, f.im[i], 0f) }   // first_word_invalid zeroing
            else { assertEquals(k.toFloat(), f.re[i], 0f); assertEquals(-k.toFloat(), f.im[i], 0f) }
        }
        assertFalse(f.validMask[32]); assertFalse(f.validMask[33]); assertTrue(f.validMask[34]); assertTrue(f.validMask[6])
        assertEquals(51, f.validCount)
    }

    @Test fun parsesStatusFromFirmwareStruct() {
        val s = Esp32Protocol.parseStatus(hex("d2040000a0860100050000004d00000092100000e703000020bf0200020bc10396000000"))
        assertNotNull(s); s!!
        assertEquals(1234L, s.uptimeS); assertEquals(100000L, s.csiTotal); assertEquals(5L, s.csiDropped)
        assertEquals(77L, s.csiFiltered); assertEquals(4242L, s.pingsRx); assertEquals(999L, s.txCount)
        assertEquals(180000L, s.freeHeap); assertEquals(2, s.role); assertEquals("rx", s.roleName)
        assertEquals(11, s.channel); assertEquals(-63, s.rssi); assertEquals(3, s.staCount); assertEquals(150, s.txRateHz)
    }
}
