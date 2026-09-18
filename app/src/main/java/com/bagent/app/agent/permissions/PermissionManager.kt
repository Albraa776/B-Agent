package com.bagent.app.agent.permissions

import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel
import com.bagent.app.core.settings.SettingsRepository
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.ConcurrentHashMap

/** How the user resolved a permission request. */
enum class Approval { ALLOW, ALLOW_ALWAYS, ALLOW_SESSION, DENY }

sealed class Authorization {
    data class Granted(val level: String) : Authorization()
    object Denied : Authorization()
}

/**
 * Granular permission engine. Decisions are resolved in this order:
 *   1. Settings-level always-allow policies
 *   2. Grants made for the current session
 *   3. The default auto/confirm policy per action
 *   4. An interactive user request (provided by the UI)
 */
class PermissionManager(private val settings: SettingsRepository) {

    private val sessionGrants = ConcurrentHashMap<PermAction, Boolean>()

    /** Headless default: confirm-type actions are denied unless a requester is attached. */
    private var requester: suspend (PermAction, RiskLevel, String) -> Approval = { _, _, _ -> Approval.DENY }

    fun setRequester(fn: suspend (PermAction, RiskLevel, String) -> Approval) {
        requester = fn
    }

    fun clearSessionGrants() = sessionGrants.clear()

    fun riskFor(action: PermAction): RiskLevel = when (action) {
        PermAction.READ_FILES, PermAction.WRITE_FILES, PermAction.NETWORK_ACCESS -> RiskLevel.LOW
        PermAction.DELETE_FILES -> RiskLevel.HIGH
        PermAction.EXECUTE_COMMANDS, PermAction.PROCESS_CONTROL -> RiskLevel.HIGH
        PermAction.INSTALL_PACKAGES,
        PermAction.ACCESSIBILITY_CONTROL,
        PermAction.SYSTEM_SETTINGS,
        PermAction.ROOT_OPERATIONS -> RiskLevel.CRITICAL
    }

    /** Spec default policy: automatic for benign reads/writes/net, confirmation otherwise. */
    fun defaultAuto(action: PermAction): Boolean = when (action) {
        PermAction.READ_FILES, PermAction.WRITE_FILES, PermAction.NETWORK_ACCESS -> true
        else -> false
    }

    suspend fun authorize(
        action: PermAction,
        reason: String
    ): Authorization {
        val risk = riskFor(action)

        // 1. Always-allow configured by the user
        val auto = settings.autoApprove.firstOrNull() ?: emptySet()
        if (action in auto) return Authorization.Granted("always")

        // 2. Session-level grants
        if (sessionGrants[action] == true) return Authorization.Granted("session")

        // 3. Safe-by-default actions run automatically
        if (defaultAuto(action)) return Authorization.Granted("automatic")

        // 4. Interactive confirmation
        return when (requester(action, risk, reason)) {
            Approval.ALLOW -> Authorization.Granted("once")
            Approval.ALLOW_ALWAYS -> {
                settings.setAutoApproveFor(action, true)
                Authorization.Granted("always")
            }
            Approval.ALLOW_SESSION -> {
                sessionGrants[action] = true
                Authorization.Granted("session")
            }
            Approval.DENY -> Authorization.Denied
        }
    }
}