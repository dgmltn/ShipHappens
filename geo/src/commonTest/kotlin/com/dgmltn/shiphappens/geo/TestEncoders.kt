package com.dgmltn.shiphappens.geo

internal class BigEndianWriter {
    private val out = ArrayList<Byte>()
    fun bytes(b: ByteArray) { b.forEach { out.add(it) } }
    fun int(v: Int) { for (s in intArrayOf(24, 16, 8, 0)) out.add((v ushr s).toByte()) }
    fun short(v: Int) { out.add((v ushr 8).toByte()); out.add(v.toByte()) }
    fun float(v: Float) = int(v.toRawBits())
    fun toByteArray() = out.toByteArray()
}

internal fun encodePlaces(places: Map<String, LatLng>): ByteArray = BigEndianWriter().apply {
    bytes("SHPL".encodeToByteArray()); int(1); int(places.size)
    places.entries.sortedWith { a, b -> compareBytes(a.key.encodeToByteArray(), b.key.encodeToByteArray()) }.forEach { (k, v) ->
        val kb = k.encodeToByteArray(); short(kb.size); bytes(kb); float(v.lat.toFloat()); float(v.lng.toFloat())
    }
}.toByteArray()

internal fun encodeLand(rings: List<List<Pair<Float, Float>>>): ByteArray = BigEndianWriter().apply {
    bytes("SHLD".encodeToByteArray()); int(1); int(rings.size)
    rings.forEach { ring -> int(ring.size); ring.forEach { (lng, lat) -> float(lng); float(lat) } }
}.toByteArray()
