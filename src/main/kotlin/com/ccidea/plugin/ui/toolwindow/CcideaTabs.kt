package com.ccidea.plugin.ui.toolwindow

import com.ccidea.plugin.blocks.BlockService
import com.ccidea.plugin.data.Format
import com.ccidea.plugin.data.ProjectKeyFormat
import com.ccidea.plugin.data.model.SessionBlock
import com.ccidea.plugin.i18n.ccideaMsg
import com.ccidea.plugin.poller.PollerService
import com.ccidea.plugin.service.AggregationService
import com.ccidea.plugin.service.CcideaBus
import com.ccidea.plugin.service.RefreshListener
import com.ccidea.plugin.ui.chart.ChartPanel
import com.ccidea.plugin.ui.chart.Charts
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

abstract class BaseTab : JPanel(BorderLayout()), Disposable {
    private val statusLabel = JBLabel("loading…")
    protected val toolbar: JPanel

    init {
        toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        val refreshAction = ActionManager.getInstance().getAction("Ccidea.Refresh")
        if (refreshAction != null) {
            val group = DefaultActionGroup(refreshAction)
            val ab = ActionManager.getInstance()
                .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
            ab.targetComponent = this
            toolbar.add(ab.component)
        }
        toolbar.add(statusLabel)
        add(toolbar, BorderLayout.NORTH)

        val conn = ApplicationManager.getApplication().messageBus.connect(this)
        conn.subscribe(CcideaBus.TOPIC, object : RefreshListener {
            override fun refreshed(at: Instant) { onRefresh(); updateStatus(at) }
        })
        // Initial status from current state.
        updateStatus(PollerService.getInstance().lastUpdated())
    }

    override fun dispose() { /* messageBus connection auto-disposed */ }

    /** Subclasses can hang their own widgets (range pickers, etc.) on the toolbar. */
    protected fun addToolbarComponent(comp: javax.swing.JComponent) {
        toolbar.add(comp)
        toolbar.revalidate()
    }

    protected fun updateStatus(at: Instant?) {
        val ts = at?.atZone(ZoneId.systemDefault())?.format(TIME_FMT)
            ?: ccideaMsg("toolbar.lastUpdated.never")
        statusLabel.text = ccideaMsg("toolbar.lastUpdated", ts)
    }

    /** Triggered on EDT after a refresh tick. Implementations should rebuild their model. */
    abstract fun onRefresh()
}

class DailyTab : BaseTab() {
    private val model = DailyTableModel()
    private val table = JBTable(model)
    private val stackedChart = ChartPanel(this)
    private val trendChart = ChartPanel(this)
    private val costChart = ChartPanel(this)
    private val rangeSelector = RangeSelector(default = 30) { onRefresh() }
    @Volatile private var rows: List<com.ccidea.plugin.data.model.DailyAggregate> = emptyList()

    init {
        table.rowSorter = TableRowSorter(model)
        for (c in 1..6) TableSetup.applyToken(table, c)
        TableSetup.applyCost(table, 7)
        TableSetup.applyToken(table, 8)
        TableSetup.applyCost(table, 9)
        addToolbarComponent(rangeSelector.component)
        addToolbarComponent(ColumnFilterButton(table).component)
        stackedChart.preferredSize = Dimension(0, 240)
        trendChart.preferredSize = Dimension(0, 180)
        costChart.preferredSize = Dimension(0, 180)
        val charts = JPanel()
        charts.layout = javax.swing.BoxLayout(charts, javax.swing.BoxLayout.Y_AXIS)
        charts.add(stackedChart); charts.add(trendChart); charts.add(costChart)
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(charts), JBScrollPane(table)).apply {
            resizeWeight = 0.7; isContinuousLayout = true; border = null
        }
        add(split, BorderLayout.CENTER)
        onRefresh()
    }

    override fun onRefresh() {
        val days = rangeSelector.selectedDays
        val all = AggregationService.getInstance().daily()
        val cutoff = if (days <= 0) java.time.LocalDate.MIN else java.time.LocalDate.now().minusDays(days.toLong())
        rows = all.filter { it.date >= cutoff }
        TableSetup.preservingSort(table) { model.setRows(rows) }
        val s = com.ccidea.plugin.settings.CcideaSettings.getInstance().state
        stackedChart.isVisible = s.showDailyStackedBar
        trendChart.isVisible = s.showDailyTokenTrend
        costChart.isVisible = s.showDailyCostTrend
        if (s.showDailyStackedBar) stackedChart.render { Charts.dailyStackedBar(rows) }
        if (s.showDailyTokenTrend) trendChart.render { Charts.dailyTrendLine(rows) }
        if (s.showDailyCostTrend) costChart.render { Charts.dailyCostTrend(rows) }
    }
}

