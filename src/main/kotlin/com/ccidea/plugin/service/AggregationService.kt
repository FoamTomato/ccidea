package com.ccidea.plugin.service

import com.ccidea.plugin.blocks.BlockService
import com.ccidea.plugin.data.model.DailyAggregate
import com.ccidea.plugin.data.model.MonthlyAggregate
import com.ccidea.plugin.data.model.SessionAggregate
import com.ccidea.plugin.data.model.TokenTotals
import com.ccidea.plugin.data.model.UsageEntry
import com.ccidea.plugin.pricing.PricingService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@Service(Service.Level.APP)
class AggregationService {

    fun daily(zone: ZoneId = ZoneId.systemDefault()): List<DailyAggregate> {
        val pricing = PricingService.getInstance()
        val groups = BlockService.getInstance().all().groupBy { it.timestamp.atZone(zone).toLocalDate() }
        return groups.entries
            .map { (date, entries) -> dailyOf(date, entries, pricing) }
            .sortedByDescending { it.date }
    }

    fun monthly(zone: ZoneId = ZoneId.systemDefault()): List<MonthlyAggregate> {
        val pricing = PricingService.getInstance()
        val groups = BlockService.getInstance().all().groupBy {
            val ld = it.timestamp.atZone(zone).toLocalDate(); YearMonth.from(ld)
        }
        return groups.entries
            .map { (ym, entries) -> monthlyOf(ym, entries, pricing) }
            .sortedByDescending { it.yearMonth }
    }

    fun sessions(): List<SessionAggregate> {
        val pricing = PricingService.getInstance()
        val groups = BlockService.getInstance().all().groupBy { it.sessionId }
        return groups.entries
            .map { (sid, entries) ->
                val totals = entries.fold(TokenTotals()) { a, e -> a + e }
                val cost = entries.sumOf { pricing.costFor(it).total }
                SessionAggregate(
                    sessionId = sid,
                    projectKey = entries.first().projectKey,
                    firstTs = entries.minOf { it.timestamp },
                    lastTs = entries.maxOf { it.timestamp },
                    totals = totals,
                    cost = cost,
                    models = entries.map { it.model }.toSet()
                )
            }
            .sortedByDescending { it.lastTs }
    }

    private fun dailyOf(date: LocalDate, entries: List<UsageEntry>, pricing: PricingService): DailyAggregate {
        val totals = entries.fold(TokenTotals()) { a, e -> a + e }
        val cost = entries.sumOf { pricing.costFor(it).total }
        return DailyAggregate(date, totals, cost, entries.map { it.model }.toSet())
    }

    private fun monthlyOf(ym: YearMonth, entries: List<UsageEntry>, pricing: PricingService): MonthlyAggregate {
        val totals = entries.fold(TokenTotals()) { a, e -> a + e }
        val cost = entries.sumOf { pricing.costFor(it).total }
        return MonthlyAggregate(ym, totals, cost, entries.map { it.model }.toSet())
    }

    companion object {
        fun getInstance(): AggregationService = ApplicationManager.getApplication().service()
    }
}

/** Trivial helper used by UI for "Δ vs yesterday" lines. */
fun Instant?.orEpoch(): Instant = this ?: Instant.EPOCH
