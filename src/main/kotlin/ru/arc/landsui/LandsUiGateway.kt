package ru.arc.landsui

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.items.ItemType
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.lands.currentLands
import ru.arc.lands.trustedPlayerIds
import ru.arc.onboarding.ClaimBlockIdentity

interface LandsUiGateway {
    fun lands(player: Player): List<LandsUiLand>
    fun land(player: Player, id: String): LandsUiLand?
    fun onlinePlayers(): List<LandsUiPlayer>
    fun playerName(id: java.util.UUID): String?
    fun execute(player: Player, command: String): Boolean
    fun select(player: Player, landId: String): Boolean
    fun selectAndExecute(player: Player, landId: String, command: String): LandsUiCommandResult
    fun unclaimCurrent(player: Player, landId: String): LandsUiCommandResult
    fun currentLandId(player: Player): String?
    fun inspectedLand(player: Player): LandsUiLand?
    fun administerCurrent(player: Player, landId: String, action: LandsUiAdminAction): LandsUiCommandResult
    fun currentClaim(player: Player): LandsUiClaim?
    fun managementView(player: Player, context: LandsUiContext): LandsUiManagementView?
    fun roleRules(player: Player, context: LandsUiContext, roleId: String): List<LandsUiRule>
    fun searchLands(player: Player, query: String): List<LandsUiLand>
    fun change(player: Player, context: LandsUiContext, change: LandsUiChange): LandsUiChangeResult
    fun giveClaimBlock(player: Player): LandsUiClaimBlockResult
}

data class LandsUiClaim(val landId: String, val worldId: java.util.UUID, val chunkX: Int, val chunkZ: Int)

internal fun sameClaim(expected: LandsUiClaim, actual: LandsUiClaim?): Boolean = expected == actual

internal fun canConfirmUnclaim(expected: LandsUiClaim, actual: LandsUiClaim?, currentLandId: String?): Boolean =
    sameClaim(expected, actual) && currentLandId == expected.landId

enum class LandsUiCommandResult { EXECUTED, LAND_UNAVAILABLE, COMMAND_REJECTED, ACTIVE_SELECTION }

enum class LandsUiClaimBlockResult { GIVEN, ALREADY_PRESENT, INVENTORY_FULL, COOLDOWN, UNAVAILABLE }

enum class LandsUiAdminAction(val command: String, private val commandPermission: String) {
    MENU("lands menu", "lands.command.menu"),
    MEMBERS("lands member menu", "lands.command.member.menu");

    fun allowed(player: Player): Boolean =
        player.hasPermission("lands.admin.command.edit") && player.hasPermission(commandPermission)
}