class MonthlyTab : BaseTab() {
    private val model = MonthlyTableModel()
    private val table = JBTable(model)
    private val chart = ChartPanel(this)
    private val rangeSelector = RangeSelector(default = 365) { onRefresh() }

    init {
        table.rowSorter = TableRowSorter(model)
        for (c in 1..6) TableSetup.applyToken(table, c)
        TableSetup.applyCost(table, 7)
        TableSetup.applyToken(table, 8)
        TableSetup.applyCost(table, 9)
        addToolbarComponent(rangeSelector.component)
        addToolbarComponent(ColumnFilterButton(table).component)
        chart.preferredSize = Dimension(0, 240)
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, chart, JBScrollPane(table)).apply {
            resizeWeight = 0.5; isContinuousLayout = true; border = null
        }
        add(split, BorderLayout.CENTER)
        onRefresh()
    }

    override fun onRefresh() {
        val days = rangeSelector.selectedDays
        val all = AggregationService.getInstance().monthly()
        val cutoffYm = if (days <= 0) java.time.YearMonth.of(1970, 1)
                       else java.time.YearMonth.now().minusMonths((days / 30).toLong().coerceAtLeast(1))
        val rows = all.filter { it.yearMonth >= cutoffYm }
        TableSetup.preservingSort(table) { model.setRows(rows) }
        chart.render { Charts.monthlyStackedBar(rows) }
    }
}

class SessionsTab : BaseTab() {
    private val model = SessionTableModel()
    private val table = JBTable(model)
    private val projectFilter = mutableSetOf<String>()
    private var allSessions: List<com.ccidea.plugin.data.model.SessionAggregate> = emptyList()
    private val filterStatus = JBLabel("")
    private val filterButton = javax.swing.JButton("项目筛选").apply {
        toolTipText = "按项目多选筛选"
        addActionListener { showProjectFilterPopup(this) }
    }

    init {
        table.rowSorter = TableRowSorter(model)
        TableSetup.applyProject(table, 1)
        TableSetup.applyModels(table, 3)
        TableSetup.applyToken(table, 4)
        TableSetup.applyCost(table, 5)
        TableSetup.applyToken(table, 6)
        TableSetup.applyCost(table, 7)
        addToolbarComponent(ColumnFilterButton(table).component)
        addToolbarComponent(filterButton)
        addToolbarComponent(filterStatus)
        attachProjectContextMenu()
        add(JBScrollPane(table), BorderLayout.CENTER)
        onRefresh()
    }

