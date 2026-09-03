package ru.arc.landsui

import me.angeschossen.lands.api.LandsIntegration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC

interface LandsUiGateway {
    fun lands(player: Player): List<LandsUiLand>
    fun land(player: Player, id: Int): LandsUiLand?
    fun onlinePlayers(): List<LandsUiPlayer>
    fun playerName(id: java.util.UUID): String?
    fun execute(player: Player, command: String): Boolean
    fun selectAndExecute(player: Player, landId: Int, command: String): LandsUiCommandResult
}

enum class LandsUiCommandResult { EXECUTED, LAND_UNAVAILABLE, COMMAND_REJECTED }

class BukkitLandsUiGateway internal constructor(
    private val integration: LandsIntegration = LandsIntegration.of(ARC.instance),
) : LandsUiGateway {

    override fun lands(player: Player): List<LandsUiLand> =
        integration.getLandPlayer(player.uniqueId)?.lands.orEmpty()
            .asSequence()
            .filter { it.exists() }
            .map { land ->
                LandsUiLand(
                    id = land.id,
                    name = land.name,
                    ownerId = land.ownerUID,
                    chunks = land.chunksAmount,
                    memberIds = land.trustedPlayers.toSet() + land.ownerUID,
                    maxMembers = land.maxMembers,
                    balance = land.balance,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()

    override fun land(player: Player, id: Int): LandsUiLand? = lands(player).firstOrNull { it.id == id }

    override fun onlinePlayers(): List<LandsUiPlayer> = Bukkit.getOnlinePlayers().map { LandsUiPlayer(it.uniqueId, it.name) }

    @Suppress("DEPRECATION")
    override fun playerName(id: java.util.UUID): String? = Bukkit.getOfflinePlayer(id).name

    override fun execute(player: Player, command: String): Boolean = player.performCommand(command)

    override fun selectAndExecute(player: Player, landId: Int, command: String): LandsUiCommandResult {
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return LandsUiCommandResult.LAND_UNAVAILABLE
        val land = landPlayer.lands.firstOrNull { it.id == landId && it.exists() }
            ?: return LandsUiCommandResult.LAND_UNAVAILABLE
        landPlayer.setEditLand(land)
        return if (player.performCommand(command)) {
            LandsUiCommandResult.EXECUTED
        } else {
            LandsUiCommandResult.COMMAND_REJECTED
        }
    }
}
