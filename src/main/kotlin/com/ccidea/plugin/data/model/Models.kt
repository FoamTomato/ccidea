package com.ccidea.plugin.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

@Serializable
data class RawJsonlRecord(
    val timestamp: String? = null,
    val sessionId: String? = null,
    val requestId: String? = null,
    val cwd: String? = null,
    val message: RawMessage? = null
)

@Serializable
data class RawMessage(
    val id: String? = null,
    val model: String? = null,
    val usage: RawUsage? = null
)

@Serializable
data class RawUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
    @SerialName("cache_creation") val cacheCreation: CacheCreationSplit? = null
)

@Serializable
data class CacheCreationSplit(
    @SerialName("ephemeral_1h_input_tokens") val oneHour: Long = 0,
    @SerialName("ephemeral_5m_input_tokens") val fiveMin: Long = 0
)

data class UsageEntry(
    val timestamp: Instant,
    val sessionId: String,
    val projectKey: String,
    val messageId: String,
    val requestId: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreation5m: Long,
    val cacheCreation1h: Long,
    val cacheRead: Long,
    val sourceFile: Path,
    val sourceLineByteOffset: Long
) {
    val cacheCreationTotal: Long get() = cacheCreation5m + cacheCreation1h
    val totalTokens: Long get() = inputTokens + outputTokens + cacheCreationTotal + cacheRead
    val dedupKey: String get() = "$messageId:$requestId"
}

data class Pricing(
    val model: String,
    val inputPerToken: Double,
    val outputPerToken: Double,
    val cacheCreate5mPerToken: Double,
    val cacheReadPerToken: Double
) {
    fun cacheCreate1hPerToken(): Double = cacheCreate5mPerToken * 2.0
}

data class Cost(
    val input: Double,
    val output: Double,
    val cacheCreate: Double,
    val cacheRead: Double
) {
    val total: Double get() = input + output + cacheCreate + cacheRead

    operator fun plus(other: Cost) = Cost(
        input + other.input,
        output + other.output,
        cacheCreate + other.cacheCreate,
        cacheRead + other.cacheRead
    )

    companion object { val ZERO = Cost(0.0, 0.0, 0.0, 0.0) }
}

data class TokenTotals(
    val input: Long = 0,
    val output: Long = 0,
    val cacheCreate5m: Long = 0,
    val cacheCreate1h: Long = 0,
    val cacheRead: Long = 0
) {
    val total: Long get() = input + output + cacheCreate5m + cacheCreate1h + cacheRead

    operator fun plus(e: UsageEntry) = TokenTotals(
        input + e.inputTokens,
        output + e.outputTokens,
        cacheCreate5m + e.cacheCreation5m,
        cacheCreate1h + e.cacheCreation1h,
        cacheRead + e.cacheRead
    )

    operator fun plus(o: TokenTotals) = TokenTotals(
        input + o.input,
        output + o.output,
        cacheCreate5m + o.cacheCreate5m,
        cacheCreate1h + o.cacheCreate1h,
        cacheRead + o.cacheRead
    )
}

data class SessionBlock(
    val startTime: Instant,
    val endTime: Instant,
    val actualEndTime: Instant?,
    val isActive: Boolean,
    val isGap: Boolean,
    val entries: List<UsageEntry>,
    val totals: TokenTotals,
    val totalCost: Double,
    val models: Set<String>
) {
    val totalTokens: Long get() = totals.total
}

data class DailyAggregate(
    val date: LocalDate,
    val totals: TokenTotals,
    val cost: Double,
    val models: Set<String>
)

data class MonthlyAggregate(
    val yearMonth: YearMonth,
    val totals: TokenTotals,
    val cost: Double,
    val models: Set<String>
)

data class SessionAggregate(
    val sessionId: String,
    val projectKey: String,
    val firstTs: Instant,
    val lastTs: Instant,
    val totals: TokenTotals,
    val cost: Double,
    val models: Set<String>
)

data class BurnRate(val tokensPerMin: Double, val costPerHour: Double, val window: Duration)

data class LoadDelta(val newEntries: List<UsageEntry>, val fullRescan: Boolean = false) {
    val isEmpty: Boolean get() = newEntries.isEmpty()
}
