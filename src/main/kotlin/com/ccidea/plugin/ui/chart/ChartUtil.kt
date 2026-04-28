package com.ccidea.plugin.ui.chart

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.JBColor
import org.jetbrains.letsPlot.batik.plot.component.DefaultPlotPanelBatik
import org.jetbrains.letsPlot.core.util.MonolithicCommon
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.intern.toSpec
import org.jetbrains.letsPlot.themes.elementLine
import org.jetbrains.letsPlot.themes.elementRect
import org.jetbrains.letsPlot.themes.elementText
import org.jetbrains.letsPlot.themes.theme
import java.awt.Color
import javax.swing.JComponent

object ChartUtil {

    /**
     * Stable category colors used across all charts so the legend means the same thing
     * everywhere. Kept light enough to read on dark themes and dark enough on light themes.
     */
    object Series {
        // Series labels are localized; ORDER is rebuilt every time so changing the
        // UI language refreshes the legend on the next chart rebuild.
        val INPUT: String get() = com.ccidea.plugin.i18n.ccideaMsg("chart.series.input")
        val OUTPUT: String get() = com.ccidea.plugin.i18n.ccideaMsg("chart.series.output")
        val CACHE_CREATE_5M: String get() = com.ccidea.plugin.i18n.ccideaMsg("chart.series.cacheCreate5m")
        val CACHE_CREATE_1H: String get() = com.ccidea.plugin.i18n.ccideaMsg("chart.series.cacheCreate1h")
        val CACHE_READ: String get() = com.ccidea.plugin.i18n.ccideaMsg("chart.series.cacheRead")

        val ORDER: List<String>
            get() = listOf(INPUT, OUTPUT, CACHE_CREATE_5M, CACHE_CREATE_1H, CACHE_READ)
        val COLORS = listOf("#4FC3F7", "#81C784", "#FFB74D", "#FF8A65", "#BA68C8")
    }

    fun jbHex(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    fun ideThemeOverlay(plot: Plot): Plot {
        val fg = jbHex(JBColor.foreground())
        val grid = jbHex(JBColor.border())
        val transparent = "rgba(0,0,0,0)"
        return plot + theme(
            plotBackground = elementRect(fill = transparent, color = transparent),
            panelBackground = elementRect(fill = transparent, color = transparent),
            legendBackground = elementRect(fill = transparent, color = transparent),
            panelGrid = elementLine(color = grid),
            axisLine = elementLine(color = fg),
            axisTicks = elementLine(color = fg),
            axisText = elementText(color = fg),
            axisTitle = elementText(color = fg),
            legendText = elementText(color = fg),
            legendTitle = elementText(color = fg),
            plotTitle = elementText(color = fg)
        )
    }

    /** Apply the IDE-theme overlay and convert to a Swing component via the Batik backend. */
    fun toSwing(plot: Plot): JComponent {
        val themed = ideThemeOverlay(plot)
        val rawSpec = themed.toSpec()
        val processed = MonolithicCommon.processRawSpecs(rawSpec, frontendOnly = false)
        val log = thisLogger()
        // Batik's SAXDocumentFactory does ServiceLoader.load(SAXParserFactory) at <clinit>,
        // which fails inside IntelliJ because the platform classloader has its own copy of
        // Xerces. Pin the thread-context classloader to *our* plugin classloader for the
        // duration of plot construction so Batik's service discovery resolves classes from
        // the same loader.
        val original = Thread.currentThread().contextClassLoader
        return try {
            Thread.currentThread().contextClassLoader = ChartUtil::class.java.classLoader
            DefaultPlotPanelBatik(
                processedSpec = processed,
                preserveAspectRatio = false,
                preferredSizeFromPlot = false,
                repaintDelay = 300
            ) { msgs -> msgs.forEach { log.info("[lets-plot] $it") } }.also {
                it.isOpaque = false
            }
        } finally {
            Thread.currentThread().contextClassLoader = original
        }
    }
}
