package ru.arc.metrics

import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.delayed
import java.io.File
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap

/** zMenu 1.1.1.8 InventoryListener observes actual opens; its onButtonClick is BEFORE requirements.
 * Physical button presses are therefore ATTEMPT, never fabricated accepted clicks or gameplay results.
 * No listener changes cancellation, requirements, button handlers or player items.
 */
internal class ProductZMenuAdapter(private val plugin: Plugin, private val observer: ProductUiObservation) : AutoCloseable {
    private data class View(val inventory: Inventory, val slots: Map<Int, String>)
    private val views = mutableMapOf<UUID, View>()
    private val revisions = WeakHashMap<Any, String>()
    private val refreshes = mutableMapOf<UUID, ScheduledTask>()
    var failures: Long = 0; private set
    private var manager: Any? = null
    private var listener: Any? = null

    fun register(): Boolean {
        if (listener != null) return true
        val producer = plugin.server.pluginManager.getPlugin("zMenu")?.takeIf { it.isEnabled } ?: return false
        val type = Class.forName("fr.maxlego08.menu.api.InventoryListener", false, producer.javaClass.classLoader)
        val target = call(producer, "getInventoryManager") ?: return false
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "equals" -> self === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "ARC product UI observer"
                "onInventoryPostOpen" -> { observe(args?.getOrNull(0) as? Player, args?.getOrNull(1)); null }
                "addItem" -> {
                    val engine = args?.getOrNull(0)
                    val player = engine?.let { call(it, "getPlayer") } as? Player
                    if (player != null && views.containsKey(player.uniqueId) && player.uniqueId !in refreshes) {
                        refreshes[player.uniqueId] = delayed(1) {
                            refreshes.remove(player.uniqueId)
                            observe(player, engine)
                        }
                    }
                    false
                }
                else -> null
            }
        }
        target.javaClass.getMethod("registerInventoryListener", type).invoke(target, proxy)
        manager = target
        listener = proxy
        return true
    }

    private fun observe(player: Player?, engine: Any?) {
        runCatching { postOpen(player, engine) }.onFailure {
            failures++
            if (failures == 1L) plugin.logger.warning("zMenu UI observations failed: ${it.javaClass.simpleName}")
        }
    }

    private fun postOpen(player: Player?, engine: Any?) {
        if (player == null || engine == null) return
        val menu = call(engine, "getMenuInventory") ?: return
        val file = call(menu, "getFile") as? File ?: return
        // New menus in the canonical public graph are covered without adding another code allowlist.
        if (file.parentFile.name != "ruscrafting_test") return
        val name = file.nameWithoutExtension.lowercase(Locale.ROOT)
        if (name.startsWith("admin_") || !ProductUiCodec.ID.matches(name)) return
        val inventory = call(engine, "getSpigotInventory") as? Inventory ?: return
        if (player.openInventory.topInventory !== inventory) return
        val rendered = (call(engine, "getItems") as? Map<*, *>)?.keys?.filterIsInstance<Int>()?.toSet() ?: return
        val declared = call(engine, "getButtons") as? Collection<*> ?: return
        val page = (call(engine, "getPage") as? Int) ?: return
        val slots = linkedMapOf<Int, String>()
        for (button in declared.filterNotNull()) {
            if (call(button, "isClickable") != true || call(button, "isPlayerInventory") == true) continue
            val id = (call(button, "getName") as? String)?.lowercase(Locale.ROOT)
                ?.takeIf(ProductUiCodec.ID::matches) ?: continue
            val positions = (call(button, "getSlots") as? Collection<*>)?.filterIsInstance<Int>() ?: continue
            val permanent = call(button, "isPermanent") == true
            for (position in positions) {
                val slot = if (permanent) position else position - (page - 1) * inventory.size
                if (slot in rendered && slot in 0 until inventory.size) slots[slot] = id
            }
        }
        val revision = revisions.getOrPut(menu) {
            if (!file.isFile || file.length() > 524_288) return
            ProductUiCodec.revision(file.readText())
        }
        val surface = "zmenu:$name"
        val buttons = slots.entries.groupBy({ it.value }, { it.key }).mapValues { (id, positions) ->
            ProductUiButton(positions.first(), productUiFeature(surface, id))
        }
        views[player.uniqueId] = View(inventory, slots)
        observer.open(player, inventory, ProductUiView(surface, revision, buttons))
    }

    /** Called once at LOWEST, before native click handlers can replace/close this inventory. */
    fun onRawClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val view = views[player.uniqueId]?.takeIf { it.inventory === event.view.topInventory } ?: return
        val button = view.slots[event.rawSlot] ?: return
        observer.attempt(player, view.inventory, button)
    }

    fun forget(player: UUID, inventory: Inventory? = null) {
        val view = views[player] ?: return
        if (inventory == null || view.inventory === inventory) {
            views.remove(player)
            refreshes.remove(player)?.cancel()
        }
    }

    override fun close() {
        val proxy = listener
        if (proxy != null) manager?.javaClass?.methods?.firstOrNull {
            it.name == "unregisterInventoryListener" && it.parameterCount == 1
        }?.invoke(manager, proxy)
        manager = null
        listener = null
        refreshes.values.forEach { it.cancel() }
        refreshes.clear()
        views.clear()
        revisions.clear()
    }

    private fun call(target: Any, name: String): Any? =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)

}
