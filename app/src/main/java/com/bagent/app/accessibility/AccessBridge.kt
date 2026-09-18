package com.bagent.app.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections

/**
 * Bridge between the running AccessibilityService and the agent's tools.
 * Tools address nodes by index into the latest captured snapshot, preferring
 * semantic information over raw coordinates.
 */
object AccessBridge {

    @Volatile
    private var serviceRef: BAgentAccessibilityService? = null

    private val snapshot = Collections.synchronizedList(mutableListOf<AccessibilityNodeInfo>())

    fun attach(service: BAgentAccessibilityService) {
        serviceRef = service
    }

    fun detach(service: BAgentAccessibilityService) {
        if (serviceRef === service) serviceRef = null
        synchronized(snapshot) { snapshot.clear() }
    }

    fun connected(): Boolean = serviceRef != null

    fun service(): BAgentAccessibilityService? = serviceRef

    fun setSnapshot(nodes: List<AccessibilityNodeInfo>) {
        synchronized(snapshot) {
            snapshot.clear()
            snapshot.addAll(nodes)
        }
    }

    fun snapshotNodes(): List<AccessibilityNodeInfo> = synchronized(snapshot) { snapshot.toList() }

    fun nodeAt(index: Int): AccessibilityNodeInfo? = snapshotNodes().getOrNull(index)
}