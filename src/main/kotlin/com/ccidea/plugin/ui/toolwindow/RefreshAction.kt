package com.ccidea.plugin.ui.toolwindow

import com.ccidea.plugin.poller.PollerService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class RefreshAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        PollerService.getInstance().runOnce()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
