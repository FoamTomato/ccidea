package com.ccidea.plugin.ui.toolwindow

import com.ccidea.plugin.i18n.ccideaMsg
import com.ccidea.plugin.patterns.PatternEngine
import com.ccidea.plugin.patterns.Recommendation
import com.ccidea.plugin.patterns.Severity
import com.ccidea.plugin.ui.chart.ChartPanel
import com.ccidea.plugin.ui.chart.Charts
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

class PatternsTab : BaseTab() {
    private val recommendationsArea = JBLabel().apply {
        verticalAlignment = SwingConstants.TOP
    }
    private val outliersModel = OutliersTableModel()
    private val outliersTable = JBTable(outliersModel).apply {
        rowSorter = TableRowSorter(outliersModel)
        TableSetup.applyProject(this, 1)
        TableSetup.applyModels(this, 3)
        TableSetup.applyToken(this, 4)
        TableSetup.applyCost(this, 5)
    }

    private val heatmap = ChartPanel(this)
    private val hitRatio = ChartPanel(this)

    /** When the charts were last repainted. Idle 30s ticks reuse the previous render
     *  unless this is older than [CHART_THROTTLE]; user-initiated refreshes always rebuild. */
    private var lastChartRenderAt: java.time.Instant? = null

    init {
        addToolbarComponent(ColumnFilterButton(outliersTable).component)
        val charts = JPanel(GridLayout(1, 2, 8, 0))
        heatmap.preferredSize = Dimension(0, 220)
        hitRatio.preferredSize = Dimension(0, 220)
        charts.add(heatmap); charts.add(hitRatio)

        val recScroll = JBScrollPane(recommendationsArea).apply { preferredSize = Dimension(0, 140) }
        val north = JPanel(BorderLayout())
        north.add(recScroll, BorderLayout.NORTH)
        north.add(charts, BorderLayout.CENTER)

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, north, JBScrollPane(outliersTable)).apply {
            resizeWeight = 0.6; isContinuousLayout = true; border = null
        }
        add(split, BorderLayout.CENTER)
        // First show: paint everything, including charts.
        onRefresh(forced = true)
        // When the user actually scrolls/clicks into this tab after a hidden period,
        // refresh charts once so they don't remain stale forever.
        addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
                && isShowing) onRefresh(forced = true)
        }
    }

    override fun onRefresh(forced: Boolean) {
        val report = PatternEngine.getInstance().report(ZoneId.systemDefault())
        recommendationsArea.text = renderRecommendations(report.recommendations)
        TableSetup.preservingSort(outliersTable) {
            outliersModel.setRows(report.outliers + report.topSessions.filter { it !in report.outliers })
        }
        val s = com.ccidea.plugin.settings.CcideaSettings.getInstance().state
        heatmap.isVisible = s.showPatternsHeatmap
        hitRatio.isVisible = s.showPatternsHitRatio
        // Charts are expensive (Lets-Plot rebuild + Swing component swap). On idle 30s
        // ticks we throttle them to CHART_THROTTLE so they still update over time without
        // flickering every half minute. User-initiated refreshes (toolbar button, tab
        // show events) always bypass the throttle.
        val now = java.time.Instant.now()
        val last = lastChartRenderAt
        val throttled = !forced && last != null &&
            java.time.Duration.between(last, now) < CHART_THROTTLE
        if (throttled) return
        if (s.showPatternsHeatmap) heatmap.render { Charts.activityHeatmap(report.heatmap) }
        if (s.showPatternsHitRatio) hitRatio.render { Charts.cacheHitRatioBar(report.modelHitRatios) }
        lastChartRenderAt = now
    }

    companion object {
        private val CHART_THROTTLE: java.time.Duration = java.time.Duration.ofMinutes(2)
    }

    private fun renderRecommendations(items: List<Recommendation>): String {
        if (items.isEmpty()) return ccideaMsg("patterns.empty")
        val rows = items.joinToString("") { r ->
            val tag = when (r.severity) {
                Severity.WARN -> "<span style='color:#E57373'>● </span>"
                Severity.HINT -> "<span style='color:#FFB74D'>● </span>"
                Severity.INFO -> "<span style='color:#90CAF9'>● </span>"
            }
            "<li>$tag<b>${escape(r.title)}</b><br/>${escape(r.detail)}</li>"
        }
        return "<html><ul style='margin:0;padding-left:18px'>$rows</ul></html>"
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

private val SESSION_LAST_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private class OutliersTableModel : AbstractTableModel() {
    private val keys = arrayOf(
        "table.col.lastSeen", "table.col.project", "table.col.session",
        "table.col.models", "table.col.tokens", "table.col.cost"
    )
    private var rows: List<com.ccidea.plugin.data.model.SessionAggregate> = emptyList()
    fun setRows(r: List<com.ccidea.plugin.data.model.SessionAggregate>) { rows = r; fireTableDataChanged() }
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
            0 -> row.lastTs.atZone(ZoneId.systemDefault()).format(SESSION_LAST_FMT)
            1 -> row.projectKey
            2 -> row.sessionId.take(8)
            3 -> row.models.joinToString(",")
            4 -> row.totals.total
            5 -> row.cost
            else -> ""
        }
    }
}
