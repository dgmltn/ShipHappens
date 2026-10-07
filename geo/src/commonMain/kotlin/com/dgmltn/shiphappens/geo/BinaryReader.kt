package com.dgmltn.shiphappens.geo

internal class BinaryReader(private val b: ByteArray) {
    var pos = 0
        private set

    private fun need(n: Int) = require(n >= 0 && pos + n <= b.size) { "truncated asset at $pos (+$n of ${b.size})" }

    fun magic(expected: String) {
        need(expected.length)
        val got = b.decodeToString(pos, pos + expected.length)
        require(got == expected) { "bad magic: $got" }
        pos += expected.length
    }

    fun int(): Int {
        need(4)
        val v = ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or
            ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }

    fun ushort(): Int {
        need(2)
        val v = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun float(): Float = Float.fromBits(int())

    val remaining: Int get() = b.size - pos

    fun bytes(len: Int): ByteArray {
        need(len)
        return b.copyOfRange(pos, pos + len).also { pos += len }
    }

    fun utf8(len: Int): String {
        need(len)
        return b.decodeToString(pos, pos + len).also { pos += len }
    }
}

internal fun compareBytes(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
        if (d != 0) return d
    }
    return a.size - b.size
}
