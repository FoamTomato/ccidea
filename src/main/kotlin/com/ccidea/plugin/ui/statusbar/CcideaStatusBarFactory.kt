package com.ccidea.plugin.ui.statusbar

import com.ccidea.plugin.blocks.BlockService
import com.ccidea.plugin.data.Format
import com.ccidea.plugin.data.model.SessionBlock
import com.ccidea.plugin.i18n.ccideaMsg
import com.ccidea.plugin.poller.PollerService
import com.ccidea.plugin.service.CcideaBus
import com.ccidea.plugin.service.RefreshListener
import com.ccidea.plugin.settings.CcideaSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import com.intellij.openapi.wm.ToolWindowManager

class CcideaStatusBarFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "ccidea"
    override fun isAvailable(project: Project): Boolean =
        CcideaSettings.getInstance().state.showStatusBarWidget
    override fun createWidget(project: Project): StatusBarWidget = CcideaStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object { const val ID = "ccidea.statusBar" }
}

class CcideaStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private var refreshFuture: ScheduledFuture<*>? = null

    override fun ID(): String = CcideaStatusBarFactory.ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val conn = ApplicationManager.getApplication().messageBus.connect(this)
        conn.subscribe(CcideaBus.TOPIC, object : RefreshListener {
            override fun refreshed(at: Instant, forced: Boolean) {
                ApplicationManager.getApplication().invokeLater {
                    statusBar.updateWidget(CcideaStatusBarFactory.ID)
                }
            }
        })
        // Keep the status bar live even when the tool window is hidden:
        // trigger a poll immediately, then every 30s.
        val period = CcideaSettings.getInstance().state.refreshSeconds.coerceAtLeast(5).toLong()
        refreshFuture = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ PollerService.getInstance().runOnce() }, 0L, period, TimeUnit.SECONDS)
    }

    override fun dispose() {
        refreshFuture?.cancel(false)
        refreshFuture = null
        statusBar = null
    }

    override fun getText(): String {
        val service = BlockService.getInstance()
        val current = service.currentBlock() ?: return ccideaMsg("statusbar.idle")
        val costLimit = service.detectedCostLimit()
        val pct = if (costLimit > 0) (current.totalCost / costLimit).coerceIn(0.0, 1.5) else 0.0
        val bar = drawBar(pct)
        val pctStr = if (costLimit > 0) "%.1f%%".format(pct * 100) else "—"
        val tokens = Format.tokens(current.totalTokens)
        val cost = Format.cost(current.totalCost)
        val costStr = if (costLimit > 0) "$cost / ${Format.cost(costLimit)}" else cost
        return "$bar $pctStr · $tokens tokens · $costStr"
    }

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String {
        val service = BlockService.getInstance()
        val b = service.currentBlock() ?: return ccideaMsg("statusbar.tooltip.noBlock")
        val br1h = service.burnRate(Duration.ofMinutes(60))
        val br24h = service.burnRate(Duration.ofHours(24))
        val costLimit = service.detectedCostLimit()
        val tokensStr = Format.tokens(b.totalTokens)
        val costStr = if (costLimit > 0) "${Format.cost(b.totalCost)} / ${Format.cost(costLimit)}"
        else Format.cost(b.totalCost)
        val tf = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        val range = "${tf.format(b.startTime)} – ${tf.format(b.endTime)}"
        return buildString {
            append("<html>")
            append(ccideaMsg("statusbar.tooltip.title", range))
            append("<br>").append(ccideaMsg("statusbar.tooltip.tokens"))
                .append(": ").append(tokensStr)
            append("&nbsp;&nbsp;").append(ccideaMsg("statusbar.tooltip.cost"))
                .append(": ").append(costStr)
            append("<br>").append(ccideaMsg("statusbar.tooltip.burn1h"))
                .append(": ").append(ccideaMsg("blocks.summary.perHour", Format.cost(br1h.costPerHour)))
            append("&nbsp;&nbsp;").append(ccideaMsg("statusbar.tooltip.burn24h"))
                .append(": ").append(ccideaMsg("blocks.summary.perHour", Format.cost(br24h.costPerHour)))
            append("</html>")
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow("Ccidea")?.activate(null)
    }

    private fun limit(service: BlockService): Long {
        val custom = CcideaSettings.getInstance().state.customTokenLimit
        return if (custom > 0) custom else service.detectedTokenLimit()
    }

    private fun drawBar(pct: Double): String {
        val cells = 8
        val filled = (pct * cells).toInt().coerceIn(0, cells)
        return "█".repeat(filled) + "░".repeat(cells - filled)
    }
}
