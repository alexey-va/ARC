package ru.arc.metrics.telemetry

import io.papermc.paper.event.player.AsyncChatEvent
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.event.NPCLeftClickEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.core.EventScope
import ru.arc.core.eventScope
import ru.arc.product.ProductCommandClassifier
import java.util.UUID

/** Native event snapshots only. Never serializes, touches files/SQL, reads chat, command arguments or item NBT. */
internal class PlayerTelemetryListener : AutoCloseable {
    private var scope: EventScope? = null
    private val packs = mutableMapOf<Pair<UUID, UUID>, Long>()

    fun start() {
        scope = eventScope().also { events ->
            events.on<PlayerJoinEvent>(EventPriority.MONITOR) { PlayerTelemetryModule.join(it.player) }
            events.on<PlayerQuitEvent>(EventPriority.MONITOR) {
                packs.keys.removeIf { key -> key.first == it.player.uniqueId }
                PlayerTelemetryModule.leave(it.player)
            }
            events.on<PlayerKickEvent>(EventPriority.MONITOR, ignoreCancelled = true) {
                PlayerTelemetryModule.recordNative(it.player.uniqueId, "player", "connection.kick", attributes = mapOf("cause" to it.cause.name))
            }
            events.on<PlayerChangedWorldEvent>(EventPriority.MONITOR) {
                PlayerTelemetryModule.relocate(it.player, it.player.location)
                PlayerTelemetryModule.recordNative(it.player.uniqueId, "player", "world.enter", it.player.world.name,
                    attributes = mapOf("fromWorld" to it.from.name))
            }
            events.on<PlayerTeleportEvent>(EventPriority.MONITOR, ignoreCancelled = false) {
                if (!it.isCancelled) PlayerTelemetryModule.relocate(it.player, it.to)
                PlayerTelemetryModule.recordNative(it.player.uniqueId, "player", "teleport", it.to.world.name,
                    attributes = mapOf("cause" to it.cause.name, "cancelled" to it.isCancelled.toString(),
                        "fromWorld" to it.from.world.name, "fromX" to it.from.blockX.toString(), "fromY" to it.from.blockY.toString(),
                        "fromZ" to it.from.blockZ.toString(), "toX" to it.to.blockX.toString(), "toY" to it.to.blockY.toString(), "toZ" to it.to.blockZ.toString()))
            }
            events.on<PlayerCommandPreprocessEvent>(EventPriority.MONITOR, ignoreCancelled = false) {
                ProductCommandClassifier.root(it.message)?.let { root ->
                    PlayerTelemetryModule.action(it.player, "command", root, mapOf("cancelled" to it.isCancelled.toString()))
                }
            }
            events.on<BlockBreakEvent>(EventPriority.MONITOR, ignoreCancelled = false) {
                PlayerTelemetryModule.action(it.player, "block.break", it.block.type.key.toString(),
                    mapOf("cancelled" to it.isCancelled.toString(), "targetX" to it.block.x.toString(), "targetY" to it.block.y.toString(), "targetZ" to it.block.z.toString()))
            }
            events.on<BlockPlaceEvent>(EventPriority.MONITOR, ignoreCancelled = false) {
                PlayerTelemetryModule.action(it.player, "block.place", it.block.type.key.toString(), mapOf("cancelled" to it.isCancelled.toString()))
            }
            events.on<PlayerInteractEvent>(EventPriority.MONITOR, ignoreCancelled = false) {
                if (it.hand == EquipmentSlot.HAND) PlayerTelemetryModule.action(it.player, "interact", it.clickedBlock?.type?.key?.toString(),
                    mapOf("action" to it.action.name, "useBlock" to it.useInteractedBlock().name, "useItem" to it.useItemInHand().name))
            }
            events.on<CraftItemEvent>(EventPriority.MONITOR, ignoreCancelled = false) { event ->
                (event.whoClicked as? Player)?.let { PlayerTelemetryModule.action(it, "craft.attempt", event.recipe.result.type.key.toString(),
                    mapOf("cancelled" to event.isCancelled.toString(), "shift" to event.isShiftClick.toString())) }
            }
            events.on<EntityDeathEvent>(EventPriority.MONITOR) {
                if (it.entity !is Player) it.entity.killer?.let { player ->
                    PlayerTelemetryModule.action(player, "combat.kill", it.entity.type.key.toString())
                }
            }
            events.on<PlayerDeathEvent>(EventPriority.MONITOR) {
                PlayerTelemetryModule.recordNative(it.player.uniqueId, "player", "death",
                    attributes = mapOf("cause" to (it.player.lastDamageCause?.cause?.name ?: "UNKNOWN")))
            }
            events.on<PlayerAdvancementDoneEvent>(EventPriority.MONITOR) {
                PlayerTelemetryModule.recordNative(it.player.uniqueId, "player", "advancement", it.advancement.key.toString())
            }
            events.on<PlayerItemConsumeEvent>(EventPriority.MONITOR, ignoreCancelled = true) {
                PlayerTelemetryModule.action(it.player, "item.consume", it.item.type.key.toString())
            }
            events.on<PlayerFishEvent>(EventPriority.MONITOR, ignoreCancelled = false) {
                PlayerTelemetryModule.action(it.player, "fishing", it.state.name.lowercase(), mapOf("cancelled" to it.isCancelled.toString()))
            }
            events.on<AsyncChatEvent>(EventPriority.MONITOR, ignoreCancelled = true) {
                // The message, recipient list and signed chat payload are deliberately never accessed.
                PlayerTelemetryModule.recordNative(it.player.uniqueId, "player", "chat.sent")
            }
            events.on<InventoryOpenEvent>(EventPriority.MONITOR, ignoreCancelled = false) { event ->
                (event.player as? Player)?.let { PlayerTelemetryModule.recordNative(it.uniqueId, "inventory", "inventory.open", event.inventory.type.name.lowercase(),
                    attributes = mapOf("cancelled" to event.isCancelled.toString(), "holderClass" to (event.inventory.holder?.javaClass?.name?.replace('$', '.') ?: "none"))) }
            }
            events.on<InventoryClickEvent>(EventPriority.MONITOR, ignoreCancelled = false) { event ->
                (event.whoClicked as? Player)?.let { PlayerTelemetryModule.action(it, "inventory.click", event.inventory.type.name.lowercase(),
                    mapOf("rawSlot" to event.rawSlot.toString(), "clickType" to event.click.name,
                        "action" to event.action.name, "cancelled" to event.isCancelled.toString(),
                        "material" to (event.currentItem?.type?.key?.toString() ?: "minecraft:air"))) }
            }
            events.on<InventoryCloseEvent>(EventPriority.MONITOR) { event ->
                (event.player as? Player)?.let { PlayerTelemetryModule.recordNative(it.uniqueId, "inventory", "inventory.close", event.inventory.type.name.lowercase(),
                    attributes = mapOf("reason" to event.reason.name)) }
            }
            events.on<PlayerResourcePackStatusEvent>(EventPriority.MONITOR) {
                val now = System.currentTimeMillis()
                val key = it.player.uniqueId to it.getID()
                if (it.status == PlayerResourcePackStatusEvent.Status.ACCEPTED && packs.size < 4_096) packs.putIfAbsent(key, now)
                val attributes = mutableMapOf("status" to it.status.name, "timingBasis" to "accepted_to_status")
                packs[key]?.let { started -> attributes["elapsedMs"] = (now - started).coerceAtLeast(0).toString() }
                PlayerTelemetryModule.recordNative(it.player.uniqueId, "paper", "resource_pack.status", it.getID().toString(), attributes = attributes)
                if (it.status.name !in setOf("ACCEPTED", "DOWNLOADED")) packs.remove(key)
            }
            if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
                events.on<NPCRightClickEvent>(EventPriority.MONITOR, ignoreCancelled = false) {
                    PlayerTelemetryModule.action(it.clicker, "npc.click", it.npc.id.toString(),
                        mapOf("button" to "right", "cancelled" to it.isCancelled.toString()))
                }
                events.on<NPCLeftClickEvent>(EventPriority.MONITOR, ignoreCancelled = false) {
                    PlayerTelemetryModule.action(it.clicker, "npc.click", it.npc.id.toString(),
                        mapOf("button" to "left", "cancelled" to it.isCancelled.toString()))
                }
            }
            if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) PlayerTelemetryFurniture.register(events)
        }
    }

    override fun close() { scope?.unregisterAll(); scope = null; packs.clear() }
}
