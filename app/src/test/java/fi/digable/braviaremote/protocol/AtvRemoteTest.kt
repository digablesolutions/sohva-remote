package fi.digable.braviaremote.protocol

import com.google.common.truth.Truth.assertThat
import fi.digable.braviaremote.protocol.AtvWire.string
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AtvRemoteTest {
    private val tv = FakeAtvRemoteTv()
    private val events = LinkedBlockingQueue<AtvEvent>()
    private lateinit var remote: AtvRemote

    @Before
    fun setUp() {
        tv.start()
        remote = AtvRemote(
            socketFactory = AtvTls.socketFactory(arrayOf(tv.clientKeyManager)),
            deviceModel = "XQ-DE54",
            appPackage = "fi.digable.braviaremote",
            appVersion = "0.1.1",
        )
    }

    @After
    fun tearDown() = tv.stop()

    private fun connect() = runBlocking {
        remote.connect("127.0.0.1", tv.certificate.certificate.encoded, tv.port) { events += it }
    }

    private fun nextEvent(): AtvEvent = events.poll(5, TimeUnit.SECONDS) ?: error("No event")

    @Test
    fun `connecting answers the TV's configuration with our device info`() {
        connect().use { connection ->
            assertThat(connection.isOpen).isTrue()
            assertThat(tv.deviceInfo.string(1)).isEqualTo("XQ-DE54")
            assertThat(tv.deviceInfo.string(5)).isEqualTo("fi.digable.braviaremote")
            assertThat(tv.deviceInfo.string(6)).isEqualTo("0.1.1")
        }
    }

    @Test
    fun `keys arrive in order as Android key codes`() {
        connect().use { connection ->
            assertThat(connection.sendKey(20)).isTrue()
            assertThat(connection.sendKey(23)).isTrue()
            assertThat(connection.sendKey(19, AtvKeyDirection.START_LONG)).isTrue()
            assertThat(connection.sendKey(19, AtvKeyDirection.END_LONG)).isTrue()
            tv.awaitKeys(4)
            assertThat(tv.keys).containsExactly(20 to 3, 23 to 3, 19 to 1, 19 to 2).inOrder()
        }
    }

    @Test
    fun `pings are answered with their value`() {
        connect().use {
            tv.ping(7)
            assertThat(tv.pongs.poll(5, TimeUnit.SECONDS)).isEqualTo(7L)
        }
    }

    @Test
    fun `pushes become events`() {
        connect().use {
            assertThat(nextEvent()).isEqualTo(AtvEvent.Volume(level = 28, max = 100, muted = false))
            assertThat(nextEvent()).isEqualTo(AtvEvent.Power(on = true))
            tv.pushCurrentApp("com.sony.dtv.tvx")
            assertThat(nextEvent()).isEqualTo(AtvEvent.CurrentApp("com.sony.dtv.tvx"))
            tv.pushVolume(30, muted = true)
            assertThat(nextEvent()).isEqualTo(AtvEvent.Volume(level = 30, max = 100, muted = true))
        }
    }

    @Test
    fun `a dropped connection completes awaitClosed and refuses keys`() = runBlocking {
        val connection = connect()
        tv.disconnect()
        withTimeout(5000) { connection.awaitClosed() }
        assertThat(connection.isOpen).isFalse()
        assertThat(connection.sendKey(20)).isFalse()
    }

    @Test
    fun `a TV whose certificate changed since pairing is Unauthorized`() {
        assertThrows(BraviaException.Unauthorized::class.java) {
            runBlocking { remote.connect("127.0.0.1", byteArrayOf(1, 2, 3), tv.port) }
        }
    }

    @Test
    fun `only keys with an Android equivalent go over the channel`() {
        assertThat(RemoteKey.DOWN.atvKeyCode).isEqualTo(20)
        assertThat(RemoteKey.RETURN.atvKeyCode).isEqualTo(4)
        assertThat(RemoteKey.GUIDE.atvKeyCode).isNull()
        assertThat(RemoteKey.POWER.atvKeyCode).isNull()
        assertThat(RemoteKey.ACTION_MENU.atvKeyCode).isNull()
        assertThat(RemoteKey.HDMI1.atvKeyCode).isNull()
        // Distinct codes, so no two keys collapse into one.
        val codes = RemoteKey.entries.mapNotNull { it.atvKeyCode }
        assertThat(codes).containsNoDuplicates()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** Skips the volume and power pushes that follow every connect. */
    private fun nextTextEvent(): AtvEvent {
        while (true) {
            val event = nextEvent()
            if (event is AtvEvent.TextField || event == AtvEvent.TextFieldClosed) return event
        }
    }

    @Test
    fun `a text field opening and closing on the TV become events`() {
        connect().use {
            tv.openTextField(counter = 23, id = 237, hint = "Hae elokuvia")
            assertThat(nextTextEvent()).isEqualTo(AtvEvent.TextField("", "Hae elokuvia"))
            tv.closeTextField()
            assertThat(nextTextEvent()).isEqualTo(AtvEvent.TextFieldClosed)
        }
    }

    @Test
    fun `typing sends one character per edit with the TV's newest counter and field id`() {
        connect().use { connection ->
            tv.openTextField(counter = 23, id = 237)
            nextTextEvent()
            assertThat(connection.edit(AtvTextEdit.Insert("aä"))).isTrue()
            tv.awaitTextEdits(2)
            // Captured from the KD-65XF9005 on 2026-10-24: "a" with counter 23 and field id 237.
            assertThat(tv.textEdits[0].hex()).isEqualTo("aa0112081710ed011a0b08011207080010001a0161")
            // The second waited for the echo and carries its field id (240), as the TV requires.
            assertThat(tv.textEdits[1].hex()).isEqualTo("aa0113081710f0011a0c08011208080010001a02c3a4")
            assertThat(nextTextEvent()).isEqualTo(AtvEvent.TextField("a", "Search"))
            assertThat(nextTextEvent()).isEqualTo(AtvEvent.TextField("aä", "Search"))
        }
    }

    @Test
    fun `delete and submit go as Backspace and Enter keys after the typing`() {
        connect().use { connection ->
            tv.openTextField(counter = 5, id = 10)
            nextTextEvent()
            connection.edit(AtvTextEdit.Insert("tv"))
            connection.edit(AtvTextEdit.DeleteBackward)
            connection.edit(AtvTextEdit.Submit)
            tv.awaitKeys(2)
            assertThat(tv.keys).containsExactly(67 to 3, 66 to 3).inOrder()
            assertThat(tv.fieldText).isEqualTo("t")
        }
    }

    @Test
    fun `without an echo the rest of the text is dropped rather than sent with a stale id`() {
        connect().use { connection ->
            tv.openTextField(counter = 5, id = 10)
            nextTextEvent()
            tv.echo = false
            connection.edit(AtvTextEdit.Insert("abc"))
            tv.awaitTextEdits(1)
            Thread.sleep(1500)
            assertThat(tv.textEdits).hasSize(1)
            assertThat(tv.fieldText).isEqualTo("a")
        }
    }

    @Test
    fun `edits are refused while the TV's keyboard is down`() {
        connect().use { connection ->
            assertThat(connection.edit(AtvTextEdit.Insert("a"))).isFalse()
        }
    }

    @Test
    fun `the phone's text becomes deletes back to the shared start, then the rest`() {
        val del = AtvTextEdit.DeleteBackward
        assertThat(AtvTextEdit.between("", "Yle")).containsExactly(AtvTextEdit.Insert("Yle"))
        assertThat(AtvTextEdit.between("Yle", "Yle Areena")).containsExactly(AtvTextEdit.Insert(" Areena"))
        assertThat(AtvTextEdit.between("Yle Areena", "Yle")).containsExactly(del, del, del, del, del, del, del)
        // A keyboard correction rewrites the end of a word.
        assertThat(AtvTextEdit.between("teh", "the")).containsExactly(del, del, AtvTextEdit.Insert("he")).inOrder()
        assertThat(AtvTextEdit.between("ä😀", "ä")).containsExactly(del)
        assertThat(AtvTextEdit.between("same", "same")).isEmpty()
    }
}
