package ru.arc.tablist

import java.util.UUID
import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.hooks.HookRegistry
import ru.arc.sidebar.ArcBaseSidebar
import ru.arc.sidebar.SidebarSection
import ru.arc.sidebar.resolveOptionalSidebarLine
import ru.arc.sidebar.resolveServerSidebarLine
import ru.arc.sidebar.sidebarPlayerData

/** Bukkit state is rendered on the main thread; async TAB-Bridge requests read immutable snapshots. */
internal class ArcTablist(private val plugin: ARC) : AutoCloseable {
    private val config = ConfigManager.of(plugin.dataPath, "modules/tablist.yml")
    @Volatile private var frames: Map<UUID, TablistFrame> = emptyMap()
    private var task: ScheduledTask? = null
    private val failedPlayers = mutableSetOf<UUID>()

    fun start() {
        check(task == null) { "Tablist is already started" }
        task = Tasks.scheduler.runTimer(1L, 20L, Runnable(::refresh))
    }

    fun value(playerId: UUID, key: String): String = when (key.lowercase()) {
        "tablist_ready" -> (playerId in frames).toString()
        "tablist_header" -> frames[playerId]?.header.orEmpty()
        "tablist_footer" -> frames[playerId]?.footer.orEmpty()
        else -> ""
    }

    fun capacity(selected: Set<TablistSection>): TablistCapacity {
        val serverId = ARC.serverName.orEmpty()
        return tablistCapacity(config.stringList("brand"), selected.associateWith { sectionTemplates(it, serverId) }, maximumRows())
    }

    fun refresh() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            frames = emptyMap()
            return
        }
        val next = linkedMapOf<UUID, TablistFrame>()
        Bukkit.getOnlinePlayers().forEach { player ->
            try {
                next[player.uniqueId] = render(player)
                failedPlayers -= player.uniqueId
            } catch (failure: Exception) {
                if (failedPlayers.add(player.uniqueId)) plugin.logger.log(java.util.logging.Level.WARNING, "Could not render personal tablist for ${player.uniqueId}", failure)
            }
        }
        frames = next
        failedPlayers.retainAll(next.keys + Bukkit.getOnlinePlayers().map(Player::getUniqueId))
    }

    private fun render(player: Player): TablistFrame {
        if (!tablistEnabled(player::hasPermission)) return TablistFrame()
        val serverId = ARC.serverName.orEmpty()
        val sections = TablistSection.entries.filter {
            it.enabled(HookRegistry.luckPermsHook?.getCachedMeta(player.uniqueId, it.metaKey))
        }.associateWith { section -> sectionRows(player, section, serverId) }
        return composeTablistSections(
            config.stringList("brand").map { resolve(player, it) }, sections,
            maximumRows(),
        )
    }

    private fun maximumRows() = config.int("maximum-rows", 22).coerceIn(8, 40)

    private fun sectionTemplates(section: TablistSection, serverId: String): List<String> {
        val layout = if (serverId.equals("slimefun", true)) "slimefun.sections" else "sections"
        return config.stringList("$layout.${section.id}").mapNotNull { resolveServerSidebarLine(it, serverId) }
    }

    private fun sectionRows(player: Player, section: TablistSection, serverId: String): List<String> {
        if (section == TablistSection.RANK_PROGRESS && resolve(player, "%arcranks_next_rank_progress_compact%") in setOf("", "…", "...")) return emptyList()
        val dataSection = when (section) {
            TablistSection.PROFESSION -> SidebarSection.PROFESSION
            TablistSection.SKILLS -> SidebarSection.SKILLS
            TablistSection.ACTIVITY -> SidebarSection.ACTIVITY
            else -> null
        }
        val data = dataSection?.let { sidebarPlayerData(player, it, TABLIST_SKILLS_META_KEY) }.orEmpty()
        if (dataSection != null && data.isEmpty()) return emptyList()
        val rows = sectionTemplates(section, serverId).mapNotNull { template ->
            val line = resolveOptionalSidebarLine(template) { token ->
                if (token.startsWith("%arc_sidebar_")) data[token].orEmpty() else resolve(player, token)
            } ?: return@mapNotNull null
            resolve(player, line, data)
        }
        return if (section in HEADED_SECTIONS && rows.size <= 1) emptyList() else rows
    }

    private fun resolve(player: Player, template: String, data: Map<String, String> = emptyMap()): String {
        val native = ArcBaseSidebar.replaceNativePlaceholders(template, player, ARC.serverName.orEmpty())
        return resolveTablistTemplate(native) { token -> data[token] ?: PlaceholderAPI.setPlaceholders(player, token) }
            .replace('§', '&')
    }

    override fun close() {
        task?.cancel()
        task = null
        frames = emptyMap()
        failedPlayers.clear()
    }

    companion object {
        private val HEADED_SECTIONS = setOf(TablistSection.QUESTS, TablistSection.PROFESSION, TablistSection.SKILLS)
    }
}

/** Resolve authored tokens once; values may contain literal percent signs or player text. */
internal fun resolveTablistTemplate(template: String, resolve: (String) -> String): String =
    TABLIST_PLACEHOLDER.replace(template) { match ->
        resolve(match.value).let { value ->
            if (value != match.value) value else when (match.value) {
                "%lands_land_name_plain_here%" -> "Серверная"
                "%arc_worldname%" -> "Сервер"
                else -> ""
            }
        }
    }

private val TABLIST_PLACEHOLDER = Regex("%[^%\\r\\n]+%")
