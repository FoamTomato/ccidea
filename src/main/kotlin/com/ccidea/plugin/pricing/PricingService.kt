package com.ccidea.plugin.pricing

import com.ccidea.plugin.data.model.Cost
import com.ccidea.plugin.data.model.Pricing
import com.ccidea.plugin.data.model.UsageEntry
import com.ccidea.plugin.settings.CcideaSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service(Service.Level.APP)
class PricingService {
    private val log = thisLogger()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val lock = ReentrantLock()

    @Volatile private var table: Map<String, Pricing> = emptyMap()
    @Volatile private var loadedAt: Instant? = null
    private val warnedUnknown = ConcurrentHashMap.newKeySet<String>()
    private val resolveCache = ConcurrentHashMap<String, Pricing>()

    fun ensureLoaded() {
        if (table.isNotEmpty()) return
        lock.withLock {
            if (table.isNotEmpty()) return
            val raw = readBest()
            table = parse(raw)
            loadedAt = Instant.now()
        }
    }

    fun lastFetchedAt(): Instant? = loadedAt

    private fun readBest(): String {
        val cacheFile = cachePath()
        val settings = CcideaSettings.getInstance().state
        val cacheFresh = Files.exists(cacheFile) &&
            Duration.between(Files.getLastModifiedTime(cacheFile).toInstant(), Instant.now())
                .compareTo(Duration.ofHours(24)) < 0
        if (cacheFresh) {
            return runCatching { Files.readString(cacheFile) }.getOrElse { fallbackResource() }
        }
        if (!settings.offlineOnly) {
            try {
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                val req = HttpRequest.newBuilder(URI.create(LITELLM_URL))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    runCatching {
                        Files.createDirectories(cacheFile.parent)
                        val tmp = cacheFile.resolveSibling(cacheFile.fileName.toString() + ".tmp")
                        Files.writeString(tmp, resp.body())
                        Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                    return resp.body()
                }
            } catch (t: Throwable) {
                log.info("ccidea: pricing fetch failed (${t.javaClass.simpleName}: ${t.message}); using cache/fallback")
            }
        }
        return runCatching { Files.readString(cacheFile) }.getOrElse { fallbackResource() }
    }

    private fun cachePath(): Path =
        Paths.get(PathManager.getSystemPath()).resolve("ccidea").resolve("pricing.json")

    private fun fallbackResource(): String {
        val stream = javaClass.getResourceAsStream("/pricing/litellm-fallback.json")
            ?: return "{}"
        return stream.bufferedReader().use { it.readText() }
    }

    private fun parse(text: String): Map<String, Pricing> {
        val root = try { json.parseToJsonElement(text).jsonObject } catch (t: Throwable) {
            log.warn("ccidea: pricing JSON parse failed: ${t.message}"); return emptyMap()
        }
        val out = HashMap<String, Pricing>(root.size)
        for ((name, el) in root) {
            val obj = el as? JsonObject ?: continue
            // Some entries are not models (e.g. "sample_spec"). Skip if no input cost field.
            val input = obj.numField("input_cost_per_token") ?: continue
            val output = obj.numField("output_cost_per_token") ?: 0.0
            val cw5m = obj.numField("cache_creation_input_token_cost") ?: (input * 1.25)
            val cr = obj.numField("cache_read_input_token_cost") ?: (input * 0.10)
            out[name] = Pricing(name, input, output, cw5m, cr)
        }
        return out
    }

    private fun JsonObject.numField(key: String): Double? =
        (this[key] as? JsonElement)?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    fun resolve(model: String): Pricing? {
        ensureLoaded()
        resolveCache[model]?.let { return it }
        val candidates = buildList {
            add(model)
            add("anthropic/$model")
            if (!model.startsWith("claude-")) add("claude-$model")
            // Strip trailing -YYYYMMDD if present.
            val stripped = model.replace(Regex("-\\d{8}$"), "")
            if (stripped != model) {
                add(stripped); add("anthropic/$stripped")
            }
        }
        for (c in candidates) table[c]?.let {
            resolveCache[model] = it
            return it
        }
        // Substring fallback: pick the first key that contains the model name.
        val fuzzy = table.entries.firstOrNull { (k, _) -> k.endsWith(model) || k.endsWith("/$model") }
        if (fuzzy != null) {
            resolveCache[model] = fuzzy.value
            return fuzzy.value
        }
        if (warnedUnknown.add(model)) log.info("ccidea: no pricing for model '$model'; cost will be 0")
        return null
    }

    fun costFor(e: UsageEntry): Cost {
        val p = resolve(e.model) ?: return Cost.ZERO
        val premium = CcideaSettings.getInstance().state.oneHourCachePremium
        val cc1hRate = if (premium) p.cacheCreate1hPerToken() else p.cacheCreate5mPerToken
        val cc = e.cacheCreation5m * p.cacheCreate5mPerToken + e.cacheCreation1h * cc1hRate
        return Cost(
            input = e.inputTokens * p.inputPerToken,
            output = e.outputTokens * p.outputPerToken,
            cacheCreate = cc,
            cacheRead = e.cacheRead * p.cacheReadPerToken
        )
    }

    companion object {
        private const val LITELLM_URL =
            "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"

        fun getInstance(): PricingService = ApplicationManager.getApplication().service()
    }
}
