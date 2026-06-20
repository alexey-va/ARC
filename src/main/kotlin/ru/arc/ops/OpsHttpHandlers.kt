package ru.arc.ops

import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.ModuleRegistry
import ru.arc.core.modules.EconomyModule
import ru.arc.hooks.HookRegistry
import ru.arc.util.playSoundSelf
import ru.arc.util.sendActionBarMM
import ru.arc.util.sendMM
import ru.arc.util.showTitleMM
import ru.arc.xserver.XActionManager
import ru.arc.xserver.XCondition
import ru.arc.xserver.XMessage
import ru.arc.xserver.playerlist.PlayerManager
import org.bukkit.boss.BarColor
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs Bukkit API code on the main thread from HTTP worker threads.
 */
object OpsBukkitSync {
    fun <T> call(timeoutSeconds: Long = 5, block: () -> T): T {
        if (Bukkit.isPrimaryThread()) {
            return block()
        }
        val plugin = ARC.instance
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                try {
                    result.set(block())
                } catch (t: Throwable) {
                    error.set(t)
                } finally {
                    latch.countDown()
                }
            },
        )
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw IllegalStateException("Bukkit sync timed out after ${timeoutSeconds}s")
        }
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }
}

object OpsHttpHandlers {
    fun health(): Map<String, Any?> =
        mapOf(
            "service" to "arc-ops",
            "serverName" to (ARC.serverName ?: "unknown"),
            "online" to Bukkit.getOnlinePlayers().size,
            "maxPlayers" to Bukkit.getMaxPlayers(),
        )

    fun serverInfo(): Map<String, Any?> =
        OpsBukkitSync.call {
            val tps = runCatching { Bukkit.getServer().tps[0] }.getOrDefault(20.0)
            mapOf(
                "serverName" to (ARC.serverName ?: "unknown"),
                "bukkitVersion" to Bukkit.getBukkitVersion(),
                "pluginVersion" to ARC.instance.pluginMeta.version,
                "online" to Bukkit.getOnlinePlayers().size,
                "maxPlayers" to Bukkit.getMaxPlayers(),
                "tps" to "%.2f".format(tps),
                "redisConnected" to (ARC.redisManager?.isConnected() == true),
                "worlds" to Bukkit.getWorlds().map { it.name },
            )
        }

    fun onlinePlayers(): Map<String, Any?> =
        OpsBukkitSync.call {
            val players = Bukkit.getOnlinePlayers().map { playerSummary(it) }
            mapOf(
                "count" to players.size,
                "max" to Bukkit.getMaxPlayers(),
                "players" to players,
            )
        }

    fun player(name: String): Map<String, Any?>? =
        OpsBukkitSync.call {
            val player = Bukkit.getPlayerExact(name) ?: return@call null
            playerSummary(player, extended = true)
        }

    fun playerLookup(name: String): Map<String, Any?> {
        val online = OpsBukkitSync.call { Bukkit.getPlayerExact(name) }
        if (online != null) {
            return OpsBukkitSync.call {
                playerSummary(online, extended = true) + mapOf("online" to true)
            }
        }
        return lpLookupOffline(name)
    }

    fun playerWhere(name: String): Map<String, Any?> =
        OpsBukkitSync.call {
            val local = Bukkit.getPlayerExact(name)
            if (local != null) {
                return@call mapOf(
                    "player" to local.name,
                    "uuid" to local.uniqueId.toString(),
                    "online" to true,
                    "server" to (ARC.serverName ?: "unknown"),
                    "source" to "local",
                )
            }
            val cross = PlayerManager.findByName(name)
            if (cross != null) {
                return@call mapOf(
                    "player" to cross.username,
                    "uuid" to cross.uuid.toString(),
                    "online" to true,
                    "server" to cross.server,
                    "source" to "proxy",
                    "joinTime" to cross.joinTime,
                )
            }
            mapOf(
                "player" to name,
                "online" to false,
                "source" to "none",
            )
        }

