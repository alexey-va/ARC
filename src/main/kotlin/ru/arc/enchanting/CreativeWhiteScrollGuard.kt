package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

/** AE's ordinary cursor settlement cannot consume a creative slot-replacement proposal. */
internal class CreativeWhiteScrollGuard(
    private val config: EnchantingConfig,
    private val applyEventType: Class<out Event>,
) : Listener, AutoCloseable {
    private val playerMethod = applyEventType.getMethod("getPlayer")
    private val scrollMethod = applyEventType.getMethod("getAeItemStack")
    private val clicks = mutableMapOf<UUID, InventoryCreativeEvent>()

    init {
        require(Cancellable::class.java.isAssignableFrom(applyEventType))
        require(playerMethod.returnType == Player::class.java)
        require(scrollMethod.returnType == ItemStack::class.java)
    }

    constructor(provider: Plugin, config: EnchantingConfig) : this(config, kotlin.run {
        require(provider.description.version == "9.24.15") { "Unverified AE white-scroll event contract" }
        // ItemApplyEvent is absent from the published compile-time AE 8.7.4 API.
        Class.forName("net.advancedplugins.ae.api.ItemApplyEvent", false, provider.javaClass.classLoader)
            .asSubclass(Event::class.java)
    })

    fun start(owner: Plugin) {
        Bukkit.getPluginManager().registerEvents(this, owner)
        Bukkit.getPluginManager().registerEvent(
            applyEventType, this, EventPriority.HIGHEST, { _, event -> onNativeApply(event) }, owner, true,
        )
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        if (event is InventoryCreativeEvent && isWhiteScroll(event.cursor)) {
            clicks[event.whoClicked.uniqueId] = event
        }
    }

    fun onNativeApply(event: Event) {
        val player = playerMethod.invoke(event) as Player
        val click = clicks[player.uniqueId] ?: return
        val scroll = scrollMethod.invoke(event) as ItemStack
        if (!isWhiteScroll(scroll) || scroll != click.cursor) return
        // Veto only AE's application. Cancelling the creative click would retain the original
        // sword on the server while the client can already be placing its displaced copy.
        // AE returns before any writes; Paper completes the ordinary creative slot replacement.
        (event as Cancellable).isCancelled = true
        player.sendMessage(config.text("messages.white-scroll-survival-only"))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClickFinished(event: InventoryClickEvent) {
        if (clicks[event.whoClicked.uniqueId] === event) clicks.remove(event.whoClicked.uniqueId)
    }

    override fun close() {
        HandlerList.unregisterAll(this)
        clicks.clear()
    }

    private fun isWhiteScroll(item: ItemStack): Boolean =
        item.persistentDataContainer.get(whiteScrollKey, PersistentDataType.STRING) == "true"

    private companion object {
        val whiteScrollKey = NamespacedKey("advancedenchantments", "whitescroll-item")
    }
}
