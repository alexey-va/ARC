package ru.arc.chat

import org.bukkit.event.HandlerList
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.listeners.ChatListener
import ru.arc.redis.RedisOperations
import ru.arc.xserver.playerlist.PlayerListMessager

/**
 * Adds shared chat rendering and the proxy player list without loading the
 * FULL HooksModule or Lands-backed NetworkModule in the Slimefun profile.
 */
object SlimefunNetworkChatModule : PluginModule {
    override val name = "SlimefunNetworkChat"
    override val priority = 67

    private var chatListener: ChatListener? = null
    private var playerListRedis: RedisOperations? = null
    private var playerListMessager: PlayerListMessager? = null

    override fun init() {
        shutdown()

        val plugin = ARC.instance
        val chatConfig = ChatModeConfig.load(plugin.dataPath)
        val listener = ChatListener(
            npcMessageHandler = { _, _ -> },
            modeProvider = ChatModeService::getMode,
            titleInputProvider = { false },
            messageColorVariationProvider = { chatConfig.messageColorVariation },
            rejectGlyphs = { it.isCancelled },
        )
        chatListener = listener

        try {
            plugin.server.pluginManager.registerEvents(listener, plugin)

            ARC.redisManager?.let { redis ->
                val messager = PlayerListMessager(PLAYER_LIST_CHANNEL)
                redis.registerChannelUnique(PLAYER_LIST_CHANNEL, messager)
                playerListRedis = redis
                playerListMessager = messager
            }
        } catch (failure: Throwable) {
            runCatching(::shutdown).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun reload() = init()

    override fun shutdown() {
        chatListener?.let(HandlerList::unregisterAll)
        chatListener = null

        val redis = playerListRedis
        val messager = playerListMessager
        playerListRedis = null
        playerListMessager = null
        if (redis != null && messager != null) {
            redis.unregisterChannel(PLAYER_LIST_CHANNEL, messager)
        }
    }

    private const val PLAYER_LIST_CHANNEL = "arc.proxy_player_list"
}
