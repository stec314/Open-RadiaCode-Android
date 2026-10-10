package com.radiacode.ble.pro

/** Least-squares fit of E = a0 + a1*ch (+ a2*ch^2 with three or more points). */
object CalibrationFit {
    fun fit(points: List<Pair<Double, Double>>): DoubleArray? {
        val pts = points.distinctBy { it.first }
        if (pts.size < 2) return null
        if (pts.size == 2) {
            val (c1, e1) = pts[0]
            val (c2, e2) = pts[1]
            if (c1 == c2) return null
            val a1 = (e2 - e1) / (c2 - c1)
            return doubleArrayOf(e1 - a1 * c1, a1, 0.0)
        }
        // Normal equations for a quadratic
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for ((x, y) in pts) {
            val x2 = x * x
            s0 += 1; s1 += x; s2 += x2; s3 += x2 * x; s4 += x2 * x2
            t0 += y; t1 += x * y; t2 += x2 * y
        }
        val m = arrayOf(doubleArrayOf(s0, s1, s2, t0), doubleArrayOf(s1, s2, s3, t1), doubleArrayOf(s2, s3, s4, t2))
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[piv][col])) piv = r
            if (kotlin.math.abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in 0 until 3) if (r != col) {
                val f = m[r][col] / m[col][col]
                for (k in col until 4) m[r][k] -= f * m[col][k]
            }
        }
        return doubleArrayOf(m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2])
    }
}
