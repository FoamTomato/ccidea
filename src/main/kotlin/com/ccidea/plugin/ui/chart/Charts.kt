package com.ccidea.plugin.ui.chart

import com.ccidea.plugin.data.Format
import com.ccidea.plugin.data.ProjectKeyFormat
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

    /** Per-day total cost bar over the most recent [maxDays] days. */
    fun dailyCostBar(rows: List<DailyAggregate>, maxDays: Int = 30): Plot? {
        val recent = rows.sortedBy { it.date }.takeLast(maxDays)
        if (recent.isEmpty() || recent.sumOf { it.cost } == 0.0) return null
        val xs = recent.map { it.date.format(DAY_FMT) }
        val cost = recent.map { it.cost }
        val avgCostPerHour = recent.map { it.cost / 24.0 }
        val avgPerHour = recent.map { (it.totals.total / 24.0).toLong() }
        val data = mapOf(
            "x" to xs,
            "y" to cost,
            "avgPerHour" to avgPerHour,
            "avgCostPerHour" to avgCostPerHour
        )
        val tip = layerTooltips()
            .format("@y", "$,.2f")
            .format("@avgPerHour", ",.3~s")
            .format("@avgCostPerHour", "$,.2f")
            .line("${ccideaMsg("chart.axis.cost")}|@y")
            .line("${ccideaMsg("chart.tooltip.avgTokensPerHour")}|@avgPerHour")
            .line("${ccideaMsg("chart.tooltip.avgCostPerHour")}|@avgCostPerHour")
        return letsPlot(data) +
            geomBar(stat = Stat.identity, tooltips = tip, fill = "#FFB74D") {
                this.x = "x"; this.y = "y"
            } +
            labs(title = ccideaMsg("chart.daily.titleCost"), x = "", y = "$")
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

    /** Per-month total cost bar over the most recent [maxMonths] months. */
    fun monthlyCostBar(rows: List<MonthlyAggregate>, maxMonths: Int = 12): Plot? {
        val recent = rows.sortedBy { it.yearMonth }.takeLast(maxMonths)
        if (recent.isEmpty() || recent.sumOf { it.cost } == 0.0) return null
        val xs = recent.map { it.yearMonth.format(MONTH_FMT) }
        val cost = recent.map { it.cost }
        val avgPerHour = recent.map { row ->
            val hours = row.yearMonth.lengthOfMonth() * 24.0
            (row.totals.total / hours).toLong()
        }
        val avgCostPerHour = recent.map { row ->
            val hours = row.yearMonth.lengthOfMonth() * 24.0
            row.cost / hours
        }
        val data = mapOf(
            "x" to xs, "y" to cost,
            "avgPerHour" to avgPerHour, "avgCostPerHour" to avgCostPerHour
        )
        val tip = layerTooltips()
            .format("@y", "$,.2f")
            .format("@avgPerHour", ",.3~s")
            .format("@avgCostPerHour", "$,.2f")
            .line("${ccideaMsg("chart.axis.cost")}|@y")
            .line("${ccideaMsg("chart.tooltip.avgTokensPerHour")}|@avgPerHour")
            .line("${ccideaMsg("chart.tooltip.avgCostPerHour")}|@avgCostPerHour")
        return letsPlot(data) +
            geomBar(stat = Stat.identity, tooltips = tip, fill = "#FFB74D") {
                this.x = "x"; this.y = "y"
            } +
            labs(title = ccideaMsg("chart.monthly.titleCost"), x = "", y = "$")
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
        etaInstant: Instant? = null,
        costMode: Boolean = false
    ): Plot? {
        // Each X step = 1 minute since block start. Y = actual tokens (or USD) consumed
        // *that minute*. Sum over all minutes equals the block total shown in the table.
        val from = block.startTime.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        val to = now
        if (!to.isAfter(from)) return null
        val pricing = com.ccidea.plugin.pricing.PricingService.getInstance()
        val minutes = Duration.between(from, to).toMinutes().toInt().coerceAtLeast(1)
        // perMinute[i] = total this minute (in active metric).
        // perProjectTok[i] / perProjectCost[i] = per-project token / cost for tooltip.
        val perMinute = DoubleArray(minutes + 1)
        val perProjectTok = Array(minutes + 1) { LinkedHashMap<String, Long>() }
        val perProjectCost = Array(minutes + 1) { LinkedHashMap<String, Double>() }
        for (e in block.entries) {
            if (e.timestamp < from) continue
            val idx = Duration.between(from, e.timestamp).toMinutes().toInt().coerceIn(0, minutes)
            val cost = pricing.costFor(e).total
            val tok = e.totalTokens
            perMinute[idx] += if (costMode) cost else tok.toDouble()
            val key = e.projectKey.ifBlank { "(unknown)" }
            perProjectTok[idx].merge(key, tok) { a, b -> a + b }
            perProjectCost[idx].merge(key, cost) { a, b -> a + b }
        }
        val xs = (0..minutes).map { it.toDouble() }
        val ys = perMinute.toList()
        val slotCount = 5
        // lets-plot's "label|value" only supports static labels, and value-only lines
        // are center-aligned. To keep the tooltip readable we put the entire row
        // (project · tokens · USD) inside the value field; lets-plot will center it.
        val rowSlot = Array(slotCount) { ArrayList<String>(minutes + 1) }
        val moreSlot = ArrayList<String>(minutes + 1)
        val timeVal = ArrayList<String>(minutes + 1)
        val totalVal = ArrayList<String>(minutes + 1)
        for (i in 0..minutes) {
            val keys = perProjectTok[i].keys
            val sorted = keys.sortedByDescending {
                if (costMode) perProjectCost[i][it] ?: 0.0
                else (perProjectTok[i][it] ?: 0L).toDouble()
            }
            for (s in 0 until slotCount) {
                val k = sorted.getOrNull(s)
                rowSlot[s].add(if (k == null) "" else {
                    val name = ProjectKeyFormat.shortName(k)
                    val tok = perProjectTok[i][k] ?: 0L
                    val cost = perProjectCost[i][k] ?: 0.0
                    "$name  ·  ${Format.tokens(tok)}  ·  $${"%.3f".format(cost)}"
                })
            }
            moreSlot.add(if (sorted.size > slotCount) "+${sorted.size - slotCount} 项" else "")

            timeVal.add(from.plus(Duration.ofMinutes(i.toLong())).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm")))
            val totTok = perProjectTok[i].values.sum()
            val totCost = perProjectCost[i].values.sum()
            totalVal.add("${Format.tokens(totTok)}  ·  $${"%.3f".format(totCost)}")
        }

        val df = HashMap<String, Any>().apply {
            put("t", xs); put("v", ys)
            put("timeVal", timeVal)
            put("totalVal", totalVal)
            put("more", moreSlot)
            for (s in 0 until slotCount) put("r$s", rowSlot[s])
        }
        val zone = ZoneId.systemDefault()
        val hhmm = DateTimeFormatter.ofPattern("HH:mm")
        val totalMinutes = Duration.between(from, block.endTime).toMinutes().toInt().coerceAtLeast(60)
        val step = 60
        val breaks = (0..totalMinutes step step).map { it.toDouble() }
        val labels = breaks.map { from.plus(Duration.ofMinutes(it.toLong())).atZone(zone).format(hhmm) }

        val title = if (costMode) ccideaMsg("chart.burnRate.titleCost") else ccideaMsg("chart.burnRate.title")
        val yLab = if (costMode) ccideaMsg("chart.burnRate.yCost") else ccideaMsg("chart.burnRate.y")
        val color = if (costMode) "#FFB74D" else "#4FC3F7"
        val tip = layerTooltips()
            .line("${ccideaMsg("chart.burnRate.tip.time")}|@timeVal")
            .line("${ccideaMsg("chart.burnRate.tip.total")}|@totalVal")
            .line("@r0")
            .line("@r1")
            .line("@r2")
            .line("@r3")
            .line("@r4")
            .line("@more")
        var p: Plot = letsPlot(df) +
            geomLine(color = color, size = 1.5, tooltips = tip) { x = "t"; y = "v" } +
            scaleXContinuous(breaks = breaks, labels = labels, limits = 0.0 to totalMinutes.toDouble()) +
            labs(title = title, x = "", y = yLab)
        if (etaInstant != null && etaInstant.isAfter(from)) {
            p = p + geomVLine(
                xintercept = Duration.between(from, etaInstant).toMinutes().toDouble(),
                linetype = "dashed",
                color = "#FF5252"
            )
        }
        return p
    }

    private fun bucketPerMinute(
        entries: List<UsageEntry>,
        from: Instant,
        to: Instant,
        costMode: Boolean = false
    ): List<Pair<Instant, Double>> {
        val bucketCount = (Duration.between(from, to).toMinutes() + 1).coerceAtLeast(1).toInt()
        val buckets = DoubleArray(bucketCount)
        val pricing = if (costMode) com.ccidea.plugin.pricing.PricingService.getInstance() else null
        for (e in entries) {
            val idx = Duration.between(from, e.timestamp).toMinutes().toInt().coerceIn(0, bucketCount - 1)
            buckets[idx] += if (costMode) pricing!!.costFor(e).total else e.totalTokens.toDouble()
        }
        // Smooth via a 5-minute moving average. For tokens this approximates tokens/min;
        // for cost we scale to USD/hour so the y-axis matches the title label.
        val window = 5
        val result = ArrayList<Pair<Instant, Double>>(bucketCount)
        for (i in buckets.indices) {
            var sum = 0.0; var count = 0
            for (j in (i - window + 1)..i) {
                if (j in buckets.indices) { sum += buckets[j]; count++ }
            }
            val perMin = if (count > 0) sum / count else 0.0
            val v = if (costMode) perMin * 60.0 else perMin
            result += from.plus(Duration.ofMinutes(i.toLong())) to v
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
        val tip = layerTooltips()
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