    fun parsePlaceholder(
        playerName: String,
        text: String,
    ): Map<String, Any?> {
        if (HookRegistry.papiHook == null) {
            return mapOf("error" to "PlaceholderAPI hook not available")
        }
        require(text.isNotBlank()) { "text required" }
        return OpsBukkitSync.call {
            val offline = resolveOfflinePlayer(playerName)
            val parsed = HookRegistry.papiHook!!.parse(text, offline)
            mapOf(
                "player" to (offline.name ?: playerName),
                "uuid" to offline.uniqueId.toString(),
                "text" to text,
                "result" to parsed,
            )
        }
    }

    fun permissionCheck(
        playerName: String,
        node: String,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val player = Bukkit.getPlayerExact(playerName)
            if (player == null) {
                return@call mapOf(
                    "player" to playerName,
                    "permission" to node,
                    "online" to false,
                    "result" to "offline",
                )
            }
            mapOf(
                "player" to player.name,
                "uuid" to player.uniqueId.toString(),
                "permission" to node,
                "online" to true,
                "source" to "bukkit",
                "result" to if (player.hasPermission(node)) "true" else "false",
            )
        }

    fun lpPermissionCheck(
        playerName: String,
        node: String,
    ): Map<String, Any?> {
        if (HookRegistry.luckPermsHook == null) {
            return mapOf("error" to "LuckPerms hook not available")
        }
        val result = AtomicReference<Map<String, Any?>>()
        val error = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        Bukkit.getScheduler().runTaskAsynchronously(
            ARC.instance,
            Runnable {
                try {
                    val offline = resolveOfflinePlayer(playerName)
                    val hook = HookRegistry.luckPermsHook!!
                    val allowed = hook.hasPermission(offline, node)
                    val groups = hook.getGroups(offline)
                    result.set(
                        mapOf(
                            "player" to (offline.name ?: playerName),
                            "uuid" to offline.uniqueId.toString(),
                            "permission" to node,
                            "online" to offline.isOnline,
                            "source" to "luckperms",
                            "result" to if (allowed) "true" else "false",
                            "groups" to groups,
                        ),
                    )
                } catch (t: Throwable) {
                    error.set(t)
                } finally {
                    latch.countDown()
                }
            },
        )
        if (!latch.await(15, TimeUnit.SECONDS)) {
            return mapOf("error" to "LuckPerms check timed out")
        }
        error.get()?.let { throw it }
        return result.get() ?: mapOf("error" to "empty LuckPerms result")
    }

    fun errors(
        limit: Int,
        grep: String?,
        sinceMs: Long?,
    ): Map<String, Any?> {
        var entries = OpsLogBuffer.recent(limit.coerceIn(1, 500))
        if (sinceMs != null) {
            entries = entries.filter { it.timestamp >= sinceMs }
        }
        if (!grep.isNullOrBlank()) {
            val pattern = grep.lowercase()
            entries = entries.filter { it.message.lowercase().contains(pattern) || it.level.lowercase().contains(pattern) }
        }
        return mapOf(
            "count" to entries.size,
            "entries" to entries.map { it.toMap() },
        )
    }

    fun modules(): Map<String, Any?> {
        val modules =
            ModuleRegistry.getModules().map { mod ->
                mapOf("name" to mod.name, "enabled" to mod.enabled, "priority" to mod.priority)
            }
        return mapOf("modules" to modules, "count" to modules.size)
    }

    fun feature(name: String): Map<String, Any?> {
        val module =
            ModuleRegistry.getModules().firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: return mapOf("error" to "Unknown ARC module: $name")
        return mapOf(
            "name" to module.name,
            "enabled" to module.enabled,
            "priority" to module.priority,
            "class" to module.javaClass.simpleName,
        )
    }

    fun redis(): Map<String, Any?> {
        val rm = ARC.redisManager
        return mapOf(
            "present" to (rm != null),
            "connected" to (rm?.isConnected() == true),
            "channels" to (rm?.getChannelCount() ?: 0),
            "subscriptionActive" to (rm?.isSubscriptionActive() == true),
        )
    }

