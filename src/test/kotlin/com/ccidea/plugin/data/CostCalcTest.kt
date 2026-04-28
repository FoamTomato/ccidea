package com.ccidea.plugin.data

import com.ccidea.plugin.data.model.Pricing
import com.ccidea.plugin.data.model.UsageEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class CostCalcTest {

    @Test
    fun `1h cache costs 2x the 5m rate`() {
        val p = Pricing(
            model = "claude-opus-4-7",
            inputPerToken = 1.0e-6,
            outputPerToken = 5.0e-6,
            cacheCreate5mPerToken = 1.0e-6,
            cacheReadPerToken = 1.0e-7
        )
        val e = UsageEntry(
            timestamp = Instant.parse("2026-04-28T10:00:00Z"),
            sessionId = "s", projectKey = "p", messageId = "m", requestId = "r",
            model = "claude-opus-4-7",
            inputTokens = 0, outputTokens = 0,
            cacheCreation5m = 0, cacheCreation1h = 1000, cacheRead = 0,
            sourceFile = Path.of("/x"), sourceLineByteOffset = 0
        )
        val cc = e.cacheCreation5m * p.cacheCreate5mPerToken +
            e.cacheCreation1h * p.cacheCreate1hPerToken()
        assertThat(cc).isEqualTo(1000 * 2.0e-6)
    }
}
