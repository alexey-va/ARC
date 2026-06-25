package ru.arc.ai.tools

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Containers.CMIUser
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.Tasks
import ru.arc.hooks.HookRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object PaperAiToolExecutors {

    private val gson = Gson()

    fun all(): Map<String, ToolExecutor> =
        mapOf(
            ToolNames.GET_BAL_TOP to ToolExecutor { payload -> executeBalTop(payload) },
            ToolNames.GET_PLAYER_INFO to ToolExecutor { payload -> executePlayerInfo(payload) },
            ToolNames.GET_INVENTORY to ToolExecutor { payload -> executeInventory(payload) },
        )

    private fun executeBalTop(payload: JsonElement): JsonElement {
        if (HookRegistry.redisEcoHook == null) {
            throw IllegalStateException("RedisEconomy hook required for AI tools")
        }
        val dto = gson.fromJson(payload, BalTopPayload::class.java)
        val names = dto.mustIncludePlayers ?: emptyList()
        val uuids = names.map { Bukkit.getOfflinePlayer(it).uniqueId }
        val top = HookRegistry.redisEcoHook!!.getTopAccounts(100)
        val specific = HookRegistry.redisEcoHook!!.getAccounts(uuids)
        val text =
            specific.join().plus(top.join())
                .sortedByDescending { it.balance }
                .distinctBy { it.name }
                .distinctBy { it.uuid }
                .mapIndexed { index, balance ->
                    "${index + 1}. ${balance.name} - ${"%,.2f".format(balance.balance)}"
                }.joinToString("\n")
        return JsonPrimitive(text)
    }

    private fun executePlayerInfo(payload: JsonElement): JsonElement {
        val dto = gson.fromJson(payload, PlayerInfoPayload::class.java)
        val names = dto.playerNames ?: emptyList()
        val uuids = names.map { Bukkit.getOfflinePlayer(it).uniqueId }
        val accounts = HookRegistry.redisEcoHook!!.getAccounts(uuids).join()
        val result = JsonObject()
        for (name in names) {
            val user = CMI.getInstance().playerManager.getUser(name) ?: continue
            val groups =
                HookRegistry.luckPermsHook!!.getGroups(user.offlinePlayer)
                    .filter { it.contains("vip", ignoreCase = true) || it.contains("admin", ignoreCase = true) }
            val timePlayed = user.totalPlayTime
            val formatted =
                String.format(
                    "%dч %dм",
                    timePlayed / 1000 / 60 / 60,
                    timePlayed / 1000 / 60 % 60,
                )
            val entry = JsonObject()
            entry.addProperty("name", user.name)
            entry.addProperty("isAfk", user.isAfk)
            entry.addProperty("isOnline", user.isOnline)
            entry.addProperty("isFlying", user.isFlying)
            entry.addProperty("isJailed", user.isJailed)
            entry.addProperty("exp", user.exp)
            entry.addProperty("level", user.level)
            entry.addProperty("mails", user.mails.size)
            entry.addProperty("rank", user.rank.name)
            entry.addProperty("rankDisplay", user.rank.displayName)
            entry.addProperty("totalPlayTime", formatted)
            entry.addProperty("uuid", user.uniqueId.toString())
            entry.addProperty(
                "balance",
                accounts.firstOrNull { it.uuid == user.uniqueId }?.balance?.toString() ?: "0.0",
            )
            entry.addProperty("nonDefaultGroups", groups.joinToString(", "))
            result.add(user.name, entry)
        }
        return result
    }

    private fun executeInventory(payload: JsonElement): JsonElement {
        val dto = gson.fromJson(payload, InventoryPayload::class.java)
        val playerName = dto.playerName ?: return JsonPrimitive("playerName required")
        val future = CompletableFuture<JsonElement>()
        Tasks.scheduler.runSync(
            Runnable {
                val player = Bukkit.getPlayerExact(playerName)
                if (player == null) {
                    future.complete(JsonPrimitive("Player $playerName is not online on ${ARC.serverName}"))
                    return@Runnable
                }
                future.complete(buildInventoryJson(player))
            },
        )
        return future.get(5, TimeUnit.SECONDS)
    }

    private fun buildInventoryJson(player: Player): JsonElement {
        val items = JsonArray()
        player.inventory.contents.filterNotNull().forEach { stack ->
            if (stack.type == Material.AIR) return@forEach
            val item = JsonObject()
            item.addProperty("material", stack.type.name)
            item.addProperty("amount", stack.amount)
            if (stack.itemMeta.hasDisplayName()) {
                item.addProperty("displayName", stack.itemMeta.displayName)
            }
            items.add(item)
        }
        val root = JsonObject()
        root.addProperty("player", player.name)
        root.addProperty("server", ARC.serverName)
        root.add("items", items)
        return root
    }

    private data class BalTopPayload(val mustIncludePlayers: List<String>? = null)

    private data class PlayerInfoPayload(val playerNames: List<String>? = null)

    private data class InventoryPayload(val playerName: String? = null)
}
