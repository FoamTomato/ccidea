package com.ccidea.plugin.ui.statusbar

import com.ccidea.plugin.blocks.BlockService
import com.ccidea.plugin.data.Format
import com.ccidea.plugin.data.model.SessionBlock
import com.ccidea.plugin.i18n.ccideaMsg
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
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
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

    override fun ID(): String = CcideaStatusBarFactory.ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val conn = ApplicationManager.getApplication().messageBus.connect(this)
        conn.subscribe(CcideaBus.TOPIC, object : RefreshListener {
            override fun refreshed(at: Instant) {
                ApplicationManager.getApplication().invokeLater {
                    statusBar.updateWidget(CcideaStatusBarFactory.ID)
                }
            }
        })
    }

    override fun dispose() { statusBar = null }

    override fun getText(): String {
        val service = BlockService.getInstance()
        val current = service.currentBlock() ?: return ccideaMsg("statusbar.idle")
        val limit = limit(service)
        val pct = if (limit > 0) (current.totalTokens.toDouble() / limit).coerceIn(0.0, 1.5) else 0.0
        val bar = drawBar(pct)
        val left = service.timeUntilReset() ?: Duration.ZERO
        val cost = Format.cost(current.totalCost)
        val pctStr = if (limit > 0) "${(pct * 100).toInt()}%" else "—"
        return "$bar $pctStr · ${left.toHours()}h${left.toMinutesPart()}m · $cost"
    }

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String {
        val service = BlockService.getInstance()
        val b = service.currentBlock() ?: return ccideaMsg("statusbar.tooltip.noBlock")
        val br1h = service.burnRate(Duration.ofMinutes(60))
        val br24h = service.burnRate(Duration.ofHours(24))
        val limit = limit(service)
        return buildString {
            append(ccideaMsg("statusbar.tooltip.title")).append('\n')
            append("  ").append(ccideaMsg("statusbar.tooltip.tokens"))
                .append(": ").append(Format.tokens(b.totalTokens))
            if (limit > 0) append(" / ").append(Format.tokens(limit))
            append("\n  ").append(ccideaMsg("statusbar.tooltip.cost"))
                .append(": ").append(Format.cost(b.totalCost))
            append("\n  ").append(ccideaMsg("statusbar.tooltip.burn1h"))
                .append(": ").append(ccideaMsg("blocks.summary.perMin", Format.tokens(br1h.tokensPerMin.toLong())))
                .append(" · ").append(ccideaMsg("blocks.summary.per5min", Format.cost(br1h.costPerHour / 12.0)))
                .append(" · ").append(ccideaMsg("blocks.summary.perHour", Format.cost(br1h.costPerHour)))
            append("\n  ").append(ccideaMsg("statusbar.tooltip.burn24h"))
                .append(": ").append(ccideaMsg("blocks.summary.perMin", Format.tokens(br24h.tokensPerMin.toLong())))
                .append(" · ").append(ccideaMsg("blocks.summary.perHour", Format.cost(br24h.costPerHour)))
            append("\n  ").append(ccideaMsg("statusbar.tooltip.models"))
                .append(": ").append(b.models.joinToString(", "))
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