class BukkitLandsUiGateway internal constructor(
    private val integration: LandsIntegration = LandsIntegration.of(ARC.instance),
    roleName: (me.angeschossen.lands.api.role.Role) -> String = { it.name },
) : LandsUiGateway {
    private val management = LandsUiManagementService(integration, roleName)

    override fun giveClaimBlock(player: Player): LandsUiClaimBlockResult {
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return LandsUiClaimBlockResult.UNAVAILABLE
        val inventory = player.inventory
        if (inventory.contents.any { it != null && ClaimBlockIdentity.matches(it) && ClaimBlockIdentity.usableBy(it, player) }) {
            return LandsUiClaimBlockResult.ALREADY_PRESENT
        }
        val slot = inventory.storageContents.indexOfFirst { it == null || it.type.isAir }
        if (slot < 0) return LandsUiClaimBlockResult.INVENTORY_FULL
        val now = System.currentTimeMillis()
        val cooldownKey = NamespacedKey("arc", "lands_claim_block_next_at")
        if ((player.persistentDataContainer.get(cooldownKey, PersistentDataType.LONG) ?: 0L) > now) {
            return LandsUiClaimBlockResult.COOLDOWN
        }
        inventory.setItem(slot, ItemType.CLAIM_BLOCK.build(landPlayer).also { it.amount = 1 })
        player.persistentDataContainer.set(cooldownKey, PersistentDataType.LONG, now + 30 * 60 * 1_000L)
        return LandsUiClaimBlockResult.GIVEN
    }

    override fun lands(player: Player): List<LandsUiLand> {
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return emptyList()
        val selectedId = landPlayer.editLand?.takeIf { it.exists() }?.ulid?.toString()
        return landPlayer.currentLands()
            .filter { it.exists() }
            .map { land ->
                LandsUiLand(
                    id = land.ulid.toString(),
                    name = land.name,
                    ownerId = land.ownerUID,
                    chunks = land.chunksAmount,
                    maxChunks = land.maxChunks,
                    memberIds = land.trustedPlayerIds() + land.ownerUID,
                    maxMembers = land.maxMembers,
                    balance = land.balance,
                    selected = land.ulid.toString() == selectedId,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    override fun land(player: Player, id: String): LandsUiLand? = lands(player).firstOrNull { it.id == id }

    override fun onlinePlayers(): List<LandsUiPlayer> = Bukkit.getOnlinePlayers().map { LandsUiPlayer(it.uniqueId, it.name) }

    @Suppress("DEPRECATION")
    override fun playerName(id: java.util.UUID): String? = Bukkit.getOfflinePlayer(id).name

    override fun execute(player: Player, command: String): Boolean = player.performCommand(command)

    override fun select(player: Player, landId: String): Boolean {
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return false
        val land = landPlayer.currentLands().firstOrNull { it.ulid.toString() == landId && it.exists() } ?: return false
        landPlayer.setEditLand(land)
        return true
    }

    override fun selectAndExecute(player: Player, landId: String, command: String): LandsUiCommandResult {
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return LandsUiCommandResult.LAND_UNAVAILABLE
        val land = landPlayer.currentLands().firstOrNull { it.ulid.toString() == landId && it.exists() }
            ?: return LandsUiCommandResult.LAND_UNAVAILABLE
        landPlayer.setEditLand(land)
        return if (player.performCommand(command)) {
            LandsUiCommandResult.EXECUTED
        } else {
            LandsUiCommandResult.COMMAND_REJECTED
        }
    }

    override fun currentLandId(player: Player): String? {
        val location = player.location
        return integration.getLandByUnloadedChunk(location.world, location.blockX shr 4, location.blockZ shr 4)
            ?.ulid?.toString()
    }

    override fun inspectedLand(player: Player): LandsUiLand? {
        val location = player.location
        val land = integration.getLandByUnloadedChunk(location.world, location.blockX shr 4, location.blockZ shr 4)
            ?.takeIf { it.exists() } ?: return null
        return LandsUiLand(
            id = land.ulid.toString(), name = land.name, ownerId = land.ownerUID,
            chunks = land.chunksAmount, maxChunks = land.maxChunks,
            memberIds = land.trustedPlayerIds() + land.ownerUID, maxMembers = land.maxMembers,
            balance = land.balance, selected = false,
        )
    }

    override fun administerCurrent(player: Player, landId: String, action: LandsUiAdminAction): LandsUiCommandResult {
        if (!action.allowed(player)) return LandsUiCommandResult.COMMAND_REJECTED
        val location = player.location
        val land = integration.getLandByUnloadedChunk(location.world, location.blockX shr 4, location.blockZ shr 4)
            ?.takeIf { it.exists() && it.ulid.toString() == landId }
            ?: return LandsUiCommandResult.LAND_UNAVAILABLE
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return LandsUiCommandResult.LAND_UNAVAILABLE
        landPlayer.setEditLand(land)
        return if (player.performCommand(action.command)) LandsUiCommandResult.EXECUTED
        else LandsUiCommandResult.COMMAND_REJECTED
    }

    override fun currentClaim(player: Player): LandsUiClaim? {
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return null
        val selected = landPlayer.getEditLand(false)?.takeIf { it.exists() } ?: return null
        if (landPlayer.currentLands().none { it.ulid == selected.ulid && it.exists() }) return null
        val chunkX = player.location.blockX shr 4
        val chunkZ = player.location.blockZ shr 4
        val claim = integration.getLandByUnloadedChunk(player.world, chunkX, chunkZ) ?: return null
        if (claim.ulid != selected.ulid) return null
        return LandsUiClaim(claim.ulid.toString(), player.world.uid, chunkX, chunkZ)
    }

    override fun unclaimCurrent(player: Player, landId: String): LandsUiCommandResult {
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return LandsUiCommandResult.LAND_UNAVAILABLE
        val land = landPlayer.currentLands().firstOrNull { it.ulid.toString() == landId && it.exists() }
            ?: return LandsUiCommandResult.LAND_UNAVAILABLE
        if (landPlayer.selection != null) return LandsUiCommandResult.ACTIVE_SELECTION
        landPlayer.setEditLand(land)
        return if (player.performCommand("lands unclaim")) {
            LandsUiCommandResult.EXECUTED
        } else {
            LandsUiCommandResult.COMMAND_REJECTED
        }
    }

    override fun managementView(player: Player, context: LandsUiContext): LandsUiManagementView? =
        management.managementView(player, context)

    override fun roleRules(player: Player, context: LandsUiContext, roleId: String): List<LandsUiRule> =
        management.roleRules(player, context, roleId)

    override fun searchLands(player: Player, query: String): List<LandsUiLand> = management.searchLands(player, query)

    override fun change(player: Player, context: LandsUiContext, change: LandsUiChange): LandsUiChangeResult =
        management.change(player, context, change)
}
