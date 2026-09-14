package ru.arc.sidebar

import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import ru.arc.paper.api.ArcSidebarPriorities
import ru.arc.paper.api.ArcSidebarService
import ru.arc.xserver.playerlist.PlayerManager

/** Lowest-priority replacement for the former Velocity TAB sidebar layouts. */
internal class ArcBaseSidebar(
    private val plugin: ARC,
    service: ArcSidebarService,
) : AutoCloseable {
    private val config = ConfigManager.of(plugin.dataPath, RESOURCE)
    private val source: ArcSidebarHandle = service.register(plugin, "base", ArcSidebarPriorities.BASE)
    private val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
    private var task: BukkitTask? = null
    private var shown = emptySet<UUID>()

    fun start() {
        check(task == null) { "Base sidebar is already started" }
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable(::refresh), 10L, 20L)
    }

    internal fun refresh() {
        val online = Bukkit.getOnlinePlayers().associateBy(Player::getUniqueId)
        (shown - online.keys).forEach(source::hide)
        val next = linkedSetOf<UUID>()
        online.values.forEach { player ->
            val style = selectedSidebarStyle(player)
            if (!enabledOnCurrentServer() || style == null) {
                source.hide(player)
                return@forEach
            }
            val rows = config.stringList("styles.$style.lines").map { render(player, it) }
            if (rows.isEmpty()) {
                source.hide(player)
                return@forEach
            }
            source.show(
                player,
                ArcSidebarFrame(
                    title = render(player, config.string("title", "&#B22222&lRus&f&lCrafting")),
                    rows = rows,
                ),
            )
            next += player.uniqueId
        }
        shown = next
    }

    override fun close() {
        task?.cancel()
        task = null
        shown = emptySet()
        source.close()
    }

    private fun enabledOnCurrentServer(): Boolean {
        if (!config.bool("enabled", true)) return false
        val serverId = ARC.serverName?.lowercase() ?: return false
        return serverId in config.stringList("enabled-servers", listOf("spawn", "survival")).map(String::lowercase)
    }

    private fun selectedSidebarStyle(player: Player): String? =
        (1..STYLE_COUNT).firstOrNull { index ->
            player.hasPermission(if (index == 1) "tab.scoreboard" else "tab.scoreboard$index")
        }?.let { index -> "style${index.toString().padStart(2, '0')}" }

    private fun render(player: Player, template: String): Component {
        var rendered = replaceNativePlaceholders(template, player, ARC.serverName.orEmpty())
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            rendered = PlaceholderAPI.setPlaceholders(player, rendered)
        }
        rendered = rendered
            .replace("%lands_land_name_plain_here%", "Серверная")
            .replace("%arc_worldname%", "Сервер")
            .replace(UNRESOLVED_PLACEHOLDER, "…")
            .replace('§', '&')
        return if (rendered.isEmpty()) Component.empty() else legacy.deserialize(rendered)
    }

    companion object {
        private const val RESOURCE = "modules/scoreboard.yml"
        private const val STYLE_COUNT = 20
        private val UNRESOLVED_PLACEHOLDER = Regex("%[^%\r\n]+%")
        private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        internal fun replaceNativePlaceholders(template: String, player: Player, serverId: String): String {
            val networkPlayers = PlayerManager.getPlayerNames()
            val networkTotal = networkPlayers.size.coerceAtLeast(Bukkit.getOnlinePlayers().size)
            fun serverOnline(id: String): Int = networkPlayers.count { name ->
                PlayerManager.findByName(name)?.server.equals(id, ignoreCase = true)
            }.let { count -> if (id.equals(serverId, true) && count == 0) Bukkit.getOnlinePlayers().size else count }
            val now = LocalDateTime.now()
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MEBIBYTE
            val totalMb = runtime.maxMemory() / MEBIBYTE
            val tps = Bukkit.getTPS().firstOrNull()?.coerceAtMost(20.0) ?: 20.0
            val uptime = ManagementFactory.getRuntimeMXBean().uptime
            val replacements = linkedMapOf(
                "%player%" to player.name,
                "%player_ping%" to player.ping.toString(),
                "%player_x%" to player.location.blockX.toString(),
                "%player_y%" to player.location.blockY.toString(),
                "%player_z%" to player.location.blockZ.toString(),
                "%online%" to networkTotal.toString(),
                "%serveronline%" to serverOnline(serverId).toString(),
                "%online_spawn%" to serverOnline("spawn").toString(),
                "%online_survival%" to serverOnline("survival").toString(),
                "%online_parkour%" to serverOnline("parkour").toString(),
                "%server%" to serverId,
                "%server_version%" to Bukkit.getMinecraftVersion(),
                "%server_tps_5_colored%" to String.format("%.1f", tps),
                "%server_uptime%" to formatDuration(uptime),
                "%server_ram_used%" to usedMb.toString(),
                "%server_ram_total%" to totalMb.toString(),
                "%date%" to now.format(DATE),
                "%time%" to now.format(TIME),
            )
            return replacements.entries.fold(template) { value, (token, replacement) -> value.replace(token, replacement) }
        }

        private fun formatDuration(milliseconds: Long): String {
            val minutes = milliseconds.coerceAtLeast(0) / 60_000
            val hours = minutes / 60
            val days = hours / 24
            return when {
                days > 0 -> "${days}д ${hours % 24}ч"
                hours > 0 -> "${hours}ч ${minutes % 60}м"
                else -> "${minutes}м"
            }
        }

        private const val MEBIBYTE = 1024L * 1024L
    }
}
