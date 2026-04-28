package com.ccidea.plugin.data

import com.ccidea.plugin.settings.CcideaSettings

/**
 * Display-side formatting for tokens, cost, durations.
 *
 * Token unit modes (controlled by [CcideaSettings.tokenUnit]):
 *   "smart"  → 1.2k / 3.4M / 5.6B  (default)
 *   "raw"    → 5,434,148           (with thousand-separator)
 *   "kilo"   → 5,434k              (always /1000, like Anthropic)
 */
object Format {

    fun tokens(value: Long): String {
        val mode = CcideaSettings.getInstance().state.tokenUnit
        return when (mode) {
            "raw" -> "%,d".format(value)
            "kilo" -> "%,dk".format(value / 1000L)
            else -> smart(value)
        }
    }

    fun cost(value: Double): String {
        if (value == 0.0) return "$0"
        return when {
            value >= 100.0 -> "$%.0f".format(value)
            value >= 1.0   -> "$%.2f".format(value)
            value >= 0.01  -> "$%.3f".format(value)
            else           -> "$%.4f".format(value)
        }
    }

    /** Compact human format: 1234 → "1.2k", 12_345_678 → "12.3M". */
    fun smart(value: Long): String {
        val n = value.toDouble()
        return when {
            n < 1_000        -> value.toString()
            n < 1_000_000    -> "%.1fk".format(n / 1_000.0).trimZero()
            n < 1_000_000_000 -> "%.1fM".format(n / 1_000_000.0).trimZero()
            else             -> "%.2fB".format(n / 1_000_000_000.0).trimZero()
        }
    }

    /** Duration in "1h47m" / "47m12s" form. */
    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h${m}m"
            m > 0 -> "${m}m${s}s"
            else  -> "${s}s"
        }
    }

    fun durationFromMinutes(minutes: Long): String = duration(minutes * 60)

    private fun String.trimZero(): String =
        replace(Regex("\\.0+([a-zA-Z])$"), "$1").replace(Regex("(\\.\\d*?)0+([a-zA-Z])$"), "$1$2")
}
