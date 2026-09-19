package com.smarttools.netguard.service

/** Monotonic rate calculation; counter resets do not create negative spikes. */
class TrafficRateCounter {
    data class Sample(val rxBytes: Long, val txBytes: Long, val rxPerSecond: Long, val txPerSecond: Long)
    private var lastTime = -1L
    private var lastRx = 0L
    private var lastTx = 0L
    private var totalRx = 0L
    private var totalTx = 0L

    fun sample(rx: Long, tx: Long, nowNanos: Long): Sample {
        require(rx >= 0 && tx >= 0)
        if (lastTime < 0) {
            lastTime = nowNanos; lastRx = rx; lastTx = tx
            return Sample(0, 0, 0, 0)
        }
        val elapsed = nowNanos - lastTime
        if (elapsed <= 0) return Sample(totalRx, totalTx, 0, 0)
        val dr = if (rx >= lastRx) rx - lastRx else rx
        val dt = if (tx >= lastTx) tx - lastTx else tx
        totalRx += dr; totalTx += dt
        lastRx = rx; lastTx = tx; lastTime = nowNanos
        return Sample(totalRx, totalTx, (dr.toDouble() * 1e9 / elapsed).toLong(), (dt.toDouble() * 1e9 / elapsed).toLong())
    }
}
