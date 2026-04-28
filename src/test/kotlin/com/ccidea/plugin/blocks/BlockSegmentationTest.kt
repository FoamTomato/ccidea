package com.ccidea.plugin.blocks

import com.ccidea.plugin.data.model.UsageEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class BlockSegmentationTest {

    private fun entry(ts: String): UsageEntry = UsageEntry(
        timestamp = Instant.parse(ts),
        sessionId = "s", projectKey = "p", messageId = "m-$ts", requestId = "r",
        model = "claude-sonnet-4-6",
        inputTokens = 10, outputTokens = 5,
        cacheCreation5m = 0, cacheCreation1h = 0, cacheRead = 0,
        sourceFile = Path.of("/x"), sourceLineByteOffset = 0
    )

    @Test
    fun `floors first entry to UTC hour`() {
        val entries = listOf(entry("2026-04-28T10:37:42Z"), entry("2026-04-28T11:00:00Z"))
        val now = Instant.parse("2026-04-28T11:30:00Z")
        val blocks = BlockService.computeBlocks(entries, now)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0].startTime).isEqualTo(Instant.parse("2026-04-28T10:00:00Z"))
        assertThat(blocks[0].endTime).isEqualTo(Instant.parse("2026-04-28T15:00:00Z"))
        assertThat(blocks[0].isActive).isTrue
    }

    @Test
    fun `gap longer than 5h splits into two blocks plus a gap block`() {
        val entries = listOf(
            entry("2026-04-28T10:00:00Z"),
            entry("2026-04-28T11:00:00Z"),
            entry("2026-04-28T20:00:00Z") // 9h after the 11:00 entry
        )
        val now = Instant.parse("2026-04-28T20:30:00Z")
        val blocks = BlockService.computeBlocks(entries, now)
        assertThat(blocks).hasSize(3)
        assertThat(blocks[0].isGap).isFalse
        assertThat(blocks[1].isGap).isTrue
        assertThat(blocks[2].isGap).isFalse
        assertThat(blocks.count { it.isActive && !it.isGap }).isEqualTo(1)
    }

    @Test
    fun `closes block when more than 5h elapses since blockStart even without gap`() {
        val entries = listOf(
            entry("2026-04-28T10:00:00Z"),
            entry("2026-04-28T13:00:00Z"),
            entry("2026-04-28T15:30:00Z") // 5h30m after blockStart 10:00
        )
        val now = Instant.parse("2026-04-28T16:00:00Z")
        val blocks = BlockService.computeBlocks(entries, now).filter { !it.isGap }
        assertThat(blocks).hasSize(2)
    }
}
