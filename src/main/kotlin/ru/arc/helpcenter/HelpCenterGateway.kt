package ru.arc.helpcenter

import com.Zrips.CMI.CMI
import dev.lone.itemsadder.api.CustomStack
import me.angeschossen.lands.api.LandsIntegration
import net.william278.huskhomes.api.HuskHomesAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.chat.ChatModeService
import ru.arc.core.modules.EconomyModule
import ru.arc.lands.currentLands
import ru.arc.util.TextUtil
import ru.arc.util.displayNamePlain
import ru.arc.xserver.playerlist.PlayerManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.floor

interface HelpCenterGateway {
    fun loadHomes(player: Player, timeoutSeconds: Long): CompletableFuture<HelpCenterHomes>

    fun loadProfile(player: Player, timeoutSeconds: Long): CompletableFuture<HelpCenterProfile>

    fun features(): Set<HelpCenterFeature>

    fun onlinePlayers(): List<HelpCenterPlayer>

    fun context(player: Player): HelpCenterContext

    fun settings(player: Player): HelpCenterSettingSnapshot

    fun execute(player: Player, command: String): Boolean
}

class BukkitHelpCenterGateway : HelpCenterGateway {
    override fun loadHomes(player: Player, timeoutSeconds: Long): CompletableFuture<HelpCenterHomes> {
        if (!Bukkit.getPluginManager().isPluginEnabled("HuskHomes")) {
            return CompletableFuture.failedFuture(IllegalStateException("HuskHomes is unavailable"))
        }
        return runCatching {
            val api = HuskHomesAPI.getInstance()
            val user = api.adaptUser(player)
            val maxSlots = api.getMaxHomeSlots(user).coerceAtLeast(0)
            api.getUserHomes(user)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .thenApply { homes ->
                    val snapshots = homes.map { home ->
                        HelpCenterHome(
                            name = home.name,
                            server = home.server,
                            world = home.world.name,
                            x = floor(home.x).toInt(),
                            y = floor(home.y).toInt(),
                            z = floor(home.z).toInt(),
                        )
                    }
                    HelpCenterHomes(snapshots, snapshots.size, maxOf(maxSlots, snapshots.size))
                }
        }.getOrElse(CompletableFuture<HelpCenterHomes>::failedFuture)
    }

    override fun loadProfile(player: Player, timeoutSeconds: Long): CompletableFuture<HelpCenterProfile> {
        val location = player.location
        val base = HelpCenterProfile(
            playerName = player.name,
            server = ARC.serverName?.takeIf(String::isNotBlank) ?: "—",
            world = location.world.name,
            x = floor(location.x).toInt(),
            y = floor(location.y).toInt(),
            z = floor(location.z).toInt(),
            balance = runCatching {
                EconomyModule.getEconomy()?.getBalance(player)?.takeIf(Double::isFinite)?.let(TextUtil::formatAmount)
            }.getOrNull(),
            rank = runCatching {
                if (!Bukkit.getPluginManager().isPluginEnabled("CMI")) return@runCatching null
                CMI.getInstance().playerManager.getUser(player.uniqueId)?.rank?.name
            }.getOrNull(),
            homes = null,
            lands = runCatching {
                if (!Bukkit.getPluginManager().isPluginEnabled("Lands")) return@runCatching null
                LandsIntegration.of(ARC.instance)
                    .getLandPlayer(player.uniqueId)
                    ?.currentLands()
                    .orEmpty()
                    .count { it.exists() }
            }.getOrNull(),
            chatMode = if (ChatModeService.getMode(player.uniqueId).name == "GLOBAL") {
                HelpCenterChatMode.GLOBAL
            } else {
                HelpCenterChatMode.LOCAL
            },
            onlinePlayers = onlinePlayers().size,
        )
        return loadHomes(player, timeoutSeconds).handle { homes, _ -> base.copy(homes = homes) }
    }

    override fun execute(player: Player, command: String): Boolean = Bukkit.dispatchCommand(player, command)

    override fun features(): Set<HelpCenterFeature> = HelpCenterFeature.entries
        .filterTo(linkedSetOf()) { feature ->
            feature.pluginName == null || Bukkit.getPluginManager().isPluginEnabled(feature.pluginName)
        }

    override fun onlinePlayers(): List<HelpCenterPlayer> {
        val networkPlayers = PlayerManager.getPlayerUuids()
            .mapNotNull(PlayerManager::getPlayerData)
            .map { HelpCenterPlayer(it.uuid, it.username, it.server.takeIf(String::isNotBlank)) }
        if (networkPlayers.isNotEmpty()) return networkPlayers

        return Bukkit.getOnlinePlayers().map { player ->
            HelpCenterPlayer(player.uniqueId, player.name, ARC.serverName?.takeIf(String::isNotBlank))
        }
    }

    override fun context(player: Player): HelpCenterContext {
        val location = player.location
        val features = features()
        val item = player.inventory.itemInMainHand.takeUnless { it.type.isAir }?.let { stack ->
            val customId = if (HelpCenterFeature.ITEMS in features) {
                runCatching { CustomStack.byItemStack(stack)?.namespacedID }.getOrNull()
            } else {
                null
            }
            HelpCenterHeldItem(
                displayName = stack.displayNamePlain?.takeIf(String::isNotBlank)
                    ?: stack.type.name.lowercase().replace('_', ' '),
                material = stack.type.name,
                amount = stack.amount.coerceAtLeast(1),
                itemsAdderId = customId,
            )
        }
        val area = if (HelpCenterFeature.LANDS in features) {
            runCatching { LandsIntegration.of(ARC.instance).getArea(location) }.getOrNull()
        } else {
            null
        }
        val land = area?.land
        return HelpCenterContext(
            server = ARC.serverName?.takeIf(String::isNotBlank) ?: "—",
            world = location.world.name,
            worldKind = worldKind(location.world.name),
            x = floor(location.x).toInt(),
            y = floor(location.y).toInt(),
            z = floor(location.z).toInt(),
            heldItem = item,
            landName = land?.name,
            landOwner = land?.ownerUID == player.uniqueId,
            features = features,
        )
    }

    override fun settings(player: Player): HelpCenterSettingSnapshot {
        val trails = Bukkit.getPluginManager().getPlugin("Trails")?.takeIf { it.isEnabled }
        fun trailsState(methodName: String): Boolean? = trails?.let { plugin ->
            runCatching {
                val method = plugin.javaClass.methods.single { candidate ->
                    candidate.name == methodName && candidate.parameterTypes.contentEquals(arrayOf(java.util.UUID::class.java))
                }
                method.invoke(plugin, player.uniqueId) as Boolean
            }.getOrNull()
        }
        return HelpCenterSettingSnapshot(
            chatMode = if (ChatModeService.getMode(player.uniqueId).name == "GLOBAL") {
                HelpCenterChatMode.GLOBAL
            } else {
                HelpCenterChatMode.LOCAL
            },
            trailsEnabled = trailsState("trailsEnabled"),
            trailBoostEnabled = trailsState("boostEnabled"),
        )
    }

    private fun worldKind(worldName: String): HelpCenterWorldKind {
        val normalized = worldName.lowercase()
        return when {
            normalized.contains("mining") || normalized.contains("resource") -> HelpCenterWorldKind.MINING
            normalized.contains("biome") || normalized.contains("iris") -> HelpCenterWorldKind.NEW_BIOMES
            normalized == "world" || normalized.contains("survival") || normalized.contains("vanilla") -> HelpCenterWorldKind.VANILLA
            else -> HelpCenterWorldKind.OTHER
        }
    }
}
