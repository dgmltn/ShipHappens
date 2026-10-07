package com.dgmltn.shiphappens.geo

/** Land polygons as interleaved `[lng, lat, …]` rings, decoded from `land110m.bin`. */
class LandOutline(val rings: List<FloatArray>) {
    companion object {
        val EMPTY = LandOutline(emptyList())

        fun decode(bytes: ByteArray): LandOutline {
            val r = BinaryReader(bytes)
            r.magic("SHLD")
            require(r.int() == 1) { "unsupported land version" }
            val ringCount = r.int()
            require(ringCount >= 0) { "negative ring count" }
            val rings = ArrayList<FloatArray>()
            repeat(ringCount) {
                val n = r.int()
                require(n >= 0 && n <= Int.MAX_VALUE / 2) { "bad point count" }
                rings += FloatArray(n * 2) { r.float() }
            }
            return LandOutline(rings)
        }
    }
}
