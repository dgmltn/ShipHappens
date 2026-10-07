package com.dgmltn.shiphappens.geo

/** Sorted, immutable key → coordinate table decoded from `places.bin`. */
class PlacesTable private constructor(
    private val keys: Array<ByteArray>,
    private val coords: FloatArray, // lat0, lng0, lat1, lng1, …
) {
    val size: Int get() = keys.size

    fun lookup(key: String): LatLng? {
        val needle = key.encodeToByteArray()
        var lo = 0
        var hi = keys.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareBytes(keys[mid], needle)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return LatLng(coords[mid * 2].toDouble(), coords[mid * 2 + 1].toDouble())
            }
        }
        return null
    }

    /** Every "CITY, XX" place named [city], in key order. */
    fun candidates(city: String): List<CityCandidate> {
        val prefix = "$city, ".encodeToByteArray()
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareBytes(keys[mid], prefix) < 0) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<CityCandidate>()
        var i = lo
        while (i < keys.size && keys[i].startsWith(prefix)) {
            found += CityCandidate(keys[i].decodeToString(), LatLng(coords[i * 2].toDouble(), coords[i * 2 + 1].toDouble()))
            i++
        }
        return found
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (j in prefix.indices) if (this[j] != prefix[j]) return false
        return true
    }

    companion object {
        private const val MIN_ENTRY_BYTES = 10
        val EMPTY = PlacesTable(emptyArray(), FloatArray(0))

        fun decode(bytes: ByteArray): PlacesTable {
            val r = BinaryReader(bytes)
            r.magic("SHPL")
            require(r.int() == 1) { "unsupported places version" }
            val count = r.int()
            require(count >= 0) { "negative count" }
            // Each entry is at least a 2-byte length plus two floats, so a lying count can't over-allocate.
            require(count <= r.remaining / MIN_ENTRY_BYTES) { "count $count exceeds asset size" }
            val coords = FloatArray(count * 2)
            val keys = Array(count) { i ->
                r.bytes(r.ushort()).also {
                    coords[i * 2] = r.float()
                    coords[i * 2 + 1] = r.float()
                }
            }
            return PlacesTable(keys, coords)
        }
    }
}
