package net.rslvd.wifiradar.rooted.nexmon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NexmonTest {

    @Test fun chanspecMatchesBroadcomEncoding() {
        assertEquals(0x1006, CsiParams.chanspec(6, 20))
        assertEquals(0xD024, CsiParams.chanspec(36, 20))
        assertEquals(0xD826, CsiParams.chanspec(36, 40))     // centre 38, control = lower
        assertEquals(0xD926, CsiParams.chanspec(40, 40))     // centre 38, control = upper
        assertEquals(0xE29B, CsiParams.chanspec(157, 80))    // centre 155, sideband UL
        assertEquals(0xE02A, CsiParams.chanspec(36, 80))     // centre 42, sideband LL
    }

    @Test fun paramsBlobMatchesMakecsiparams() {
        val cfg = CsiParams.Config(157, 80, coreMask = 1, nssMask = 1,
            macFilters = listOf("00:11:22:33:44:55", "66:77:88:99:aa:bb"), firstPacketByte = 0x88)
        val blob = CsiParams.encode(cfg)
        assertEquals(34, blob.size)
        val expectedPrefix = "9be2011101880200001122334455667788" + "99aabb"
        val got = blob.joinToString("") { "%02x".format(it) }
        assertTrue(got, got.startsWith(expectedPrefix))
        assertTrue(CsiParams.encodeBase64(cfg).startsWith("m+IBEQGIAgAAESIzRFVm"))   // nexmon_csi README example prefix
    }

    @Test fun parsesInt16Payload() {
        val nfft = 64
        val b = ByteBuffer.allocate(18 + nfft * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x11).put(0x11).put((-40).toByte()).put(0x88.toByte())
        b.put(byteArrayOf(0x24, 0x6f, 0x28, 0x01, 0x02, 0x03))
        b.putShort(1234).putShort(0).putShort(0x1006).putShort(0)
        for (k in 0 until nfft) { b.putShort(k.toShort()); b.putShort((-k).toShort()) }
        val f = NexmonParser.parse(b.array(), b.capacity(), NexmonChip.BCM4339, 0, 0)
        assertNotNull(f); f!!
        assertEquals(-40, f.rssi); assertEquals(6, f.channel); assertEquals(20, f.bandwidthMhz); assertEquals("24:6f:28:01:02:03", f.sourceMac)
        assertEquals(64, f.subcarrierCount); assertEquals(55, f.validCount)   // 56 used tones minus the +28 phystatus tone
        assertTrue(!f.validMask[32 + 28] && f.validMask[32 + 27])
        for (i in 0 until nfft) { val k = (i + 32) % 64; assertEquals(k.toFloat(), f.re[i], 0f); assertEquals(-k.toFloat(), f.im[i], 0f) }
    }

    @Test fun unpackFloatScalesToNbits() {
        // word layout fmt 1: [29] sign_re [28:18] mant_re [17] sign_im [16:6] mant_im [5:0] exp
        val plusOne = 1 shl 18                       // mant_re = 1, exp = 0  -> +1.0
        val minusOne = (1 shl 29) or (1 shl 18)      // sign_re set            -> -1.0
        val twoTimesHalf = (2 shl 18) or 0x3f        // mant_re = 2, exp = -1  -> +1.0
        val imOne = 1 shl 6                          // mant_im = 1            -> im = +1.0
        val out = IntArray(8)
        NexmonParser.unpackFloatAcphy(10, true, 12, 6, 4, intArrayOf(plusOne, minusOne, twoTimesHalf, imOne), out)
        assertArrayEquals(intArrayOf(1024, 0, -1024, 0, 1024, 0, 0, 1024), out)
    }
}
