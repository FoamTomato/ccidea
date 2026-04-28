package com.ccidea.plugin.ui.chart

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.intern.toSpec
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Container that holds one Lets-Plot chart, rebuilds the chart on theme changes,
 * and exposes a [render] method for callers to push fresh data.
 */
class ChartPanel(parent: Disposable) : JPanel(BorderLayout()), Disposable {
    private var current: JComponent? = null
    private var producer: (() -> Plot?)? = null
    private val empty = JBLabel("no data yet").also { it.horizontalAlignment = javax.swing.SwingConstants.CENTER }

    /** Last-rendered plot spec signature; used to skip rebuild (and therefore avoid flicker)
     *  when the underlying data has not changed since the previous tick. */
    private var lastSpecKey: Int? = null

    init {
        isOpaque = false
        add(empty, BorderLayout.CENTER)
        Disposer.register(parent, this)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { rebuild() })
    }

    /** Set/replace the producer that builds the plot from the latest data. Triggers a rebuild. */
    fun render(producer: () -> Plot?) {
        this.producer = producer
        rebuild()
    }

    private fun rebuild() {
        val producer = this.producer
        if (producer == null) {
            swapTo(empty.also { it.text = "no data yet" }, key = 0)
            return
        }
        val plot = try { producer() } catch (t: Throwable) {
            thisLogger().warn("ccidea: chart producer failed", t)
            swapTo(empty.also { it.text = "chart error: ${t.javaClass.simpleName}: ${t.message ?: ""}" }, key = -1)
            return
        }
        if (plot == null) {
            swapTo(empty.also { it.text = "no data yet" }, key = 0)
            return
        }
        // Compute a stable signature of the plot's spec. If unchanged since the last
        // render, skip the whole component swap → no flicker on idle 30s ticks.
        val key = try { plot.toSpec().toString().hashCode() } catch (_: Throwable) { System.identityHashCode(plot) }
        if (current != null && lastSpecKey == key) return

        val swing = try { ChartUtil.toSwing(plot) } catch (t: Throwable) {
            thisLogger().warn("ccidea: chart render failed", t)
            swapTo(empty.also { it.text = "render error: ${t.javaClass.simpleName}: ${t.message ?: ""}" }, key = -1)
            return
        }
        swapTo(swing, key = key)
    }

    /** Atomic component swap: only remove the old component after the new one is ready,
     *  and only repaint once. Prevents the brief blank frame the previous "remove → add"
     *  approach produced. */
    private fun swapTo(next: JComponent, key: Int) {
        if (current === next && lastSpecKey == key) return
        val old = current
        if (old !== next) {
            if (old != null) remove(old)
            else remove(empty)
            add(next, BorderLayout.CENTER)
            current = next
        }
        lastSpecKey = key
        revalidate(); repaint()
    }

    override fun dispose() {
        producer = null
        current = null
        // LAF connection is auto-disposed via messageBus.connect(this).
    }
}
