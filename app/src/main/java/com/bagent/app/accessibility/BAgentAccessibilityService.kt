package com.bagent.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Real AccessibilityService exposing on-screen UI to the agent. The agent
 * addresses semantic nodes; coordinates are never blindly clicked.
 */
class BAgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are observed but the agent captures trees on demand, which is
        // cheaper and always fresh.
    }

    override fun onInterrupt() {
        // No long-running feedback to interrupt.
    }

    override fun onDestroy() {
        AccessBridge.detach(this)
        super.onDestroy()
    }

    /** Flatten the active window into a bounded list of semantic nodes. */
    fun captureNodes(maxNodes: Int = 400, maxDepth: Int = 25): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && out.size < maxNodes) {
            val (node, depth) = queue.removeFirst()
            out.add(node)
            if (depth >= maxDepth) continue
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                queue.add(child to depth + 1)
            }
        }
        AccessBridge.setSnapshot(out)
        return out
    }

    fun activePackage(): String? = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
}