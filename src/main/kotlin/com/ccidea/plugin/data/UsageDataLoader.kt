package com.ccidea.plugin.data

import com.ccidea.plugin.data.model.LoadDelta
import com.ccidea.plugin.data.model.UsageEntry
import com.ccidea.plugin.settings.CcideaSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Walks ~/.claude/projects recursively for jsonl files and tail-reads new lines
 * since the last poll. Maintains per-file byte offsets (persisted) and a LRU dedup set.
 */
@Service(Service.Level.APP)
class UsageDataLoader {
    private val log = thisLogger()
    private val lock = ReentrantLock()

    /** path string -> offset cache. Mirrors LoaderOffsetState but in-memory for hot path. */
    private val offsets = mutableMapOf<String, LoaderOffsetState.FileOffset>()

    /** Bounded LRU dedup set keyed by "$messageId:$requestId". */
    private val seen: MutableSet<String> = Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>?): Boolean = size > 500_000
        }
    )

    private var initialized = false

    private fun lazyInit() {
        if (initialized) return
        ApplicationManager.getApplication().service<LoaderOffsetState>().snapshot().forEach { (k, v) ->
            offsets[k] = v
        }
        initialized = true
    }

    fun pollIncremental(): LoadDelta = lock.withLock {
        lazyInit()
        val root = resolveRoot() ?: return LoadDelta(emptyList())
        val newEntries = mutableListOf<UsageEntry>()
        val files = walkJsonl(root)
        for (file in files) {
            try {
                readNewLines(file, newEntries)
            } catch (t: Throwable) {
                log.warn("ccidea: error reading $file", t)
            }
        }
        persistOffsets()
        LoadDelta(newEntries)
    }

    fun rescan(): LoadDelta = lock.withLock {
        offsets.clear()
        seen.clear()
        ApplicationManager.getApplication().service<LoaderOffsetState>().clear()
        initialized = true
        val delta = pollIncremental()
        delta.copy(fullRescan = true)
    }

    fun resetOffsets() = lock.withLock {
        offsets.clear()
        seen.clear()
        initialized = true
        ApplicationManager.getApplication().service<LoaderOffsetState>().clear()
    }

    /** Returns the projects/ root, or null if it doesn't exist yet. */
    private fun resolveRoot(): Path? {
        val settings = CcideaSettings.getInstance().state
        val base = settings.claudeConfigDir.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home") + "/.claude"
        val root = Paths.get(base, "projects")
        return root.takeIf { Files.isDirectory(it) }
    }

    private fun walkJsonl(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "jsonl" }.toList()
        }
    }

    private fun readNewLines(file: Path, sink: MutableList<UsageEntry>) {
        val pathStr = file.toString()
        val sizeNow = Files.size(file)
        val mtimeNow = Files.getLastModifiedTime(file).toMillis()
        val tracked = offsets[pathStr]

        val startOffset = if (tracked != null && tracked.sizeBytes > sizeNow) 0L else tracked?.offset ?: 0L
        if (startOffset >= sizeNow) {
            offsets[pathStr] = LoaderOffsetState.FileOffset(pathStr, startOffset, sizeNow, mtimeNow)
            return
        }

        val projectKey = file.parent?.fileName?.toString() ?: ""
        var lineStart = startOffset
        var cursor = startOffset
        val buf = StringBuilder()

        RandomAccessFile(file.toFile(), "r").use { raf ->
            raf.seek(startOffset)
            val byteBuf = ByteArray(64 * 1024)
            while (true) {
                val read = raf.read(byteBuf)
                if (read <= 0) break
                for (i in 0 until read) {
                    val b = byteBuf[i].toInt() and 0xFF
                    cursor++
                    if (b == 0x0A) {
                        val line = buf.toString().trimEnd('\r')
                        buf.setLength(0)
                        val entry = JsonlParser.parseLine(line, file, lineStart, projectKey)
                        if (entry != null && seen.add(entry.dedupKey)) sink += entry
                        lineStart = cursor
                    } else {
                        buf.append(b.toChar())
                    }
                }
            }
        }
        // lineStart now points just past the last confirmed '\n'; any unterminated trailing
        // bytes will be re-read on the next poll once they are flushed with a newline.
        offsets[pathStr] = LoaderOffsetState.FileOffset(pathStr, lineStart, sizeNow, mtimeNow)
    }

    private fun persistOffsets() {
        ApplicationManager.getApplication().service<LoaderOffsetState>().replaceAll(offsets)
    }

    companion object {
        fun getInstance(): UsageDataLoader =
            ApplicationManager.getApplication().service()
    }
}
