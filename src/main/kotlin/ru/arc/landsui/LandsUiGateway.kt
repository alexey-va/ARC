package ru.arc.landsui

import me.angeschossen.lands.api.LandsIntegration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.lands.currentLands
import ru.arc.lands.trustedPlayerIds

interface LandsUiGateway {
    fun lands(player: Player): List<LandsUiLand>
    fun land(player: Player, id: String): LandsUiLand?
    fun onlinePlayers(): List<LandsUiPlayer>
    fun playerName(id: java.util.UUID): String?
    fun execute(player: Player, command: String): Boolean
    fun select(player: Player, landId: String): Boolean
    fun selectAndExecute(player: Player, landId: String, command: String): LandsUiCommandResult
    fun unclaimCurrent(player: Player, landId: String): LandsUiCommandResult
    fun currentClaim(player: Player): LandsUiClaim?
}

data class LandsUiClaim(val landId: String, val worldId: java.util.UUID, val chunkX: Int, val chunkZ: Int)

internal fun sameClaim(expected: LandsUiClaim, actual: LandsUiClaim?): Boolean = expected == actual

internal fun canConfirmUnclaim(expected: LandsUiClaim, actual: LandsUiClaim?, currentLandId: String?): Boolean =
    sameClaim(expected, actual) && currentLandId == expected.landId

enum class LandsUiCommandResult { EXECUTED, LAND_UNAVAILABLE, COMMAND_REJECTED, ACTIVE_SELECTION }

class BukkitLandsUiGateway internal constructor(
    private val integration: LandsIntegration = LandsIntegration.of(ARC.instance),
) : LandsUiGateway {

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
}
