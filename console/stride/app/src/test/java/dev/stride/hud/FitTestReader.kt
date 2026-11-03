package dev.stride.hud

/**
 * Just enough FIT reading to check what FitActivity wrote: normal headers,
 * little-endian, no compressed timestamps, no developer fields — which is all
 * FitWriter produces. Values come back unsigned; callers sign-convert.
 */
object FitTestReader {
    fun messages(b: ByteArray): List<Pair<Int, Map<Int, Long>>> {
        fun u(i: Int) = b[i].toInt() and 0xFF
        var p = u(0)
        val end = b.size - 2
        val defs = HashMap<Int, Pair<Int, List<Pair<Int, Int>>>>()
        val out = mutableListOf<Pair<Int, Map<Int, Long>>>()
        while (p < end) {
            val h = u(p++)
            val local = h and 0x0F
            if (h and 0x40 != 0) {
                p += 2 // reserved, architecture
                val global = u(p) or (u(p + 1) shl 8); p += 2
                val n = u(p++)
                val fields = (0 until n).map { val f = u(p) to u(p + 1); p += 3; f }
                defs[local] = global to fields
            } else {
                val (global, fields) = defs.getValue(local)
                val values = LinkedHashMap<Int, Long>()
                for ((num, size) in fields) {
                    var v = 0L
                    for (i in 0 until size) v = v or (u(p + i).toLong() shl (8 * i))
                    p += size
                    values[num] = v
                }
                out.add(global to values)
            }
        }
        return out
    }
}