    fun plugins(
        limit: Int = 100,
        statusFilter: String? = null,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val pm = Bukkit.getPluginManager()
            val loaded =
                pm.plugins
                    .map { plugin ->
                        val status = if (plugin.isEnabled) "ok" else "disabled"
                        mapOf(
                            "name" to plugin.name,
                            "enabled" to plugin.isEnabled,
                            "status" to status,
                            "version" to plugin.pluginMeta.version,
                            "authors" to plugin.pluginMeta.authors,
                            "main" to plugin.description.main,
                        )
                    }.sortedBy { it["name"] as String }

            val filtered =
                when (statusFilter?.lowercase()) {
                    "ok", "enabled" -> loaded.filter { it["status"] == "ok" }
                    "disabled" -> loaded.filter { it["status"] == "disabled" }
                    else -> loaded
                }.take(limit.coerceIn(1, 300))

            val unloadedJars = findUnloadedPluginJars(pm, limit = 20)

            mapOf(
                "count" to filtered.size,
                "plugins" to filtered,
                "unloadedJars" to unloadedJars,
                "summary" to
                    mapOf(
                        "ok" to loaded.count { it["status"] == "ok" },
                        "disabled" to loaded.count { it["status"] == "disabled" },
                        "unloadedJars" to unloadedJars.size,
                    ),
            )
        }

    fun configHash(
        paths: List<String>,
        prefixes: List<String>,
        limit: Int,
    ): Map<String, Any?> {
        val root = serverRoot()
        return when {
            paths.isNotEmpty() -> OpsConfigHasher.hashPaths(root, paths)
            prefixes.isNotEmpty() -> OpsConfigHasher.hashPrefixes(root, prefixes, limit)
            else ->
                OpsConfigHasher.hashPrefixes(
                    root,
                    listOf("plugins/ARC/modules", "config"),
                    limit,
                )
        }
    }

    fun sendMessage(body: JsonObject): Map<String, Any?> =
        OpsBukkitSync.call {
            val channel = body.get("channel")?.asString?.lowercase() ?: body.get("target")?.asString?.lowercase()
            val text = body.get("text")?.asString ?: body.get("message")?.asString
            require(!channel.isNullOrBlank()) { "channel required: broadcast, player, ops" }
            require(!text.isNullOrBlank()) { "text required" }

            when (channel) {
                "broadcast", "all" -> {
                    Bukkit.getOnlinePlayers().forEach { it.sendMM(text) }
                    mapOf("sent" to true, "channel" to "broadcast", "recipients" to Bukkit.getOnlinePlayers().size)
                }
                "ops", "staff" -> {
                    val recipients =
                        Bukkit.getOnlinePlayers().filter { it.isOp || it.hasPermission("arc.ops.notify") }
                    recipients.forEach { it.sendMM(text) }
                    mapOf("sent" to true, "channel" to "ops", "recipients" to recipients.size)
                }
                "player" -> {
                    val playerName = body.get("player")?.asString
                    require(!playerName.isNullOrBlank()) { "player required for channel=player" }
                    val player = Bukkit.getPlayerExact(playerName)
                        ?: throw IllegalArgumentException("Player not online: $playerName")
                    player.sendMM(text)
                    mapOf("sent" to true, "channel" to "player", "player" to player.name)
                }
                else -> throw IllegalArgumentException("Unknown channel: $channel")
            }
        }

