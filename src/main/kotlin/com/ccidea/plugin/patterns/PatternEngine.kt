package com.ccidea.plugin.patterns

import com.ccidea.plugin.blocks.BlockService
import com.ccidea.plugin.data.model.SessionAggregate
import com.ccidea.plugin.data.model.UsageEntry
import com.ccidea.plugin.pricing.PricingService
import com.ccidea.plugin.service.AggregationService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@Service(Service.Level.APP)
class PatternEngine {

    fun report(zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): PatternReport {
        val entries = BlockService.getInstance().all()
        val sessions = AggregationService.getInstance().sessions()
        return PatternReport(
            topSessions = topExpensive(sessions, 10),
            modelHitRatios = modelHitRatios(entries),
            heatmap = heatmap(entries, zone, now, lookback = Duration.ofDays(30)),
            outliers = p95Outliers(sessions),
            recommendations = recommend(entries, sessions)
        )
    }

    fun topExpensive(sessions: List<SessionAggregate>, n: Int): List<SessionAggregate> =
        sessions.sortedByDescending { it.cost }.take(n)

    /** cache_read / (cache_read + input + cache_create_total). 0..1; 0 means no reuse. */
    fun modelHitRatios(entries: List<UsageEntry>): List<ModelHitRatio> {
        if (entries.isEmpty()) return emptyList()
        val byModel = entries.groupBy { it.model }
        return byModel.map { (model, es) ->
            val cacheRead = es.sumOf { it.cacheRead }
            val input = es.sumOf { it.inputTokens }
            val cacheCreate = es.sumOf { it.cacheCreationTotal }
            val denom = (cacheRead + input + cacheCreate).coerceAtLeast(1L)
            val ratio = cacheRead.toDouble() / denom
            ModelHitRatio(model, ratio, cacheRead, input + cacheCreate)
        }.sortedByDescending { it.totalReadAndCacheable() }
    }

    /** Day-of-week (Mon=0..Sun=6) × hour (0..23) bucket of total tokens, last 30 days. */
    fun heatmap(
        entries: List<UsageEntry>,
        zone: ZoneId,
        now: Instant,
        lookback: Duration
    ): Array<LongArray> {
        val cutoff = now.minus(lookback)
        val grid = Array(7) { LongArray(24) }
        for (e in entries) {
            if (e.timestamp < cutoff) continue
            val zdt = e.timestamp.atZone(zone)
            val dow = (zdt.dayOfWeek.value - 1).coerceIn(0, 6)
            val hour = zdt.hour.coerceIn(0, 23)
            grid[dow][hour] += e.totalTokens
        }
        return grid
    }

    fun p95Outliers(sessions: List<SessionAggregate>): List<SessionAggregate> {
        if (sessions.size < 5) return emptyList()
        val sorted = sessions.map { it.cost }.sorted()
        val idx = ((sorted.size - 1) * 0.95).toInt().coerceAtLeast(0)
        val cut = sorted[idx]
        return sessions.filter { it.cost > cut }.sortedByDescending { it.cost }
    }

