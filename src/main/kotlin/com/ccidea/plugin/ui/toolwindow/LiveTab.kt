package com.ccidea.plugin.ui.toolwindow

import com.ccidea.plugin.blocks.BlockService
import com.ccidea.plugin.data.Format
import com.ccidea.plugin.data.ProjectKeyFormat
import com.ccidea.plugin.data.model.UsageEntry
import com.ccidea.plugin.i18n.ccideaMsg
import com.ccidea.plugin.pricing.PricingService
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * Real-time view of conversation activity. By default shows recent messages from
 * *all* sources (multiple IDEs, CLI, Antigravity, etc.). A toggle in the toolbar
 * narrows it down to the current IntelliJ project's slug.
 */
class LiveTab(private val project: Project) : BaseTab() {
    private val summary = JBLabel().apply { horizontalAlignment = SwingConstants.LEFT }
    private val model = LiveMessageTableModel()
    private val table = JBTable(model).apply {
        rowSorter = TableRowSorter(model)
        TableSetup.applyProject(this, 1)
        TableSetup.applyToken(this, 4)
        TableSetup.applyCost(this, 5)
    }
    private var onlyCurrentProject = false
    private val scopeToggle = javax.swing.JCheckBox("仅当前项目", false).apply {
        toolTipText = "勾选后只显示当前 IDE 项目的活动；不勾选则显示所有来源（多个 IDE / CLI / Antigravity 等）"
        addActionListener {
            onlyCurrentProject = isSelected
            onRefresh()
        }
    }

    init {
        addToolbarComponent(ColumnFilterButton(table).component)
        addToolbarComponent(scopeToggle)
        val north = JPanel(BorderLayout())
        north.preferredSize = Dimension(0, 60)
        north.add(summary, BorderLayout.CENTER)
        add(north, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        onRefresh()
    }

    private fun expectedSlug(): String? {
        val base = project.basePath ?: return null
        val abs = runCatching { Paths.get(base).toAbsolutePath().toString() }.getOrNull() ?: return null
        return abs.replace('/', '-')
    }

    private fun matchesCurrentProject(slug: String, entry: UsageEntry): Boolean =
        entry.projectKey == slug || slug.endsWith("-${entry.projectKey}") || entry.projectKey.endsWith(slug)

    override fun onRefresh() {
        val slug = expectedSlug()
        val all = BlockService.getInstance().all()
        val matches = if (onlyCurrentProject && slug != null)
            all.filter { matchesCurrentProject(slug, it) }
        else all

        val recent = matches.sortedByDescending { it.timestamp }.take(200)
        TableSetup.preservingSort(table) { model.setRows(recent) }
        summary.text = renderSummary(slug, matches)
    }

    private fun renderSummary(slug: String?, all: List<UsageEntry>): String {
        if (all.isEmpty()) {
            val scopeLabel = if (onlyCurrentProject && slug != null)
                "<b>${ProjectKeyFormat.shortName(slug)}</b> · "
            else ""
            return "<html>$scopeLabel<i>${ccideaMsg("live.noActivity")}</i></html>"
        }
        val pricing = PricingService.getInstance()
        val totalCost = all.sumOf { pricing.costFor(it).total }
        val totalTokens = all.sumOf { it.totalTokens }
        val oneHourAgo = Instant.now().minus(Duration.ofHours(1))
        val recentEntries = all.filter { it.timestamp >= oneHourAgo }
        val recentCount = recentEntries.size
        val recentTokens = recentEntries.sumOf { it.totalTokens }
        val recentCost = recentEntries.sumOf { pricing.costFor(it).total }
        val distinctProjects = all.map { it.projectKey }.toSet().size
        val parts = mutableListOf<String>()
        parts += if (onlyCurrentProject && slug != null)
            "<b>${ProjectKeyFormat.shortName(slug)}</b>"
        else "<b>全部来源</b> · ${distinctProjects} 个项目"
        parts += "${all.size} ${ccideaMsg("live.totalMessages")}"
        parts += Format.tokens(totalTokens)
        parts += Format.cost(totalCost)
        if (recentEntries.isNotEmpty()) {
            parts += ccideaMsg("blocks.summary.per5min", Format.cost(recentCost / 12.0))
            parts += ccideaMsg("blocks.summary.perHour", Format.cost(recentCost))
        }
        if (recentCount > 0) {
            parts += "${ccideaMsg("live.last1h")}: $recentCount · ${Format.tokens(recentTokens)}"
        }
        return "<html>" + parts.joinToString(" · ") + "</html>"
    }
}

private val LIVE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

private class LiveMessageTableModel : AbstractTableModel() {
    private val keys = arrayOf(
        "live.col.time", "table.col.project", "live.col.session", "live.col.model",
        "live.col.tokens", "live.col.cost"
    )
    private var rows: List<UsageEntry> = emptyList()
    fun setRows(r: List<UsageEntry>) { rows = r; fireTableDataChanged() }
    override fun getRowCount() = rows.size
    override fun getColumnCount() = keys.size
    override fun getColumnName(c: Int): String = ccideaMsg(keys[c])
    override fun getColumnClass(c: Int): Class<*> = when (c) {
        4 -> Long::class.javaObjectType
        5 -> Double::class.javaObjectType
        else -> String::class.java
    }
    override fun getValueAt(r: Int, c: Int): Any {
        val row = rows[r]
        return when (c) {
            0 -> row.timestamp.atZone(ZoneId.systemDefault()).format(LIVE_TIME_FMT)
            1 -> row.projectKey
            2 -> row.sessionId.take(8)
            3 -> row.model
            4 -> row.totalTokens
            5 -> com.ccidea.plugin.pricing.PricingService.getInstance().costFor(row).total
            else -> ""
        }
    }
}
