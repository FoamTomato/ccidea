package com.ccidea.plugin.quota

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Pulls subscription utilization from Anthropic's `/api/oauth/usage` and caches the
 * result on disk so multiple IDEs (and multiple project windows in the same IDE) share
 * one fetch instead of each hitting the endpoint.
 *
 * Coordination protocol:
 *   • Cache file: ~/.claude/ccidea-quota-cache.json — { snapshot fields..., fetchedAt, failed }
 *   • SOFT_TTL (5min): re-fetch in the background, but keep using the cached value meanwhile
 *   • HARD_TTL (10min): cache is treated as expired (UI falls back to local estimate)
 *   • FAILURE_COOLDOWN (10min): if the last attempt failed, don't retry until cooldown elapses
 *   • Before any HTTP call we take an exclusive lock on a sibling .lock file and re-check the
 *     cache mtime — another process may have refreshed while we were queueing.
 *
 * Failures are intentionally silent (logged at debug level only); the UI never shows
 * an error banner, it just keeps the local estimate.
 */
@Service(Service.Level.APP)
class OfficialQuotaService {
    private val log = thisLogger()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** Snapshot the UI consumes. `failed=true` means the last fetch tried and failed (used
     *  for the cooldown gate); the percent fields are still null in that case. */
    data class Snapshot(
        val fiveHourPercent: Double?,
        val fiveHourResetsAt: Instant?,
        val sevenDayPercent: Double?,
        val sevenDayResetsAt: Instant?,
        val fetchedAt: Instant,
        val failed: Boolean = false
    )

    private val memSnapshot = AtomicReference<Snapshot?>(null)
    @Volatile private var inFlight: Boolean = false

    /** Returns the in-memory snapshot, lazily warming from disk on first access. Only
     *  yields a snapshot whose fetchedAt is within HARD_TTL — older data is dropped so
     *  the UI falls back to the local estimate instead of stale numbers. */
    fun current(now: Instant = Instant.now()): Snapshot? {
        val mem = memSnapshot.get() ?: readDiskCache()?.also { memSnapshot.set(it) }
        if (mem == null) return null
        if (mem.failed) return null
        if (Duration.between(mem.fetchedAt, now) >= HARD_TTL) return null
        return mem
    }

