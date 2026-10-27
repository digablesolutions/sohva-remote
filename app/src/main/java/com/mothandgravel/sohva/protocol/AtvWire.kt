package com.mothandgravel.sohva.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The few protobuf pieces the Android TV Remote protocol needs: varint and length-delimited
 * fields, and messages framed by a varint length prefix. Hand-written so that no protobuf
 * plugin or generated code is needed for a handful of small messages.
 */
internal object AtvWire {

    /** A decoded field: [value] is a [Long] for varints and a [ByteArray] for everything else. */
    data class Field(val number: Int, val value: Any)

    class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(number: Int, value: Long) = apply {
            writeVarint(out, (number.toLong() shl 3) or 0L)
            writeVarint(out, value)
        }

        fun varint(number: Int, value: Int) = varint(number, value.toLong())

        fun bytes(number: Int, value: ByteArray) = apply {
            writeVarint(out, (number.toLong() shl 3) or 2L)
            writeVarint(out, value.size.toLong())
            out.write(value)
        }

        fun string(number: Int, value: String) = bytes(number, value.toByteArray(Charsets.UTF_8))

        fun message(number: Int, build: Writer.() -> Unit) = bytes(number, Writer().apply(build).toByteArray())

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    fun message(build: Writer.() -> Unit): ByteArray = Writer().apply(build).toByteArray()

    fun parse(bytes: ByteArray): List<Field> {
        val fields = mutableListOf<Field>()
        var i = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (i >= bytes.size) throw BraviaException.Protocol("Truncated varint")
                val b = bytes[i++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw BraviaException.Protocol("Varint too long")
            }
        }
        fun take(n: Int): ByteArray {
            if (n < 0 || i + n > bytes.size) throw BraviaException.Protocol("Truncated field")
            return bytes.copyOfRange(i, i + n).also { i += n }
        }
        while (i < bytes.size) {
            val tag = readVarint()
            val number = (tag ushr 3).toInt()
            fields += when ((tag and 7).toInt()) {
                0 -> Field(number, readVarint())
                1 -> Field(number, take(8))
                2 -> Field(number, take(readVarint().toInt()))
                5 -> Field(number, take(4))
                else -> throw BraviaException.Protocol("Unsupported wire type in field $number")
            }
        }
        return fields
    }

    fun List<Field>.varint(number: Int): Long? = firstOrNull { it.number == number }?.value as? Long

    fun List<Field>.bytes(number: Int): ByteArray? = firstOrNull { it.number == number }?.value as? ByteArray

    fun List<Field>.message(number: Int): List<Field>? = bytes(number)?.let(::parse)

    fun List<Field>.string(number: Int): String? = bytes(number)?.toString(Charsets.UTF_8)

    /** Writes [message] with its varint length prefix and flushes. */
    fun writeFrame(out: OutputStream, message: ByteArray) {
        val framed = ByteArrayOutputStream(message.size + 5)
        writeVarint(framed, message.size.toLong())
        framed.write(message)
        out.write(framed.toByteArray())
        out.flush()
    }

    /** Reads one length-prefixed message. Throws [EOFException] when the peer closed the stream. */
    fun readFrame(input: InputStream, maxSize: Int = 64 * 1024): ByteArray {
        var size = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Connection closed")
            size = size or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw BraviaException.Protocol("Frame length too long")
        }
        if (size > maxSize) throw BraviaException.Protocol("Frame of $size bytes")
        val buffer = ByteArray(size.toInt())
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) throw EOFException("Connection closed")
            read += n
        }
        return buffer
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}
