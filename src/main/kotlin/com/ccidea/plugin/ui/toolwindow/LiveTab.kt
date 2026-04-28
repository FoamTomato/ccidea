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
 * Real-time view of conversation activity for the *currently open* IntelliJ project.
 * Maps `Project.basePath` to a Claude project slug and shows a reverse-chronological
 * stream of recent messages (model / tokens / cost / time).
 */
class LiveTab(private val project: Project) : BaseTab() {
    private val summary = JBLabel().apply { horizontalAlignment = SwingConstants.LEFT }
    private val model = LiveMessageTableModel()
    private val table = JBTable(model).apply {
        rowSorter = TableRowSorter(model)
        TableSetup.applyToken(this, 3)
        TableSetup.applyCost(this, 4)
    }

    init {
        addToolbarComponent(ColumnFilterButton(table).component)
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
        // Claude turns the absolute path "/Users/foam/.../proj" into "-Users-foam-...-proj".
        // Multiple consecutive '-' represent intermediate '/' segments (e.g. ".." or empty parts).
        return abs.replace('/', '-')
    }

    override fun onRefresh() {
        val slug = expectedSlug()
        val all = BlockService.getInstance().all()
        val matches = if (slug != null)
            all.filter { it.projectKey == slug || slug.endsWith("-${it.projectKey}") || it.projectKey.endsWith(slug) }
        else emptyList()

        // Last 200 messages, newest first.
        val recent = matches.sortedByDescending { it.timestamp }.take(200)
        TableSetup.preservingSort(table) { model.setRows(recent) }
        summary.text = renderSummary(slug, matches, recent)
    }

    private fun renderSummary(slug: String?, all: List<UsageEntry>, recent: List<UsageEntry>): String {
        if (slug == null) return "<html><i>${ccideaMsg("live.noProject")}</i></html>"
        if (all.isEmpty()) {
            return "<html><b>${ProjectKeyFormat.shortName(slug)}</b> · " +
                "${ccideaMsg("live.noActivity")}</html>"
        }
        val pricing = PricingService.getInstance()
        val totalCost = all.sumOf { pricing.costFor(it).total }
        val totalTokens = all.sumOf { it.totalTokens }
        // Activity in the last hour.
        val oneHourAgo = Instant.now().minus(Duration.ofHours(1))
        val recentEntries = all.filter { it.timestamp >= oneHourAgo }
        val recentCount = recentEntries.size
        val recentTokens = recentEntries.sumOf { it.totalTokens }
        val recentCost = recentEntries.sumOf { pricing.costFor(it).total }
        val parts = mutableListOf<String>()
        parts += "<b>${ProjectKeyFormat.shortName(slug)}</b>"
        parts += "${all.size} ${ccideaMsg("live.totalMessages")}"
        parts += Format.tokens(totalTokens)
        parts += Format.cost(totalCost)
        // Average cost rates from the last 1h activity (or 0 if idle).
        if (recentEntries.isNotEmpty()) {
            // recentCost is the actual cost over the past 60 minutes, so /h directly,
            // /5min = recentCost / 12.
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
        "live.col.time", "live.col.session", "live.col.model",
        "live.col.tokens", "live.col.cost"
    )
    private var rows: List<UsageEntry> = emptyList()
    fun setRows(r: List<UsageEntry>) { rows = r; fireTableDataChanged() }
    override fun getRowCount() = rows.size
    override fun getColumnCount() = keys.size
    override fun getColumnName(c: Int): String = ccideaMsg(keys[c])
    override fun getColumnClass(c: Int): Class<*> = when (c) {
        3 -> Long::class.javaObjectType
        4 -> Double::class.javaObjectType
        else -> String::class.java
    }
    override fun getValueAt(r: Int, c: Int): Any {
        val row = rows[r]
        return when (c) {
            0 -> row.timestamp.atZone(ZoneId.systemDefault()).format(LIVE_TIME_FMT)
            1 -> row.sessionId.take(8)
            2 -> row.model
            3 -> row.totalTokens
            4 -> com.ccidea.plugin.pricing.PricingService.getInstance().costFor(row).total
            else -> ""
        }
    }
}
