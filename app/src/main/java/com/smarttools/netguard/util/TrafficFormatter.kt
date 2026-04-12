package com.smarttools.netguard.util

object TrafficFormatter {

    fun formatSpeed(bytesPerSec: Long): String {
        val b = bytesPerSec.coerceAtLeast(0)
        return when {
            b < 1024 -> "$b B/s"
            b < 1024 * 1024 -> "%.1f KB/s".format(b / 1024.0)
            b < 1024L * 1024 * 1024 -> "%.1f MB/s".format(b / (1024.0 * 1024))
            b < 1024L * 1024 * 1024 * 1024 -> "%.2f GB/s".format(b / (1024.0 * 1024 * 1024))
            else -> "%.2f TB/s".format(b / (1024.0 * 1024 * 1024 * 1024))
        }
    }

    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
            b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
            b < 1024L * 1024 * 1024 * 1024 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
            else -> "%.2f TB".format(b / (1024.0 * 1024 * 1024 * 1024))
        }
    }

    fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }
}