    fun recommend(
        entries: List<UsageEntry>,
        sessions: List<SessionAggregate>
    ): List<Recommendation> {
        if (entries.isEmpty()) return emptyList()
        val pricing = PricingService.getInstance()
        val out = mutableListOf<Recommendation>()

        // 0. Always-on overview — gives users a useful summary even when no patterns trigger.
        val totalCostAll = sessions.sumOf { it.cost }
        val totalTokensAll = entries.sumOf { it.totalTokens }
        out += Recommendation(
            severity = Severity.INFO,
            title = "使用概览",
            detail = "共 ${sessions.size} 个会话，${entries.size} 条调用，累计 ${formatTokens(totalTokensAll)} tokens，总花费 $%.2f。"
                .format(totalCostAll)
        )

        // 1. Low cache hit ratio per model.
        for (mhr in modelHitRatios(entries)) {
            if (mhr.totalReadAndCacheable() > 5_000 && mhr.ratio < 0.50) {
                out += Recommendation(
                    severity = Severity.HINT,
                    title = "缓存复用偏低：${mhr.model}",
                    detail = "缓存读取占比 %.0f%%。保持 CLAUDE.md 与工具定义稳定，可显著提升复用率。"
                        .format(mhr.ratio * 100)
                )
            }
        }

        // 2. Opus cost share — heavy Opus reliance suggests downshift opportunities.
        val opusSessions = sessions.filter { s -> s.models.any { it.contains("opus", ignoreCase = true) } }
        val totalCost = sessions.sumOf { it.cost }
        val opusCostShare = if (totalCost > 0) opusSessions.sumOf { it.cost } / totalCost else 0.0
        if (opusCostShare > 0.6 && opusSessions.size >= 3) {
            out += Recommendation(
                severity = Severity.HINT,
                title = "Opus 占用较高",
                detail = "Opus 占总成本 ${(opusCostShare * 100).toInt()}%（共 ${opusSessions.size} 个会话）。" +
                    "对短改动/简单任务可改用 Sonnet 以降低成本。"
            )
        }

        // 3. 1h cache writes that aren't reused inside the same block (the cache will expire idle).
        val blocks = BlockService.getInstance().allBlocks()
        for (b in blocks.takeLast(10)) {
            val written1h = b.entries.sumOf { it.cacheCreation1h }
            val read = b.entries.sumOf { it.cacheRead }
            if (written1h > 5_000 && read < written1h / 4) {
                out += Recommendation(
                    severity = Severity.HINT,
                    title = "1h 缓存利用率低（区块 ${b.startTime}）",
                    detail = "写入 1h TTL 缓存 ${written1h} tokens，仅读取 ${read}。" +
                        "对短任务可改用默认 5m TTL，写入更便宜。"
                )
                break
            }
        }

        // 4. Unknown-model warning surface.
        val unknownModels = entries.map { it.model }.toSet().filter { pricing.resolve(it) == null }
        if (unknownModels.isNotEmpty()) {
            out += Recommendation(
                severity = Severity.INFO,
                title = "${unknownModels.size} 个模型未计入成本",
                detail = unknownModels.joinToString(", ") + "。请更新 LiteLLM 定价或自定义费率。"
            )
        }

        // 5. Top-cost session call-out — make the heaviest session visible.
        val totalSessionCost = sessions.sumOf { it.cost }
        sessions.maxByOrNull { it.cost }?.let { top ->
            if (totalSessionCost > 0 && top.cost / totalSessionCost > 0.15 && sessions.size >= 5) {
                out += Recommendation(
                    severity = Severity.INFO,
                    title = "高成本会话：${top.projectKey} / ${top.sessionId.take(8)}",
                    detail = "该会话花费 $%.2f，占总成本 ${(top.cost / totalSessionCost * 100).toInt()}%。"
                        .format(top.cost)
                )
            }
        }

        // 6. High output-to-input ratio — usually indicates verbose responses.
        val totalInput = entries.sumOf { it.inputTokens }
        val totalOutput = entries.sumOf { it.outputTokens }
        if (totalInput > 100_000 && totalOutput > totalInput * 2) {
            out += Recommendation(
                severity = Severity.HINT,
                title = "输出量明显高于输入",
                detail = "输出/输入 ≈ %.1f×。可考虑要求更简洁的回答，或减少不必要的总结。"
                    .format(totalOutput.toDouble() / totalInput)
            )
        }

        // 7. Recent activity surge — last 24h cost > 30% of last 7d cost.
        val now = Instant.now()
        val day = now.minus(Duration.ofDays(1))
        val week = now.minus(Duration.ofDays(7))
        val last24hCost = entries.filter { it.timestamp >= day }
            .sumOf { pricing.costFor(it).total }
        val last7dCost = entries.filter { it.timestamp >= week }
            .sumOf { pricing.costFor(it).total }
        if (last7dCost > 5.0 && last24hCost / last7dCost > 0.30) {
            out += Recommendation(
                severity = Severity.WARN,
                title = "近 24 小时使用量上升",
                detail = "近 24 小时花费 $%.2f，占近 7 天的 ${(last24hCost / last7dCost * 100).toInt()}%。"
                    .format(last24hCost)
            )
        }

        return out
    }

    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }

    companion object {
        fun getInstance(): PatternEngine = ApplicationManager.getApplication().service()
    }
}

data class ModelHitRatio(
    val model: String,
    val ratio: Double,
    val cacheRead: Long,
    val cacheable: Long
) {
    fun totalReadAndCacheable(): Long = cacheRead + cacheable
}

enum class Severity { INFO, HINT, WARN }

data class Recommendation(val severity: Severity, val title: String, val detail: String)

data class PatternReport(
    val topSessions: List<SessionAggregate>,
    val modelHitRatios: List<ModelHitRatio>,
    val heatmap: Array<LongArray>,
    val outliers: List<SessionAggregate>,
    val recommendations: List<Recommendation>
) {
    /** Heatmap overrides to keep `data class` happy (Array<LongArray> doesn't equal-by-value). */
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
