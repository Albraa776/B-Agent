package com.bagent.app.tools.accessibility

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.bagent.app.accessibility.AccessBridge
import com.bagent.app.accessibility.BAgentAccessibilityService
import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.tools.ToolBase
import com.bagent.app.tools.ToolEnv
import com.bagent.app.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Android UI automation through Accessibility. When the service is not
 * connected these tools honestly report the limitation instead of faking it.
 */
class AccessibilityTools {

    fun all(): List<ToolBase> = listOf(
        A11yStatus(), A11yGetWindow(), A11yFind(), A11yClick(),
        A11yLongClick(), A11yInputText(), A11yScroll(), A11yGlobalAction()
    )

    private suspend fun <T> onService(block: (BAgentAccessibilityService) -> T): T? =
        withContext(Dispatchers.Main) {
            AccessBridge.service()?.let(block)
        }

    private fun nodeToJson(node: AccessibilityNodeInfo, index: Int): JsonObject {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return buildJsonObject {
            put("index", index)
            put("text", node.text?.toString() ?: "")
            put("contentDescription", node.contentDescription?.toString() ?: "")
            put("className", node.className?.toString() ?: "")
            put("viewId", node.viewIdResourceName ?: "")
            put("package", node.packageName?.toString() ?: "")
            put("clickable", node.isClickable)
            put("longClickable", node.isLongClickable)
            put("scrollable", node.isScrollable)
            put("editable", node.isEditable)
            put("enabled", node.isEnabled)
            put("checked", node.isChecked)
            put("bounds", buildJsonObject {
                put("left", rect.left); put("top", rect.top)
                put("right", rect.right); put("bottom", rect.bottom)
            })
        }
    }

    private fun matchNode(node: AccessibilityNodeInfo, query: String): Boolean {
        val q = query.lowercase()
        return (node.text?.toString()?.lowercase()?.contains(q) == true) ||
            (node.contentDescription?.toString()?.lowercase()?.contains(q) == true) ||
            (node.viewIdResourceName?.lowercase()?.contains(q) == true)
    }