    fun publishBroadcast(body: com.google.gson.JsonObject): Map<String, Any?> =
        OpsBukkitSync.call {
            val text = body.get("text")?.asString ?: body.get("message")?.asString
            require(!text.isNullOrBlank()) { "text required" }

            val typeName = body.get("type")?.asString?.uppercase()?.replace('-', '_') ?: "CHAT"
            val type =
                runCatching { XMessage.Type.valueOf(typeName) }
                    .getOrElse { throw IllegalArgumentException("Unknown type: $typeName (chat, boss_bar, action_bar, toast)") }

            val conditions = mutableListOf<XCondition>()
            body.get("permission")?.asString?.takeIf { it.isNotBlank() }?.let {
                conditions.add(XCondition.ofPermission(it))
            }
            body.get("player")?.asString?.takeIf { it.isNotBlank() }?.let {
                conditions.add(XCondition.ofPlayerName(it))
            }
            parseServerConditions(body.get("servers"))?.let { conditions.addAll(it) }

            val bossBarData =
                body.get("bossbar")?.asJsonObject?.let { bb ->
                    XMessage.BossBarData(
                        name = bb.get("name")?.asString ?: "arc-broadcast",
                        color =
                            runCatching {
                                BarColor.valueOf(bb.get("color")?.asString?.uppercase() ?: "BLUE")
                            }.getOrElse { BarColor.BLUE },
                        seconds = bb.get("seconds")?.asInt ?: 10,
                        keepFor = bb.get("keep-for")?.asInt ?: bb.get("keepFor")?.asInt ?: 0,
                    )
                }

            val actionBarData =
                body.get("actionbar")?.asJsonObject?.let { ab ->
                    XMessage.ActionBarData(seconds = ab.get("seconds")?.asInt ?: 5)
                }

            val message =
                XMessage(
                    type = type,
                    serializedMessage = text,
                    serializationType = XMessage.SerializationType.MINI_MESSAGE,
                    conditions = conditions.ifEmpty { null },
                    bossBarData = bossBarData,
                    actionBarData = actionBarData,
                )

            XActionManager.publish(message)
            mapOf(
                "published" to true,
                "channel" to "arc.xactions",
                "type" to type.name.lowercase(),
                "origin" to (ARC.serverName ?: "unknown"),
                "conditions" to conditions.size,
            )
        }

    private fun parseServerConditions(element: com.google.gson.JsonElement?): List<XCondition>? {
        if (element == null || element.isJsonNull) return null
        val names =
            when {
                element.isJsonArray ->
                    element.asJsonArray.mapNotNull { entry ->
                        if (entry.isJsonNull) null else entry.asString.trim()
                    }.filter { it.isNotEmpty() }
                element.isJsonPrimitive ->
                    element.asString.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }
        val filtered = names.filterNot { it.equals("all", ignoreCase = true) }
        if (filtered.isEmpty()) return null
        return filtered.map { XCondition.ofServerName(it) }
    }

    fun applyEffect(body: JsonObject): Map<String, Any?> =
        OpsBukkitSync.call {
            val playerName = body.get("player")?.asString
            require(!playerName.isNullOrBlank()) { "player required" }
            val player = Bukkit.getPlayerExact(playerName)
                ?: throw IllegalArgumentException("Player not online: $playerName")
            val type = body.get("type")?.asString?.lowercase() ?: "actionbar"

            when (type) {
                "title" -> {
                    val title = body.get("title")?.asString ?: ""
                    val subtitle = body.get("subtitle")?.asString ?: ""
                    player.showTitleMM(title, subtitle)
                }
                "actionbar" -> {
                    val text = body.get("text")?.asString ?: body.get("message")?.asString ?: ""
                    require(text.isNotBlank()) { "text required for actionbar" }
                    player.sendActionBarMM(text)
                }
                "sound" -> {
                    val sound = body.get("sound")?.asString ?: throw IllegalArgumentException("sound required")
                    val volume = body.get("volume")?.asFloat ?: 1f
                    val pitch = body.get("pitch")?.asFloat ?: 1f
                    require(player.playSoundSelf(sound, volume, pitch)) { "Unknown sound: $sound" }
                }
                else -> throw IllegalArgumentException("Unknown effect type: $type")
            }
            mapOf("applied" to true, "type" to type, "player" to player.name)
        }

