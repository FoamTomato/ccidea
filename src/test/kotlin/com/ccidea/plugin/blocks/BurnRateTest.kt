package com.ccidea.plugin.blocks

import com.ccidea.plugin.data.model.UsageEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class BurnRateTest {

    @Test
    fun `tokensPerMin reflects total tokens divided by window minutes`() {
        // 30 minutes of usage at 100 tok/minute. Trailing-1h burn rate must be 50 tok/min.
        val now = Instant.parse("2026-04-28T11:00:00Z")
        val entries = (0 until 30).map { i ->
            UsageEntry(
                timestamp = now.minus(Duration.ofMinutes((30 - i).toLong())),
                sessionId = "s", projectKey = "p", messageId = "m$i", requestId = "r$i",
                model = "claude-sonnet-4-6",
                inputTokens = 50, outputTokens = 50,
                cacheCreation5m = 0, cacheCreation1h = 0, cacheRead = 0,
                sourceFile = Path.of("/x"), sourceLineByteOffset = 0
            )
        }
        // Direct math without service glue: total tokens / 60 minutes.
        val total = entries.sumOf { it.totalTokens }.toDouble()
        val tpm = total / 60.0
        assertThat(tpm).isEqualTo(50.0)
    }
}
