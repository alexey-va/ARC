package ru.arc.xserver.playerlist

import com.google.gson.reflect.TypeToken
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.core.Tasks
import ru.arc.util.Common
import ru.arc.util.Logging.error
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object PlayerManager {
    private val playerMap = ConcurrentHashMap<UUID, PlayerData>()
    private val servers = HashSet<String>()

    @JvmStatic
    fun getOnlinePlayersThreadSafe(): List<Player> {
        if (Bukkit.isPrimaryThread()) return ArrayList(Bukkit.getOnlinePlayers())
        val future = CompletableFuture<List<Player>>()
        Tasks.scheduler.runSync(Runnable { future.complete(ArrayList(Bukkit.getOnlinePlayers())) })
        return try {
            future.get(3, TimeUnit.MINUTES)
        } catch (e: Exception) {
            error("Timeout waiting for players", e)
            emptyList()
        }
    }

    @JvmStatic
    fun getPlayerNames(): Set<String> = playerMap.values.mapTo(HashSet()) { it.username }

    @JvmStatic
    fun getPlayerUuids(): Set<UUID> = playerMap.keys.toSet()

    @JvmStatic
    fun getServerNames(): Set<String> = servers.toSet()

    @JvmStatic
    fun readMessage(json: String) {
        val type = object : TypeToken<List<PlayerData>>() {}.type
        val playerData: List<PlayerData>? = Common.gson.fromJson(json, type)
        if (playerData == null) {
            error("Message {} cannot be parsed!", json)
            return
        }
        val newMap = HashMap<UUID, PlayerData>()
        for (data in playerData) {
            if (!data.server.isNullOrBlank()) {
                servers.add(data.server)
            }
            newMap[data.uuid] = data
        }
        playerMap.clear()
        playerMap.putAll(newMap)
    }

    @JvmStatic
    fun getPlayerData(uniqueId: UUID): PlayerData? = playerMap[uniqueId]

    @JvmStatic
    fun findByName(name: String): PlayerData? = playerMap.values.firstOrNull { it.username.equals(name, ignoreCase = true) }

    data class PlayerData(
        val username: String,
        val server: String,
        val uuid: UUID,
        val joinTime: Long,
    )
}
