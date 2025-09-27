package ru.arc.ai.assistant

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Containers.CMIUser
import lombok.extern.slf4j.Slf4j
import org.bukkit.Bukkit
import ru.arc.hooks.HookRegistry
import java.util.concurrent.ConcurrentHashMap

@Slf4j
object Tools {
    val tools: MutableMap<String, Class<out Tool>> = ConcurrentHashMap()

    init {
        tools[GetBalTop::class.java.simpleName.lowercase()] = GetBalTop::class.java
        tools[GetPlayerInfo::class.java.simpleName.lowercase()] = GetPlayerInfo::class.java
    }

    fun getTool(toolName: String): Class<out Tool>? {
        return tools[toolName.lowercase()]
    }

    class GetBalTop : Tool {
        val mustIncludePlayers: List<String> = listOf()

        override fun execute(): Any {
            val uuids = mustIncludePlayers.map { Bukkit.getOfflinePlayer(it).uniqueId }
            val top = HookRegistry.redisEcoHook.getTopAccounts(100)
            val specificPlayers = HookRegistry.redisEcoHook.getAccounts(uuids)

            return specificPlayers.join().plus(top.join())
                .sortedByDescending { it.balance }
                .distinctBy { it.name }
                .distinctBy { it.uuid }
                .mapIndexed { index, balance ->
                    "${index + 1}. ${balance.name} - ${"%,.2f".format(balance.balance)}"
                }.joinToString("\n")
        }
    }

    class GetPlayerInfo : Tool {
        val playerNames: List<String> = listOf()

        override fun execute(): Any {
            val uuids = playerNames.map { Bukkit.getOfflinePlayer(it).uniqueId }
            val accounts = HookRegistry.redisEcoHook.getAccounts(uuids).join()
            return playerNames.map { CMI.getInstance().playerManager.getUser(it) }
                .associateWith { user: CMIUser ->
                    val groups = HookRegistry.luckPermsHook.getGroups(user.offlinePlayer)
                        .filter { it.contains("vip") || it.contains("admin") }
                    val result = mutableMapOf<String, String>()
                    val timePlayed = user.totalPlayTime
                    val stringFormattedTimePlayed = String.format(
                        "%dч %dм",
                        timePlayed / 20 / 60 / 60,
                        timePlayed / 20 / 60 % 60
                    )
                    result["name"] = user.name
                    result["isAfk"] = user.isAfk.toString()
                    result["isOnline"] = user.isOnline.toString()
                    result["isFlying"] = user.isFlying.toString()
                    result["isJailed"] = user.isJailed.toString()
                    result["exp"] = user.exp.toString()
                    result["level"] = user.level.toString()
                    result["mails"] = user.mails.size.toString()
                    result["rank"] = user.rank.name
                    result["rankDisplay"] = user.rank.displayName
                    result["totalPlayTime"] = stringFormattedTimePlayed
                    result["uuid"] = user.uniqueId.toString()
                    result["balance"] = accounts.firstOrNull { it.uuid == user.uniqueId }?.balance?.toString() ?: "0.0"
                    result["nonDefaultGroups"] = groups.joinToString(separator = ", ")

                    result
                }.mapKeys { it.key.name }
        }
    }
}
