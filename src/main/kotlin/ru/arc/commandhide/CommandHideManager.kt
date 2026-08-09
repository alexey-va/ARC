package ru.arc.commandhide

import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import ru.arc.ARC
import ru.arc.util.Logging.info

internal object CommandHideManager {
    private var policies: CommandHidePolicyResolver? = null
    private var listener: CommandHideListener? = null

    fun init() {
        check(policies == null && listener == null) { "CommandHideManager is already initialized" }

        val resolver = CommandHidePolicyResolver(CommandHideModuleConfig.load(ARC.instance.dataPath))
        val createdListener = CommandHideListener(resolver)
        policies = resolver
        listener = createdListener
        Bukkit.getPluginManager().registerEvents(createdListener, ARC.instance)
        logLoaded(resolver.currentSnapshot(), "loaded")
    }

    fun reload() {
        val resolver = checkNotNull(policies) { "CommandHideManager is not initialized" }
        val replacement = resolver.reload(CommandHideModuleConfig.load(ARC.instance.dataPath))
        Bukkit.getOnlinePlayers().forEach(PlayerCommandTreeUpdater::update)
        logLoaded(replacement, "reloaded")
    }

    fun shutdown() {
        listener?.let(HandlerList::unregisterAll)
        listener = null
        policies?.clear()
        policies = null
    }

    private fun logLoaded(
        snapshot: CommandHideSnapshot,
        action: String,
    ) {
        info(
            "CommandHide {}: enabled={}, groups={}, resolved-patterns={}",
            action,
            snapshot.enabled,
            snapshot.groupCount,
            snapshot.patternCount,
        )
    }
}

private object PlayerCommandTreeUpdater {
    fun update(player: org.bukkit.entity.Player) {
        player.updateCommands()
    }
}