    /** Schedule a background refresh if the cache has aged past SOFT_TTL (and we aren't
     *  in a failure cooldown). Cheap; safe to call from the UI on every tick. */
    fun refreshIfStale(now: Instant = Instant.now()) {
        if (inFlight) return
        val mem = memSnapshot.get() ?: readDiskCache()?.also { memSnapshot.set(it) }
        if (mem != null) {
            val age = Duration.between(mem.fetchedAt, now)
            // Within SOFT_TTL on success: nothing to do
            if (!mem.failed && age < SOFT_TTL) return
            // In failure cooldown: don't hammer the endpoint
            if (mem.failed && age < FAILURE_COOLDOWN) return
        }
        inFlight = true
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                attemptCoordinatedFetch(now)
            } catch (t: Throwable) {
                log.debug("ccidea: quota refresh failed (silent)", t)
            } finally {
                inFlight = false
            }
        }
    }

    /** Take the cross-process lock; another IDE may have refreshed since we queued. */
    private fun attemptCoordinatedFetch(now: Instant) {
        ensureCacheDir()
        val lockPath = lockFilePath()
        RandomAccessFile(lockPath.toFile(), "rw").use { raf ->
            raf.channel.use { ch ->
                // Another IDE may already be fetching. Try a few times with backoff; if it
                // never frees up we just bail — when our peer succeeds, the next UI tick will
                // pick up the new disk cache.
                val lock: FileLock = acquireWithRetry(ch) ?: return
                try {
                    // Re-read the disk cache while holding the lock — another process may have
                    // just finished writing a fresh snapshot, in which case we adopt it and skip
                    // the HTTP call entirely.
                    val onDisk = readDiskCache()
                    if (onDisk != null) {
                        val age = Duration.between(onDisk.fetchedAt, now)
                        if (!onDisk.failed && age < SOFT_TTL) {
                            memSnapshot.set(onDisk)
                            return
                        }
                        if (onDisk.failed && age < FAILURE_COOLDOWN) {
                            memSnapshot.set(onDisk)
                            return
                        }
                    }
                    val fresh = doHttpFetch()
                    val toWrite = fresh ?: failureSnapshot()
                    memSnapshot.set(toWrite)
                    writeDiskCache(toWrite)
                } finally {
                    lock.release()
                }
            }
        }
    }

    private fun acquireWithRetry(ch: FileChannel): FileLock? {
        repeat(6) { attempt ->
            val lock = runCatching { ch.tryLock() }.getOrNull()
            if (lock != null) return lock
            try { Thread.sleep(250L * (attempt + 1)) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    private fun doHttpFetch(): Snapshot? {
        val token = ClaudeOAuthToken.read() ?: return null
        val req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/api/oauth/usage"))
            .header("Authorization", "Bearer $token")
            .header("anthropic-beta", "oauth-2025-04-20")
            .header("User-Agent", "ccidea-intellij-plugin")
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return null
        return parse(resp.body())
    }

    private fun parse(body: String): Snapshot? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val (fhPct, fhReset) = window(root, "five_hour")
        val (sdPct, sdReset) = window(root, "seven_day")
        Snapshot(
            fiveHourPercent = fhPct,
            fiveHourResetsAt = fhReset,
            sevenDayPercent = sdPct,
            sevenDayResetsAt = sdReset,
            fetchedAt = Instant.now(),
            failed = false
        )
    }.getOrNull()

    private fun window(root: JsonObject, key: String): Pair<Double?, Instant?> {
        val obj = root[key]?.jsonObject ?: return null to null
        val pct = obj["utilization"]?.jsonPrimitive?.doubleOrNull
        val reset = obj["resets_at"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return pct to reset
    }

    private fun failureSnapshot(): Snapshot = Snapshot(
        fiveHourPercent = null, fiveHourResetsAt = null,
        sevenDayPercent = null, sevenDayResetsAt = null,
        fetchedAt = Instant.now(), failed = true
    )

    // ------------------------------------------------------------------ disk cache

    private fun cacheFilePath(): Path =
        Paths.get(System.getProperty("user.home"), ".claude", "ccidea-quota-cache.json")

    private fun lockFilePath(): Path =
        Paths.get(System.getProperty("user.home"), ".claude", "ccidea-quota-cache.lock")

    private fun ensureCacheDir() {
        val dir = Paths.get(System.getProperty("user.home"), ".claude")
        if (!Files.isDirectory(dir)) Files.createDirectories(dir)
    }

    private fun readDiskCache(): Snapshot? = runCatching {
        val path = cacheFilePath()
        if (!Files.isRegularFile(path)) return@runCatching null
        val raw = Files.readString(path)
        if (raw.isBlank()) return@runCatching null
        val obj = json.parseToJsonElement(raw).jsonObject
        Snapshot(
            fiveHourPercent = obj["fiveHourPercent"]?.jsonPrimitive?.doubleOrNull,
            fiveHourResetsAt = obj["fiveHourResetsAt"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Instant.parse(it) }.getOrNull() },
            sevenDayPercent = obj["sevenDayPercent"]?.jsonPrimitive?.doubleOrNull,
            sevenDayResetsAt = obj["sevenDayResetsAt"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Instant.parse(it) }.getOrNull() },
            fetchedAt = obj["fetchedAt"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@runCatching null,
            failed = obj["failed"]?.jsonPrimitive?.boolean ?: false
        )
    }.getOrNull()

    private fun writeDiskCache(s: Snapshot) {
        runCatching {
            val obj = buildJsonObject {
                if (s.fiveHourPercent != null) put("fiveHourPercent", s.fiveHourPercent)
                if (s.fiveHourResetsAt != null) put("fiveHourResetsAt", s.fiveHourResetsAt.toString())
                if (s.sevenDayPercent != null) put("sevenDayPercent", s.sevenDayPercent)
                if (s.sevenDayResetsAt != null) put("sevenDayResetsAt", s.sevenDayResetsAt.toString())
                put("fetchedAt", s.fetchedAt.toString())
                put("failed", s.failed)
            }
            val tmp = cacheFilePath().resolveSibling("ccidea-quota-cache.json.tmp")
            Files.writeString(tmp, obj.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            // Atomic-ish rename so concurrent readers never see a half-written file
            Files.move(tmp, cacheFilePath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    companion object {
        /** Re-fetch in the background when cache is older than this. */
        private val SOFT_TTL: Duration = Duration.ofMinutes(5)
        /** Stop trusting the cache (and stop showing it) once it's older than this. */
        private val HARD_TTL: Duration = Duration.ofMinutes(10)
        /** After a failed fetch, wait at least this long before retrying. */
        private val FAILURE_COOLDOWN: Duration = Duration.ofMinutes(10)

        fun getInstance(): OfficialQuotaService =
            ApplicationManager.getApplication().service()
    }
}