    private fun clickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    inner class A11yStatus : ToolBase() {
        override val id = "a11y_status"
        override val name = "a11y_status"
        override val category = "accessibility"
        override val description = "Report whether the B Agent accessibility service is connected and the active package."
        override val risk = RiskLevel.SAFE
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val connected = AccessBridge.connected()
            val pkg = onService { it.activePackage() }
            return ToolResult.Success(buildJsonObject {
                put("connected", connected)
                put("activePackage", pkg ?: "")
                put("note", if (connected) "service connected" else "enable B Agent Accessibility Control in Android settings")
            }.toString())
        }
    }

    inner class A11yGetWindow : ToolBase() {
        override val id = "a11y_get_window"
        override val name = "a11y_get_window"
        override val category = "accessibility"
        override val description = "Capture a semantic snapshot of the current screen as a flat node list."
        override val requiredPermission = PermAction.ACCESSIBILITY_CONTROL
        override val risk = RiskLevel.CRITICAL
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"maxNodes":{"type":"integer"}}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            if (!AccessBridge.connected()) return ToolResult.Failure("accessibility service is not connected")
            val max = (args["maxNodes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 300).coerceIn(10, 800)
            val nodes = onService { it.captureNodes(max) } ?: return ToolResult.Failure("service unavailable")
            return ToolResult.Success(buildJsonObject {
                put("count", nodes.size)
                put("nodes", buildJsonArray { nodes.forEachIndexed { i, n -> add(nodeToJson(n, i)) } })
            }.toString())
        }
    }

    inner class A11yFind : ToolBase() {
        override val id = "a11y_find"
        override val name = "a11y_find"
        override val category = "accessibility"
        override val description = "Find on-screen nodes by text, content description or view id."
        override val requiredPermission = PermAction.ACCESSIBILITY_CONTROL
        override val risk = RiskLevel.CRITICAL
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            if (!AccessBridge.connected()) return ToolResult.Failure("accessibility service is not connected")
            val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing query")
            val nodes = onService { it.captureNodes(500) } ?: return ToolResult.Failure("service unavailable")
            val matches = nodes.filter { matchNode(it, query) }
            return ToolResult.Success(buildJsonObject {
                put("count", matches.size)
                put("matches", buildJsonArray {
                    matches.forEach { n -> add(nodeToJson(n, AccessBridge.snapshotNodes().indexOf(n))) }
                })
            }.toString())
        }
    }

    inner class A11yClick : ToolBase() {
        override val id = "a11y_click"
        override val name = "a11y_click"
        override val category = "accessibility"
        override val description = "Click a node by index or by matching text/content description."
        override val requiredPermission = PermAction.ACCESSIBILITY_CONTROL
        override val risk = RiskLevel.CRITICAL
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"index":{"type":"integer"},"query":{"type":"string"}}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            if (!AccessBridge.connected()) return ToolResult.Failure("accessibility service is not connected")
            val index = args["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val query = args["query"]?.jsonPrimitive?.contentOrNull
            val nodes = onService { it.captureNodes(500) } ?: return ToolResult.Failure("service unavailable")
            val target = when {
                index != null -> nodes.getOrNull(index)
                query != null -> nodes.firstOrNull { matchNode(it, query) }
                else -> null
            } ?: return ToolResult.Failure("no matching node")
            val clickable = clickableSelfOrParent(target) ?: return ToolResult.Failure("node is not clickable")
            val ok = withContext(Dispatchers.Main) { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            return if (ok) ToolResult.Success("clicked '${target.text ?: target.contentDescription ?: target.className}'")
            else ToolResult.Failure("click action was refused by the system")
        }
    }

    inner class A11yLongClick : ToolBase() {
        override val id = "a11y_long_click"
        override val name = "a11y_long_click"
        override val category = "accessibility"
        override val description = "Long-click a node by index or matching text."
        override val requiredPermission = PermAction.ACCESSIBILITY_CONTROL
        override val risk = RiskLevel.CRITICAL
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"index":{"type":"integer"},"query":{"type":"string"}}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            if (!AccessBridge.connected()) return ToolResult.Failure("accessibility service is not connected")
            val index = args["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val query = args["query"]?.jsonPrimitive?.contentOrNull
            val nodes = onService { it.captureNodes(500) } ?: return ToolResult.Failure("service unavailable")
            val target = (index?.let { nodes.getOrNull(it) } ?: query?.let { q -> nodes.firstOrNull { matchNode(it, q) } })
                ?: return ToolResult.Failure("no matching node")
            val ok = withContext(Dispatchers.Main) { target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
            return if (ok) ToolResult.Success("long-clicked") else ToolResult.Failure("long-click refused by the system")
        }
    }

    inner class A11yInputText : ToolBase() {
        override val id = "a11y_input_text"
        override val name = "a11y_input_text"
        override val category = "accessibility"
        override val description = "Set the text of an editable node by index or matching hint."
        override val requiredPermission = PermAction.ACCESSIBILITY_CONTROL
        override val risk = RiskLevel.CRITICAL
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"index":{"type":"integer"},"query":{"type":"string"},"text":{"type":"string"}},"required":["text"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            if (!AccessBridge.connected()) return ToolResult.Failure("accessibility service is not connected")
            val index = args["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val query = args["query"]?.jsonPrimitive?.contentOrNull
            val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing text")
            val nodes = onService { it.captureNodes(500) } ?: return ToolResult.Failure("service unavailable")
            val target = when {
                index != null -> nodes.getOrNull(index)
                query != null -> nodes.firstOrNull { matchNode(it, query) && it.isEditable }
                    ?: nodes.firstOrNull { matchNode(it, query) }
                else -> nodes.firstOrNull { it.isEditable }
            } ?: return ToolResult.Failure("no editable node found")
            val bundle = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = withContext(Dispatchers.Main) {
                var n: AccessibilityNodeInfo? = target
                var result = false
                var hops = 0
                while (n != null && hops < 5 && !result) {
                    if (n.isEditable) result = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    n = n.parent
                    hops++
                }
                result
            }
            return if (ok) ToolResult.Success("text set") else ToolResult.Failure("set-text refused by the system")
        }
    }

    inner class A11yScroll : ToolBase() {
        override val id = "a11y_scroll"
        override val name = "a11y_scroll"
        override val category = "accessibility"
        override val description = "Scroll a node forward or backward."
        override val requiredPermission = PermAction.ACCESSIBILITY_CONTROL
        override val risk = RiskLevel.CRITICAL
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"index":{"type":"integer"},"backward":{"type":"boolean"}}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            if (!AccessBridge.connected()) return ToolResult.Failure("accessibility service is not connected")
            val index = args["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val backward = args["backward"]?.jsonPrimitive?.contentOrNull == "true"
            val nodes = onService { it.captureNodes(500) } ?: return ToolResult.Failure("service unavailable")
            val target = index?.let { nodes.getOrNull(it) } ?: nodes.firstOrNull { it.isScrollable }
                ?: return ToolResult.Failure("no scrollable node")
            val action = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val ok = withContext(Dispatchers.Main) { target.performAction(action) }
            return if (ok) ToolResult.Success("scrolled") else ToolResult.Failure("scroll refused by the system")
        }
    }

    inner class A11yGlobalAction : ToolBase() {
        override val id = "a11y_global_action"
        override val name = "a11y_global_action"
        override val category = "accessibility"
        override val description = "Perform a global action: back, home, recents or notifications."
        override val requiredPermission = PermAction.ACCESSIBILITY_CONTROL
        override val risk = RiskLevel.CRITICAL
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"action":{"type":"string","enum":["back","home","recents","notifications"]}},"required":["action"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            if (!AccessBridge.connected()) return ToolResult.Failure("accessibility service is not connected")
            val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "back"
            val code = when (action) {
                "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                else -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            }
            val ok = withContext(Dispatchers.Main) { AccessBridge.service()?.performGlobalAction(code) ?: false }
            return if (ok) ToolResult.Success("performed $action") else ToolResult.Failure("global action refused")
        }
    }
}