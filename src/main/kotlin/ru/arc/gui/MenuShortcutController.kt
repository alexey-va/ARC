package ru.arc.gui

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.plugin.Plugin
import ru.arc.onboarding.ClaimBlockIdentity
import ru.arc.landsui.LandsUiModule
import ru.arc.helpcenter.HelpCenterModule
import ru.arc.helpcenter.HelpCenterPage
import ru.arc.hooks.HookRegistry
import ru.arc.mounts.MountModule
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.util.TextUtil

enum class MenuShortcutAction(val id: String, val page: HelpCenterPage? = null) {
    MAIN("main", HelpCenterPage.ROOT),
    MOUNT("mount"),
    TRAVEL("travel", HelpCenterPage.TRAVEL),
    FAVORITES("favorites", HelpCenterPage.FAVORITES),
    SETTINGS("settings", HelpCenterPage.SETTINGS),
    DISABLED("disabled"),
    ;

    companion object {
        const val META_KEY = "arc-shift-f-action"
        fun from(value: String?): MenuShortcutAction = entries.firstOrNull { it.id == value } ?: MAIN
        fun selected(player: Player): MenuShortcutAction = from(HookRegistry.luckPermsHook?.getCachedMeta(player.uniqueId, META_KEY))
    }
}

/** One owner for the network-wide personal Shift + swap-hands binding. */
class MenuShortcutController(
    plugin: Plugin,
    private val selection: (Player) -> MenuShortcutAction = MenuShortcutAction::selected,
    private val openMenu: (Player, HelpCenterPage) -> Boolean = HelpCenterModule::open,
    private val summonMount: (Player) -> Boolean = MountModule::summonFavorite,
    private val inDungeon: (Player) -> Boolean = { ARC.hookRegistry?.dungeonQol?.panelView(it) != null },
    private val openDungeonMenu: (Player) -> Unit = { ARC.hookRegistry?.dungeonQol?.action(it, "menu") },
) : Listener, AutoCloseable {
    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        if (event.isCancelled || !event.player.isSneaking) return
        if (ClaimBlockIdentity.matches(event.player.inventory.itemInMainHand) ||
            ClaimBlockIdentity.matches(event.player.inventory.itemInOffHand)) {
            event.isCancelled = true
            LandsUiModule.open(event.player)
            return
        }
        if (inDungeon(event.player)) {
            event.isCancelled = true
            ArcMenus.beginDialogFlow(event.player)
            openDungeonMenu(event.player)
            return
        }
        val action = selection(event.player)
        if (action == MenuShortcutAction.DISABLED) return
        // Cancel before opening a screen: the selected action must never also swap items.
        event.isCancelled = true
        val accepted = if (action == MenuShortcutAction.MOUNT) summonMount(event.player)
            else action.page?.let { openMenu(event.player, it) } == true
        if (!accepted) event.player.sendMessage(TextUtil.mm(
            ConfigManager.ofModule(ARC.instance.dataPath, "help-center.yml").string(
                "text.shortcut-unavailable",
                "<red>Назначенное действие сейчас недоступно. Выберите другое в настройках меню.",
            ), true,
        ))
    }

    override fun close() = HandlerList.unregisterAll(this)
}
