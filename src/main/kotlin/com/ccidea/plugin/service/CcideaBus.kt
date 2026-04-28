package com.ccidea.plugin.service

import com.intellij.util.messages.Topic
import java.time.Instant

interface RefreshListener {
    fun refreshed(at: Instant)
}

object CcideaBus {
    val TOPIC: Topic<RefreshListener> =
        Topic.create("ccidea.refresh", RefreshListener::class.java, Topic.BroadcastDirection.NONE)
}
