package com.smarttools.netguard.repository

import android.content.Context
import java.util.Calendar

class StatsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("traffic_stats", Context.MODE_PRIVATE)
    private val lock = Any()

    data class SessionStats(
        val todayRx: Long,
        val todayTx: Long,
        val weekRx: Long,
        val weekTx: Long,
        val totalRx: Long,
        val totalTx: Long
    )

    private fun safeAdd(current: Long, delta: Long): Long {
        return if (current > Long.MAX_VALUE - delta) Long.MAX_VALUE else current + delta
    }

    fun addTraffic(rx: Long, tx: Long) {
        if (rx <= 0 && tx <= 0) return
        synchronized(lock) {
            resetIfNeeded()
            prefs.edit().apply {
                putLong("today_rx", safeAdd(prefs.getLong("today_rx", 0), rx))
                putLong("today_tx", safeAdd(prefs.getLong("today_tx", 0), tx))
                putLong("week_rx", safeAdd(prefs.getLong("week_rx", 0), rx))
                putLong("week_tx", safeAdd(prefs.getLong("week_tx", 0), tx))
                putLong("total_rx", safeAdd(prefs.getLong("total_rx", 0), rx))
                putLong("total_tx", safeAdd(prefs.getLong("total_tx", 0), tx))
                apply()
            }
        }
    }

    fun getStats(): SessionStats {
        synchronized(lock) {
            resetIfNeeded()
            return SessionStats(
                todayRx = prefs.getLong("today_rx", 0),
                todayTx = prefs.getLong("today_tx", 0),
                weekRx = prefs.getLong("week_rx", 0),
                weekTx = prefs.getLong("week_tx", 0),
                totalRx = prefs.getLong("total_rx", 0),
                totalTx = prefs.getLong("total_tx", 0)
            )
        }
    }

    fun resetAll() {
        synchronized(lock) {
            prefs.edit().clear().apply()
        }
    }

    private fun resetIfNeeded() {
        val cal = Calendar.getInstance()
        val currentDay = cal.get(Calendar.DAY_OF_YEAR)
        val currentWeek = cal.get(Calendar.WEEK_OF_YEAR)
        val currentYear = cal.get(Calendar.YEAR)
        val savedDay = prefs.getInt("last_day", -1)
        val savedWeek = prefs.getInt("last_week", -1)
        val savedYear = prefs.getInt("last_year", -1)

        val editor = prefs.edit()
        if (savedDay != currentDay || savedYear != currentYear) {
            editor.putLong("today_rx", 0)
            editor.putLong("today_tx", 0)
            editor.putInt("last_day", currentDay)
        }
        if (savedWeek != currentWeek || savedYear != currentYear) {
            editor.putLong("week_rx", 0)
            editor.putLong("week_tx", 0)
            editor.putInt("last_week", currentWeek)
        }
        if (savedYear != currentYear) {
            editor.putInt("last_year", currentYear)
        }
        editor.apply()
    }
}
