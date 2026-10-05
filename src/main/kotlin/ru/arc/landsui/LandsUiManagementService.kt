package ru.arc.landsui

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.framework.holder.Changeable
import me.angeschossen.lands.api.flags.type.Flags
import me.angeschossen.lands.api.land.Area
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.player.LandPlayer
import me.angeschossen.lands.api.role.Role
import me.angeschossen.lands.api.memberholder.CMDTarget
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.lands.currentLands
import ru.arc.lands.trustedPlayerIds
import java.util.UUID

/** Lands-backed implementation for the detailed, permission-aware dialog pages. */
internal class LandsUiManagementService(
    private val integration: LandsIntegration,
    private val roleName: (Role) -> String,
) {
    fun managementView(player: Player, context: LandsUiContext): LandsUiManagementView? {
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return null
        val land = resolveLand(player, landPlayer, context) ?: return null
        val area = resolveArea(player, land, context) ?: return null
        val landArea = land.defaultArea
        val viewerRole = area.getRole(player.uniqueId) ?: area.visitorRole
        val maySetRoles = canChangeRoleMembership(player, context, landPlayer, land, area)
        val isAdmin = context.access == LandsUiAccess.ADMIN
        val areaRoles = roles(area)
        val assignableRoles = areaRoles
            .filter { it.type.canBeSet() && (isAdmin || it.priority < viewerRole.priority) }
            .mapTo(mutableSetOf()) { roleId(it) }
        val mayUntrust = canUntrust(player, context, landPlayer, area)
        val memberIds = (trustedIds(area) + area.ownerUID).distinct()
        val members = memberIds.map { memberId ->
            val memberRole = area.getRole(memberId) ?: area.visitorRole
            LandsUiMember(
                id = memberId,
                name = playerName(memberId),
                role = roleName(memberRole),
                online = Bukkit.getPlayer(memberId) != null,
                owner = memberId == area.ownerUID,
                removable = memberId != area.ownerUID && mayUntrust && (isAdmin || memberRole.priority < viewerRole.priority),
                assignableRoleIds = if (memberId != area.ownerUID && maySetRoles && (isAdmin || memberRole.priority < viewerRole.priority)) {
                    assignableRoles
                } else emptySet(),
            )
        }.sortedWith(compareBy<LandsUiMember> { !it.owner }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        val roleFlags = integration.flagRegistry.roleFlags.asSequence().filterIsInstance<me.angeschossen.lands.api.flags.type.RoleFlag>()
            .filter { it.name in ROLE_RULE_KEYS && it.isDisplay && it.shouldDisplay(area, landPlayer) }
            .toList()
        val naturalFlags = integration.flagRegistry.naturalFlags.asSequence().filterIsInstance<me.angeschossen.lands.api.flags.type.NaturalFlag>()
            .filter { it.name in NATURAL_RULE_KEYS && it.isDisplay && it.shouldDisplay(area, landPlayer) }
            .toList()
        val effectiveRules = roleFlags.map { flag ->
            LandsUiRule(flag.name, area.hasRoleFlag(player.uniqueId, flag), false)
        }
        val naturalRules = naturalFlags.map { flag ->
            LandsUiRule(flag.name, area.hasNaturalFlag(flag), canToggleNaturalFlag(player, context, landPlayer, area, flag))
        }

        return LandsUiManagementView(
            context = context.copy(areaId = area.ulid.toString()),
            land = uiLand(player, land),
            areaName = area.name,
            viewerRole = roleName(viewerRole),
            description = titleText(land.getTitleMessage(landPlayer)),
            members = members,
            roles = areaRoles.map { LandsUiRole(roleId(it), roleName(it), it.isVisitorRole) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            areas = areas(land).map { LandsUiArea(it.ulid.toString(), it.name, it.isDefault) }
                .sortedWith(compareBy<LandsUiArea> { !it.main }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            effectiveRules = effectiveRules,
            naturalRules = naturalRules,
            permissions = permissions(player, context, landPlayer, land, area, landArea),
            currentClaim = currentClaim(player, land),
        )
    }

    fun roleRules(player: Player, context: LandsUiContext, roleId: String): List<LandsUiRule> {
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return emptyList()
        val land = resolveLand(player, landPlayer, context) ?: return emptyList()
        val area = resolveArea(player, land, context) ?: return emptyList()
        val role = roles(area).firstOrNull { roleId(it) == roleId } ?: return emptyList()
        return integration.flagRegistry.roleFlags
            .asSequence()
            .filterIsInstance<me.angeschossen.lands.api.flags.type.RoleFlag>()
            .filter { it.name in ROLE_RULE_KEYS && it.isDisplay && it.shouldDisplay(area, landPlayer) }
            .map { flag ->
                LandsUiRule(flag.name, role.hasFlag(flag), false)
            }
            .toList()
    }

    fun searchLands(player: Player, query: String): List<LandsUiLand> {
        if (!player.hasPermission(ADMIN_EDIT_PERMISSION)) return emptyList()
        val normalizedQuery = query.trim().take(MAX_SEARCH_QUERY_LENGTH)
        if (normalizedQuery.isBlank()) return emptyList()
        val needle = normalizedQuery.lowercase()
        return lands().asSequence()
            .filter { it.exists() }
            .map { land ->
                val members = land.trustedPlayerIds() + land.ownerUID
                val matchingMember = members.any { id -> playerName(id).contains(needle, ignoreCase = true) }
                val nameMatch = land.name.contains(needle, ignoreCase = true)
                Triple(land, nameMatch, matchingMember)
            }
            .filter { (_, nameMatch, memberMatch) -> nameMatch || memberMatch }
            .sortedWith(compareBy<Triple<Land, Boolean, Boolean>> { !it.second }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.name })
            .take(MAX_SEARCH_RESULTS)
            .map { uiLand(player, it.first) }
            .toList()
    }

    fun change(player: Player, context: LandsUiContext, change: LandsUiChange): LandsUiChangeResult {
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return LandsUiChangeResult.NOT_FOUND
        val land = resolveLand(player, landPlayer, context) ?: return LandsUiChangeResult.NOT_FOUND
        val area = resolveArea(player, land, context) ?: return LandsUiChangeResult.NOT_FOUND
        val isAdmin = context.access == LandsUiAccess.ADMIN
        val isMember = context.access == LandsUiAccess.MEMBER && land.isTrusted(player.uniqueId)
        if (context.access == LandsUiAccess.CURRENT) {
            if (change !is LandsUiChange.Borders) return LandsUiChangeResult.DENIED
            if (currentLand(player)?.ulid != land.ulid) return LandsUiChangeResult.STALE
        } else if (isAdmin) {
            if (!player.hasPermission(ADMIN_EDIT_PERMISSION)) return LandsUiChangeResult.DENIED
        } else if (!isMember) {
            return LandsUiChangeResult.DENIED
        }

        return when (change) {
            LandsUiChange.Borders -> {
                if (currentLand(player)?.ulid != land.ulid) return LandsUiChangeResult.STALE
                if (!player.hasPermission("lands.command.view")) return LandsUiChangeResult.DENIED
                if (player.performCommand("lands view")) LandsUiChangeResult.DISPATCHED else LandsUiChangeResult.REJECTED
            }
            LandsUiChange.Spawn -> {
                if (!hasAction(player, context, landPlayer, land.defaultArea, "lands.command.spawn", Flags.SPAWN_TELEPORT)) return LandsUiChangeResult.DENIED
                dispatch(player, landPlayer, land, "lands spawn", locationSensitive = true)
            }
            LandsUiChange.SetSpawn -> {
                if (!hasAction(player, context, landPlayer, land.defaultArea, "lands.command.setspawn", Flags.SPAWN_SET, "lands.admin.setting_edit_land")) {
                    return LandsUiChangeResult.DENIED
                }
                dispatch(player, landPlayer, land, "lands setspawn", locationSensitive = true)
            }
            LandsUiChange.Claim -> {
                if (isAdmin || !hasAction(player, context, landPlayer, land.defaultArea, "lands.command.claim", Flags.LAND_CLAIM)) return LandsUiChangeResult.DENIED
                if (landPlayer.selection != null) return LandsUiChangeResult.ACTIVE_SELECTION
                dispatch(player, landPlayer, land, "lands claim")
            }
            is LandsUiChange.Unclaim -> {
                if (!hasAction(player, context, landPlayer, land.defaultArea, "lands.command.unclaim", Flags.LAND_CLAIM, "lands.admin.land_delete")) {
                    return LandsUiChangeResult.DENIED
                }
                val actual = currentClaim(player, land)
                if (!canConfirmUnclaim(change.expected, actual, currentLand(player)?.ulid?.toString())) {
                    return LandsUiChangeResult.STALE
                }
                if (landPlayer.selection != null) return LandsUiChangeResult.ACTIVE_SELECTION
                dispatch(player, landPlayer, land, "lands unclaim", locationSensitive = true)
            }
            LandsUiChange.Leave -> {
                if (isAdmin || !isMember || land.ownerUID == player.uniqueId ||
                    !player.hasPermission("lands.command.leave")
                ) return LandsUiChangeResult.DENIED
                dispatch(player, landPlayer, land, "lands leave confirm", locationSensitive = true)
            }
            LandsUiChange.NativeMenu -> {
                if (isAdmin && !player.hasPermission(ADMIN_EDIT_PERMISSION)) return LandsUiChangeResult.DENIED
                val command = if (area.isDefault) {
                    if (!player.hasPermission("lands.command.menu")) return LandsUiChangeResult.DENIED
                    "lands menu"
                } else {
                    if (!player.hasPermission("lands.command.area.menu")) return LandsUiChangeResult.DENIED
                    val areaToken = commandToken(CMDTarget.parseCMDName(area.name)) ?: return LandsUiChangeResult.INVALID
                    "lands area $areaToken menu"
                }
                dispatch(player, landPlayer, land, command)
            }
            is LandsUiChange.Rename -> {
                if (!hasAction(player, context, landPlayer, land.defaultArea, "lands.command.rename", Flags.SETTING_EDIT_LAND, "lands.admin.setting_edit_land")) {
                    return LandsUiChangeResult.DENIED
                }
                val command = runCatching { LandsUiCommands.rename(change.name) }.getOrNull()
                    ?: return LandsUiChangeResult.INVALID
                dispatch(player, landPlayer, land, command, locationSensitive = true)
            }
            is LandsUiChange.Description -> {
                if (change.text.length > MAX_DESCRIPTION_LENGTH || change.text.any { it == '\n' || it == '\r' }) {
                    return LandsUiChangeResult.INVALID
                }
                if (!canEditLandSettings(player, context, landPlayer, land.defaultArea)) return LandsUiChangeResult.DENIED
                try {
                    land.setTitleMessage(change.text.takeIf { it.isNotEmpty() })
                } catch (_: IllegalArgumentException) {
                    return LandsUiChangeResult.INVALID
                }
                land.saveAndPublishToRedis()
                LandsUiChangeResult.APPLIED
            }
            is LandsUiChange.Trust -> {
                val permission = scopedMemberPermission(area, "trust")
                if (!hasAction(player, context, landPlayer, area, permission, Flags.PLAYER_TRUST)) return LandsUiChangeResult.DENIED
                val command = runCatching { memberCommand(area, "trust ${LandsUiCommands.member(change.name)}") }.getOrNull()
                    ?: return LandsUiChangeResult.INVALID
                dispatch(player, landPlayer, land, command, locationSensitive = true)
            }
            is LandsUiChange.Remove -> {
                val permission = scopedMemberPermission(area, "untrust")
                if (!hasAction(player, context, landPlayer, area, permission, Flags.PLAYER_UNTRUST)) {
                    return LandsUiChangeResult.DENIED
                }
                if (change.memberId == area.ownerUID || !area.isTrusted(change.memberId)) return LandsUiChangeResult.NOT_FOUND
                val actorRole = area.getRole(player.uniqueId) ?: area.visitorRole
                val targetRole = area.getRole(change.memberId) ?: area.visitorRole
                if (!isAdmin && targetRole.priority >= actorRole.priority) return LandsUiChangeResult.DENIED
                val name = playerNameOrNull(change.memberId) ?: return LandsUiChangeResult.NOT_FOUND
                val command = runCatching { memberCommand(area, "untrust ${LandsUiCommands.member(name)}") }.getOrNull()
                    ?: return LandsUiChangeResult.INVALID
                dispatch(player, landPlayer, land, command, locationSensitive = true)
            }
            is LandsUiChange.AssignRole -> {
                val permission = scopedMemberPermission(area, "setrole")
                if (!hasAction(player, context, landPlayer, area, permission, Flags.PLAYER_SETROLE, "lands.admin.setting_edit_role")) {
                    return LandsUiChangeResult.DENIED
                }
                if (change.memberId == area.ownerUID || !area.isTrusted(change.memberId)) return LandsUiChangeResult.NOT_FOUND
                val targetRole = area.getRole(change.memberId) ?: area.visitorRole
                val actorRole = area.getRole(player.uniqueId) ?: area.visitorRole
                val role = roles(area).firstOrNull { roleId(it) == change.roleId } ?: return LandsUiChangeResult.NOT_FOUND
                if (!role.type.canBeSet()) return LandsUiChangeResult.INVALID
                if (!isAdmin && (targetRole.priority >= actorRole.priority || role.priority >= actorRole.priority)) {
                    return LandsUiChangeResult.DENIED
                }
                val name = playerNameOrNull(change.memberId) ?: return LandsUiChangeResult.NOT_FOUND
                val cmdRole = commandToken(CMDTarget.parseCMDName(role.name)) ?: return LandsUiChangeResult.INVALID
                val command = runCatching { memberCommand(area, "setrole ${LandsUiCommands.member(name)} $cmdRole") }
                    .getOrNull() ?: return LandsUiChangeResult.INVALID
                dispatch(player, landPlayer, land, command, locationSensitive = true)
            }
            is LandsUiChange.Transfer -> {
                if (land.ownerUID != change.expectedOwnerId) return LandsUiChangeResult.STALE
                if (change.memberId == land.ownerUID || !land.isTrusted(change.memberId)) return LandsUiChangeResult.NOT_FOUND
                if (!hasAction(player, context, landPlayer, land.defaultArea, "lands.command.member.setowner", Flags.SETTING_EDIT_LAND, "lands.admin.land_setowner")) {
                    return LandsUiChangeResult.DENIED
                }
                if (!isAdmin && land.ownerUID != player.uniqueId) return LandsUiChangeResult.DENIED
                val name = playerNameOrNull(change.memberId) ?: return LandsUiChangeResult.NOT_FOUND
                dispatch(
                    player,
                    landPlayer,
                    land,
                    "lands member setowner ${LandsUiCommands.member(name)} confirm",
                    locationSensitive = true,
                )
            }
            is LandsUiChange.Delete -> {
                if (land.ownerUID != change.expectedOwnerId) return LandsUiChangeResult.STALE
                if (!player.hasPermission("lands.command.delete")) return LandsUiChangeResult.DENIED
                if (isAdmin) {
                    if (!player.hasPermission("lands.admin.land_delete")) return LandsUiChangeResult.DENIED
                } else if (land.ownerUID != player.uniqueId) {
                    return LandsUiChangeResult.DENIED
                }
                dispatch(player, landPlayer, land, "lands delete confirm")
            }
            is LandsUiChange.NaturalFlag -> changeNaturalFlag(player, context, landPlayer, land, area, change)
        }
    }

    private fun hasAction(
        player: Player,
        context: LandsUiContext,
        landPlayer: LandPlayer,
        area: Area,
        commandPermission: String,
        roleFlag: me.angeschossen.lands.api.flags.type.RoleFlag,
        adminPermission: String? = null,
    ): Boolean {
        if (!player.hasPermission(commandPermission)) return false
        return when (context.access) {
            LandsUiAccess.ADMIN -> player.hasPermission(ADMIN_EDIT_PERMISSION) &&
                (adminPermission == null || player.hasPermission(adminPermission))
            LandsUiAccess.MEMBER -> area.hasRoleFlag(landPlayer, roleFlag, null, false)
            LandsUiAccess.CURRENT -> false
        }
    }

    private fun canEditLandSettings(player: Player, context: LandsUiContext, landPlayer: LandPlayer, area: Area): Boolean = when (context.access) {
        LandsUiAccess.ADMIN -> player.hasPermission(ADMIN_EDIT_PERMISSION) && player.hasPermission("lands.admin.setting_edit_land")
        LandsUiAccess.MEMBER -> area.hasRoleFlag(landPlayer, Flags.SETTING_EDIT_LAND, null, false)
        LandsUiAccess.CURRENT -> false
    }

    private fun changeNaturalFlag(
        player: Player,
        context: LandsUiContext,
        landPlayer: LandPlayer,
        land: Land,
        area: Area,
        change: LandsUiChange.NaturalFlag,
    ): LandsUiChangeResult {
        if (change.key !in NATURAL_RULE_KEYS) return LandsUiChangeResult.INVALID
        val flag = integration.flagRegistry.getNatural(change.key) ?: return LandsUiChangeResult.NOT_FOUND
        val current = area.hasNaturalFlag(flag)
        if (current != change.expected) return LandsUiChangeResult.STALE
        if (flag.isActiveInWar && land.isInWar) return LandsUiChangeResult.DENIED
        if (!canToggleNaturalFlag(player, context, landPlayer, area, flag)) return LandsUiChangeResult.DENIED
        val changeable = area as? Changeable ?: return LandsUiChangeResult.REJECTED
        area.toggleNaturalFlag(flag)
        if (area.hasNaturalFlag(flag) == change.expected) return LandsUiChangeResult.REJECTED
        changeable.saveAndPublishToRedis()
        return LandsUiChangeResult.APPLIED
    }

    private fun dispatch(
        player: Player,
        landPlayer: LandPlayer,
        land: Land,
        command: String,
        locationSensitive: Boolean = false,
    ): LandsUiChangeResult {
        if (locationSensitive && editByLocationEnabled() && currentLand(player)?.ulid != land.ulid) {
            return LandsUiChangeResult.STALE
        }
        landPlayer.setEditLand(land)
        return if (player.performCommand(command)) LandsUiChangeResult.DISPATCHED else LandsUiChangeResult.REJECTED
    }

    private fun editByLocationEnabled(): Boolean = runCatching {
        val config = integration.configuration.mainConfig
        !config.hasValue("general.edit-by-loc") || config.getBoolean("general.edit-by-loc")
    }.getOrDefault(true)

    @Suppress("DEPRECATION")
    private fun playerNameOrNull(id: UUID): String? = Bukkit.getPlayer(id)?.name ?: Bukkit.getOfflinePlayer(id).name

    private fun resolveLand(player: Player, landPlayer: LandPlayer, context: LandsUiContext): Land? = when (context.access) {
        LandsUiAccess.MEMBER -> landPlayer.currentLands().firstOrNull {
            it.exists() && it.ulid.toString() == context.landId && it.isTrusted(player.uniqueId)
        }
        LandsUiAccess.CURRENT -> currentLand(player)?.takeIf {
            it.exists() && it.ulid.toString() == context.landId
        }
        LandsUiAccess.ADMIN -> if (!player.hasPermission(ADMIN_EDIT_PERMISSION)) null else lands().firstOrNull {
            it.exists() && it.ulid.toString() == context.landId
        }
    }

    private fun resolveArea(player: Player, land: Land, context: LandsUiContext): Area? {
        val areas = areas(land)
        val selected = context.areaId?.let { id -> areas.firstOrNull { it.ulid.toString() == id } }
        if (context.areaId != null && selected == null) return null
        return when {
            selected != null -> selected
            context.access == LandsUiAccess.CURRENT -> land.getArea(player.location) ?: land.defaultArea
            else -> land.defaultArea
        }
    }

    private fun permissions(
        player: Player,
        context: LandsUiContext,
        landPlayer: LandPlayer,
        land: Land,
        area: Area,
        landArea: Area,
    ): Set<LandsUiPermission> {
        val admin = context.access == LandsUiAccess.ADMIN
        val member = context.access == LandsUiAccess.MEMBER && land.isTrusted(player.uniqueId)
        val current = currentLand(player)?.ulid == land.ulid
        val actorFlags = { target: Area, flag: me.angeschossen.lands.api.flags.type.RoleFlag ->
            target.hasRoleFlag(landPlayer, flag, null, false)
        }
        val result = mutableSetOf<LandsUiPermission>()
        if (current && player.hasPermission("lands.command.view")) result += LandsUiPermission.BORDERS
        val nativeMenuPermission = if (area.isDefault) "lands.command.menu" else "lands.command.area.menu"
        if ((member || admin) && player.hasPermission(nativeMenuPermission) &&
            (!admin || player.hasPermission(ADMIN_EDIT_PERMISSION))) {
            result += LandsUiPermission.NATIVE_MENU
        }

        if (member || admin) {
            val command = { node: String -> player.hasPermission(node) && (!admin || player.hasPermission(ADMIN_EDIT_PERMISSION)) }
            fun allowed(
                commandNode: String,
                target: Area,
                flag: me.angeschossen.lands.api.flags.type.RoleFlag,
                adminNode: String? = null,
            ): Boolean = command(commandNode) &&
                if (admin) adminNode?.let(player::hasPermission) ?: true else actorFlags(target, flag)
            if (allowed("lands.command.spawn", landArea, Flags.SPAWN_TELEPORT)) result += LandsUiPermission.SPAWN
            if (allowed("lands.command.setspawn", landArea, Flags.SPAWN_SET, "lands.admin.setting_edit_land")) result += LandsUiPermission.SET_SPAWN
            if (member && allowed("lands.command.claim", landArea, Flags.LAND_CLAIM)) result += LandsUiPermission.CLAIM
            if (allowed("lands.command.unclaim", landArea, Flags.LAND_CLAIM, "lands.admin.land_delete") && currentClaim(player, land) != null) {
                result += LandsUiPermission.UNCLAIM
            }
            if (allowed(scopedMemberPermission(area, "trust"), area, Flags.PLAYER_TRUST)) result += LandsUiPermission.TRUST
            if (allowed(scopedMemberPermission(area, "untrust"), area, Flags.PLAYER_UNTRUST)) result += LandsUiPermission.UNTRUST
            if (allowed(scopedMemberPermission(area, "setrole"), area, Flags.PLAYER_SETROLE, "lands.admin.setting_edit_role")) result += LandsUiPermission.SET_ROLE
            if (allowed("lands.command.rename", landArea, Flags.SETTING_EDIT_LAND, "lands.admin.setting_edit_land")) result += LandsUiPermission.RENAME
            val mayTransfer = allowed("lands.command.member.setowner", landArea, Flags.SETTING_EDIT_LAND, "lands.admin.land_setowner")
            if (mayTransfer && (admin || member && land.ownerUID == player.uniqueId)) result += LandsUiPermission.TRANSFER
            if (command("lands.command.leave") && member && land.ownerUID != player.uniqueId) result += LandsUiPermission.LEAVE
            if (command("lands.command.delete") && (land.ownerUID == player.uniqueId || admin && player.hasPermission("lands.admin.land_delete"))) {
                result += LandsUiPermission.DELETE
            }
        }
        if (canEditLandSettings(player, context, landPlayer, landArea)) {
            result += LandsUiPermission.DESCRIPTION
        }
        return result
    }

    private fun canChangeRoleMembership(
        player: Player,
        context: LandsUiContext,
        landPlayer: LandPlayer,
        land: Land,
        area: Area,
    ): Boolean = if (context.access == LandsUiAccess.ADMIN) {
        player.hasPermission(ADMIN_EDIT_PERMISSION) && player.hasPermission("lands.admin.setting_edit_role") &&
            player.hasPermission(scopedMemberPermission(area, "setrole"))
    } else context.access == LandsUiAccess.MEMBER && land.isTrusted(player.uniqueId) &&
        player.hasPermission(scopedMemberPermission(area, "setrole")) && area.hasRoleFlag(landPlayer, Flags.PLAYER_SETROLE, null, false)

    private fun canUntrust(player: Player, context: LandsUiContext, landPlayer: LandPlayer, area: Area): Boolean =
        if (context.access == LandsUiAccess.ADMIN) {
            player.hasPermission(ADMIN_EDIT_PERMISSION) && player.hasPermission(scopedMemberPermission(area, "untrust"))
        } else context.access == LandsUiAccess.MEMBER && player.hasPermission(scopedMemberPermission(area, "untrust")) &&
            area.hasRoleFlag(landPlayer, Flags.PLAYER_UNTRUST, null, false)

    private fun canToggleNaturalFlag(
        player: Player,
        context: LandsUiContext,
        landPlayer: LandPlayer,
        area: Area,
        flag: me.angeschossen.lands.api.flags.type.NaturalFlag,
    ): Boolean {
        if (!flag.isDisplay || !flag.shouldDisplay(area, landPlayer) || !player.hasPermission(flag.togglePermission)) return false
        if (!area.isDefault && !flag.isApplyInSubareas) return false
        return if (context.access == LandsUiAccess.ADMIN) {
            player.hasPermission(ADMIN_EDIT_PERMISSION) && player.hasPermission("lands.admin.setting_edit_land")
        } else context.access == LandsUiAccess.MEMBER && area.hasRoleFlag(landPlayer, Flags.SETTING_EDIT_LAND, null, false)
    }

    private fun currentClaim(player: Player, land: Land): LandsUiClaim? {
        val location = player.location
        val world = location.world ?: return null
        val chunkX = location.blockX shr 4
        val chunkZ = location.blockZ shr 4
        val claim = integration.getLandByUnloadedChunk(world, chunkX, chunkZ) ?: return null
        if (claim.ulid != land.ulid) return null
        return LandsUiClaim(land.ulid.toString(), world.uid, chunkX, chunkZ)
    }

    private fun currentLand(player: Player): Land? {
        val location = player.location
        val world = location.world ?: return null
        return integration.getLandByUnloadedChunk(world, location.blockX shr 4, location.blockZ shr 4)
    }

    private fun uiLand(player: Player, land: Land): LandsUiLand {
        val selected = integration.getLandPlayer(player.uniqueId)?.getEditLand(false)
            ?.takeIf { it.exists() }?.ulid == land.ulid
        return LandsUiLand(
            id = land.ulid.toString(),
            name = land.name,
            ownerId = land.ownerUID,
            chunks = land.chunksAmount,
            maxChunks = land.maxChunks,
            memberIds = land.trustedPlayerIds() + land.ownerUID,
            maxMembers = land.maxMembers,
            balance = land.balance,
            selected = selected,
        )
    }

    @Suppress("DEPRECATION")
    private fun playerName(id: UUID): String = Bukkit.getPlayer(id)?.name ?: Bukkit.getOfflinePlayer(id).name ?: id.toString()

    private fun roleId(role: Role): String = role.ulid.toString()

    private fun roles(area: Area): List<Role> = area.roles.asSequence().filterIsInstance<Role>().toList()

    private fun areas(land: Land): List<Area> = land.allAreas.asSequence().filterIsInstance<Area>().toList()

    private fun trustedIds(area: Area): Set<UUID> = area.trustedPlayers.asSequence().filterIsInstance<UUID>().toSet()

    private fun lands(): List<Land> = integration.lands.asSequence().filterIsInstance<Land>().toList()

    private fun scopedMemberPermission(area: Area, action: String): String =
        if (area.isDefault) "lands.command.member.$action" else "lands.command.area.member.$action"

    private fun memberCommand(area: Area, arguments: String): String {
        if (area.isDefault) return "lands member $arguments"
        val areaToken = commandToken(CMDTarget.parseCMDName(area.name))
            ?: throw IllegalArgumentException("Invalid Lands area command name")
        return "lands area $areaToken member $arguments"
    }

    private fun commandToken(value: String): String? = value.takeIf(COMMAND_TOKEN::matches)

    private fun titleText(value: String?): String = value.orEmpty()
        .replace(MINI_MESSAGE_TAG, "")
        .replace(LEGACY_COLOR_CODE, "")

    private companion object {
        const val ADMIN_EDIT_PERMISSION = "lands.admin.command.edit"
        const val MAX_DESCRIPTION_LENGTH = 160
        val COMMAND_TOKEN = Regex("[\\p{L}\\p{N}_-]{1,32}")
        val MINI_MESSAGE_TAG = Regex("</?[^>]+>")
        val LEGACY_COLOR_CODE = Regex("(?i)§[0-9a-fk-or]")
        const val MAX_SEARCH_RESULTS = 50
        const val MAX_SEARCH_QUERY_LENGTH = 64
        val ROLE_RULE_KEYS = setOf(
            "block_break", "block_place", "interact_container", "interact_door", "interact_trapdoor",
            "interact_mechanism", "attack_animal", "attack_player", "interact_villager",
        )
        val NATURAL_RULE_KEYS = setOf("tnt_griefing", "fire_spread", "animal_spawn", "monster_spawn")
    }
}
