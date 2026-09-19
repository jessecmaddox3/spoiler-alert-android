package com.jessemaddox.spoileralert.schedule

internal data class SoccerClockEvidence(
    val clock: SoccerClock,
    val earliestMillis: Long,
    val latestMillis: Long,
)

/** A neutral match position. Period spacing keeps added time before the next half. */
internal data class SoccerClock(val period: Int, val minute: Int, val added: Int = 0, val second: Int = 0) {
    val position: Int get() = (period - 1) * PERIOD_SPACE + (minute + added) * 60 + second
    val label: String get() = when {
        added > 0 -> "$minute+$added′"
        second > 0 -> "%d:%02d".format(minute, second)
        period > 1 && minute == startMinute(period) -> when (period) {
            2 -> "Start of second half"
            3 -> "Start of extra time"
            else -> "Start of second extra-time half"
        }
        minute == 0 -> "Start of match"
        else -> "$minute′"
    }

    fun leadIn(minutes: Int): SoccerClock {
        val seconds = ((minute + added) * 60 + second - minutes.coerceIn(0, 10) * 60)
            .coerceAtLeast(startMinute(period) * 60)
        val end = endMinute(period)
        return if (seconds / 60 > end) SoccerClock(period, end, seconds / 60 - end, seconds % 60)
            else SoccerClock(period, seconds / 60, second = seconds % 60)
    }

    companion object {
        const val PERIOD_SPACE = 100_000
        fun startMinute(period: Int): Int = listOf(0, 45, 90, 105)[period - 1]
        fun endMinute(period: Int): Int = listOf(45, 90, 105, 120)[period - 1]

        /** Provider minute labels are coarse; user-entered clocks still use [input] directly. */
        fun provider(period: Int, display: String?, value: String? = null): SoccerClockEvidence? {
            val numeric = value?.toBigDecimalOrNull()
            if (value != null && (numeric == null || numeric < java.math.BigDecimal.ZERO ||
                numeric > java.math.BigDecimal(12_000))) return null
            val clock = parse(period, display, numeric?.toDouble()) ?: return null
            val phaseMillis = (period - 1L) * PERIOD_SPACE * 1_000L
            val clockStartMillis = (clock.minute + clock.added) * 60_000L + clock.second * 1_000L
            val hasSeconds = display?.contains(':') == true
            // Some added-time feeds clamp the numeric clock at the regulation boundary.
            val clampedAddedTime = clock.added > 0 && numeric?.compareTo(java.math.BigDecimal(clock.minute * 60)) == 0
            if (numeric != null && !clampedAddedTime) {
                val exact = numeric * java.math.BigDecimal(1_000)
                val earliest = exact.setScale(0, java.math.RoundingMode.FLOOR).toLong()
                val latest = exact.setScale(0, java.math.RoundingMode.CEILING).toLong()
                if (display != null) {
                    val width = if (hasSeconds) 1_000L else 60_000L
                    if (earliest !in clockStartMillis until clockStartMillis + width) return null
                    // A numeric minute boundary does not establish seconds absent from a label.
                    if (!hasSeconds && earliest == clockStartMillis && latest == earliest) {
                        return SoccerClockEvidence(clock, phaseMillis + earliest, phaseMillis + earliest + 59_999)
                    }
                }
                val preciseClock = parse(period, null, numeric.toDouble()) ?: return null
                return SoccerClockEvidence(preciseClock, phaseMillis + earliest, phaseMillis + latest)
            }
            val earliest = phaseMillis + clockStartMillis
            return SoccerClockEvidence(clock, earliest, earliest + if (hasSeconds) 0 else 59_999)
        }

        fun parse(period: Int, display: String?, valueSeconds: Double? = null): SoccerClock? {
            if (period !in 1..4) return null
            val normalized = display?.trim()?.replace("′", "")?.replace("'", "")
            val match = normalized?.let { Regex("^(\\d{1,3})(?:\\+(\\d{1,2}))?(?::([0-5]\\d))?$").matchEntire(it) }
            val secondsFromValue = valueSeconds?.takeIf { it.isFinite() && it in 0.0..12_000.0 }?.toInt()
            if (display != null && match == null) return null
            var minute = match?.groupValues?.get(1)?.toIntOrNull() ?: secondsFromValue?.div(60) ?: return null
            var added = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val second = match?.groupValues?.get(3)?.toIntOrNull()
                ?: if (match == null) (secondsFromValue ?: 0) % 60 else 0
            if (minute < startMinute(period)) return null
            val end = endMinute(period)
            if (added > 0 && minute != end) return null
            if (minute > end) {
                added = minute - end
                minute = end
            }
            if (added > 30) return null
            return SoccerClock(period, minute, added, second)
        }

        fun input(text: String): SoccerClock? {
            val input = text.trim().lowercase()
            val explicit = Regex("^([1-4])h\\s+(.+)$").matchEntire(input)
            val clockText = explicit?.groupValues?.get(2) ?: input
            val minute = Regex("^\\d{1,3}").find(clockText)?.value?.toIntOrNull() ?: return null
            // 45:20 can be first-half added time or the start of the second half.
            if (explicit == null && (minute == 45 || minute >= 90) && '+' !in clockText) return null
            val period = explicit?.groupValues?.get(1)?.toInt() ?: when {
                minute <= 45 -> 1
                minute <= 90 -> 2
                minute <= 105 -> 3
                else -> 4
            }
            return parse(period, clockText)
        }
    }
}
