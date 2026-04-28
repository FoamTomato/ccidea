package com.ccidea.plugin.ui.chart

import com.ccidea.plugin.data.model.DailyAggregate
import com.ccidea.plugin.data.model.MonthlyAggregate
import com.ccidea.plugin.data.model.SessionBlock
import com.ccidea.plugin.data.model.UsageEntry
import com.ccidea.plugin.i18n.ccideaMsg
import com.ccidea.plugin.patterns.ModelHitRatio
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.coord.coordFlip
import org.jetbrains.letsPlot.geom.geomBar
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomTile
import org.jetbrains.letsPlot.geom.geomVLine
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.pos.positionStack
import org.jetbrains.letsPlot.scale.scaleFillGradient
import org.jetbrains.letsPlot.scale.scaleFillManual
import org.jetbrains.letsPlot.scale.scaleXContinuous
import org.jetbrains.letsPlot.scale.scaleYDiscrete
import org.jetbrains.letsPlot.tooltips.layerTooltips
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Charts {

    private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    /** Stacked-bar token breakdown over the most recent [maxDays] days. */
    fun dailyStackedBar(rows: List<DailyAggregate>, maxDays: Int = 30): Plot? {
        val recent = rows.sortedBy { it.date }.takeLast(maxDays)
        if (recent.isEmpty() || recent.sumOf { it.totals.total } == 0L) return null
        val xs = recent.map { it.date.format(DAY_FMT) }
        val avgPerHour = recent.map { (it.totals.total / 24.0).toLong() }
        val avgCostPerHour = recent.map { it.cost / 24.0 }
        val ex = explodeWithAvg(xs, avgPerHour, avgCostPerHour) { i ->
            val r = recent[i].totals
            longArrayOf(r.input, r.output, r.cacheCreate5m, r.cacheCreate1h, r.cacheRead)
        }
        return baseStacked(ccideaMsg("chart.daily.title"), ex)
    }

    /** Total-tokens-per-day trend line over the most recent [maxDays] days. */
    fun dailyTrendLine(rows: List<DailyAggregate>, maxDays: Int = 30): Plot? {
        val recent = rows.sortedBy { it.date }.takeLast(maxDays)
        if (recent.size < 2) return null
        // Use a constant numeric x — Lets-Plot needs a numeric scale for geom_line.
        val xs = (0 until recent.size).map { it.toDouble() }
        val data = mapOf(
            "i" to xs,
            "tokens" to recent.map { it.totals.total }
        )
        return letsPlot(data) +
            geomLine(color = "#4FC3F7", size = 1.8) { x = "i"; y = "tokens" } +
            labs(title = ccideaMsg("chart.dailyTrend.title", maxDays), x = "", y = ccideaMsg("chart.axis.tokens"))
    }

    /** Cost-per-day trend line over the most recent [maxDays] days. */
    fun dailyCostTrend(rows: List<DailyAggregate>, maxDays: Int = 30): Plot? {
        val recent = rows.sortedBy { it.date }.takeLast(maxDays)
        if (recent.size < 2) return null
        val xs = (0 until recent.size).map { it.toDouble() }
        val data = mapOf(
            "i" to xs,
            "cost" to recent.map { it.cost }
        )
        return letsPlot(data) +
            geomLine(color = "#FFB74D", size = 1.8) { x = "i"; y = "cost" } +
            labs(title = ccideaMsg("chart.dailyCost.title", maxDays), x = "", y = "$")
    }

    /** Stacked-bar over the most recent [maxMonths] months. */
    fun monthlyStackedBar(rows: List<MonthlyAggregate>, maxMonths: Int = 12): Plot? {
        val recent = rows.sortedBy { it.yearMonth }.takeLast(maxMonths)
        if (recent.isEmpty() || recent.sumOf { it.totals.total } == 0L) return null
        val xs = recent.map { it.yearMonth.format(MONTH_FMT) }
        val avgPerHour = recent.map { row ->
            val hours = row.yearMonth.lengthOfMonth() * 24.0
            (row.totals.total / hours).toLong()
        }
        val avgCostPerHour = recent.map { row ->
            val hours = row.yearMonth.lengthOfMonth() * 24.0
            row.cost / hours
        }
        val ex = explodeWithAvg(xs, avgPerHour, avgCostPerHour) { i ->
            val r = recent[i].totals
            longArrayOf(r.input, r.output, r.cacheCreate5m, r.cacheCreate1h, r.cacheRead)
        }
        return baseStacked(ccideaMsg("chart.monthly.title"), ex)
    }

    /**
     * Burn-rate time series for the trailing 1h, sampled in 1-minute buckets.
     * Optionally adds an ETA-to-limit vertical reference line.
     */
    fun burnRateLine(
        block: SessionBlock,
        now: Instant,
        etaInstant: Instant? = null
    ): Plot? {
        val cutoff = now.minus(Duration.ofHours(1))
        val recent = block.entries.filter { it.timestamp >= cutoff }
        if (recent.isEmpty()) return null
        val series = bucketPerMinute(recent, cutoff, now)
        if (series.isEmpty()) return null

        val df = mapOf(
            "t" to series.map { it.first.toEpochMilli().toDouble() },
            "v" to series.map { it.second }
        )
        var p: Plot = letsPlot(df) +
            geomLine(color = "#4FC3F7", size = 1.5) { x = "t"; y = "v" } +
            labs(title = ccideaMsg("chart.burnRate.title"), x = "", y = ccideaMsg("chart.burnRate.y"))
        if (etaInstant != null && etaInstant.isAfter(now)) {
            p = p + geomVLine(
                xintercept = etaInstant.toEpochMilli().toDouble(),
                linetype = "dashed",
                color = "#FF5252"
            )
        }
        return p
    }

    private fun bucketPerMinute(
        entries: List<UsageEntry>,
        from: Instant,
        to: Instant
    ): List<Pair<Instant, Double>> {
        val bucketCount = (Duration.between(from, to).toMinutes() + 1).coerceAtLeast(1).toInt()
        val buckets = LongArray(bucketCount)
        for (e in entries) {
            val idx = Duration.between(from, e.timestamp).toMinutes().toInt().coerceIn(0, bucketCount - 1)
            buckets[idx] += e.totalTokens
        }
        // Convert per-minute totals into a cumulative-window rate over the last few minutes
        // for visual smoothness; here a 5-minute moving sum / 5.
        val window = 5
        val result = ArrayList<Pair<Instant, Double>>(bucketCount)
        for (i in buckets.indices) {
            var sum = 0L; var count = 0
            for (j in (i - window + 1)..i) {
                if (j in buckets.indices) { sum += buckets[j]; count++ }
            }
            val rate = if (count > 0) sum.toDouble() / count else 0.0
            result += from.plus(Duration.ofMinutes(i.toLong())) to rate
        }
        return result
    }

    private data class StackedDataset(
        val x: List<String>,
        val y: List<Long>,
        val cat: List<String>,
        val avgPerHour: List<Long>,
        val avgCostPerHour: List<Double>
    )

    private fun baseStacked(title: String, ds: StackedDataset): Plot {
        val data = mapOf(
            "x" to ds.x,
            "y" to ds.y,
            "cat" to ds.cat,
            "avgPerHour" to ds.avgPerHour,
            "avgCostPerHour" to ds.avgCostPerHour
        )
        val tip = layerTooltips("y", "cat", "avgPerHour", "avgCostPerHour")
            .format("@y", ",.3~s")
            .format("@avgPerHour", ",.3~s")
            .format("@avgCostPerHour", "$,.2f")
            .line("${ccideaMsg("chart.axis.tokens")}|@y")
            .line("${ccideaMsg("chart.legend.type")}|@cat")
            .line("${ccideaMsg("chart.tooltip.avgTokensPerHour")}|@avgPerHour")
            .line("${ccideaMsg("chart.tooltip.avgCostPerHour")}|@avgCostPerHour")
        return letsPlot(data) +
            geomBar(stat = Stat.identity, position = positionStack(), tooltips = tip) {
                this.x = "x"; this.y = "y"; this.fill = "cat"
            } +
            scaleFillManual(
                values = ChartUtil.Series.COLORS,
                limits = ChartUtil.Series.ORDER,
                name = ccideaMsg("chart.legend.type")
            ) +
            labs(title = title, x = "", y = ccideaMsg("chart.axis.tokens"))
    }

    private fun explodeWithAvg(
        xs: List<String>,
        avgPerHourPerRow: List<Long>,
        avgCostPerHourPerRow: List<Double>,
        bucket: (Int) -> LongArray
    ): StackedDataset {
        val cats = ChartUtil.Series.ORDER
        val total = xs.size * cats.size
        val xRep = ArrayList<String>(total)
        val yRep = ArrayList<Long>(total)
        val catRep = ArrayList<String>(total)
        val avgRep = ArrayList<Long>(total)
        val avgCostRep = ArrayList<Double>(total)
        for (i in xs.indices) {
            val arr = bucket(i)
            for (k in cats.indices) {
                xRep += xs[i]
                yRep += arr[k]
                catRep += cats[k]
                avgRep += avgPerHourPerRow[i]
                avgCostRep += avgCostPerHourPerRow[i]
            }
        }
        return StackedDataset(xRep, yRep, catRep, avgRep, avgCostRep)
    }

    @Suppress("UNUSED_PARAMETER") fun zoneTag(zone: ZoneId) = Unit

    /**
     * 7×24 heatmap. [grid] is indexed [dayOfWeekMon0..Sun6][hour0..23].
     * Cells with zero are still rendered (gives a sense of empty hours).
     */
    fun activityHeatmap(grid: Array<LongArray>): Plot? {
        if (grid.isEmpty()) return null
        val total = grid.sumOf { it.sum() }
        if (total == 0L) return null
        val dows = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val xs = ArrayList<Int>(7 * 24)
        val ys = ArrayList<String>(7 * 24)
        val vs = ArrayList<Long>(7 * 24)
        for (d in 0..6) for (h in 0..23) { xs += h; ys += dows[d]; vs += grid[d][h] }
        val data = mapOf("hour" to xs, "dow" to ys, "tokens" to vs)
        return letsPlot(data) +
            geomTile(showLegend = true) { x = "hour"; y = "dow"; fill = "tokens" } +
            scaleFillGradient(low = "#1A237E", high = "#FFEB3B", name = ccideaMsg("chart.axis.tokens")) +
            scaleXContinuous(breaks = listOf(0, 6, 12, 18, 23)) +
            scaleYDiscrete(limits = dows.reversed()) +
            labs(title = ccideaMsg("chart.heatmap.title"), x = ccideaMsg("chart.heatmap.x"), y = "")
    }

    /** Horizontal bar chart of cache-read ratio per model. Higher is better. */
    fun cacheHitRatioBar(rows: List<ModelHitRatio>): Plot? {
        val filtered = rows.filter { it.totalReadAndCacheable() > 0 }
            .sortedByDescending { it.ratio }
            .take(10)
        if (filtered.isEmpty()) return null
        val data = mapOf(
            "model" to filtered.map { it.model },
            "ratio" to filtered.map { it.ratio }
        )
        return letsPlot(data) +
            geomBar(stat = Stat.identity, fill = "#4FC3F7") {
                x = "model"; y = "ratio"
            } +
            coordFlip() +
            labs(title = ccideaMsg("chart.hitRatio.title"), x = "", y = ccideaMsg("chart.hitRatio.y"))
    }
}
