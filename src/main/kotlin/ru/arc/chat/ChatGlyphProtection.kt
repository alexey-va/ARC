package ru.arc.chat

import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.redis.RedisOperations
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info

/** Owns local ItemsAdder metadata and the spawn catalog used by nodes without ItemsAdder. */
internal class ChatGlyphProtection(
    private val plugin: JavaPlugin,
    private val serverName: String,
    redis: RedisOperations?,
    config: ChatModeConfig,
) : AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val network = redis?.let(::ChatGlyphNetworkCatalog)
    private val hasItemsAdder = plugin.server.pluginManager.isPluginEnabled("ItemsAdder")
    private var registry: ChatGlyphRegistry? = null
    private var loadListener: Listener? = null
    @Volatile private var localDefinitions: List<ChatGlyphDefinition> = emptyList()
    @Volatile private var localPolicy: ChatGlyphPolicy? = null
    @Volatile private var closed = false
    private var networkDefinitions: List<ChatGlyphDefinition>? = null
    private var networkPolicy: ChatGlyphPolicy? = null
    val guard = ItemsAdderChatGuard(::currentPolicy, config)

    fun start() {
        if (hasItemsAdder) {
            registry = ItemsAdderGlyphRegistry()
            loadListener = ItemsAdderGlyphLoadListener { tasks.runLater(1L, ::refreshLocal) }.also {
                plugin.server.pluginManager.registerEvents(it, plugin)
            }
            refreshLocal()
        }
        exchange()
        tasks.runTimer(300L, 300L, ::exchange)
    }

    private fun refreshLocal() {
        if (closed) return
        try {
            localDefinitions = registry?.snapshot().orEmpty()
            localPolicy = localDefinitions.takeIf { it.isNotEmpty() }?.let(::ChatGlyphPolicy)
            info("Chat glyph protection: source=ItemsAdder ready={} glyphs={}", localPolicy != null, localDefinitions.size)
        } catch (failure: Exception) {
            localDefinitions = emptyList()
            localPolicy = null
            error("Chat glyph catalog unavailable; player text remains blocked", failure)
        }
        exchange()
    }

    private fun exchange() {
        if (closed) return
        val catalog = network ?: return
        if (serverName == "spawn") {
            catalog.publish(localDefinitions).exceptionally { failure ->
                if (!closed) error("Could not publish chat glyph catalog", failure)
                null
            }
        } else if (!hasItemsAdder) {
            catalog.refresh().thenRun {
                if (!closed) currentPolicy()
            }.exceptionally { failure ->
                if (!closed) error("Could not refresh chat glyph catalog", failure)
                null
            }
        }
    }

    @Synchronized
    private fun currentPolicy(): ChatGlyphPolicy? {
        if (closed) return null
        if (hasItemsAdder) return localPolicy
        val definitions = network?.current()
        if (definitions != networkDefinitions) {
            networkDefinitions = definitions
            networkPolicy = definitions?.let(::ChatGlyphPolicy)
            info("Chat glyph protection: source=spawn ready={} glyphs={}", networkPolicy != null, definitions?.size ?: 0)
        }
        return networkPolicy
    }

    override fun close() {
        closed = true
        tasks.close()
        guard.close()
        loadListener?.let(HandlerList::unregisterAll)
        network?.close()
        localDefinitions = emptyList()
        localPolicy = null
        synchronized(this) { networkDefinitions = null; networkPolicy = null }
    }
}

// Kept separate: nodes without ItemsAdder must never register methods whose
// event parameter would require loading an absent ItemsAdder API class.
private class ItemsAdderGlyphLoadListener(private val loaded: () -> Unit) : Listener {
    @EventHandler
    fun onLoaded(@Suppress("UNUSED_PARAMETER") event: ItemsAdderLoadDataEvent) = loaded()
}
