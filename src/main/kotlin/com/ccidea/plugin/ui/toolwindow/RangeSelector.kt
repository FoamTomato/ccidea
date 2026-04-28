package com.ccidea.plugin.ui.toolwindow

import com.ccidea.plugin.i18n.ccideaMsg
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JLabel

/**
 * Daily / Monthly tabs use this to constrain the visible range. A user-friendly
 * dropdown of presets (last 30/60/90/180 days, all). Default is 30 (R4 spec).
 */
class RangeSelector(default: Int = 30, val onChange: () -> Unit) {
    data class Preset(val days: Int, val labelKey: String) {
        override fun toString(): String = ccideaMsg(labelKey)
    }

    private val presets = listOf(
        Preset(30, "range.last30"),
        Preset(60, "range.last60"),
        Preset(90, "range.last90"),
        Preset(180, "range.last180"),
        Preset(365, "range.last365"),
        Preset(0, "range.all")
    )

    private val combo = JComboBox<Preset>().apply {
        model = DefaultComboBoxModel(presets.toTypedArray())
        selectedItem = presets.firstOrNull { it.days == default } ?: presets[0]
        addActionListener { onChange() }
    }

    val component: JComponent = JPanel().apply {
        add(JLabel(ccideaMsg("range.label")))
        add(combo)
    }

    val selectedDays: Int get() = (combo.selectedItem as? Preset)?.days ?: 30
}