    private fun attachProjectContextMenu() {
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = maybeShow(e)
            private fun maybeShow(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val viewRow = table.rowAtPoint(e.point)
                if (viewRow < 0) return
                if (!table.selectionModel.isSelectedIndex(viewRow)) {
                    table.setRowSelectionInterval(viewRow, viewRow)
                }
                val modelRow = table.convertRowIndexToModel(viewRow)
                val project = model.getValueAt(modelRow, 1) as? String ?: return
                val short = ProjectKeyFormat.shortName(project)
                val menu = javax.swing.JPopupMenu()
                if (projectFilter.contains(project)) {
                    menu.add(javax.swing.JMenuItem("从筛选中移除：$short").apply {
                        addActionListener { toggleProject(project) }
                    })
                } else {
                    menu.add(javax.swing.JMenuItem("仅显示项目：$short").apply {
                        addActionListener { setProjectFilter(setOf(project)) }
                    })
                    menu.add(javax.swing.JMenuItem("追加到筛选：$short").apply {
                        addActionListener { toggleProject(project) }
                    })
                }
                menu.add(javax.swing.JMenuItem("打开多选筛选…").apply {
                    addActionListener { showProjectFilterPopup(filterButton) }
                })
                if (projectFilter.isNotEmpty()) {
                    menu.addSeparator()
                    menu.add(javax.swing.JMenuItem("清除项目筛选").apply {
                        addActionListener { setProjectFilter(emptySet()) }
                    })
                }
                menu.show(e.component, e.x, e.y)
            }
        })
    }

    private fun showProjectFilterPopup(anchor: javax.swing.JComponent) {
        val all = allSessions.map { it.projectKey }.toSortedSet().toList()
        if (all.isEmpty()) return
        data class Item(val key: String, val display: String, val checked: Boolean)
        val items = mutableListOf<Item>()
        items += Item("__all__", "(全部)", projectFilter.isEmpty())
        items += all.map { Item(it, ProjectKeyFormat.shortName(it), it in projectFilter) }
        val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<Item>("按项目筛选", items) {
            override fun getTextFor(value: Item) = (if (value.checked) "✓ " else "  ") + value.display
            override fun isSelectable(value: Item) = true
            override fun onChosen(selected: Item, finalChoice: Boolean): com.intellij.openapi.ui.popup.PopupStep<*>? {
                if (selected.key == "__all__") setProjectFilter(emptySet())
                else toggleProject(selected.key)
                showProjectFilterPopup(anchor)
                return FINAL_CHOICE
            }
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createListPopup(step).showUnderneathOf(anchor)
    }

    private fun toggleProject(project: String) {
        if (!projectFilter.add(project)) projectFilter.remove(project)
        refreshFilterStatus()
        applyRows()
    }

    private fun setProjectFilter(projects: Set<String>) {
        projectFilter.clear()
        projectFilter.addAll(projects)
        refreshFilterStatus()
        applyRows()
    }

    private fun refreshFilterStatus() {
        filterStatus.text = if (projectFilter.isEmpty()) "" else {
            val names = projectFilter.map { ProjectKeyFormat.shortName(it) }
            "筛选(${projectFilter.size})：${names.joinToString("、")}"
        }
    }

    private fun applyRows() {
        val rows = if (projectFilter.isEmpty()) allSessions
                   else allSessions.filter { it.projectKey in projectFilter }
        TableSetup.preservingSort(table) { model.setRows(rows) }
    }

    override fun onRefresh() {
        allSessions = AggregationService.getInstance().sessions()
        // Drop filter entries that no longer have data.
        projectFilter.retainAll(allSessions.map { it.projectKey }.toSet())
        refreshFilterStatus()
        applyRows()
    }
}

class BlocksTab : BaseTab() {
    private val summary = JBLabel("no active block").apply {
        horizontalAlignment = SwingConstants.LEFT
    }
    private val model = BlockTableModel()
    private val table = JBTable(model)
    private val chart = ChartPanel(this)

    init {
        val north = JPanel(BorderLayout())
        north.preferredSize = Dimension(0, 60)
        north.add(summary, BorderLayout.CENTER)

        chart.preferredSize = Dimension(0, 320)
        val splitTop = JSplitPane(JSplitPane.VERTICAL_SPLIT, chart, JBScrollPane(table)).apply {
            resizeWeight = 0.55; isContinuousLayout = true; border = null
        }
        val center = JPanel(BorderLayout())
        center.add(north, BorderLayout.NORTH)
        center.add(splitTop, BorderLayout.CENTER)
        add(center, BorderLayout.CENTER)
        table.rowSorter = TableRowSorter(model)
        TableSetup.applyToken(table, 3)
        TableSetup.applyModels(table, 4)
        TableSetup.applyCost(table, 5)
        TableSetup.applyToken(table, 6)
        TableSetup.applyCost(table, 7)
        addToolbarComponent(ColumnFilterButton(table).component)
        onRefresh()
    }

    override fun onRefresh() {
        val service = BlockService.getInstance()
        val current = service.currentBlock()
        val limit = service.detectedTokenLimit().takeIf { it > 0 }
        summary.text = describe(current, limit, service)
        TableSetup.preservingSort(table) { model.setRows(service.allBlocks().reversed()) }
        val s = com.ccidea.plugin.settings.CcideaSettings.getInstance().state
        chart.isVisible = s.showBlocksBurnRate
        if (s.showBlocksBurnRate) chart.render {
            val cur = service.currentBlock() ?: return@render null
            // Snap "now" to the start of the current minute so successive refresh ticks
            // within the same minute produce identical chart specs → no flicker.
            val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
            val eta = etaInstantFor(cur, service, now)
            Charts.burnRateLine(cur, now, eta)
        }
    }

