package com.ccidea.plugin.blocks

import com.ccidea.plugin.data.model.BurnRate
import com.ccidea.plugin.data.model.SessionBlock
import com.ccidea.plugin.data.model.TokenTotals
import com.ccidea.plugin.data.model.UsageEntry
import com.ccidea.plugin.pricing.PricingService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service(Service.Level.APP)
class BlockService {
    private val lock = ReentrantLock()
    private val allEntries = mutableListOf<UsageEntry>()
    @Volatile private var blocks: List<SessionBlock> = emptyList()

    fun ingest(delta: List<UsageEntry>) {
        if (delta.isEmpty()) return
        lock.withLock {
            allEntries += delta
            allEntries.sortBy { it.timestamp }
            val pricing = PricingService.getInstance()
            blocks = computeBlocks(allEntries, Instant.now()) { e -> pricing.costFor(e).total }
        }
    }

    fun reset() = lock.withLock {
        allEntries.clear()
        blocks = emptyList()
    }

    /** Re-run block segmentation + cost computation against the current entry list. */
    fun recomputeCosts() = lock.withLock {
        if (allEntries.isEmpty()) return@withLock
        val pricing = PricingService.getInstance()
        blocks = computeBlocks(allEntries, Instant.now()) { e -> pricing.costFor(e).total }
    }

    fun all(): List<UsageEntry> = lock.withLock { allEntries.toList() }

    fun allBlocks(limit: Int = Int.MAX_VALUE): List<SessionBlock> =
        blocks.takeLast(limit)

    fun currentBlock(): SessionBlock? = blocks.lastOrNull { !it.isGap && it.isActive }

    fun timeUntilReset(now: Instant = Instant.now()): Duration? =
        currentBlock()?.let { Duration.between(now, it.endTime) }

    /** Trailing-window burn rate over recent entries. */
    fun burnRate(window: Duration, now: Instant = Instant.now()): BurnRate {
        val cutoff = now.minus(window)
        val pricing = PricingService.getInstance()
        val recent = lock.withLock { allEntries.filter { it.timestamp >= cutoff } }
        if (recent.isEmpty()) return BurnRate(0.0, 0.0, window)
        val tokens = recent.sumOf { it.totalTokens }.toDouble()
        val cost = recent.sumOf { pricing.costFor(it).total }
        val minutes = window.toMinutes().coerceAtLeast(1).toDouble()
        return BurnRate(tokens / minutes, cost * 60.0 / minutes, window)
    }

    fun detectedTokenLimit(): Long {
        val finished = blocks.filter { !it.isGap && !it.isActive }
        return finished.maxOfOrNull { it.totalTokens } ?: 0L
    }

    fun projectedTokensForCurrent(now: Instant = Instant.now()): Long? {
        val b = currentBlock() ?: return null
        val br = burnRate(Duration.ofMinutes(60), now)
        val minutesLeft = Duration.between(now, b.endTime).toMinutes().coerceAtLeast(0)
        return b.totalTokens + (br.tokensPerMin * minutesLeft).toLong()
    }

    companion object {
        val SESSION_DURATION: Duration = Duration.ofHours(5)

        fun getInstance(): BlockService = ApplicationManager.getApplication().service()

        /**
         * Pure function exposed for tests. Mirrors ccusage:
         * - block.startTime = floor(firstEntry.timestamp, HOURS) (UTC)
         * - close when (entry - blockStart > 5h) OR (entry - lastEntry > 5h)
         * - if gap > 5h, insert a gap pseudo-block
         * - last block is active if (now - actualEnd) < 5h && now < endTime
         */
        fun computeBlocks(
            entriesSorted: List<UsageEntry>,
            now: Instant,
            costFn: (UsageEntry) -> Double = { 0.0 }
        ): List<SessionBlock> {
            if (entriesSorted.isEmpty()) return emptyList()
            val out = mutableListOf<SessionBlock>()
            var blockStart: Instant = entriesSorted.first().timestamp.truncatedTo(ChronoUnit.HOURS)
            var blockEntries = mutableListOf<UsageEntry>()
            var lastTs: Instant = entriesSorted.first().timestamp

            fun close(active: Boolean) {
                if (blockEntries.isEmpty()) return
                val totals = blockEntries.fold(TokenTotals()) { a, e -> a + e }
                val cost = blockEntries.sumOf(costFn)
                val models = blockEntries.map { it.model }.toSet()
                out += SessionBlock(
                    startTime = blockStart,
                    endTime = blockStart.plus(SESSION_DURATION),
                    actualEndTime = blockEntries.last().timestamp,
                    isActive = active,
                    isGap = false,
                    entries = blockEntries.toList(),
                    totals = totals,
                    totalCost = cost,
                    models = models
                )
            }

            for (entry in entriesSorted) {
                val ts = entry.timestamp
                val sinceStart = Duration.between(blockStart, ts)
                val sinceLast = Duration.between(lastTs, ts)
                val shouldClose = (blockEntries.isNotEmpty()) &&
                    (sinceStart > SESSION_DURATION || sinceLast > SESSION_DURATION)
                if (shouldClose) {
                    close(active = false)
                    if (sinceLast > SESSION_DURATION) {
                        out += SessionBlock(
                            startTime = lastTs.plus(SESSION_DURATION),
                            endTime = ts.truncatedTo(ChronoUnit.HOURS),
                            actualEndTime = ts.truncatedTo(ChronoUnit.HOURS),
                            isActive = false,
                            isGap = true,
                            entries = emptyList(),
                            totals = TokenTotals(),
                            totalCost = 0.0,
                            models = emptySet()
                        )
                    }
                    blockStart = ts.truncatedTo(ChronoUnit.HOURS)
                    blockEntries = mutableListOf()
                }
                blockEntries += entry
                lastTs = ts
            }

            if (blockEntries.isNotEmpty()) {
                val active = (Duration.between(lastTs, now) < SESSION_DURATION) &&
                    now.isBefore(blockStart.plus(SESSION_DURATION))
                close(active = active)
            }
            return out
        }
    }
}
