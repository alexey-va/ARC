package ru.arc.metrics

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import ru.arc.metrics.core.MetricPoint
import java.util.Locale
import java.util.UUID
import java.util.jar.JarFile
import java.io.File

interface ProductUiObservation {
    fun open(player: Player, inventory: Inventory, view: ProductUiView)
    fun click(player: Player, inventory: Inventory, buttonId: String, accepted: Boolean)
    fun attempt(player: Player, inventory: Inventory, buttonId: String)
    fun close(player: Player, inventory: Inventory)
}

/** One ARC sink for each actual shared event class (plugins shade independent copies of arc-core). */
internal class ProductUiListener(private val plugin: Plugin, private val product: ProductInterestTelemetry) : Listener, AutoCloseable, ProductUiObservation {
    private data class NativeView(val id: String, val inventory: Inventory, val view: ProductUiView)
    private val nativeViews = mutableMapOf<UUID, NativeView>()
    private val classes = mutableSetOf<Class<out Event>>()
    private val coverage = linkedMapOf<String, Boolean>()
    private val tracker = ProductUiTracker { player, kind, view, button, duration, at ->
        product.ui(player, kind, view, button, duration, at)
    }
    private val zMenu = ProductZMenuAdapter(plugin, this)

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.pluginManager.plugins.forEach(::attach)
        coverage["zmenu"] = runCatching { zMenu.register() }.getOrDefault(false)
    }

    private fun attach(producer: Plugin) {
        if (!CORE_PRODUCER.matches(producer.name) || !producer.isEnabled) return
        val type = runCatching {
            Class.forName("ru.arc.paper.menu.PaperMenuObservationEvent", false, producer.javaClass.classLoader).asSubclass(Event::class.java)
        }.getOrNull()
        coverage[producer.name.lowercase(Locale.ROOT)] = type != null && runCatching {
            val artifact = File(producer.javaClass.protectionDomain.codeSource.location.toURI())
            JarFile(artifact).use { it.getJarEntry("ru/arc/paper/menu/PaperMenuObservationEvent.class") != null }
        }.getOrDefault(false)
        if (type == null || !classes.add(type)) return
        val payload = type.getMethod("getPayload")
        plugin.server.pluginManager.registerEvent(type, this, EventPriority.MONITOR,
            EventExecutor { _, event ->
                @Suppress("UNCHECKED_CAST")
                val data = payload.invoke(event) as? Map<String, Any> ?: return@EventExecutor
                receive(data)
            }, plugin)
    }

    internal fun receive(data: Map<String, Any>, now: Long = System.currentTimeMillis()) {
        if (data["protocol"] != 1) return
        val owner = (data["owner"] as? String)?.lowercase(Locale.ROOT) ?: return
        if (!CORE_PRODUCER.matches(owner)) return
        val menu = data["surface"] as? String ?: return
        val surface = "$owner:$menu"
        val revision = data["revision"] as? String ?: return
        val player = data["playerId"] as? String ?: return
        val visit = data["visitId"] as? String ?: return
        if (!ProductUiCodec.ID.matches(surface) || !ProductUiCodec.REVISION.matches(revision) ||
            runCatching { UUID.fromString(player); UUID.fromString(visit) }.isFailure) return
        val rawButtons = data["buttons"] as? Map<*, *> ?: return
        if (rawButtons.size > 128) return
        val buttons = rawButtons.entries.mapNotNull { (rawKey, rawSlot) ->
            val key = (rawKey as? String)?.takeIf(ProductUiCodec.ID::matches) ?: return@mapNotNull null
            val slot = (rawSlot as? Number)?.toInt()?.takeIf { it in 0..127 } ?: return@mapNotNull null
            key to ProductUiButton(slot, productUiFeature(surface, key))
        }.toMap()
        coverage[owner] = true
        val view = ProductUiView(surface, revision, buttons)
        when (data["phase"]) {
            "open" -> tracker.open(player, visit, view, now)
            "render" -> tracker.render(player, visit, view, now)
            "click", "blocked" -> (data["button"] as? String)?.let {
                tracker.click(player, visit, view, it, data["phase"] == "click", now)
            }
            "close", "censored" -> tracker.close(player, visit, now,
                censored = data["phase"] == "censored" || data["reason"] != "user")
        }
    }

    @EventHandler fun onPluginEnable(event: PluginEnableEvent) {
        attach(event.plugin)
        if (event.plugin.name == "zMenu") coverage["zmenu"] = runCatching { zMenu.register() }.getOrDefault(false)
    }
    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        tracker.close(event.player.uniqueId.toString(), null, System.currentTimeMillis(), censored = true)
        nativeViews.remove(event.player.uniqueId)
        zMenu.forget(event.player.uniqueId)
    }
    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) = zMenu.onRawClick(event)
    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        close(player, event.inventory, event.reason != InventoryCloseEvent.Reason.PLAYER)
        zMenu.forget(player.uniqueId, event.inventory)
    }

    override fun open(player: Player, inventory: Inventory, view: ProductUiView) {
        val previous = nativeViews[player.uniqueId]
        val id = previous?.takeIf { it.inventory === inventory }?.id ?: UUID.randomUUID().toString()
        nativeViews[player.uniqueId] = NativeView(id, inventory, view)
        tracker.open(player.uniqueId.toString(), id, view, System.currentTimeMillis())
    }
    override fun click(player: Player, inventory: Inventory, buttonId: String, accepted: Boolean) {
        val current = nativeViews[player.uniqueId]?.takeIf { it.inventory === inventory } ?: return
        tracker.click(player.uniqueId.toString(), current.id, current.view, buttonId, accepted, System.currentTimeMillis())
    }
    override fun attempt(player: Player, inventory: Inventory, buttonId: String) {
        val current = nativeViews[player.uniqueId]?.takeIf { it.inventory === inventory } ?: return
        tracker.attempt(player.uniqueId.toString(), current.id, current.view, buttonId, System.currentTimeMillis())
    }
    override fun close(player: Player, inventory: Inventory) = close(player, inventory, false)
    private fun close(player: Player, inventory: Inventory, censored: Boolean) {
        val current = nativeViews[player.uniqueId]?.takeIf { it.inventory === inventory } ?: return
        tracker.close(player.uniqueId.toString(), current.id, System.currentTimeMillis(), censored = censored)
        nativeViews.remove(player.uniqueId)
    }
    fun snapshot(): List<MetricPoint> = coverage.map { (producer, ready) ->
        MetricPoint("arc_product_ui_producer_ready", "Installed UI producer observation hook readiness",
            if (ready && (producer != "zmenu" || zMenu.failures == 0L)) 1.0 else 0.0,
            mapOf("producer" to producer))
    } + MetricPoint("arc_product_ui_adapter_failures", "Native UI adapter observation failures", zMenu.failures.toDouble())
    fun coverage(): Map<String, Any> = mapOf("producers" to coverage.toMap(),
        "dialogDismissal" to "Escape is not reported by Paper; unresolved dialogs are censored, never counted as no-choice closes",
        "zmenuFailures" to zMenu.failures,
        "zmenuAcceptance" to "Physical presses are attempts; zMenu calls its native click observer before requirements. Accepted clicks and results are unsupported",
        "dynamicRows" to "Repeated region items are grouped by semantic region, not by player/item identity")
    override fun close() {
        zMenu.close()
        tracker.shutdown(System.currentTimeMillis())
        nativeViews.clear()
        HandlerList.unregisterAll(this)
    }
    companion object {
        private val CORE_PRODUCER = Regex("arc[a-z0-9_-]{0,32}", RegexOption.IGNORE_CASE)
    }
}
