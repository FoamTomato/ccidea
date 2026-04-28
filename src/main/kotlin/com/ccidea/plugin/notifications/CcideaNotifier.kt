package com.ccidea.plugin.notifications

import com.ccidea.plugin.data.model.SessionBlock
import com.ccidea.plugin.settings.CcideaSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class CcideaNotifier {

    private data class State(
        var warned80: Boolean = false,
        var warned95: Boolean = false,
        var resetSoonFired: Boolean = false
    )

    private val byBlock = ConcurrentHashMap<Instant, State>()

    fun evaluate(block: SessionBlock?, limit: Long, now: Instant = Instant.now()) {
        if (block == null || !block.isActive || limit <= 0) return
        val s = byBlock.computeIfAbsent(block.startTime) { State() }
        val pct = block.totalTokens.toDouble() / limit.toDouble()
        val settings = CcideaSettings.getInstance().state

        if (pct >= settings.errorPercent / 100.0 && !s.warned95) {
            notify(NotificationType.ERROR,
                "ccidea: block at ${(pct * 100).toInt()}%",
                "${block.totalTokens} / $limit tokens used in current 5h block")
            s.warned95 = true
            s.warned80 = true
        } else if (pct >= settings.warnPercent / 100.0 && !s.warned80) {
            notify(NotificationType.WARNING,
                "ccidea: block at ${(pct * 100).toInt()}%",
                "${block.totalTokens} / $limit tokens used in current 5h block")
            s.warned80 = true
        }

        val remaining = Duration.between(now, block.endTime)
        if (!s.resetSoonFired &&
            remaining > Duration.ZERO &&
            remaining <= Duration.ofMinutes(settings.resetSoonMinutes.toLong())
        ) {
            notify(NotificationType.INFORMATION,
                "ccidea: block resets in ${remaining.toMinutes()} min",
                "Current 5h block ends at ${block.endTime}")
            s.resetSoonFired = true
        }
    }

    private fun notify(type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ccidea.balloon")
            .createNotification(title, content, type)
            .notify(null)
    }

    companion object {
        fun getInstance(): CcideaNotifier = ApplicationManager.getApplication().service()
    }
}
