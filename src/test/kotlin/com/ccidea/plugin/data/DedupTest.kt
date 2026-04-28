package com.ccidea.plugin.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DedupTest {

    @Test
    fun `same messageId and requestId produces a single dedupKey`() {
        val line = """{"timestamp":"2026-04-28T10:00:00Z","sessionId":"s","requestId":"r","message":{"id":"m","model":"x","usage":{"input_tokens":1,"output_tokens":1}}}"""
        val a = JsonlParser.parseLine(line, Path.of("/x"), 0, "p")
        val b = JsonlParser.parseLine(line, Path.of("/x"), 99, "p")
        assertThat(a?.dedupKey).isEqualTo(b?.dedupKey)
    }

    @Test
    fun `different requestId gives different dedup key`() {
        val l1 = """{"timestamp":"2026-04-28T10:00:00Z","sessionId":"s","requestId":"r1","message":{"id":"m","model":"x","usage":{"input_tokens":1,"output_tokens":1}}}"""
        val l2 = """{"timestamp":"2026-04-28T10:00:00Z","sessionId":"s","requestId":"r2","message":{"id":"m","model":"x","usage":{"input_tokens":1,"output_tokens":1}}}"""
        val a = JsonlParser.parseLine(l1, Path.of("/x"), 0, "p")!!
        val b = JsonlParser.parseLine(l2, Path.of("/x"), 0, "p")!!
        assertThat(a.dedupKey).isNotEqualTo(b.dedupKey)
    }
}
