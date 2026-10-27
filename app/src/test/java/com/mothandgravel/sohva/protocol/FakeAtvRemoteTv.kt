package com.mothandgravel.sohva.protocol

import com.mothandgravel.sohva.protocol.AtvWire.bytes
import com.mothandgravel.sohva.protocol.AtvWire.message
import com.mothandgravel.sohva.protocol.AtvWire.string
import com.mothandgravel.sohva.protocol.AtvWire.varint
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * A TV's remote channel (port 6466) on a local TLS socket, following what the KD-65XF9005 sent
 * on 2026-10-24: configure, set_active, then volume, power and current-app pushes. Tests drive
 * pings and pushes and read back what the client sent.
 */
class FakeAtvRemoteTv {
    val certificate: HeldCertificate = HeldCertificate.Builder().rsa2048().commonName("atvremote/fake").build()
    val clientCertificate: HeldCertificate = HeldCertificate.Builder().rsa2048().commonName("BraviaRemote test").build()
    val clientKeyManager = HandshakeCertificates.Builder().heldCertificate(clientCertificate).build().keyManager

    /** The client's device info from its configure answer (field number to value). */
    @Volatile var deviceInfo: List<AtvWire.Field> = emptyList()

    /** (key code, direction) pairs in arrival order. */
    val keys = CopyOnWriteArrayList<Pair<Int, Int>>()
    val pongs = LinkedBlockingQueue<Long>()

    /** Text edits (field 21) as the client sent them, raw. */
    val textEdits = CopyOnWriteArrayList<ByteArray>()
    /** The text field as the TV holds it; the fake applies edits and echoes like the real TV. */
    @Volatile var fieldText = ""
    @Volatile var imeCounter = 0L
    @Volatile var fieldId = 0L
    /** When false, edits are applied but not echoed (a TV that stopped answering). */
    @Volatile var echo = true
    private val keyArrivals = LinkedBlockingQueue<Unit>()

    @Volatile private var client: SSLSocket? = null
    private lateinit var server: SSLServerSocket
    val port: Int get() = server.localPort

    fun start() {
        val tls = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .addTrustedCertificate(clientCertificate.certificate)
            .build()
        server = (tls.sslContext().serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply {
            needClientAuth = true
        }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { socket.use(::serve) } }
            }
        }
    }

    fun stop() = server.close()

    /** Drops the client as a TV does when it goes to standby. */
    fun disconnect() = client?.close()

    fun ping(value: Long) = push { message(8) { varint(1, value); varint(2, 0) } }

    fun pushVolume(level: Int, muted: Boolean = false) =
        push { message(50) { varint(1, 3); varint(6, 100); varint(7, level); varint(8, if (muted) 1 else 0) } }

    fun pushCurrentApp(packageName: String) = push { message(20) { message(1) { string(12, packageName) } } }

    /**
     * What the KD-65XF9005 sent when its search box got focus: the app's IME counter and the
     * field (id, text, hint) in field 20, then field 21 with 1 = keyboard up.
     */
    fun openTextField(counter: Long, id: Long, text: String = "", hint: String = "Search") {
        imeCounter = counter
        fieldId = id
        fieldText = text
        push {
            message(20) {
                message(1) { varint(1, counter); string(12, "com.google.android.katniss") }
                message(2) { varint(1, id); string(2, text); varint(3, 0); varint(4, 0); varint(5, 1); string(6, hint) }
            }
        }
        push { message(21) { varint(1, 1); varint(2, 3) } }
    }

    fun closeTextField() = push { message(21) { varint(1, 0); varint(2, 3) } }

    fun awaitTextEdits(count: Int, timeoutMs: Long = 5000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (textEdits.size < count) {
            if (System.currentTimeMillis() > end) error("Only ${textEdits.size} text edits arrived")
            Thread.sleep(10)
        }
    }

    /** The real TV advances the field id by 3 for a typed character and by 1 for its echo. */
    private fun echoField() {
        fieldId += 1
        push {
            message(22) {
                message(2) { varint(1, fieldId); string(2, fieldText); varint(3, fieldText.length); varint(4, fieldText.length); string(6, "Search") }
            }
        }
    }

    fun awaitKeys(count: Int, timeoutMs: Long = 5000) {
        while (keys.size < count) keyArrivals.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("No key arrived")
    }

    private fun serve(socket: SSLSocket) {
        client = socket
        send(socket, AtvWire.message {
            message(1) {
                varint(1, 639)
                message(2) { string(1, "BRAVIA 4K GB ATV3"); string(2, "Sony") }
            }
        })
        deviceInfo = AtvWire.parse(AtvWire.readFrame(socket.inputStream)).message(1)!!.message(2)!!
        send(socket, AtvWire.message { message(2) {} })
        AtvWire.parse(AtvWire.readFrame(socket.inputStream)).message(2)!!
        pushVolume(28)
        push { message(40) { varint(1, 1) } }
        while (true) {
            val fields = AtvWire.parse(AtvWire.readFrame(socket.inputStream))
            fields.message(9)?.let { pongs += it.varint(1) ?: -1 }
            fields.message(10)?.let {
                keys += (it.varint(1)!!.toInt() to it.varint(2)!!.toInt())
                keyArrivals += Unit
                if (it.varint(1) == 67L && fieldText.isNotEmpty()) {
                    fieldText = fieldText.dropLast(1)
                    fieldId += 2
                    if (echo) echoField()
                }
            }
            fields.bytes(21)?.let { raw ->
                textEdits += AtvWire.message { bytes(21, raw) }
                val edit = AtvWire.parse(raw)
                // Like the TV: only an edit with the current counter and field id is applied.
                if (edit.varint(1) == imeCounter && edit.varint(2) == fieldId) {
                    fieldText += edit.message(3)!!.message(2)!!.string(3)!!
                    fieldId += 2
                    if (echo) echoField()
                }
            }
        }
    }

    private fun push(body: AtvWire.Writer.() -> Unit) {
        val socket = client ?: error("No client connected")
        send(socket, AtvWire.message(body))
    }

    private fun send(socket: SSLSocket, message: ByteArray) =
        synchronized(this) { AtvWire.writeFrame(socket.outputStream, message) }
}
