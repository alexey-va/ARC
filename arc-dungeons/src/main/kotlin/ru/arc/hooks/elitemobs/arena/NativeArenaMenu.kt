package ru.arc.hooks.elitemobs.arena

import com.magmaguy.elitemobs.config.DefaultConfig
import com.magmaguy.elitemobs.config.DungeonsConfig
import com.magmaguy.elitemobs.config.menus.premade.ArenaMenuConfig
import com.magmaguy.elitemobs.instanced.arena.ArenaInstance
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.Config
import ru.arc.core.TaskScheduler
import ru.arc.menu.MenuCatalog
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuElementLayout
import ru.arc.menu.MenuId
import ru.arc.menu.MenuLayout
import ru.arc.menu.MenuSlot
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.menu.PaperMenuSession
import java.util.UUID

/** The existing nine-slot arena menu, with a shared selection between the two entry actions. */
internal class NativeArenaMenu(
    plugin: JavaPlugin,
    private val config: Config,
    scheduler: TaskScheduler,
    private val levels: NativeArenaLevels,
) : AutoCloseable {
    private var settings = Settings(config)
    private val runtime = PaperMenuRuntime(plugin, scheduler, settings.configuration())
    private val viewers = mutableMapOf<UUID, View>()

    fun open(player: Player, filename: String) {
        val arena = ArenaInstance.getArenaInstances()[filename]
        if (arena == null) {
            player.sendMessage(text(ArenaMenuConfig.getInvalidArenaMessage()))
            return
        }
        val lobby = levels.lobby(arena)
        val view = View(player, arena, ArenaMenuSelection(lobby, selectable(arena, lobby)))
        view.session = runtime.open(player, MENU) { content(view) }
        viewers[player.uniqueId] = view
    }

    fun reload() {
        val candidate = Settings(config)
        runtime.replace(candidate.configuration())
        settings = candidate
        viewers.clear()
    }

    fun refresh() {
        viewers.values.removeIf { !it.session.isOpen }
        viewers.values.toList().forEach { it.session.refresh() }
    }

    private fun content(view: View): PaperMenuContent {
        val arena = view.arena
        val lobby = levels.lobby(arena)
        val selection = view.selection
        selection.reconcile(lobby, selectable(arena, lobby))
        val values = mapOf(
            "level" to selection.level, "minimum" to lobby.plan.baseline, "maximum" to lobby.plan.maximum,
            "wave" to arena.currentWave, "total" to arena.customArenasConfigFields.waveCount,
        )
        val elements = linkedMapOf<MenuElementId, PaperMenuEntry>()
        val joinLore = if (lobby.plan.supported) listOf(Component.empty(), settings.message("joinLevelLine", values)) else emptyList()
        if (arena.isAcceptingNewPlayers) {
            elements[JOIN] = PaperMenuEntry(nativeItem(ArenaMenuConfig.getPlayerItem(), joinLore), onClick = { context ->
                if (acknowledgeChange(view)) return@PaperMenuEntry
                if (levels.join(arena, context.player, selection.level)) context.session.close()
                else context.session.refresh()
            })
        }
        if (DungeonsConfig.isAllowSpectatorsInInstancedContent()) {
            elements[SPECTATE] = PaperMenuEntry(nativeItem(ArenaMenuConfig.getSpectatorItem()), onClick = { context ->
                arena.addSpectator(context.player, false)
                if (arena.isSpectator(context.player)) context.session.close()
            })
        }
        val lore = mutableListOf<Component>()
        if (lobby.plan.supported) {
            lobby.plan.range(1, selection.level)?.let {
                lore += settings.message("firstWaveLevelLine", values + ("range" to range(it)))
            }
            lobby.plan.range(arena.customArenasConfigFields.waveCount, selection.level)?.let {
                lore += settings.message("lastWaveLevelLine", values + ("range" to range(it)))
            }
        } else lore += settings.message("unknownLevelLine", values)
        lore += settings.message("fullRunLine", values)
        lore += settings.message("rewardsLine", values)
        if (selection.selectable) {
            lore += Component.empty()
            lore += settings.message("allowedLevelsLine", values)
        }
        val titleKey = when {
            !lobby.plan.supported -> "originalDifficultyName"
            arena.currentWave > 0 -> "currentWaveName"
            selection.selectable -> "enemyLevelName"
            else -> "lockedEnemyLevelName"
        }
        elements[VALUE] = PaperMenuEntry(item(settings.valueMaterial, settings.message(titleKey, values), lore))
        if (selection.selectable) {
            fun step(id: MenuElementId, direction: Int, name: String, hint: String) {
                elements[id] = PaperMenuEntry(
                    item(settings.arrowMaterial, settings.message(name, values), listOf(settings.message(hint, values))),
                    acceptedClicks = setOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT),
                    onClick = { context ->
                        if (!acknowledgeChange(view)) {
                            selection.step(direction * if (context.event.isShiftClick) 10 else 1, lobby)
                            context.session.refresh()
                        }
                    },
                )
            }
            step(DECREASE, -1, "decreaseLevelName", "decreaseLevelHint")
            step(INCREASE, 1, "increaseLevelName", "increaseLevelHint")
        }
        var title = ArenaMenuConfig.getMenuName() + arena.customArenasConfigFields.arenaName
        if (DefaultConfig.useResourcePackModels()) title = "\uDB83\uDEF1\uDB83\uDE0A\uDB83\uDEF5          $title"
        return PaperMenuContent(text(title), elements = elements)
    }

    private fun acknowledgeChange(view: View): Boolean {
        val lobby = levels.lobby(view.arena)
        view.selection.reconcile(lobby, selectable(view.arena, lobby))
        if (!view.selection.acknowledge()) return false
        view.player.sendMessage(settings.message("lobbyChangedMessage"))
        view.session.refresh()
        return true
    }

    override fun close() {
        runtime.close()
        viewers.clear()
    }

    private class View(val player: Player, val arena: ArenaInstance, val selection: ArenaMenuSelection) {
        lateinit var session: PaperMenuSession
    }

    private class Settings(config: Config) {
        private val messages = DEFAULT_MESSAGES.mapValues { (key, default) -> config.string("messages.$key", default) }
        private val slots = linkedMapOf(
            JOIN to config.int("menu.player-slot", ArenaMenuConfig.getPlayerItemSlot()),
            SPECTATE to config.int("menu.spectator-slot", ArenaMenuConfig.getSpectatorItemSlot()),
            DECREASE to config.int("menu.decrease-slot", 3),
            VALUE to config.int("menu.value-slot", 4),
            INCREASE to config.int("menu.increase-slot", 5),
        )
        val valueMaterial = Material.matchMaterial(config.string("menu.value-material", "PAPER")) ?: Material.PAPER
        val arrowMaterial = Material.matchMaterial(config.string("menu.arrow-material", "ARROW")) ?: Material.ARROW

        init {
            require(slots.values.all { it in 0..8 } && slots.values.toSet().size == slots.size) {
                "Arena level menu requires five distinct slots between 0 and 8"
            }
            require(!valueMaterial.isAir && !arrowMaterial.isAir) { "Arena level controls cannot be air" }
        }

        fun message(key: String, values: Map<String, Any> = emptyMap()): Component {
            var result = messages.getValue(key)
            values.forEach { (name, value) -> result = result.replace("$$name", value.toString()) }
            return text(result)
        }

        fun configuration() = PaperMenuConfiguration(
            MenuCatalog(layouts = mapOf(MENU to MenuLayout(MENU, 1, slots.mapValues { (id, slot) ->
                MenuElementLayout.button(id, MenuSlot.of(slot))
            }))),
            emptyMap(),
        )
    }

    internal companion object {
        private val MENU = MenuId.of("native-arena-levels")
        private val JOIN = MenuElementId.of("join")
        private val SPECTATE = MenuElementId.of("spectate")
        private val DECREASE = MenuElementId.of("decrease")
        private val VALUE = MenuElementId.of("level")
        private val INCREASE = MenuElementId.of("increase")
        private val serializer = LegacyComponentSerializer.legacyAmpersand()
        fun text(value: String): Component = serializer.deserialize(value.replace('§', '&')).decoration(TextDecoration.ITALIC, false)
        private fun selectable(arena: ArenaInstance, lobby: ArenaLevelLobby) =
            lobby.plan.supported && !lobby.locked && arena.isAcceptingNewPlayers

        private fun range(range: IntRange) = if (range.first == range.last) range.first.toString() else "${range.first}–${range.last}"

        private fun nativeItem(original: ItemStack, append: List<Component> = emptyList()): ItemStack = original.clone().apply {
            editMeta { meta ->
                meta.displayName(meta.displayName()?.decoration(TextDecoration.ITALIC, false))
                meta.lore(meta.lore().orEmpty().map { it.decoration(TextDecoration.ITALIC, false) } + append)
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }
        }

        private fun item(material: Material, name: Component, lore: List<Component>) = ItemStack(material).apply {
            editMeta { meta -> meta.displayName(name); meta.lore(lore) }
        }

        private val DEFAULT_MESSAGES = mapOf(
            "enemyLevelName" to "&eУровень мобов на старте: \$level",
            "lockedEnemyLevelName" to "&eВыбран уровень старта: \$level",
            "currentWaveName" to "&eВолна \$wave из \$total",
            "originalDifficultyName" to "&eИсходная сложность",
            "firstWaveLevelLine" to "&fПервая волна: ур. \$range",
            "lastWaveLevelLine" to "&fПоследняя волна: ур. \$range",
            "unknownLevelLine" to "&fДоступна исходная сложность арены.",
            "fullRunLine" to "&fВсе \$total волн, начиная с первой.",
            "rewardsLine" to "&fСостав и уровень наград прежние.",
            "allowedLevelsLine" to "&fУровень старта: \$minimum–\$maximum",
            "decreaseLevelName" to "&f−1 уровень",
            "increaseLevelName" to "&f+1 уровень",
            "decreaseLevelHint" to "&fShift + клик: −10 уровней",
            "increaseLevelHint" to "&fShift + клик: +10 уровней",
            "joinLevelLine" to "&fУровень мобов на старте: \$level",
            "lobbyChangedMessage" to "&eУсловия арены изменились. Проверьте уровень мобов и нажмите вход ещё раз.",
        )
    }
}

/** Retains an acknowledgement even when the shared menu refreshed before the stale click. */
internal class ArenaMenuSelection(lobby: ArenaLevelLobby, selectable: Boolean) {
    var level = lobby.selected
        private set
    var selectable = selectable
        private set
    private var generation = lobby.generation
    private var changed = false

    fun reconcile(lobby: ArenaLevelLobby, canSelect: Boolean) {
        val newRun = generation != lobby.generation
        if (newRun || selectable && !canSelect && level != lobby.selected) changed = true
        if (!canSelect) level = lobby.selected
        else if (!selectable || newRun) level = lobby.plan.baseline
        generation = lobby.generation
        selectable = canSelect
    }

    fun step(amount: Int, lobby: ArenaLevelLobby) {
        if (selectable) level = (level + amount).coerceIn(lobby.plan.baseline, lobby.plan.maximum)
    }

    fun acknowledge(): Boolean = changed.also { changed = false }
}
