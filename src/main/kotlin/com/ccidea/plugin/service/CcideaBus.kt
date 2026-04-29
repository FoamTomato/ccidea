package com.ccidea.plugin.service

import com.intellij.util.messages.Topic
import java.time.Instant

interface RefreshListener {
    /** [forced] = true when the user explicitly invoked the refresh action (or first
     *  show of a tab). Tabs use this to decide whether expensive UI like charts should
     *  rebuild on every 30s tick or only when the user actually asked for it. */
    fun refreshed(at: Instant, forced: Boolean = false)
}

object CcideaBus {
    val TOPIC: Topic<RefreshListener> =
        Topic.create("ccidea.refresh", RefreshListener::class.java, Topic.BroadcastDirection.NONE)
}
