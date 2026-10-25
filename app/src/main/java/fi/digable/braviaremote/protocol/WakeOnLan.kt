package fi.digable.braviaremote.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Wakes a TV in deep standby with a UDP magic packet. The TV must have "Remote start" on. */
object WakeOnLan {
    const val PORT = 9

    /** Accepts `AA-BB-CC-DD-EE-FF`, `aa:bb:cc:dd:ee:ff` or `aabbccddeeff`. */
    fun parseMac(mac: String): ByteArray {
        val hex = mac.replace("-", "").replace(":", "").replace(".", "").trim()
        require(hex.length == 12 && hex.all { it.isLetterOrDigit() }) { "Bad MAC address: $mac" }
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Six 0xFF bytes followed by the MAC sixteen times. */
    fun magicPacket(mac: String): ByteArray {
        val macBytes = parseMac(mac)
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (rep in 0 until 16) macBytes.copyInto(packet, 6 + rep * 6)
        return packet
    }

    /**
     * Sends the magic packet to the limited broadcast address and, when [host] is given, directly
     * to the TV's last known address as well (some routers drop broadcasts from Wi-Fi clients).
     */
    suspend fun send(mac: String, host: String? = null) = withContext(Dispatchers.IO) {
        val packet = magicPacket(mac)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val targets = buildList {
                add(InetAddress.getByName("255.255.255.255"))
                host?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }?.let { add(it) }
            }
            repeat(3) {
                for (target in targets) {
                    socket.send(DatagramPacket(packet, packet.size, target, PORT))
                }
            }
        }
    }
}