    fun scopedReload(target: String): Map<String, Any?> {
        val normalized = target.lowercase()
        return when (normalized) {
            "arc" ->
                OpsBukkitSync.call {
                    ARC.instance.reload()
                    mapOf("reloaded" to true, "target" to "arc")
                }
            "denizen" -> runConsole("ex reload")
            else -> throw IllegalArgumentException("Unknown reload target: $target (allowed: arc, denizen)")
        }
    }

    fun runConsole(command: String): Map<String, Any?> {
        require(command.isNotBlank()) { "command is blank" }
        OpsBukkitSync.call {
            ARC.trySeverCommand(command)
        }
        return mapOf("executed" to true, "command" to command)
    }

    fun runAs(
        playerName: String,
        command: String,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val player = Bukkit.getPlayerExact(playerName)
                ?: throw IllegalArgumentException("Player not online: $playerName")
            val cmd = command.trim().removePrefix("/")
            require(cmd.isNotBlank()) { "command is blank" }
            val success = Bukkit.dispatchCommand(player, cmd)
            mapOf(
                "executed" to true,
                "player" to player.name,
                "command" to cmd,
                "success" to success,
            )
        }

    private fun serverRoot(): Path =
        Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize()

    private fun resolveOfflinePlayer(name: String): OfflinePlayer {
        Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        Bukkit.getOfflinePlayerIfCached(name)?.let { return it }
        return Bukkit.getOfflinePlayer(name)
    }

    private fun lpLookupOffline(name: String): Map<String, Any?> {
        if (HookRegistry.luckPermsHook == null) {
            return mapOf("player" to name, "online" to false, "error" to "LuckPerms hook not available")
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Map<String, Any?>>()
        Bukkit.getScheduler().runTaskAsynchronously(
            ARC.instance,
            Runnable {
                try {
                    val offline = resolveOfflinePlayer(name)
                    val groups = HookRegistry.luckPermsHook!!.getGroups(offline)
                    result.set(
                        mapOf(
                            "name" to (offline.name ?: name),
                            "uuid" to offline.uniqueId.toString(),
                            "online" to offline.isOnline,
                            "groups" to groups,
                        ),
                    )
                } finally {
                    latch.countDown()
                }
            },
        )
        latch.await(15, TimeUnit.SECONDS)
        return result.get() ?: mapOf("player" to name, "online" to false, "error" to "lookup timed out")
    }

    private fun findUnloadedPluginJars(
        pm: org.bukkit.plugin.PluginManager,
        limit: Int,
    ): List<Map<String, Any?>> {
        val pluginsDir = File(Bukkit.getWorldContainer().parentFile, "plugins")
        if (!pluginsDir.isDirectory) return emptyList()

        val loadedNames = pm.plugins.map { it.name.lowercase() }.toSet()
        return pluginsDir
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .filter { jar ->
                val stem = jar.nameWithoutExtension.lowercase()
                loadedNames.none { stem.contains(it) || it.contains(stem.substringBefore('-')) }
            }
            .take(limit)
            .map { jar ->
                mapOf(
                    "file" to "plugins/${jar.name}",
                    "bytes" to jar.length(),
                    "status" to "unloaded",
                )
            }.toList()
    }

    private fun playerSummary(
        player: Player,
        extended: Boolean = false,
    ): Map<String, Any?> {
        val base =
            linkedMapOf<String, Any?>(
                "name" to player.name,
                "uuid" to player.uniqueId.toString(),
                "world" to player.world.name,
                "gamemode" to player.gameMode.name.lowercase(),
                "health" to player.health,
                "level" to player.level,
                "exp" to player.exp,
            )
        if (extended) {
            val loc = player.location
            base["x"] = loc.blockX
            base["y"] = loc.blockY
            base["z"] = loc.blockZ
            base["ping"] = runCatching { player.ping }.getOrNull()
            EconomyModule.getEconomy()?.let { econ ->
                base["balance"] = econ.getBalance(player)
            }
            if (HookRegistry.luckPermsHook != null) {
                base["groups"] = HookRegistry.luckPermsHook!!.getGroups(player)
            }
        }
        return base
    }
}
