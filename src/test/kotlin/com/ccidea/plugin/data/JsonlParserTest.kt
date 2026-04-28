package com.ccidea.plugin.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class JsonlParserTest {

    @Test
    fun `parses basic record with cache_creation split`() {
        val line = """{"timestamp":"2026-04-28T10:00:00Z","sessionId":"s","requestId":"r","message":{"id":"m","model":"claude-sonnet-4-6","usage":{"input_tokens":100,"output_tokens":50,"cache_creation_input_tokens":200,"cache_read_input_tokens":300,"cache_creation":{"ephemeral_1h_input_tokens":120,"ephemeral_5m_input_tokens":80}}}}"""
        val e = JsonlParser.parseLine(line, Path.of("/fixture.jsonl"), 0L, "proj")
        assertThat(e).isNotNull
        e!!
        assertThat(e.timestamp).isEqualTo(Instant.parse("2026-04-28T10:00:00Z"))
        assertThat(e.inputTokens).isEqualTo(100L)
        assertThat(e.outputTokens).isEqualTo(50L)
        assertThat(e.cacheCreation5m).isEqualTo(80L)
        assertThat(e.cacheCreation1h).isEqualTo(120L)
        assertThat(e.cacheRead).isEqualTo(300L)
        assertThat(e.dedupKey).isEqualTo("m:r")
    }

    @Test
    fun `falls back to flat cache_creation_input_tokens when split is absent`() {
        val line = """{"timestamp":"2026-04-28T10:00:00Z","sessionId":"s","requestId":"r","message":{"id":"m","model":"claude-sonnet-4-6","usage":{"input_tokens":1,"output_tokens":1,"cache_creation_input_tokens":500,"cache_read_input_tokens":0}}}"""
        val e = JsonlParser.parseLine(line, Path.of("/x.jsonl"), 0L, "p")!!
        assertThat(e.cacheCreation5m).isEqualTo(500L)
        assertThat(e.cacheCreation1h).isEqualTo(0L)
    }

    @Test
    fun `returns null for line missing requestId or messageId`() {
        val noReq = """{"timestamp":"2026-04-28T10:00:00Z","sessionId":"s","message":{"id":"m","model":"x","usage":{"input_tokens":1,"output_tokens":1}}}"""
        val noMsgId = """{"timestamp":"2026-04-28T10:00:00Z","sessionId":"s","requestId":"r","message":{"model":"x","usage":{"input_tokens":1,"output_tokens":1}}}"""
        assertThat(JsonlParser.parseLine(noReq, Path.of("/x"), 0, "p")).isNull()
        assertThat(JsonlParser.parseLine(noMsgId, Path.of("/x"), 0, "p")).isNull()
    }

    @Test
    fun `returns null for malformed JSON`() {
        val e = JsonlParser.parseLine("{broken", Path.of("/x"), 0, "p")
        assertThat(e).isNull()
    }
}
