package ru.arc.helpcenter

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Modules.PlayerOptions.PlayerOption
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.types.PermissionNode
import net.william278.huskhomes.HuskHomes
import net.william278.huskhomes.api.HuskHomesAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.hooks.HookRegistry
import ru.arc.gui.MenuShortcutAction
import ru.arc.gui.MenuEscapeBehavior
import java.util.concurrent.CompletableFuture

data class HelpCenterLegacySettingEntry(
    val id: String,
    val labelKey: String,
    val state: String?,
    val tooltipKey: String,
)

/** Typed bridge for the old personal settings. Menu presentation stays in the controller. */
class HelpCenterLegacySettings(
    private val backend: Backend = BukkitBackend(),
) {
    interface Backend {
        fun hasPermission(player: Player, node: String): Boolean
        fun meta(player: Player, key: String): String?
        fun cmiOption(player: Player, option: CmiOption): Boolean?
        fun flightState(player: Player): String?
        fun tpaEnabled(player: Player): Boolean?
        fun setPermission(player: Player, node: String, enabled: Boolean): CompletableFuture<Boolean>
        fun setExclusiveMode(player: Player, prefix: String, mode: Int?): CompletableFuture<Boolean>
        fun setMeta(player: Player, key: String, value: String): CompletableFuture<Boolean>
        fun command(player: Player, command: PlayerCommand): CompletableFuture<Boolean>
        fun consoleCommand(player: Player, command: ConsoleCommand): CompletableFuture<Boolean>
    }

    enum class CmiOption { SHIFT_SIGN_EDIT, TOTEM_BOSSBAR }

    enum class PlayerCommand(val value: String) {
        LANDS_SHOW("lands view here"), LANDS_HIDE("lands view disable"),
        FLIGHT_EXP("cmi autorecharge exp on -s"), FLIGHT_MONEY("cmi autorecharge money on -s"),
        FLIGHT_OFF_EXP("cmi autorecharge exp off -s"), FLIGHT_OFF_MONEY("cmi autorecharge money off -s"),
        FLIGHT_RECHARGE("cmi flightcharge recharge"), FLIGHT_TOGGLE("flyc"),
        SHIFT_ENABLE("cmi options shiftsignedit enable"), SHIFT_DISABLE("cmi options shiftsignedit disable"),
        TOTEM_ENABLE("cmi options totembossbar enable"), TOTEM_DISABLE("cmi options totembossbar disable"),
        TPA_TOGGLE("huskhomes:tpignore"),
    }

    enum class ConsoleCommand { OPEN_ADMIN_SETTINGS }

    fun entries(player: Player): List<HelpCenterLegacySettingEntry> = listOf(
        entry("shortcut", "legacy-settings-shortcut", MenuShortcutAction.from(backend.meta(player, MenuShortcutAction.META_KEY)).id, "legacy-settings-shortcut-tooltip"),
        entry("escape", "legacy-settings-escape", if (backend.meta(player, MenuEscapeBehavior.META_KEY) == "back") "back" else "close", "legacy-settings-escape-tooltip"),
        entry("scoreboard", "legacy-settings-scoreboard", modeState(player, "tab.scoreboard"), "legacy-settings-scoreboard-tooltip"),
        entry("tablist", "legacy-settings-tablist", modeState(player, "tab.tablist"), "legacy-settings-tablist-tooltip"),
        entry("lands", "legacy-settings-lands", null, "legacy-settings-lands-tooltip"),
        entry("portal-by-other", "legacy-settings-portal-by-other", onOff(player, PORTAL_BY_OTHER), "legacy-settings-portal-by-other-tooltip"),
        entry("portal-for-other", "legacy-settings-portal-for-other", onOff(player, PORTAL_FOR_OTHER), "legacy-settings-portal-for-other-tooltip"),
        entry("notifications", "legacy-settings-notifications", onOff(player, CHAT_NOTIFY), "legacy-settings-notifications-tooltip"),
        entry("flight", "legacy-settings-flight", backend.flightState(player), "legacy-settings-flight-tooltip"),
        entry("shift-sign-edit", "legacy-settings-shift-edit", cmiOnOff(player, CmiOption.SHIFT_SIGN_EDIT), "legacy-settings-shift-tooltip"),
        entry("resource-pack", "legacy-settings-resource-pack", onOff(player, RESOURCE_PACK), "legacy-settings-resource-pack-tooltip"),
        entry("totem", "legacy-settings-totem", cmiOnOff(player, CmiOption.TOTEM_BOSSBAR), "legacy-settings-totem-tooltip"),
        entry("stairs-sit", "legacy-settings-stairs-sit", onOff(player, STAIRS_SIT), "legacy-settings-stairs-tooltip"),
        entry("tpa", "legacy-settings-tpa", backend.tpaEnabled(player)?.let { if (it) "on" else "off" }, "legacy-settings-tpa-tooltip"),
        entry("portal-style", "legacy-settings-portal-style", portalStyleState(player), "legacy-settings-portal-style-tooltip"),
        entry("admin", "legacy-settings-admin", if (backend.hasPermission(player, ADMIN)) "available" else "locked", "legacy-settings-admin-tooltip"),
    )

    fun execute(player: Player, id: String): CompletableFuture<Boolean> = when (id) {
        "admin" -> if (backend.hasPermission(player, ADMIN)) backend.consoleCommand(player, ConsoleCommand.OPEN_ADMIN_SETTINGS) else falseFuture()
        "scoreboard-off" -> backend.setExclusiveMode(player, "tab.scoreboard", null)
        "tablist-off" -> backend.setExclusiveMode(player, "tab.tablist", null)
        "lands-show" -> backend.command(player, PlayerCommand.LANDS_SHOW)
        "lands-hide" -> backend.command(player, PlayerCommand.LANDS_HIDE)
        "portal-by-other" -> togglePermission(player, PORTAL_BY_OTHER)
        "portal-for-other" -> togglePermission(player, PORTAL_FOR_OTHER)
        "escape-close" -> backend.setMeta(player, MenuEscapeBehavior.META_KEY, "close")
        "escape-back" -> backend.setMeta(player, MenuEscapeBehavior.META_KEY, "back")
        "notifications" -> togglePermission(player, CHAT_NOTIFY)
        "flight-exp" -> commands(player, PlayerCommand.FLIGHT_EXP, PlayerCommand.FLIGHT_OFF_MONEY)
        "flight-money" -> commands(player, PlayerCommand.FLIGHT_MONEY, PlayerCommand.FLIGHT_OFF_EXP)
        "flight-off" -> commands(player, PlayerCommand.FLIGHT_OFF_EXP, PlayerCommand.FLIGHT_OFF_MONEY)
        "flight-recharge" -> backend.command(player, PlayerCommand.FLIGHT_RECHARGE)
        "flight-toggle" -> backend.command(player, PlayerCommand.FLIGHT_TOGGLE)
        "shift-sign-edit" -> toggleCmi(player, CmiOption.SHIFT_SIGN_EDIT, SHIFT_EDIT, PlayerCommand.SHIFT_ENABLE, PlayerCommand.SHIFT_DISABLE)
        "resource-pack" -> togglePermission(player, RESOURCE_PACK)
        "totem" -> toggleCmi(player, CmiOption.TOTEM_BOSSBAR, TOTEM, PlayerCommand.TOTEM_ENABLE, PlayerCommand.TOTEM_DISABLE)
        "stairs-sit" -> togglePermission(player, STAIRS_SIT)
        "tpa" -> backend.command(player, PlayerCommand.TPA_TOGGLE)
        "portal-style" -> nextPortalStyle(player)
        else -> modeAction(player, id)
    }

    private fun modeAction(player: Player, id: String): CompletableFuture<Boolean> {
        MenuShortcutAction.entries.firstOrNull { "shortcut-${it.id}" == id }?.let {
            return backend.setMeta(player, MenuShortcutAction.META_KEY, it.id)
        }
        val match = Regex("^(scoreboard|tablist)-(\\d{1,2})$").matchEntire(id) ?: return falseFuture()
        val mode = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..20 } ?: return falseFuture()
        return backend.setExclusiveMode(player, match.groupValues[1].let { if (it == "scoreboard") "tab.scoreboard" else "tab.tablist" }, mode)
    }

    private fun togglePermission(player: Player, node: String) = backend.setPermission(player, node, !backend.hasPermission(player, node))

    private fun toggleCmi(player: Player, option: CmiOption, node: String, on: PlayerCommand, off: PlayerCommand): CompletableFuture<Boolean> {
        val enabled = backend.cmiOption(player, option) ?: return falseFuture()
        return backend.command(player, if (enabled) off else on).thenCompose { ok -> if (ok) backend.setPermission(player, node, !enabled) else falseFuture() }
    }

    private fun commands(player: Player, vararg commands: PlayerCommand): CompletableFuture<Boolean> {
        var future = CompletableFuture.completedFuture(true)
        commands.forEach { command -> future = future.thenCompose { ok -> if (ok) backend.command(player, command) else falseFuture() } }
        return future
    }

    private fun nextPortalStyle(player: Player): CompletableFuture<Boolean> {
        val current = backend.meta(player, PORTAL_STYLE_META)?.lowercase()
        val currentIndex = PORTAL_STYLES.indexOf(current).takeIf { it >= 0 } ?: PORTAL_STYLES.indexOf("origin")
        val next = PORTAL_STYLES[(currentIndex + 1) % PORTAL_STYLES.size]
        return backend.setMeta(player, PORTAL_STYLE_META, next)
    }

    private fun modeState(player: Player, prefix: String): String = (1..20).firstOrNull { mode -> backend.hasPermission(player, modeNode(prefix, mode)) }?.toString() ?: "off"
    private fun onOff(player: Player, node: String) = if (backend.hasPermission(player, node)) "on" else "off"
    private fun cmiOnOff(player: Player, option: CmiOption) = backend.cmiOption(player, option)?.let { if (it) "on" else "off" }
    private fun portalStyleState(player: Player): String {
        val preference = backend.meta(player, PORTAL_STYLE_META)?.lowercase()?.takeIf { it in PORTAL_STYLES }
        return preference ?: runCatching {
            ConfigManager.of(ARC.instance.dataPath, "modules/misc.yml")
                .string("portal.origin-gate.default-style", "origin")
                .lowercase()
                .takeIf { it in PORTAL_STYLES }
        }.getOrNull() ?: "unknown"
    }
    private fun entry(id: String, label: String, state: String?, tooltip: String) = HelpCenterLegacySettingEntry(id, label, state, tooltip)

    private fun falseFuture() = CompletableFuture.completedFuture(false)

    private class BukkitBackend : Backend {
        override fun hasPermission(player: Player, node: String) = player.hasPermission(node)
        override fun meta(player: Player, key: String) = HookRegistry.luckPermsHook?.getCachedMeta(player.uniqueId, key)
        override fun cmiOption(player: Player, option: CmiOption): Boolean? = runCatching {
            val user = CMI.getInstance().playerManager.getUser(player.uniqueId) ?: return null
            user.getOptionState(if (option == CmiOption.SHIFT_SIGN_EDIT) PlayerOption.shiftSignEdit else PlayerOption.totemBossBar)
        }.getOrNull()
        override fun flightState(player: Player): String? = runCatching {
            val charge = CMI.getInstance().playerManager.getUser(player.uniqueId)?.flightCharge ?: return null
            when {
                !charge.isAutoRecharge -> "off"
                charge.isExpAutoRecharge -> "exp"
                charge.isMoneyAutoRecharge -> "money"
                else -> "unknown"
            }
        }.getOrNull()
        override fun tpaEnabled(player: Player): Boolean? = runCatching {
            val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("HuskHomes") as? HuskHomes ?: return null
            val user = HuskHomesAPI.getInstance().adaptUser(player)
            val saved = plugin.getSavedUser(user).orElse(null) ?: return null
            !saved.isIgnoringTeleports
        }.getOrNull()
        override fun setPermission(player: Player, node: String, enabled: Boolean) = runCatching {
            LuckPermsProvider.get().userManager.modifyUser(player.uniqueId) { user ->
                user.nodes.filterIsInstance<PermissionNode>().filter { it.permission == node && it.contexts.isEmpty() && it.expiry == null }.forEach(user.data()::remove)
                user.data().add(PermissionNode.builder(node).value(enabled).build())
            }
        }.getOrElse { CompletableFuture.failedFuture(it) }.thenApply { true }
        override fun setExclusiveMode(player: Player, prefix: String, mode: Int?) = runCatching {
            LuckPermsProvider.get().userManager.modifyUser(player.uniqueId) { user ->
                (1..20).forEach { index ->
                    val node = modeNode(prefix, index)
                    user.nodes.filterIsInstance<PermissionNode>().filter { it.permission == node && it.contexts.isEmpty() && it.expiry == null }.forEach(user.data()::remove)
                    user.data().add(PermissionNode.builder(node).value(mode == index).build())
                }
            }
        }.getOrElse { CompletableFuture.failedFuture(it) }.thenApply { true }
        override fun setMeta(player: Player, key: String, value: String) = HookRegistry.luckPermsHook?.setMeta(player.uniqueId, key, value)?.thenApply { true }
            ?: CompletableFuture.completedFuture(false)
        override fun command(player: Player, command: PlayerCommand): CompletableFuture<Boolean> =
            if (command == PlayerCommand.TPA_TOGGLE) toggleTpa(player) else CompletableFuture.completedFuture(player.performCommand(command.value))

        private fun toggleTpa(player: Player): CompletableFuture<Boolean> {
            val result = CompletableFuture<Boolean>()
            fun start() {
                val before = tpaEnabled(player)
                if (before == null || !player.performCommand(PlayerCommand.TPA_TOGGLE.value)) {
                    result.complete(false)
                    return
                }
                var ticks = 0
                lateinit var task: org.bukkit.scheduler.BukkitTask
                task = Bukkit.getScheduler().runTaskTimer(ARC.instance, Runnable {
                    val after = tpaEnabled(player)
                    if (after != null && after != before) {
                        task.cancel()
                        result.complete(true)
                    } else if (++ticks >= 40) {
                        task.cancel()
                        result.complete(false)
                    }
                }, 1L, 1L)
            }
            if (Bukkit.isPrimaryThread()) start() else Bukkit.getScheduler().runTask(ARC.instance, ::start)
            return result
        }
        override fun consoleCommand(player: Player, command: ConsoleCommand) = CompletableFuture.completedFuture(
            org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "zm open admin_settings_menu ${player.name}"),
        )
    }

    companion object {
        private const val PORTAL_BY_OTHER = "arc.portal.teleport.by.other"
        private const val PORTAL_FOR_OTHER = "arc.portal.teleport.other"
        private const val CHAT_NOTIFY = "arc.chat.notify"
        private const val SHIFT_EDIT = "arc.settings.shiftedit"
        private const val RESOURCE_PACK = "arc.apply-rp"
        private const val TOTEM = "arc.settings.totem"
        private const val STAIRS_SIT = "cmi.command.sit.stairs"
        private const val ADMIN = "tab.group.admin"
        private const val PORTAL_STYLE_META = "arc-portal-style"
        private val PORTAL_STYLES = listOf("legacy", "origin", "astral", "chaos", "solar", "void")
        private fun modeNode(prefix: String, mode: Int) = if (mode == 1) prefix else "$prefix$mode"
        fun modeLabelKey(prefix: String, mode: Int): String? = when (prefix) {
            "tab.scoreboard", "tab.tablist" -> "legacy-settings-${if (prefix == "tab.scoreboard") "scoreboard" else "tablist"}-mode-$mode"
            else -> null
        }
    }
}
