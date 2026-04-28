package com.ccidea.plugin.poller

import com.ccidea.plugin.blocks.BlockService
import com.ccidea.plugin.data.UsageDataLoader
import com.ccidea.plugin.notifications.CcideaNotifier
import com.ccidea.plugin.service.CcideaBus
import com.ccidea.plugin.service.RefreshListener
import com.ccidea.plugin.settings.CcideaSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.APP)
class PollerService : Disposable {
    private val log = thisLogger()
    private var future: ScheduledFuture<*>? = null
    @Volatile private var lastUpdated: Instant? = null
    @Volatile private var toolWindowVisible: Boolean = false
    private val watcherStarted = AtomicBoolean(false)
    private val pendingNudge = AtomicLong(0)

    fun setToolWindowVisible(visible: Boolean) {
        val wasVisible = toolWindowVisible
        toolWindowVisible = visible
        // When becoming visible, refresh immediately so the user sees current data.
        if (visible && !wasVisible) runOnce()
    }

    fun start() {
        if (future != null) return
        scheduleAtCurrentInterval()
        startWatcher()
    }

    fun applySettings() {
        future?.cancel(false)
        future = null
        scheduleAtCurrentInterval()
    }

    fun lastUpdated(): Instant? = lastUpdated

    fun runOnce() {
        AppExecutorUtil.getAppScheduledExecutorService().execute { tick(forced = true) }
    }

    private fun scheduleAtCurrentInterval() {
        val period = CcideaSettings.getInstance().state.refreshSeconds.coerceAtLeast(5).toLong()
        future = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ tick(forced = false) }, 0L, period, TimeUnit.SECONDS)
    }

    private fun tick(forced: Boolean = false) {
        // R6: skip scheduled ticks while the tool window is hidden, unless the user
        // disabled this. Manual refresh runs with forced=true.
        val s = CcideaSettings.getInstance().state
        if (!forced && s.pollOnlyWhenVisible && !toolWindowVisible) return
        try {
            val loader = UsageDataLoader.getInstance()
            val delta = loader.pollIncremental()
            if (delta.newEntries.isNotEmpty() || delta.fullRescan) {
                BlockService.getInstance().ingest(delta.newEntries)
            }
            val notifier = CcideaNotifier.getInstance()
            val block = BlockService.getInstance().currentBlock()
            val limit = run {
                val custom = CcideaSettings.getInstance().state.customTokenLimit
                if (custom > 0) custom else BlockService.getInstance().detectedTokenLimit()
            }
            notifier.evaluate(block, limit)

            lastUpdated = Instant.now()
            val at = lastUpdated!!
            ApplicationManager.getApplication().invokeLater(
                {
                    ApplicationManager.getApplication().messageBus
                        .syncPublisher(CcideaBus.TOPIC).refreshed(at)
                },
                ModalityState.any()
            )
        } catch (t: Throwable) {
            log.warn("ccidea poller tick failed", t)
        }
    }

    /** Top-level WatchService for `~/.claude/projects/`. macOS recursive watch is unreliable;
     *  treat the watcher as a heuristic nudge and rely on the 30s heartbeat as the source of truth. */
    private fun startWatcher() {
        if (!watcherStarted.compareAndSet(false, true)) return
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val base = CcideaSettings.getInstance().state.claudeConfigDir.takeIf { it.isNotBlank() }
                    ?: (System.getProperty("user.home") + "/.claude")
                val root = Paths.get(base, "projects")
                if (!Files.isDirectory(root)) return@execute
                val ws: WatchService = root.fileSystem.newWatchService()
                val keys = mutableMapOf<WatchKey, java.nio.file.Path>()
                Files.list(root).use { stream ->
                    stream.filter { Files.isDirectory(it) }.forEach { dir ->
                        try {
                            val k = dir.register(ws,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY)
                            keys[k] = dir
                        } catch (_: Throwable) { /* ignore single-dir failures */ }
                    }
                }
                val rootKey = root.register(ws, StandardWatchEventKinds.ENTRY_CREATE)
                keys[rootKey] = root
                while (!Thread.currentThread().isInterrupted) {
                    val key = ws.take() ?: break
                    val pollEvents = key.pollEvents()
                    if (pollEvents.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        if (pendingNudge.getAndSet(now + 200) == 0L) {
                            AppExecutorUtil.getAppScheduledExecutorService()
                                .schedule({ pendingNudge.set(0); tick(forced = false) }, 200, TimeUnit.MILLISECONDS)
                        }
                    }
                    val dir = keys[key]
                    if (dir != null) {
                        for (ev in pollEvents) {
                            if (ev.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                                val child = dir.resolve(ev.context() as java.nio.file.Path)
                                if (Files.isDirectory(child)) {
                                    runCatching {
                                        val k = child.register(ws,
                                            StandardWatchEventKinds.ENTRY_CREATE,
                                            StandardWatchEventKinds.ENTRY_MODIFY)
                                        keys[k] = child
                                    }
                                }
                            }
                        }
                    }
                    if (!key.reset()) keys.remove(key)
                }
            } catch (_: InterruptedException) {
                /* shutdown */
            } catch (t: Throwable) {
                log.info("ccidea: WatchService stopped (${t.javaClass.simpleName}); 30s polling continues")
            }
        }
    }

    override fun dispose() {
        future?.cancel(false)
        future = null
    }

    companion object {
        fun getInstance(): PollerService = ApplicationManager.getApplication().service()
    }
}
