package com.ccidea.plugin.ui.toolwindow

import com.ccidea.plugin.data.Format
import com.ccidea.plugin.data.ProjectKeyFormat
import com.intellij.ui.table.JBTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer

/** Render Long/Double column values via [Format]. Right-aligned for numbers. */
class TokenCellRenderer : DefaultTableCellRenderer() {
    init { horizontalAlignment = SwingConstants.RIGHT }
    override fun setValue(value: Any?) {
        text = when (value) {
            null -> ""
            is Long -> Format.tokens(value)
            is Number -> Format.tokens(value.toLong())
            else -> value.toString()
        }
    }
}

class CostCellRenderer : DefaultTableCellRenderer() {
    init { horizontalAlignment = SwingConstants.RIGHT }
    override fun setValue(value: Any?) {
        text = when (value) {
            null -> ""
            is Double -> Format.cost(value)
            is Number -> Format.cost(value.toDouble())
            else -> value.toString()
        }
    }
}

class ProjectCellRenderer : DefaultTableCellRenderer() {
    override fun setValue(value: Any?) {
        when (value) {
            null -> { text = ""; toolTipText = null }
            is String -> {
                text = ProjectKeyFormat.shortName(value)
                toolTipText = ProjectKeyFormat.reconstructPath(value)
            }
            else -> { text = value.toString(); toolTipText = null }
        }
    }
}

/**
 * Renders a comma-separated model list (or a Set passed as Any) onto multiple lines via
 * HTML `<br/>` so wide model names like `claude-opus-4-6,claude-haiku-4-5-20251001` no
 * longer get cut off mid-word. Combine with [TableSetup.fitRowHeights] to expand the
 * row height accordingly.
 */
class ModelsCellRenderer : DefaultTableCellRenderer() {
    override fun setValue(value: Any?) {
        val items: List<String> = when (value) {
            null -> emptyList()
            is Collection<*> -> value.filterNotNull().map { it.toString() }
            is String -> value.split(',').map { it.trim() }.filter { it.isNotBlank() }
            else -> listOf(value.toString())
        }
        text = if (items.isEmpty()) "" else "<html>${items.joinToString("<br/>")}</html>"
        toolTipText = if (items.size > 1) items.joinToString("\n") else null
    }
}

/** Helper to apply renderers consistently across the four tables that need them. */
object TableSetup {
    fun applyToken(table: JBTable, columnIndex: Int) {
        table.columnModel.getColumn(columnIndex).cellRenderer = TokenCellRenderer()
    }
    fun applyCost(table: JBTable, columnIndex: Int) {
        table.columnModel.getColumn(columnIndex).cellRenderer = CostCellRenderer()
    }
    fun applyProject(table: JBTable, columnIndex: Int) {
        table.columnModel.getColumn(columnIndex).cellRenderer = ProjectCellRenderer()
    }

    /** Apply a multi-line model renderer; caller should also use [autoRowHeights]. */
    fun applyModels(table: JBTable, columnIndex: Int) {
        table.columnModel.getColumn(columnIndex).cellRenderer = ModelsCellRenderer()
    }

    /**
     * Recompute per-row preferred heights so multi-line cells (e.g. ModelsCellRenderer)
     * are fully visible. Call after every data refresh.
     */
    fun autoRowHeights(table: JBTable) {
        val cm = table.columnModel
        for (row in 0 until table.rowCount) {
            var rowHeight = table.rowHeight
            for (col in 0 until cm.columnCount) {
                val comp = table.prepareRenderer(table.getCellRenderer(row, col), row, col)
                rowHeight = maxOf(rowHeight, comp.preferredSize.height)
            }
            if (table.getRowHeight(row) != rowHeight) table.setRowHeight(row, rowHeight)
        }
    }

    /**
     * Run [block] (typically a `model.setRows(...)` call) while preserving the user's
     * current sort keys and selection. Solves R7: refresh shouldn't reset the sort.
     */
    fun preservingSort(table: JBTable, block: () -> Unit) {
        val sorter = table.rowSorter
        val savedKeys = sorter?.sortKeys?.toList()
        val savedSelected = table.selectedRows.toList()
        block()
        if (sorter != null && savedKeys != null) sorter.sortKeys = savedKeys
        if (savedSelected.isNotEmpty()) {
            table.selectionModel.clearSelection()
            for (row in savedSelected) {
                if (row in 0 until table.rowCount) table.selectionModel.addSelectionInterval(row, row)
            }
        }
        // After data is settled, re-fit row heights so multi-line renderers expand properly.
        autoRowHeights(table)
    }
}
