package com.ccidea.plugin.ui.settings

import com.ccidea.plugin.i18n.ccideaMsg
import com.ccidea.plugin.poller.PollerService
import com.ccidea.plugin.settings.CcideaSettings
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants

/**
 * Hand-rolled BoxLayout-based settings UI. We previously used FormBuilder + Kotlin UI
 * DSL, but in IntelliJ Platform 2024.2 a sandbox-load layout-rendering quirk left every
 * widget collapsed to zero width — labels were visible, but every spinner/text field/
 * dropdown was invisible. A plain vertical Box of "label : widget" rows is unambiguous.
 */
class CcideaConfigurable : Configurable {

    private data class LangItem(val code: String, val labelKey: String) {
        override fun toString(): String = ccideaMsg(labelKey)
    }
    private data class UnitItem(val code: String, val labelKey: String) {
        override fun toString(): String = ccideaMsg(labelKey)
    }
    private val LANGS = listOf(
        LangItem("system", "settings.uiLanguage.system"),
        LangItem("en", "settings.uiLanguage.en"),
        LangItem("zh", "settings.uiLanguage.zh")
    )
    private val UNITS = listOf(
        UnitItem("smart", "settings.tokenUnit.smart"),
        UnitItem("raw", "settings.tokenUnit.raw"),
        UnitItem("kilo", "settings.tokenUnit.kilo")
    )

    private val refresh = JSpinner(SpinnerNumberModel(30, 5, 600, 5))
    private val warn = JSpinner(SpinnerNumberModel(80, 0, 100, 5))
    private val err = JSpinner(SpinnerNumberModel(95, 0, 100, 5))
    private val resetSoon = JSpinner(SpinnerNumberModel(10, 0, 60, 1))
    private val claudeDir = JBTextField(30)
    private val customLimit = JBTextField(15)
    private val weeklyTokenLimit = JBTextField(15)
    private val weeklyCostLimit = JBTextField(15)
    private val monthlyTokenLimit = JBTextField(15)
    private val monthlyCostLimit = JBTextField(15)
    private val offline = JBCheckBox()
    private val showStatus = JBCheckBox()
    private val oneHourPremium = JBCheckBox()
    private val pollOnlyVisible = JBCheckBox()
    private val language = JComboBox<LangItem>()
    private val tokenUnit = JComboBox<UnitItem>()
    private val chartDailyBar = JBCheckBox()
    private val chartDailyTrend = JBCheckBox()
    private val chartDailyCost = JBCheckBox()
    private val chartBlocksBurn = JBCheckBox()
    private val chartHeatmap = JBCheckBox()
    private val chartHitRatio = JBCheckBox()

    private var root: JPanel? = null

    override fun getDisplayName(): String = "ccidea"

    override fun createComponent(): JComponent {
        loadFromSettings()

        val box = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
        }

        // Section: General
        box.add(sectionTitle(ccideaMsg("settings.section.general")))
        box.add(row(ccideaMsg("settings.uiLanguage"), language))
        box.add(row(ccideaMsg("settings.tokenUnit"), tokenUnit))
        box.add(row(ccideaMsg("settings.refresh"), refresh))
        box.add(row(ccideaMsg("settings.warn"), warn))
        box.add(row(ccideaMsg("settings.error"), err))
        box.add(row(ccideaMsg("settings.resetSoon"), resetSoon))
        box.add(row(ccideaMsg("settings.claudeDir"), claudeDir))
        box.add(row(ccideaMsg("settings.tokenLimit"), customLimit))

        box.add(separator())
        box.add(sectionTitle(ccideaMsg("settings.section.quota")))
        box.add(row(ccideaMsg("settings.weeklyTokenLimit"), weeklyTokenLimit))
        box.add(row(ccideaMsg("settings.weeklyCostLimit"), weeklyCostLimit))
        box.add(row(ccideaMsg("settings.monthlyTokenLimit"), monthlyTokenLimit))
        box.add(row(ccideaMsg("settings.monthlyCostLimit"), monthlyCostLimit))

