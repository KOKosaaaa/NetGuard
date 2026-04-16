package com.smarttools.netguard.repository

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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

    data class DayTraffic(
        val date: String, // "MM/dd"
        val dayOfWeek: String, // "Mon", "Tue", etc.
        val rx: Long,
        val tx: Long
    ) {
        val total: Long get() = rx + tx
    }

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

    fun getDailyHistory(days: Int = 7): List<DayTraffic> {
        synchronized(lock) {
            val result = mutableListOf<DayTraffic>()
            val dateFmt = SimpleDateFormat("MM/dd", Locale.US)
            val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
            val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

            for (i in days - 1 downTo 0) {
                val c = Calendar.getInstance()
                c.add(Calendar.DAY_OF_YEAR, -i)
                val key = keyFmt.format(c.time)
                val rx: Long
                val tx: Long
                if (i == 0) {
                    // Today = live counters
                    rx = prefs.getLong("today_rx", 0)
                    tx = prefs.getLong("today_tx", 0)
                } else {
                    rx = prefs.getLong("hist_${key}_rx", 0)
                    tx = prefs.getLong("hist_${key}_tx", 0)
                }
                result.add(DayTraffic(
                    date = dateFmt.format(c.time),
                    dayOfWeek = dayFmt.format(c.time),
                    rx = rx,
                    tx = tx
                ))
            }
            return result
        }
    }

    private fun archiveDay(cal: Calendar) {
        val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        // Archive yesterday's stats
        val yesterday = Calendar.getInstance()
        yesterday.timeInMillis = cal.timeInMillis
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        val key = keyFmt.format(yesterday.time)

        val todayRx = prefs.getLong("today_rx", 0)
        val todayTx = prefs.getLong("today_tx", 0)
        if (todayRx > 0 || todayTx > 0) {
            prefs.edit()
                .putLong("hist_${key}_rx", todayRx)
                .putLong("hist_${key}_tx", todayTx)
                .apply()
        }

        // Clean history older than 30 days — batched, wide window to catch gaps
        // when app wasn't opened for >60 days
        val editor = prefs.edit()
        val old = Calendar.getInstance()
        for (d in 31..400) {
            old.timeInMillis = cal.timeInMillis
            old.add(Calendar.DAY_OF_YEAR, -d)
            val oldKey = keyFmt.format(old.time)
            editor.remove("hist_${oldKey}_rx")
            editor.remove("hist_${oldKey}_tx")
        }
        editor.apply()
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
            // Archive yesterday's stats before resetting
            if (savedDay != -1) {
                archiveDay(cal)
            }
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
