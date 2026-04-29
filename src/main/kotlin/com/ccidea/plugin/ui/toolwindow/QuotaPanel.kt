package com.ccidea.plugin.ui.toolwindow

import com.ccidea.plugin.blocks.BlockService
import com.ccidea.plugin.data.Format
import com.ccidea.plugin.i18n.ccideaMsg
import com.ccidea.plugin.quota.QuotaCalculator
import com.ccidea.plugin.quota.QuotaWindow
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.UIManager

/**
 * Local-only quota estimation panel (no network calls). Shows three rows:
 *   • Current 5h block — limit comes from BlockService (user-configured or auto-detected peak)
 *   • This week (Mon 00:00 → next Mon 00:00, system TZ)
 *   • This month (calendar month, system TZ)
 *
 * Limits are user-configured per-window in Settings → ccidea (token preferred over USD).
 * When no limit is configured we render usage text only — no progress bar — to avoid
 * pretending we know what 100% means.
 */
class QuotaPanel : JPanel(BorderLayout()) {

    private val grid = JPanel(GridBagLayout())

    init {
        border = JBUI.Borders.empty(4, 6, 6, 6)
        add(grid, BorderLayout.CENTER)
    }

    fun refresh() {
        grid.removeAll()
        val entries = BlockService.getInstance().all()
        val windows = QuotaCalculator.computeAll(entries)
        windows.forEachIndexed { i, w -> addRow(i, w) }
        revalidate()
        repaint()
    }

    private fun addRow(row: Int, w: QuotaWindow) {
        val label = JBLabel(labelFor(w.label)).apply {
            preferredSize = Dimension(110, preferredSize.height)
        }
        val gc = GridBagConstraints().apply {
            gridx = 0; gridy = row
            anchor = GridBagConstraints.WEST
            insets = Insets(2, 4, 2, 8)
        }
        grid.add(label, gc)

        // Progress bar slot — only when a percent is available
        gc.gridx = 1; gc.weightx = 1.0; gc.fill = GridBagConstraints.HORIZONTAL
        if (w.percent != null) {
            val pct = w.percent.coerceIn(0.0, 999.0)
            val bar = JProgressBar(0, 100).apply {
                value = pct.coerceAtMost(100.0).toInt()
                isStringPainted = false
                preferredSize = Dimension(160, 12)
                foreground = colorFor(pct)
            }
            grid.add(bar, gc)
        } else {
            grid.add(JPanel().apply { isOpaque = false; preferredSize = Dimension(160, 12) }, gc)
        }

        // Percent label (separate from the bar so theme contrast doesn't matter)
        gc.gridx = 2; gc.weightx = 0.0; gc.fill = GridBagConstraints.NONE
        if (w.percent != null) {
            val pctText = "%.0f%%".format(w.percent)
            grid.add(JBLabel(pctText).apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                border = JBUI.Borders.emptyLeft(8)
            }, gc)
        } else {
            grid.add(JBLabel(""), gc)
        }

        // Usage text
        gc.gridx = 3; gc.weightx = 0.0; gc.fill = GridBagConstraints.NONE
        grid.add(JBLabel(usageText(w)).apply {
            border = JBUI.Borders.emptyLeft(8)
        }, gc)

        // Reset hint
        gc.gridx = 4
        val resetText = w.resetsAt?.let {
            ccideaMsg("quota.label.resetsIn", QuotaCalculator.timeUntil(it))
        } ?: ""
        grid.add(JBLabel(resetText).apply {
            foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            border = JBUI.Borders.emptyLeft(10)
        }, gc)
    }

    private fun labelFor(l: QuotaWindow.WindowLabel): String = when (l) {
        QuotaWindow.WindowLabel.BLOCK -> ccideaMsg("quota.label.block")
        QuotaWindow.WindowLabel.WEEK -> ccideaMsg("quota.label.week")
        QuotaWindow.WindowLabel.MONTH -> ccideaMsg("quota.label.month")
    }

    private fun usageText(w: QuotaWindow): String {
        // When the percent comes from Anthropic's /usage endpoint, the local token/cost
        // sum is a different mental model (raw JSONL volume vs. subscription utilization).
        // Showing both side-by-side looks self-contradictory ("22% used · 320M · $225"),
        // so for OFFICIAL rows we only show the percent + reset; for LOCAL rows we show
        // the local usage figures.
        if (w.source == com.ccidea.plugin.quota.QuotaSource.OFFICIAL) return ""
        val tokens = Format.tokens(w.tokens)
        val cost = Format.cost(w.cost)
        return when {
            w.tokenLimit > 0 -> "$tokens / ${Format.tokens(w.tokenLimit)} · $cost"
            w.costLimit > 0 -> "$tokens · $cost / ${Format.cost(w.costLimit)}"
            else -> "$tokens · $cost"
        }
    }

    private fun colorFor(pct: Double): Color = when {
        pct >= 95.0 -> Color(0xE5, 0x4B, 0x4B)
        pct >= 80.0 -> Color(0xE5, 0xA1, 0x4B)
        else -> Color(0x4B, 0xA1, 0xE5)
    }
}