        box.add(separator())
        box.add(sectionTitle(ccideaMsg("settings.section.toggles")))
        box.add(checkRow(offline, ccideaMsg("settings.offline")))
        box.add(checkRow(showStatus, ccideaMsg("settings.statusBar")))
        box.add(checkRow(oneHourPremium, ccideaMsg("settings.oneHourPremium")))
        box.add(checkRow(pollOnlyVisible, ccideaMsg("settings.pollOnlyVisible")))

        box.add(separator())
        box.add(sectionTitle(ccideaMsg("settings.chart.section")))
        box.add(checkRow(chartDailyBar, ccideaMsg("settings.chart.dailyBar")))
        box.add(checkRow(chartDailyTrend, ccideaMsg("settings.chart.dailyTrend")))
        box.add(checkRow(chartDailyCost, ccideaMsg("settings.chart.dailyCost")))
        box.add(checkRow(chartBlocksBurn, ccideaMsg("settings.chart.blocksBurn")))
        box.add(checkRow(chartHeatmap, ccideaMsg("settings.chart.heatmap")))
        box.add(checkRow(chartHitRatio, ccideaMsg("settings.chart.hitRatio")))

        // Push everything to the top.
        box.add(javax.swing.Box.createVerticalGlue())
        root = box
        return box
    }

    private fun sectionTitle(text: String): JComponent {
        val l = JBLabel("<html><b>$text</b></html>")
        l.alignmentX = Component.LEFT_ALIGNMENT
        l.border = JBUI.Borders.emptyBottom(4)
        return l
    }

    private fun separator(): JComponent = JSeparator(SwingConstants.HORIZONTAL).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 1)
        border = JBUI.Borders.empty(8, 0)
    }

    private fun row(label: String, widget: JComponent): JComponent {
        val p = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, widget.preferredSize.height + 12)
        }
        val l = JBLabel(label)
        l.preferredSize = Dimension(280, widget.preferredSize.height)
        p.add(l); p.add(widget)
        return p
    }

    private fun checkRow(box: JBCheckBox, label: String): JComponent {
        box.text = label
        box.alignmentX = Component.LEFT_ALIGNMENT
        return box
    }

    private fun loadFromSettings() {
        val s = CcideaSettings.getInstance().state
        refresh.value = s.refreshSeconds
        warn.value = s.warnPercent
        err.value = s.errorPercent
        resetSoon.value = s.resetSoonMinutes
        claudeDir.text = s.claudeConfigDir
        customLimit.text = if (s.customTokenLimit > 0) s.customTokenLimit.toString() else ""
        weeklyTokenLimit.text = if (s.weeklyTokenLimit > 0) s.weeklyTokenLimit.toString() else ""
        weeklyCostLimit.text = if (s.weeklyCostLimit > 0) s.weeklyCostLimit.toString() else ""
        monthlyTokenLimit.text = if (s.monthlyTokenLimit > 0) s.monthlyTokenLimit.toString() else ""
        monthlyCostLimit.text = if (s.monthlyCostLimit > 0) s.monthlyCostLimit.toString() else ""
        offline.isSelected = s.offlineOnly
        showStatus.isSelected = s.showStatusBarWidget
        oneHourPremium.isSelected = s.oneHourCachePremium
        pollOnlyVisible.isSelected = s.pollOnlyWhenVisible
        language.model = DefaultComboBoxModel(LANGS.toTypedArray())
        language.selectedItem = LANGS.firstOrNull { it.code == s.uiLanguage } ?: LANGS[0]
        tokenUnit.model = DefaultComboBoxModel(UNITS.toTypedArray())
        tokenUnit.selectedItem = UNITS.firstOrNull { it.code == s.tokenUnit } ?: UNITS[0]
        chartDailyBar.isSelected = s.showDailyStackedBar
        chartDailyTrend.isSelected = s.showDailyTokenTrend
        chartDailyCost.isSelected = s.showDailyCostTrend
        chartBlocksBurn.isSelected = s.showBlocksBurnRate
        chartHeatmap.isSelected = s.showPatternsHeatmap
        chartHitRatio.isSelected = s.showPatternsHitRatio
    }

    override fun isModified(): Boolean {
        val s = CcideaSettings.getInstance().state
        val selectedLang = (language.selectedItem as? LangItem)?.code ?: "system"
        val selectedUnit = (tokenUnit.selectedItem as? UnitItem)?.code ?: "smart"
        val parsedLimit = customLimit.text.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val parsedWeeklyTok = weeklyTokenLimit.text.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val parsedWeeklyCost = weeklyCostLimit.text.trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val parsedMonthlyTok = monthlyTokenLimit.text.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val parsedMonthlyCost = monthlyCostLimit.text.trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        return s.refreshSeconds != (refresh.value as Int) ||
            s.warnPercent != (warn.value as Int) ||
            s.errorPercent != (err.value as Int) ||
            s.resetSoonMinutes != (resetSoon.value as Int) ||
            s.claudeConfigDir != claudeDir.text ||
            s.offlineOnly != offline.isSelected ||
            s.customTokenLimit != parsedLimit ||
            s.weeklyTokenLimit != parsedWeeklyTok ||
            s.weeklyCostLimit != parsedWeeklyCost ||
            s.monthlyTokenLimit != parsedMonthlyTok ||
            s.monthlyCostLimit != parsedMonthlyCost ||
            s.showStatusBarWidget != showStatus.isSelected ||
            s.uiLanguage != selectedLang ||
            s.oneHourCachePremium != oneHourPremium.isSelected ||
            s.tokenUnit != selectedUnit ||
            s.pollOnlyWhenVisible != pollOnlyVisible.isSelected ||
            s.showDailyStackedBar != chartDailyBar.isSelected ||
            s.showDailyTokenTrend != chartDailyTrend.isSelected ||
            s.showDailyCostTrend != chartDailyCost.isSelected ||
            s.showBlocksBurnRate != chartBlocksBurn.isSelected ||
            s.showPatternsHeatmap != chartHeatmap.isSelected ||
            s.showPatternsHitRatio != chartHitRatio.isSelected
    }

    override fun apply() {
        val s = CcideaSettings.getInstance().state
        val premiumChanged = s.oneHourCachePremium != oneHourPremium.isSelected
        val dirChanged = s.claudeConfigDir != claudeDir.text.trim()

        s.refreshSeconds = refresh.value as Int
        s.warnPercent = warn.value as Int
        s.errorPercent = err.value as Int
        s.resetSoonMinutes = resetSoon.value as Int
        s.claudeConfigDir = claudeDir.text.trim()
        s.offlineOnly = offline.isSelected
        s.customTokenLimit = customLimit.text.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0L
        s.weeklyTokenLimit = weeklyTokenLimit.text.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0L
        s.weeklyCostLimit = weeklyCostLimit.text.trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        s.monthlyTokenLimit = monthlyTokenLimit.text.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0L
        s.monthlyCostLimit = monthlyCostLimit.text.trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        s.showStatusBarWidget = showStatus.isSelected
        s.uiLanguage = (language.selectedItem as? LangItem)?.code ?: "system"
        s.oneHourCachePremium = oneHourPremium.isSelected
        s.tokenUnit = (tokenUnit.selectedItem as? UnitItem)?.code ?: "smart"
        s.pollOnlyWhenVisible = pollOnlyVisible.isSelected
        s.showDailyStackedBar = chartDailyBar.isSelected
        s.showDailyTokenTrend = chartDailyTrend.isSelected
        s.showDailyCostTrend = chartDailyCost.isSelected
        s.showBlocksBurnRate = chartBlocksBurn.isSelected
        s.showPatternsHeatmap = chartHeatmap.isSelected
        s.showPatternsHitRatio = chartHitRatio.isSelected

        if (dirChanged) {
            com.ccidea.plugin.blocks.BlockService.getInstance().reset()
            com.ccidea.plugin.data.UsageDataLoader.getInstance().resetOffsets()
        } else if (premiumChanged) {
            com.ccidea.plugin.blocks.BlockService.getInstance().recomputeCosts()
        }
        PollerService.getInstance().applySettings()
        PollerService.getInstance().runOnce()
    }

    override fun reset() { loadFromSettings() }

    override fun disposeUIResources() { root = null }
}
