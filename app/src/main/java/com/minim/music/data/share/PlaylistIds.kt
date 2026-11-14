package com.minim.music.data.share

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.CRC32

/**
 * 6.6.0: the playlist payload v2 (`/m/p#2.<data>`): the name, the sharer and the YouTube video ids
 * only, packed as raw bits. Minim and the web page look the titles, artists and covers up themselves.
 *
 * `<data>` is base64url (no padding) of these bytes:
 *
 *   flags      1 byte   bit 0: a sharer name follows; bit 1: ids are 66 bits (else 64); the rest are 0
 *   count      varint   songs in the link (1..[MAX_COUNT])
 *   name       varint length + UTF-8
 *   sharer     varint length + UTF-8, only with flag bit 0
 *   ids        count x 64 (or 66) bits, most significant first, zero bits up to the next byte
 *   check      1 byte   the low byte of CRC-32 over everything before it
 *
 * A YouTube id is 11 base64url chars, but it holds a 64-bit number: the 11th char only ever uses its top
 * 4 bits (it's one of AEIMQUYcgkosw048). So each id is 8 bytes. An id that doesn't fit (never seen from
 * YouTube, but nothing here assumes it) switches the whole link to 66 bits per id, still exact.
 *
 * The exact length, the zero padding bits, strict UTF-8 and the check byte together catch a cut-short or
 * garbled link, so it's [Decoded.Broken][ShareLinks.Decoded.Broken], never a different playlist.
 * The web page (src/page.html, decodeV2) reads the same bytes.
 */
object PlaylistIds {

    private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val FLAG_SHARER = 1
    private const val FLAG_WIDE = 2
    /** More than this many songs in a link is garbage (Minim writes at most [ShareLinks.MAX_SONGS]). */
    const val MAX_COUNT = 1000
    private const val MAX_TEXT_BYTES = 1024

    data class Payload(val name: String, val sharer: String?, val ids: List<String>)

    /** Base64url bytes (no version prefix). [ids] must all be video ids (checked by the caller). */
    fun encode(name: String, sharer: String?, ids: List<String>): String {
        require(ids.isNotEmpty() && ids.size <= MAX_COUNT) { "ids" }
        val wide = ids.any { !fits64(it) }
        val out = java.io.ByteArrayOutputStream(ids.size * 8 + 64)
        out.write((if (sharer != null) FLAG_SHARER else 0) or (if (wide) FLAG_WIDE else 0))
        varint(out, ids.size)
        text(out, name)
        if (sharer != null) text(out, sharer)
        val bits = BitWriter()
        for (id in ids) {
            val v = idBits(id)
            if (wide) bits.write(v, 66) else bits.write(v.shiftRight(2), 64)
        }
        out.write(bits.toBytes())
        val body = out.toByteArray()
        val crc = CRC32().apply { update(body) }.value.toInt() and 0xFF
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body + crc.toByte())
    }

    /** The payload, or null when the data is cut short, garbled or not v2 at all. Never throws. */
    fun decode(data: String): Payload? = try {
        decodeUnsafe(data)
    } catch (_: Exception) {
        null
    }

    private fun decodeUnsafe(data: String): Payload? {
        val all = try {
            Base64.getUrlDecoder().decode(data)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (all.size < 4) return null
        val body = all.copyOfRange(0, all.size - 1)
        val crc = CRC32().apply { update(body) }.value.toInt() and 0xFF
        if ((all.last().toInt() and 0xFF) != crc) return null
        val r = Reader(body)
        val flags = r.byte()
        if (flags and (FLAG_SHARER or FLAG_WIDE).inv() != 0) return null
        val count = r.varint() ?: return null
        if (count < 1 || count > MAX_COUNT) return null
        val name = r.text() ?: return null
        val sharer = if (flags and FLAG_SHARER != 0) (r.text() ?: return null) else null
        val per = if (flags and FLAG_WIDE != 0) 66 else 64
        val idBytes = (count.toLong() * per + 7) / 8
        if (r.remaining().toLong() != idBytes) return null
        val bits = BitReader(body, r.pos)
        val ids = ArrayList<String>(count)
        repeat(count) {
            val v = if (per == 66) bits.read(66) else bits.read(64).shiftLeft(2)
            ids.add(idString(v))
        }
        // the padding up to the byte must be zeros
        val pad = (idBytes * 8 - count.toLong() * per).toInt()
        if (pad > 0 && bits.read(pad).signum() != 0) return null
        return Payload(name, sharer, ids)
    }

    // ---- ids as numbers -----------------------------------------------------------------------

    /** True when the id's 11th char only uses its top 4 bits, like every YouTube id. */
    fun fits64(id: String): Boolean = ABC.indexOf(id[10]) % 4 == 0

    private fun idBits(id: String): java.math.BigInteger {
        var v = java.math.BigInteger.ZERO
        for (c in id) {
            val x = ABC.indexOf(c)
            require(x >= 0) { "not an id" }
            v = v.shiftLeft(6).or(java.math.BigInteger.valueOf(x.toLong()))
        }
        return v
    }

    private fun idString(v: java.math.BigInteger): String {
        val sb = CharArray(11)
        var x = v
        val mask = java.math.BigInteger.valueOf(63)
        for (i in 10 downTo 0) {
            sb[i] = ABC[x.and(mask).toInt()]
            x = x.shiftRight(6)
        }
        return String(sb)
    }

    // ---- bytes ------------------------------------------------------------------------------

    private fun varint(out: java.io.ByteArrayOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) { out.write((v and 0x7F) or 0x80); v = v ushr 7 }
        out.write(v)
    }

    private fun text(out: java.io.ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        varint(out, b.size)
        out.write(b)
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun remaining() = b.size - pos
        fun byte(): Int {
            if (pos >= b.size) throw IndexOutOfBoundsException()
            return b[pos++].toInt() and 0xFF
        }
        fun varint(): Int? {
            var v = 0
            for (shift in 0..14 step 7) {
                val x = byte()
                v = v or ((x and 0x7F) shl shift)
                if (x and 0x80 == 0) {
                    // shortest form only: a padded varint is garbled
                    if (shift > 0 && x == 0) return null
                    return v
                }
            }
            return null
        }
        fun text(): String? {
            val n = varint() ?: return null
            if (n > MAX_TEXT_BYTES || n > remaining()) return null
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val s = try {
                dec.decode(ByteBuffer.wrap(b, pos, n)).toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            pos += n
            return s
        }
    }

    private class BitWriter {
        private val out = java.io.ByteArrayOutputStream()
        private var acc = 0
        private var n = 0
        fun write(v: java.math.BigInteger, bits: Int) {
            for (i in bits - 1 downTo 0) {
                acc = (acc shl 1) or (if (v.testBit(i)) 1 else 0)
                if (++n == 8) { out.write(acc); acc = 0; n = 0 }
            }
        }
        fun toBytes(): ByteArray {
            if (n > 0) { out.write(acc shl (8 - n)); acc = 0; n = 0 }
            return out.toByteArray()
        }
    }

    private class BitReader(val b: ByteArray, start: Int) {
        private var bit = start.toLong() * 8
        fun read(bits: Int): java.math.BigInteger {
            var v = java.math.BigInteger.ZERO
            repeat(bits) {
                val byte = b[(bit ushr 3).toInt()].toInt()
                val one = (byte ushr (7 - (bit and 7).toInt())) and 1
                v = v.shiftLeft(1)
                if (one == 1) v = v.setBit(0)
                bit++
            }
            return v
        }
    }
}
