package com.ccidea.plugin.quota

import com.ccidea.plugin.blocks.BlockService
import com.ccidea.plugin.data.model.UsageEntry
import com.ccidea.plugin.pricing.PricingService
import com.ccidea.plugin.settings.CcideaSettings
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Local quota estimation, fed entirely by the JSONL data we already track.
 *
 * - "Block": current 5h session block (delegates to BlockService).
 * - "Week":  ISO Monday 00:00 → next Monday 00:00, system timezone.
 * - "Month": calendar month, system timezone.
 *
 * Both token and USD limits are accepted; if both are set for a window the token
 * limit wins (matches how subscription users think about quota). When neither is
 * configured, [QuotaWindow.percent] is null and the UI shows usage only.
 */
/** Where the percent + reset time came from. UI badges OFFICIAL with a small marker. */
enum class QuotaSource { LOCAL, OFFICIAL }

data class QuotaWindow(
    val label: WindowLabel,
    val tokens: Long,
    val cost: Double,
    val tokenLimit: Long,    // 0 = unset
    val costLimit: Double,   // 0.0 = unset
    val percent: Double?,    // 0–100, null when no limit configured
    val resetsAt: Instant?,
    val rangeStart: Instant,
    val source: QuotaSource = QuotaSource.LOCAL
) {
    enum class WindowLabel { BLOCK, WEEK, MONTH }
}

object QuotaCalculator {

    fun computeAll(
        entries: List<UsageEntry>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<QuotaWindow> {
        // Side-effect: schedule a background refresh of the official quota so the next
        // tick can pick it up. Cheap when fresh, no-op when in cooldown.
        OfficialQuotaService.getInstance().refreshIfStale(now)
        val official = OfficialQuotaService.getInstance().current()

        val out = mutableListOf<QuotaWindow>()
        currentBlock(now)?.let { w ->
            out += if (official?.fiveHourPercent != null)
                w.copy(
                    percent = official.fiveHourPercent,
                    resetsAt = official.fiveHourResetsAt ?: w.resetsAt,
                    source = QuotaSource.OFFICIAL
                )
            else w
        }
        val week = currentWeek(entries, now, zone)
        out += if (official?.sevenDayPercent != null)
            week.copy(
                percent = official.sevenDayPercent,
                resetsAt = official.sevenDayResetsAt ?: week.resetsAt,
                source = QuotaSource.OFFICIAL
            )
        else week
        out += currentMonth(entries, now, zone)
        return out
    }

    /** Token + cost for the active 5h block; null when there's no live block. */
    private fun currentBlock(now: Instant): QuotaWindow? {
        val block = BlockService.getInstance().currentBlock() ?: return null
        val s = CcideaSettings.getInstance().state
        // Mirror the existing block-notification logic: prefer user override, then auto-detected peak.
        val limit = if (s.customTokenLimit > 0) s.customTokenLimit
        else BlockService.getInstance().detectedTokenLimit()
        val pct: Double? = if (limit > 0) (block.totalTokens.toDouble() / limit * 100).coerceAtLeast(0.0)
        else null
        return QuotaWindow(
            label = QuotaWindow.WindowLabel.BLOCK,
            tokens = block.totalTokens,
            cost = block.totalCost,
            tokenLimit = if (limit > 0) limit else 0L,
            costLimit = 0.0,
            percent = pct,
            resetsAt = block.endTime,
            rangeStart = block.startTime
        )
    }

    private fun currentWeek(entries: List<UsageEntry>, now: Instant, zone: ZoneId): QuotaWindow {
        val today = LocalDate.ofInstant(now, zone)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val rangeStart = weekStart.atStartOfDay(zone).toInstant()
        val rangeEnd = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant()
        val s = CcideaSettings.getInstance().state
        return aggregate(
            QuotaWindow.WindowLabel.WEEK,
            entries, rangeStart, rangeEnd,
            tokenLimit = s.weeklyTokenLimit,
            costLimit = s.weeklyCostLimit
        )
    }

    private fun currentMonth(entries: List<UsageEntry>, now: Instant, zone: ZoneId): QuotaWindow {
        val ym = YearMonth.from(now.atZone(zone))
        val rangeStart = ym.atDay(1).atStartOfDay(zone).toInstant()
        val rangeEnd = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
        val s = CcideaSettings.getInstance().state
        return aggregate(
            QuotaWindow.WindowLabel.MONTH,
            entries, rangeStart, rangeEnd,
            tokenLimit = s.monthlyTokenLimit,
            costLimit = s.monthlyCostLimit
        )
    }

    private fun aggregate(
        label: QuotaWindow.WindowLabel,
        entries: List<UsageEntry>,
        rangeStart: Instant,
        rangeEnd: Instant,
        tokenLimit: Long,
        costLimit: Double
    ): QuotaWindow {
        val pricing = PricingService.getInstance()
        var tokens = 0L
        var cost = 0.0
        for (e in entries) {
            if (e.timestamp < rangeStart || e.timestamp >= rangeEnd) continue
            tokens += e.totalTokens
            cost += pricing.costFor(e).total
        }
        val pct: Double? = when {
            tokenLimit > 0 -> (tokens.toDouble() / tokenLimit * 100).coerceAtLeast(0.0)
            costLimit > 0 -> (cost / costLimit * 100).coerceAtLeast(0.0)
            else -> null
        }
        return QuotaWindow(
            label = label,
            tokens = tokens,
            cost = cost,
            tokenLimit = tokenLimit,
            costLimit = costLimit,
            percent = pct,
            resetsAt = rangeEnd,
            rangeStart = rangeStart
        )
    }

    /** Human-friendly "Xd Yh" / "Xh Ym" string for the time remaining until [reset]. */
    fun timeUntil(reset: Instant, now: Instant = Instant.now()): String {
        val left = Duration.between(now, reset)
        if (left.isNegative || left.isZero) return "0m"
        val days = left.toDays()
        val hours = left.toHours() % 24
        val mins = left.toMinutes() % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${mins}m"
        }
    }
}