    private fun etaInstantFor(block: SessionBlock, service: BlockService, now: Instant): Instant? {
        val lim = service.detectedTokenLimit().takeIf { it > 0 } ?: return null
        val br = service.burnRate(Duration.ofMinutes(60), now)
        if (br.tokensPerMin <= 0.0) return null
        val remainingTokens = (lim - block.totalTokens).coerceAtLeast(0L).toDouble()
        if (remainingTokens <= 0.0) return now
        val minutesUntil = remainingTokens / br.tokensPerMin
        return now.plus(Duration.ofMinutes(minutesUntil.toLong()))
    }

    private fun describe(current: SessionBlock?, limit: Long?, service: BlockService): String {
        if (current == null) return ccideaMsg("blocks.summary.noBlock")
        val pct = limit?.takeIf { it > 0 }?.let { (current.totalTokens.toDouble() / it * 100).toInt() }
        val left = service.timeUntilReset() ?: Duration.ZERO
        val br = service.burnRate(Duration.ofMinutes(60))
        val parts = mutableListOf<String>()
        parts += ccideaMsg("blocks.summary.tokens", "<b>${Format.tokens(current.totalTokens)}</b>")
        if (pct != null) parts += ccideaMsg("blocks.summary.pctOf", pct, Format.tokens(limit))
        parts += ccideaMsg("blocks.summary.left", "${left.toHours()}h${left.toMinutesPart()}m")
        parts += ccideaMsg("blocks.summary.perMin", Format.tokens(br.tokensPerMin.toLong()))
        parts += ccideaMsg("blocks.summary.per5min", Format.cost(br.costPerHour / 12.0))
        parts += ccideaMsg("blocks.summary.perHour", Format.cost(br.costPerHour))
        return "<html>" + parts.joinToString(" · ") + "</html>"
    }
}

private class DailyTableModel : AbstractTableModel() {
    private val keys = arrayOf(
        "table.col.date", "table.col.input", "table.col.output",
        "table.col.cacheW5m", "table.col.cacheW1h", "table.col.cacheR",
        "table.col.total", "table.col.cost",
        "table.col.tokensPerHour", "table.col.costPerHour"
    )
    private var rows: List<com.ccidea.plugin.data.model.DailyAggregate> = emptyList()
    fun setRows(r: List<com.ccidea.plugin.data.model.DailyAggregate>) { rows = r; fireTableDataChanged() }
    override fun getRowCount() = rows.size
    override fun getColumnCount() = keys.size
    override fun getColumnName(c: Int): String = ccideaMsg(keys[c])
    override fun getColumnClass(c: Int): Class<*> = when (c) {
        0 -> String::class.java
        7, 9 -> Double::class.javaObjectType
        else -> Long::class.javaObjectType
    }
    override fun getValueAt(r: Int, c: Int): Any {
        val row = rows[r]
        // Each daily row spans 24 hours.
        val hours = 24.0
        return when (c) {
            0 -> row.date.format(DATE_FMT)
            1 -> row.totals.input
            2 -> row.totals.output
            3 -> row.totals.cacheCreate5m
            4 -> row.totals.cacheCreate1h
            5 -> row.totals.cacheRead
            6 -> row.totals.total
            7 -> row.cost
            8 -> (row.totals.total / hours).toLong()
            9 -> row.cost / hours
            else -> ""
        }
    }
}

