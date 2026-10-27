package com.mothandgravel.sohva.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun `parses the MAC formats the TV and users produce`() {
        val expected = byteArrayOf(0xAC.toByte(), 0x9B.toByte(), 0x0A, 0x11, 0x22, 0x33)
        assertThat(WakeOnLan.parseMac("AC-9B-0A-11-22-33")).isEqualTo(expected)
        assertThat(WakeOnLan.parseMac("ac:9b:0a:11:22:33")).isEqualTo(expected)
        assertThat(WakeOnLan.parseMac("AC9B0A112233")).isEqualTo(expected)
        assertThrows(IllegalArgumentException::class.java) { WakeOnLan.parseMac("not a mac") }
    }

    @Test
    fun `magic packet is 6 x FF followed by the MAC 16 times`() {
        val packet = WakeOnLan.magicPacket("AC-9B-0A-11-22-33")
        assertThat(packet.size).isEqualTo(102)
        for (i in 0 until 6) assertThat(packet[i]).isEqualTo(0xFF.toByte())
        val mac = WakeOnLan.parseMac("AC-9B-0A-11-22-33")
        for (rep in 0 until 16) {
            assertThat(packet.copyOfRange(6 + rep * 6, 12 + rep * 6)).isEqualTo(mac)
        }
    }
}
