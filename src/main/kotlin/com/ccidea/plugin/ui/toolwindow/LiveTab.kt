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
    private val summary = JBLabel().apply {
        horizontalAlignment = SwingConstants.LEFT
        verticalAlignment = SwingConstants.TOP
    }
    private val model = LiveMessageTableModel()
    private val table = JBTable(model).apply {
        rowSorter = TableRowSorter(model)
        TableSetup.applyProject(this, 1)
        TableSetup.applyToken(this, 4)
        TableSetup.applyCost(this, 5)
    }
    private var onlyCurrentProject = true
    private val scopeToggle = javax.swing.JCheckBox(ccideaMsg("live.scope.currentProject"), true).apply {
        toolTipText = "勾选后只显示当前 IDE 项目的活动；不勾选则显示所有来源（多个 IDE / CLI / Antigravity 等）"
        addActionListener {
            onlyCurrentProject = isSelected
            onRefresh(forced = true)
        }
    }
    private val quotaPanel = QuotaPanel()

    init {
        addToolbarComponent(ColumnFilterButton(table).component)
        addToolbarComponent(scopeToggle)
        val north = JPanel(BorderLayout())
        val summaryWrap = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(0, 92)
            add(summary, BorderLayout.CENTER)
        }
        north.add(summaryWrap, BorderLayout.NORTH)
        north.add(quotaPanel, BorderLayout.CENTER)
        add(north, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        onRefresh(forced = true)
    }

    private fun expectedSlug(): String? {
        val base = project.basePath ?: return null
        val abs = runCatching { Paths.get(base).toAbsolutePath().toString() }.getOrNull() ?: return null
        // Claude Code slugifies the absolute path by replacing every character that isn't
        // [A-Za-z0-9.] with '-'. A naive '/'→'-' replacement breaks for paths containing
        // non-ASCII segments (e.g. CJK directory names) — those characters become runs of '-'
        // on disk, so we must mirror the same rule here to recognise the current project.
        return abs.map { ch ->
            if (ch.isLetterOrDigit() && ch.code < 128 || ch == '.') ch else '-'
        }.joinToString("")
    }

    private fun matchesCurrentProject(slug: String, entry: UsageEntry): Boolean =
        entry.projectKey == slug || slug.endsWith("-${entry.projectKey}") || entry.projectKey.endsWith(slug)

    override fun onRefresh(forced: Boolean) {
        val slug = expectedSlug()
        val all = BlockService.getInstance().all()
        val matches = if (onlyCurrentProject && slug != null)
            all.filter { matchesCurrentProject(slug, it) }
        else all

        val recent = matches.sortedByDescending { it.timestamp }.take(200)
        TableSetup.preservingSort(table) { model.setRows(recent) }
        summary.text = renderSummary(slug, matches)
        quotaPanel.refresh()
    }

    private fun renderSummary(slug: String?, all: List<UsageEntry>): String {
        val scopeName = if (onlyCurrentProject && slug != null)
            ProjectKeyFormat.shortName(slug)
        else ccideaMsg("live.scope.allSources")

        if (all.isEmpty()) {
            return "<html><div style='padding:6px 4px;'><b>$scopeName</b> · <i>${ccideaMsg("live.noActivity")}</i></div></html>"
        }

        val pricing = PricingService.getInstance()
        val totalCost = all.sumOf { pricing.costFor(it).total }
        val totalTokens = all.sumOf { it.totalTokens }
        val oneHourAgo = Instant.now().minus(Duration.ofHours(1))
        val recentEntries = all.filter { it.timestamp >= oneHourAgo }
        val recentCount = recentEntries.size
        val recentTokens = recentEntries.sumOf { it.totalTokens }
        val recentCost = recentEntries.sumOf { pricing.costFor(it).total }

        // Active sessions = distinct sessionIds touched within last 1h
        val activeSessions = recentEntries.map { it.sessionId }.toSet().size

        // Avg per hour over the lifetime span (min 1h to avoid divide-by-tiny-window spikes)
        val firstTs = all.minOf { it.timestamp }
        val spanHours = (Duration.between(firstTs, Instant.now()).toMillis() / 3_600_000.0).coerceAtLeast(1.0)
        val avgCostPerHour = totalCost / spanHours
        val avgTokensPerHour = (totalTokens / spanHours).toLong()

        val cards = listOf(
            ccideaMsg("live.card.activeSessions") to activeSessions.toString(),
            ccideaMsg("live.card.totalMessages") to all.size.toString(),
            ccideaMsg("live.card.totalTokens") to Format.tokens(totalTokens),
            ccideaMsg("live.card.totalCost") to Format.cost(totalCost),
            ccideaMsg("live.card.rateLast1h") to "$recentCount · ${Format.tokens(recentTokens)} · ${Format.cost(recentCost)}",
            ccideaMsg("live.card.avgCostPerHour") to Format.cost(avgCostPerHour),
            ccideaMsg("live.card.avgTokensPerHour") to Format.tokens(avgTokensPerHour),
        )

        val cardCells = cards.joinToString("") { (label, value) ->
            "<td valign='top' style='padding:4px 10px; border-left:1px solid #44474a;'>" +
                "<div style='color:#9aa0a6; font-size:10px; font-weight:normal;'>$label</div>" +
                "<div style='color:#e8eaed; font-size:14px; font-weight:bold; padding-top:2px;'>$value</div>" +
            "</td>"
        }

        return "<html>" +
            "<div style='padding:4px;'>" +
                "<div style='color:#9aa0a6; font-size:11px; padding:0 0 4px 6px;'><b>$scopeName</b></div>" +
                "<table cellspacing='0' cellpadding='0' style='border:1px solid #44474a;'>" +
                    "<tr>$cardCells</tr>" +
                "</table>" +
            "</div>" +
            "</html>"
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
