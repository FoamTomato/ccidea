package com.ccidea.plugin.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.table.TableColumn

/**
 * Adds a "configure visible columns" button next to a [JBTable]. Clicking it pops up a
 * checkbox list of all model columns, and toggling a checkbox shows/hides that column.
 *
 * The hidden columns are kept in [hidden] (a snapshot so we can re-insert with the
 * original ordering). We refer to columns by their *model index* for stability.
 */
class ColumnFilterButton(private val table: JBTable) {
    private val hidden: MutableMap<Int, TableColumn> = LinkedHashMap()

    val component: JComponent = JButton(AllIcons.General.GearPlain).apply {
        toolTipText = "Show/hide columns"
        margin = JBUI.emptyInsets()
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        addActionListener { showPopup(this) }
    }

    private fun showPopup(anchor: JComponent) {
        val modelColumnCount = table.model.columnCount
        // Build a snapshot of (modelIndex, displayName, currentlyVisible) preserving
        // the model's natural order.
        val items = (0 until modelColumnCount).map { mi ->
            val name = table.model.getColumnName(mi)
            val visible = !hidden.containsKey(mi)
            ColumnItem(mi, name, visible)
        }
        val step = object : BaseListPopupStep<ColumnItem>("Columns", items) {
            override fun getTextFor(value: ColumnItem) = (if (value.visible) "✓ " else "  ") + value.name
            override fun isSelectable(value: ColumnItem) = true
            override fun onChosen(selected: ColumnItem, finalChoice: Boolean): PopupStep<*>? {
                toggle(selected.modelIndex)
                // Keep the popup open by re-showing it after the toggle.
                showPopup(anchor)
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(anchor)
    }

    private fun toggle(modelIndex: Int) {
        if (hidden.containsKey(modelIndex)) {
            // Re-add at the right insertion point: walk model columns and find the
            // first currently-visible column whose modelIndex is greater than ours.
            val column = hidden.remove(modelIndex) ?: return
            val cm = table.columnModel
            // Build a list of currently visible model indices in column-model order.
            val visibleModelIndices = (0 until cm.columnCount).map { cm.getColumn(it).modelIndex }
            val insertAt = visibleModelIndices.indexOfFirst { it > modelIndex }
                .let { if (it == -1) cm.columnCount else it }
            cm.addColumn(column)
            // addColumn always appends; move it to the right slot.
            val lastIdx = cm.columnCount - 1
            if (insertAt < lastIdx) cm.moveColumn(lastIdx, insertAt)
        } else {
            // Hide: locate the view index for this modelIndex.
            val cm = table.columnModel
            val viewIndex = (0 until cm.columnCount).firstOrNull { cm.getColumn(it).modelIndex == modelIndex }
                ?: return
            val col = cm.getColumn(viewIndex)
            hidden[modelIndex] = col
            cm.removeColumn(col)
        }
    }

    private data class ColumnItem(val modelIndex: Int, val name: String, val visible: Boolean)
}
