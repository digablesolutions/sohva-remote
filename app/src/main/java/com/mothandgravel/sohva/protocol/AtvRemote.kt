package com.mothandgravel.sohva.protocol

import com.mothandgravel.sohva.protocol.AtvWire.bytes
import com.mothandgravel.sohva.protocol.AtvWire.message
import com.mothandgravel.sohva.protocol.AtvWire.string
import com.mothandgravel.sohva.protocol.AtvWire.varint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** What the TV pushes on the remote channel without being asked. */
sealed interface AtvEvent {
    data class Volume(val level: Int, val max: Int, val muted: Boolean) : AtvEvent

    /** The Android package in front, e.g. `com.sony.dtv.tvx` for live TV. */
    data class CurrentApp(val packageName: String) : AtvEvent

    data class Power(val on: Boolean) : AtvEvent

    /** A text field on the TV has its keyboard up; [text] is what it holds now, [hint] its placeholder. */
    data class TextField(val text: String, val hint: String) : AtvEvent

    /** The TV's keyboard went away. */
    data object TextFieldClosed : AtvEvent

    /** Any message from the TV except pings, described on demand, for diagnosing (logs only). */
    class Received(val describe: () -> String) : AtvEvent
}

/** A change to the text field on the TV, see [AtvRemote.Connection.edit]. */
sealed interface AtvTextEdit {
    /** Types [text] at the TV's cursor, one character at a time. */
    data class Insert(val text: String) : AtvTextEdit

    data object DeleteBackward : AtvTextEdit

    /** Enter: the TV's search fields search and close the keyboard. */
    data object Submit : AtvTextEdit

    companion object {
        /**
         * The edits that turn [old] into [new] on the TV, with its cursor at the end: delete back to
         * the longest shared start, then type the rest. Counts characters as code points.
         */
        fun between(old: String, new: String): List<AtvTextEdit> {
            val from = old.codePoints().toArray()
            val to = new.codePoints().toArray()
            var shared = 0
            while (shared < from.size && shared < to.size && from[shared] == to[shared]) shared++
            val deletes = List(from.size - shared) { DeleteBackward }
            val rest = String(to, shared, to.size - shared)
            return if (rest.isEmpty()) deletes else deletes + Insert(rest)
        }
    }
}

/** How a key is sent: a tap, or the two halves of a hold the TV repeats by itself. */
enum class AtvKeyDirection(internal val wire: Int) { SHORT(3), START_LONG(1), END_LONG(2) }

/**
 * The Android TV Remote channel (port 6466): a persistent TLS connection that carries keys as
 * Android key codes in tens of milliseconds, where an IRCC key takes 170-660 ms.
 *
 * Needs a certificate the TV accepted in [AtvPairing]. The TV opens with its configuration; we
 * answer with ours, then the TV makes us active and starts pushing volume, power and the app in
 * front. It pings every 5 s and drops clients that do not answer.
 */
