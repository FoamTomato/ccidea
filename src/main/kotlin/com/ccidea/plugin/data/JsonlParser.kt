package com.ccidea.plugin.data

import com.ccidea.plugin.data.model.RawJsonlRecord
import com.ccidea.plugin.data.model.UsageEntry
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant

object JsonlParser {
    private val log = thisLogger()
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * Parse a single JSONL line into a UsageEntry, or null if the line should be skipped
     * (no usage info, missing fields, parse error). [byteOffset] is the offset of the
     * START of this line within [file].
     */
    fun parseLine(line: String, file: Path, byteOffset: Long, projectKey: String): UsageEntry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        val raw: RawJsonlRecord = try {
            json.decodeFromString(RawJsonlRecord.serializer(), trimmed)
        } catch (t: Throwable) {
            log.debug("ccidea: skipping unparseable JSONL line in $file at offset $byteOffset: ${t.message}")
            return null
        }

        val ts = raw.timestamp ?: return null
        val msg = raw.message ?: return null
        val usage = msg.usage ?: return null
        val messageId = msg.id ?: return null
        val requestId = raw.requestId ?: return null
        val sessionId = raw.sessionId ?: return null
        val model = msg.model ?: return null

        val cc = usage.cacheCreation
        val oneHour = cc?.oneHour ?: 0L
        val fiveMin = cc?.fiveMin ?: usage.cacheCreationInputTokens
        // If split absent but flat field present, attribute everything to 5m bucket.

        val instant = try { Instant.parse(ts) } catch (t: Throwable) {
            log.debug("ccidea: bad timestamp '$ts' in $file"); return null
        }

        return UsageEntry(
            timestamp = instant,
            sessionId = sessionId,
            projectKey = projectKey,
            messageId = messageId,
            requestId = requestId,
            model = model,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cacheCreation5m = fiveMin,
            cacheCreation1h = oneHour,
            cacheRead = usage.cacheReadInputTokens,
            sourceFile = file,
            sourceLineByteOffset = byteOffset
        )
    }
}
