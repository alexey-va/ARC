package ru.arc.landsui

import java.util.UUID

enum class LandsUiAccess { MEMBER, CURRENT, ADMIN }

data class LandsUiContext(val landId: String, val access: LandsUiAccess, val areaId: String? = null)

enum class LandsUiPermission {
    BORDERS, SPAWN, SET_SPAWN, CLAIM, UNCLAIM, TRUST, UNTRUST, SET_ROLE,
    RENAME, DESCRIPTION, TRANSFER, LEAVE, DELETE, NATIVE_MENU,
}

data class LandsUiMember(
    val id: UUID,
    val name: String,
    val role: String,
    val online: Boolean,
    val owner: Boolean,
    val removable: Boolean,
    val assignableRoleIds: Set<String>,
)

data class LandsUiRole(val id: String, val name: String, val visitor: Boolean)

data class LandsUiArea(val id: String, val name: String, val main: Boolean)

data class LandsUiRule(val key: String, val enabled: Boolean, val editable: Boolean)

data class LandsUiManagementView(
    val context: LandsUiContext,
    val land: LandsUiLand,
    val areaName: String,
    val viewerRole: String,
    val description: String,
    val members: List<LandsUiMember>,
    val roles: List<LandsUiRole>,
    val areas: List<LandsUiArea>,
    val effectiveRules: List<LandsUiRule>,
    val naturalRules: List<LandsUiRule>,
    val permissions: Set<LandsUiPermission>,
    val currentClaim: LandsUiClaim?,
)

sealed interface LandsUiChange {
    data object Borders : LandsUiChange
    data object Spawn : LandsUiChange
    data object SetSpawn : LandsUiChange
    data object Claim : LandsUiChange
    data object Leave : LandsUiChange
    data object NativeMenu : LandsUiChange
    data class Unclaim(val expected: LandsUiClaim) : LandsUiChange
    data class Rename(val name: String) : LandsUiChange
    data class Description(val text: String) : LandsUiChange
    data class Trust(val name: String) : LandsUiChange
    data class Remove(val memberId: UUID) : LandsUiChange
    data class AssignRole(val memberId: UUID, val roleId: String) : LandsUiChange
    data class Transfer(val memberId: UUID, val expectedOwnerId: UUID) : LandsUiChange
    data class Delete(val expectedOwnerId: UUID) : LandsUiChange
    data class NaturalFlag(val key: String, val expected: Boolean) : LandsUiChange
}

enum class LandsUiChangeResult { APPLIED, DISPATCHED, DENIED, NOT_FOUND, STALE, INVALID, ACTIVE_SELECTION, REJECTED }
