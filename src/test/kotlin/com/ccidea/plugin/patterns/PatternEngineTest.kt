package com.ccidea.plugin.patterns

import com.ccidea.plugin.data.model.SessionAggregate
import com.ccidea.plugin.data.model.TokenTotals
import com.ccidea.plugin.data.model.UsageEntry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class PatternEngineTest {
    private val engine = PatternEngine()

    private fun entry(model: String, ts: Instant, input: Long = 0, output: Long = 0,
                       cc5m: Long = 0, cc1h: Long = 0, cr: Long = 0): UsageEntry =
        UsageEntry(
            timestamp = ts, sessionId = "s", projectKey = "p",
            messageId = "m-${ts}-$model", requestId = "r-${ts}-$model",
            model = model,
            inputTokens = input, outputTokens = output,
            cacheCreation5m = cc5m, cacheCreation1h = cc1h, cacheRead = cr,
            sourceFile = Path.of("/x"), sourceLineByteOffset = 0
        )

    private fun session(id: String, cost: Double, first: Instant, last: Instant): SessionAggregate =
        SessionAggregate(
            sessionId = id, projectKey = "p", firstTs = first, lastTs = last,
            totals = TokenTotals(input = 100), cost = cost, models = setOf("claude-sonnet-4-6")
        )

    @Test
    fun `model hit ratio uses cache_read over read+input+create`() {
        val now = Instant.parse("2026-04-28T10:00:00Z")
        val entries = listOf(
            entry("claude-sonnet-4-6", now, input = 100, cc5m = 100, cr = 800),
            entry("claude-sonnet-4-6", now.plusSeconds(60), input = 100, cc5m = 0, cr = 0)
        )
        val ratios = engine.modelHitRatios(entries)
        assertThat(ratios).hasSize(1)
        // cache_read=800, denom = 800 + 200 + 100 = 1100, ratio ≈ 0.7273
        assertThat(ratios[0].ratio).isCloseTo(800.0 / 1100.0, within(1e-9))
    }

    @Test
    fun `heatmap buckets entries by zoned dayOfWeek and hour and ignores entries before lookback`() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-04-28T10:00:00Z") // Tuesday
        val entries = listOf(
            entry("m", Instant.parse("2026-04-28T10:30:00Z"), input = 50),    // Tue 10
            entry("m", Instant.parse("2026-04-27T22:15:00Z"), input = 70),    // Mon 22
            entry("m", Instant.parse("2025-04-28T10:00:00Z"), input = 9999)   // outside 30d lookback
        )
        val grid = engine.heatmap(entries, zone, now, Duration.ofDays(30))
        assertThat(grid[1][10]).isEqualTo(50L)  // Tuesday (=1) hour 10
        assertThat(grid[0][22]).isEqualTo(70L)  // Monday (=0) hour 22
        assertThat(grid.sumOf { it.sum() }).isEqualTo(120L)
    }

    @Test
    fun `p95Outliers returns sessions strictly above the 95th percentile of cost`() {
        val now = Instant.parse("2026-04-28T10:00:00Z")
        // 21 sessions with costs 0..20; p95 index = 20*0.95 = 19, so cut = 19, only the $20 one is above.
        val sessions = (0..20).map { i ->
            session("s$i", cost = i.toDouble(), first = now, last = now)
        }
        val out = engine.p95Outliers(sessions)
        assertThat(out.map { it.cost }).containsExactly(20.0)
    }

    @Test
    fun `topExpensive returns the n most expensive sessions`() {
        val now = Instant.parse("2026-04-28T10:00:00Z")
        val sessions = listOf(
            session("a", 1.0, now, now),
            session("b", 5.0, now, now),
            session("c", 2.0, now, now)
        )
        val top = engine.topExpensive(sessions, 2)
        assertThat(top.map { it.sessionId }).containsExactly("b", "c")
    }
}
