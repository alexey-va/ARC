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
import ru.arc.hooks.HookRegistry
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import ru.arc.paper.api.ArcSidebarPriorities
import ru.arc.xserver.playerlist.PlayerManager

/** Lowest-priority replacement for the former Velocity TAB sidebar layouts. */
internal class ArcBaseSidebar(
    private val plugin: ARC,
    private val service: SectionedSidebarService,
) : AutoCloseable {
    private val config = ConfigManager.of(plugin.dataPath, RESOURCE)
    private val source: ArcSidebarHandle = service.register(plugin, "base", ArcSidebarPriorities.BASE)
    private val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
    private var task: BukkitTask? = null
    private var shown = emptySet<UUID>()

    fun start() {
        check(task == null) { "Base sidebar is already started" }
        service.refreshPlayer = ::refreshPlayer
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable(::refresh), 10L, 20L)
    }

    internal fun refresh() {
        val online = Bukkit.getOnlinePlayers().associateBy(Player::getUniqueId)
        (shown - online.keys).forEach(source::hide)
        shown = shown.intersect(online.keys)
        online.values.forEach(::refreshPlayer)
    }

    internal fun refreshPlayer(player: Player) {
        if (!enabledOnCurrentServer() || !supportsSidebarClient(player) || !sidebarEnabled(player::hasPermission)) {
            source.hide(player)
            shown -= player.uniqueId
            return
        }
        val serverId = ARC.serverName.orEmpty()
        val layout = if (serverId.equals("slimefun", ignoreCase = true)) "slimefun.sections" else "sections"
        fun enabled(section: SidebarSection) = section.enabled(HookRegistry.luckPermsHook?.getCachedMeta(player.uniqueId, section.metaKey))
        val rows = composeSidebarSections(
            enabled = ::enabled,
            lines = { section -> sectionRows(player, section, layout, serverId) },
            separator = Component.empty(),
            isBlank = { it == Component.empty() },
        )
        if (rows.isEmpty()) {
            source.hide(player)
            shown -= player.uniqueId
            return
        }
        val frame = ArcSidebarFrame(render(player, config.string("title", "&#B22222&lRus&f&lCrafting")), rows)
        source.show(player, frame)
        service.present(player, frame, enabled(SidebarSection.ACTIVITY))
        shown += player.uniqueId
    }

    private fun sectionRows(player: Player, section: SidebarSection, layout: String, serverId: String): List<Component> {
        if (section == SidebarSection.ACTIVITY) service.activity(player.uniqueId)?.let { return it.rows }
        if (section == SidebarSection.RANK_PROGRESS) {
            val token = "%arcranks_next_rank_progress_compact%"
            val progress = resolvePlaceholder(player, token)
            if (progress.isBlank() || progress == token || progress in setOf("…", "...")) return emptyList()
        }
        val data = sidebarPlayerData(player, section)
        if (section == SidebarSection.SKILLS && data.isEmpty()) return emptyList()
        return config.stringList("$layout.${section.id}").mapNotNull { template ->
            val serverLine = resolveServerSidebarLine(template, serverId) ?: return@mapNotNull null
            val line = resolveOptionalSidebarLine(serverLine) { placeholder ->
                if (placeholder.startsWith("%arc_sidebar_")) data[placeholder].orEmpty() else resolvePlaceholder(player, placeholder)
            } ?: return@mapNotNull null
            render(player, data.entries.fold(line) { text, (key, value) -> text.replace(key, value) })
        }
    }

    override fun close() {
        service.refreshPlayer = null
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

    private fun supportsSidebarClient(player: Player): Boolean =
        isSidebarClientSupported(
            HookRegistry.viaVersionHook?.getPlayerVersion(player),
            config.int("minimum-client-protocol", DEFAULT_MINIMUM_CLIENT_PROTOCOL).coerceAtLeast(0),
        )

    private fun render(player: Player, template: String): Component {
        var rendered = replaceNativePlaceholders(template, player, ARC.serverName.orEmpty())
        rendered = resolvePlaceholder(player, rendered)
        rendered = rendered
            .replace("%lands_land_name_plain_here%", "Серверная")
            .replace("%arc_worldname%", "Сервер")
            .replace(UNRESOLVED_PLACEHOLDER, "…")
            .replace('§', '&')
        return if (rendered.isEmpty()) Component.empty() else legacy.deserialize(rendered)
    }

    private fun resolvePlaceholder(player: Player, template: String): String =
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            PlaceholderAPI.setPlaceholders(player, template)
        } else {
            template
        }

    companion object {
        private const val RESOURCE = "modules/scoreboard.yml"
        private const val DEFAULT_MINIMUM_CLIENT_PROTOCOL = 774
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
                "%player_direction%" to sidebarDirection(player.location.yaw),
                "%online%" to networkTotal.toString(),
                "%serveronline%" to serverOnline(serverId).toString(),
                "%online_spawn%" to serverOnline("spawn").toString(),
                "%online_survival%" to serverOnline("survival").toString(),
                "%online_parkour%" to serverOnline("parkour").toString(),
                "%server%" to serverId,
                "%slimefun_place%" to when {
                    player.world.name == "slimefun_hub" -> "Хаб SkyBlock"
                    player.world.name.startsWith("slimefun_skyblock") -> "Острова"
                    else -> "Мир Slimefun"
                },
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

internal fun isSidebarClientSupported(
    clientProtocol: Int?,
    minimumClientProtocol: Int,
): Boolean = clientProtocol == null || clientProtocol < 0 || clientProtocol >= minimumClientProtocol

internal fun resolveOptionalSidebarLine(
    template: String,
    resolvePlaceholder: (String) -> String,
): String? {
    if (!template.startsWith(OPTIONAL_LINE_PREFIX)) return template
    val content = template.drop(OPTIONAL_LINE_PREFIX.length)
    val placeholders = SIDEBAR_PLACEHOLDER.findAll(content).map(MatchResult::value).toList()
    if (placeholders.isEmpty()) return content
    return content.takeIf {
        placeholders.any { placeholder ->
            resolvePlaceholder(placeholder).let { resolved -> resolved.isNotBlank() && resolved != placeholder && resolved !in setOf("…", "...") }
        }
    }
}

internal fun resolveServerSidebarLine(template: String, serverId: String): String? {
    val match = SERVER_SCOPED_LINE.matchEntire(template) ?: return template
    return match.groupValues[2].takeIf { match.groupValues[1].equals(serverId, ignoreCase = true) }
}

private const val OPTIONAL_LINE_PREFIX = "?"
private val SIDEBAR_PLACEHOLDER = Regex("%[^%\\r\\n]+%")
private val SERVER_SCOPED_LINE = Regex("^@([a-z0-9_-]+) (.+)$", RegexOption.IGNORE_CASE)
