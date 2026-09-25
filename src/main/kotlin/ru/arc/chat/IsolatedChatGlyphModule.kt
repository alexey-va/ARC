package ru.arc.chat

import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.config.ArcRedisConfig
import ru.arc.core.PluginModule

/**
 * Minimal chat-glyph protection for opted-in isolated runtime profiles.
 *
 * This owns only the glyph guard and its two event registrations. Its server
 * identity is read when the module starts, so changing Redis identity still
 * requires a restart; ChatModeConfig reads remain hot-reloadable through the
 * canonical ConfigManager instance.
 */
object IsolatedChatGlyphModule : PluginModule {
    override val name = "ChatGlyphProtection"
    override val priority = 15

    private var protection: ChatGlyphProtection? = null
    private var chatListener: Listener? = null
    private val hotConfigManager: ChatModeConfig
        get() = ChatModeConfig.load(ARC.instance.dataPath)

    override fun init() {
        shutdown()

        val plugin = ARC.instance
        val activeProtection = ChatGlyphProtection(
            plugin = plugin,
            serverName = ArcRedisConfig.get().serverName,
            redis = ARC.redisManager,
            config = hotConfigManager,
        )
        protection = activeProtection

        try {
            plugin.server.pluginManager.registerEvents(activeProtection.guard, plugin)
            val listener = IsolatedChatGlyphChatListener(activeProtection.guard)
            chatListener = listener
            plugin.server.pluginManager.registerEvents(listener, plugin)
            activeProtection.start()
        } catch (failure: Throwable) {
            runCatching(::shutdown).exceptionOrNull()?.let { cleanupFailure ->
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    /** The identity captured by [init] is restart-bound; config values reload in place. */
    override fun reload() = Unit

    override fun shutdown() {
        val listener = chatListener
        chatListener = null
        val activeProtection = protection
        protection = null

        var cleanupFailure: Throwable? = null
        fun attemptCleanup(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure
                else if (cleanupFailure !== failure) cleanupFailure.addSuppressed(failure)
            }
        }

        attemptCleanup { listener?.let(HandlerList::unregisterAll) }
        attemptCleanup { activeProtection?.let { HandlerList.unregisterAll(it.guard) } }
        attemptCleanup { activeProtection?.close() }
        cleanupFailure?.let { throw it }
    }
}

private class IsolatedChatGlyphChatListener(
    private val guard: ItemsAdderChatGuard,
) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        guard.rejectChat(event)
    }
}