private class MonthlyTableModel : AbstractTableModel() {
    private val keys = arrayOf(
        "table.col.month", "table.col.input", "table.col.output",
        "table.col.cacheW5m", "table.col.cacheW1h", "table.col.cacheR",
        "table.col.total", "table.col.cost",
        "table.col.tokensPerHour", "table.col.costPerHour"
    )
    private var rows: List<com.ccidea.plugin.data.model.MonthlyAggregate> = emptyList()
    fun setRows(r: List<com.ccidea.plugin.data.model.MonthlyAggregate>) { rows = r; fireTableDataChanged() }
    override fun getRowCount() = rows.size
    override fun getColumnCount() = keys.size
    override fun getColumnName(c: Int): String = ccideaMsg(keys[c])
    override fun getColumnClass(c: Int): Class<*> = when (c) {
        0 -> String::class.java
        7, 9 -> Double::class.javaObjectType
        else -> Long::class.javaObjectType
    }
    override fun getValueAt(r: Int, c: Int): Any {
        val row = rows[r]
        val hours = row.yearMonth.lengthOfMonth() * 24.0
        return when (c) {
            0 -> row.yearMonth.toString()
            1 -> row.totals.input
            2 -> row.totals.output
            3 -> row.totals.cacheCreate5m
            4 -> row.totals.cacheCreate1h
            5 -> row.totals.cacheRead
            6 -> row.totals.total
            7 -> row.cost
            8 -> (row.totals.total / hours).toLong()
            9 -> row.cost / hours
            else -> ""
        }
    }
}

private class SessionTableModel : AbstractTableModel() {
    private val keys = arrayOf(
        "table.col.lastSeen", "table.col.project", "table.col.session",
        "table.col.models", "table.col.tokens", "table.col.cost",
        "table.col.tokensPerHour", "table.col.costPerHour"
    )
    private var rows: List<com.ccidea.plugin.data.model.SessionAggregate> = emptyList()
    fun setRows(r: List<com.ccidea.plugin.data.model.SessionAggregate>) { rows = r; fireTableDataChanged() }
    override fun getRowCount() = rows.size
    override fun getColumnCount() = keys.size
    override fun getColumnName(c: Int): String = ccideaMsg(keys[c])
    override fun getColumnClass(c: Int): Class<*> = when (c) {
        4, 6 -> Long::class.javaObjectType
        5, 7 -> Double::class.javaObjectType
        else -> String::class.java
    }
    override fun getValueAt(r: Int, c: Int): Any {
        val row = rows[r]
        val durationSec = java.time.Duration.between(row.firstTs, row.lastTs).seconds.coerceAtLeast(60L)
        val hours = durationSec / 3600.0
        return when (c) {
            0 -> row.lastTs.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            1 -> row.projectKey
            2 -> row.sessionId.take(8)
            3 -> row.models.joinToString(",")
            4 -> row.totals.total
            5 -> row.cost
            6 -> (row.totals.total / hours).toLong()
            7 -> row.cost / hours
            else -> ""
        }
    }
}

private class BlockTableModel : AbstractTableModel() {
    private val keys = arrayOf(
        "blocks.col.start", "blocks.col.duration", "blocks.col.active",
        "blocks.col.tokens", "blocks.col.models", "blocks.col.cost",
        "table.col.tokensPerHour", "table.col.costPerHour"
    )
    private var rows: List<SessionBlock> = emptyList()
    fun setRows(r: List<SessionBlock>) { rows = r; fireTableDataChanged() }
    override fun getRowCount() = rows.size
    override fun getColumnCount() = keys.size
    override fun getColumnName(c: Int): String = ccideaMsg(keys[c])
    override fun getColumnClass(c: Int): Class<*> = when (c) {
        3, 6 -> Long::class.javaObjectType
        5, 7 -> Double::class.javaObjectType
        else -> String::class.java
    }
    override fun getValueAt(r: Int, c: Int): Any {
        val row = rows[r]
        val end = row.actualEndTime ?: row.endTime
        val durationSec = Duration.between(row.startTime, end).seconds.coerceAtLeast(60L)
        val hours = durationSec / 3600.0
        return when (c) {
            0 -> row.startTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            1 -> {
                val d = Duration.between(row.startTime, end)
                if (row.isGap) ccideaMsg("blocks.gap") else "${d.toHours()}h${d.toMinutesPart()}m"
            }
            2 -> if (row.isActive) "•" else ""
            3 -> row.totalTokens
            4 -> row.models.joinToString(",")
            5 -> row.totalCost
            6 -> if (row.isGap) 0L else (row.totalTokens / hours).toLong()
            7 -> if (row.isGap) 0.0 else row.totalCost / hours
            else -> ""
        }
    }
}
