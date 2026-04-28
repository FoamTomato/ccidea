package com.ccidea.plugin.ui.toolwindow

import com.ccidea.plugin.i18n.ccideaMsg
import com.ccidea.plugin.poller.PollerService
import com.ccidea.plugin.service.CcideaBus
import com.ccidea.plugin.service.RefreshListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import java.time.Instant

class CcideaToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tabs = JBTabbedPane()
        val daily = DailyTab(); val monthly = MonthlyTab()
        val sessions = SessionsTab(); val blocks = BlocksTab()
        val patterns = PatternsTab()
        val live = LiveTab(project)
        tabs.addTab(ccideaMsg("toolwindow.tab.live"), live)
        tabs.addTab(ccideaMsg("toolwindow.tab.daily"), daily)
        tabs.addTab(ccideaMsg("toolwindow.tab.monthly"), monthly)
        tabs.addTab(ccideaMsg("toolwindow.tab.sessions"), sessions)
        tabs.addTab(ccideaMsg("toolwindow.tab.blocks"), blocks)
        tabs.addTab(ccideaMsg("toolwindow.tab.patterns"), patterns)
        val content = ContentFactory.getInstance().createContent(tabs, "", false)
        // Tie tab disposables (and their internal ChartPanel + messageBus connections) to the
        // tool window content so they are torn down on window close / project close.
        Disposer.register(content, live)
        Disposer.register(content, daily)
        Disposer.register(content, monthly)
        Disposer.register(content, sessions)
        Disposer.register(content, blocks)
        Disposer.register(content, patterns)

        // Re-label the tabs when the user changes uiLanguage (signaled via CcideaBus.refreshed).
        val conn = ApplicationManager.getApplication().messageBus.connect(content)
        conn.subscribe(CcideaBus.TOPIC, object : RefreshListener {
            override fun refreshed(at: Instant) {
                tabs.setTitleAt(0, ccideaMsg("toolwindow.tab.live"))
                tabs.setTitleAt(1, ccideaMsg("toolwindow.tab.daily"))
                tabs.setTitleAt(2, ccideaMsg("toolwindow.tab.monthly"))
                tabs.setTitleAt(3, ccideaMsg("toolwindow.tab.sessions"))
                tabs.setTitleAt(4, ccideaMsg("toolwindow.tab.blocks"))
                tabs.setTitleAt(5, ccideaMsg("toolwindow.tab.patterns"))
            }
        })

        toolWindow.contentManager.addContent(content)

        // R6: tell the poller whether our tool window is currently visible. The first
        // time content is created the window is being shown, so seed visible=true.
        PollerService.getInstance().setToolWindowVisible(toolWindow.isVisible)
        project.messageBus.connect(content).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(manager: ToolWindowManager) {
                    val tw = manager.getToolWindow("Ccidea") ?: return
                    PollerService.getInstance().setToolWindowVisible(tw.isVisible)
                }
            }
        )
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