class AtvRemote(
    private val socketFactory: SSLSocketFactory,
    private val deviceModel: String,
    private val appPackage: String,
    private val appVersion: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Int = 10_000,
) {
    /**
     * Connects and returns once the TV made this client active. [expectedCertificate] is the TV's
     * certificate (DER) from pairing; a different one fails with [BraviaException.Unauthorized].
     * [onEvent] runs on the reader thread.
     */
    suspend fun connect(
        host: String,
        expectedCertificate: ByteArray,
        port: Int = PORT,
        onEvent: (AtvEvent) -> Unit = {},
    ): Connection = withContext(io) {
        val socket = socketFactory.createSocket() as SSLSocket
        // Same reasons as in AtvPairing: what the TV negotiates, PKCS#1 signing, early errors.
        socket.enabledProtocols = arrayOf("TLSv1.2")
        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: IOException) {
            socket.closeQuietly()
            throw BraviaException.Unreachable(host, e)
        }
        try {
            try {
                socket.startHandshake()
            } catch (e: IOException) {
                throw BraviaException.Protocol("TLS handshake failed: ${e.message}", e)
            }
            val tvCertificate = socket.session.peerCertificates.firstOrNull()?.encoded
            if (!tvCertificate.contentEquals(expectedCertificate)) {
                throw BraviaException.Unauthorized("TV certificate changed since pairing")
            }
            val connection = Connection(socket, onEvent)
            connection.handshake()
            // A silence well beyond the TV's ping interval means the connection is gone.
            socket.soTimeout = READ_TIMEOUT_MS
            connection.markHeard()
            connection.startLoops()
            connection
        } catch (e: Exception) {
            socket.closeQuietly()
            throw when (e) {
                is BraviaException -> e
                else -> BraviaException.Protocol(e.message ?: e.javaClass.simpleName, e)
            }
        }
    }

    inner class Connection internal constructor(
        private val socket: SSLSocket,
        private val onEvent: (AtvEvent) -> Unit,
    ) : Closeable {
        private val scope = CoroutineScope(SupervisorJob() + io)
        private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
        private val closed = CompletableDeferred<Throwable?>()
        private val writeLock = Any()
        @Volatile private var lastFrameAt = System.nanoTime()

        private val edits = Channel<AtvTextEdit>(Channel.UNLIMITED)
        @Volatile private var keyboardUp = false
        /** The TV's IME counter (field 20 › 1 › 1); a text edit must carry the newest one. */
        @Volatile private var imeCounter = 0L
        /** The text field's id (field 20 › 2 › 1 or 22 › 2 › 1); it changes with every edit the TV applies. */
        @Volatile private var fieldId = 0L
        @Volatile private var fieldText = ""
        @Volatile private var fieldHint = ""
        /** Counts the TV's echoes of the text field, so an edit can wait for its own. */
        private val echoes = MutableStateFlow(0)

        /**
         * Open and recently heard from. The TV pings regularly, so silence beyond [STALE_MS] means
         * the link is probably gone (Wi-Fi dropped, TV unplugged) even before the read times out;
         * keys must not be handed to a connection that would swallow them.
         */
        val isOpen: Boolean
            get() = !closed.isCompleted && System.nanoTime() - lastFrameAt < STALE_MS * 1_000_000

        /** Queues a key; false when the connection is already closed. Never blocks. */
        fun sendKey(keyCode: Int, direction: AtvKeyDirection = AtvKeyDirection.SHORT): Boolean =
            isOpen && outbox.trySend(AtvWire.message {
                message(KEY_INJECT) { varint(1, keyCode); varint(2, direction.wire) }
            }).isSuccess

        /**
         * Queues a change to the text field on the TV; false when its keyboard is not up. Edits go
         * out one at a time, each after the TV echoed the one before: an edit with an outdated
         * field id, or with more than one character, is ignored by the TV and can make it drop the
         * connection. When an echo does not come, the edits still queued are dropped.
         */
        fun edit(edit: AtvTextEdit): Boolean = isOpen && keyboardUp && edits.trySend(edit).isSuccess

        /** Suspends until the connection ends; returns why, or null after [close]. */
        suspend fun awaitClosed(): Throwable? = closed.await()

        override fun close() = finish(null)

        /** Answers the TV's configuration until it makes us active. */
        internal fun handshake() {
            while (true) {
                val fields = AtvWire.parse(AtvWire.readFrame(socket.inputStream))
                when {
                    fields.bytes(CONFIGURE) != null -> write(AtvWire.message {
                        message(CONFIGURE) {
                            varint(1, FEATURES)
                            message(2) {
                                string(1, deviceModel)
                                string(2, VENDOR)
                                varint(3, 1)
                                string(4, "1")
                                string(5, appPackage)
                                string(6, appVersion)
                            }
                        }
                    })
                    fields.bytes(SET_ACTIVE) != null -> {
                        write(AtvWire.message { message(SET_ACTIVE) { varint(1, FEATURES) } })
                        return
                    }
                    else -> handle(fields)
                }
            }
        }

        internal fun markHeard() {
            lastFrameAt = System.nanoTime()
        }

        internal fun startLoops() {
            scope.launch {
                try {
                    for (frame in outbox) write(frame)
                } catch (e: Exception) {
                    finish(e)
                }
            }
            scope.launch {
                for (edit in edits) {
                    val delivered = when (edit) {
                        is AtvTextEdit.Insert -> edit.text.codePoints().toArray().all { codePoint ->
                            sendAndAwaitEcho { insertMessage(String(Character.toChars(codePoint))) }
                        }
                        AtvTextEdit.DeleteBackward -> sendAndAwaitEcho { keyMessage(KEYCODE_DEL) }
                        AtvTextEdit.Submit -> outbox.trySend(keyMessage(KEYCODE_ENTER)).isSuccess
                    }
                    if (!delivered) while (edits.tryReceive().isSuccess) Unit
                }
            }
            scope.launch {
                try {
                    while (true) {
                        val frame = AtvWire.readFrame(socket.inputStream)
                        lastFrameAt = System.nanoTime()
                        handle(AtvWire.parse(frame))
                    }
                } catch (e: Exception) {
                    finish(e)
                }
            }
        }

        private suspend fun sendAndAwaitEcho(message: () -> ByteArray): Boolean {
            val before = echoes.value
            if (!keyboardUp || !outbox.trySend(message()).isSuccess) return false
            return withTimeoutOrNull(ECHO_TIMEOUT_MS) { echoes.first { it != before } } != null
        }

        /** One character: start and end 0, as the TV accepts it (a longer text is ignored). */
        private fun insertMessage(character: String) = AtvWire.message {
            message(IME_BATCH_EDIT) {
                varint(1, imeCounter)
                varint(2, fieldId)
                message(3) {
                    varint(1, 1)
                    message(2) { varint(1, 0); varint(2, 0); string(3, character) }
                }
            }
        }

        private fun keyMessage(keyCode: Int) = AtvWire.message {
            message(KEY_INJECT) { varint(1, keyCode); varint(2, AtvKeyDirection.SHORT.wire) }
        }

        private fun handle(fields: List<AtvWire.Field>) {
            fields.message(PING)?.let { ping ->
                outbox.trySend(AtvWire.message { message(PONG) { varint(1, ping.varint(1) ?: 0L) } })
            }
            fields.message(VOLUME)?.let { v ->
                onEvent(AtvEvent.Volume(
                    level = v.varint(7)?.toInt() ?: 0,
                    max = v.varint(6)?.toInt() ?: 100,
                    muted = (v.varint(8) ?: 0L) != 0L,
                ))
            }
            if (fields.none { it.number == PING }) onEvent(AtvEvent.Received { describe(fields) })
            fields.message(IME_KEY_INJECT)?.let { ime ->
                ime.message(1)?.varint(1)?.let { imeCounter = it }
                ime.message(1)?.string(12)?.let { onEvent(AtvEvent.CurrentApp(it)) }
                ime.message(2)?.let { field ->
                    field.varint(1)?.let { fieldId = it }
                    fieldText = field.string(2).orEmpty()
                    fieldHint = field.string(6).orEmpty()
                    if (keyboardUp) onEvent(AtvEvent.TextField(fieldText, fieldHint))
                }
            }
            // From the TV, field 1 says whether its keyboard is up.
            fields.message(IME_BATCH_EDIT)?.varint(1)?.let { shown ->
                keyboardUp = shown != 0L
                onEvent(if (keyboardUp) AtvEvent.TextField(fieldText, fieldHint) else AtvEvent.TextFieldClosed)
            }
            fields.message(IME_SHOW)?.message(2)?.let { field ->
                field.varint(1)?.let { fieldId = it }
                fieldText = field.string(2).orEmpty()
                field.string(6)?.let { fieldHint = it }
                echoes.value++
                if (keyboardUp) onEvent(AtvEvent.TextField(fieldText, fieldHint))
            }
            fields.message(START)?.let { onEvent(AtvEvent.Power(on = (it.varint(1) ?: 0L) != 0L)) }
        }

        private fun write(message: ByteArray) {
            synchronized(writeLock) { AtvWire.writeFrame(socket.outputStream, message) }
        }

        private fun finish(cause: Throwable?) {
            if (!closed.complete(cause)) return
            outbox.close()
            edits.close()
            keyboardUp = false
            socket.closeQuietly()
            scope.cancel()
        }
    }

    companion object {
        const val PORT = 6466

        /**
         * A compact view of a message for the log: field numbers, numbers, and short readable text,
         * a few levels deep ("20{1{1=7 12=com.netflix.ninja}}"). Never used for anything but logs.
         */
        internal fun describe(fields: List<AtvWire.Field>, depth: Int = 0): String = fields.joinToString(" ") { f ->
            when (val v = f.value) {
                is Long -> "${f.number}=$v"
                is ByteArray -> {
                    val text = v.toString(Charsets.UTF_8)
                    val nested = if (depth < 3) runCatching { AtvWire.parse(v) }.getOrNull() else null
                    when {
                        text.isNotEmpty() && text.length <= 80 && text.all { it.code in 32..126 } -> "${f.number}=$text"
                        nested != null && nested.isNotEmpty() -> "${f.number}{${describe(nested, depth + 1)}}"
                        else -> "${f.number}=<${v.size} bytes>"
                    }
                }
                else -> "${f.number}=?"
            }
        }

        /** Feature bits as the Android TV Remote apps send them (keys, IME, power, volume, app links). */
        private const val FEATURES = 622
        private const val VENDOR = "Sohva Remote"
        /** The TV's first ping comes about 9 s after connecting, then one every 5 s. */
        private const val READ_TIMEOUT_MS = 12_000
        private const val STALE_MS = 11_000L
        /** The TV echoes a text edit in tens of milliseconds. */
        private const val ECHO_TIMEOUT_MS = 1_000L
        private const val KEYCODE_DEL = 67
        private const val KEYCODE_ENTER = 66

        private const val CONFIGURE = 1
        private const val SET_ACTIVE = 2
        private const val PING = 8
        private const val PONG = 9
        private const val KEY_INJECT = 10
        private const val IME_KEY_INJECT = 20
        private const val IME_BATCH_EDIT = 21
        private const val IME_SHOW = 22
        private const val START = 40
        private const val VOLUME = 50

        private fun SSLSocket.closeQuietly() = runCatching { close() }
    }
}
