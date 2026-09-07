package ru.arc.cleanup

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockDispenseArmorEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.Repairable
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.EventBus
import ru.arc.core.EventScope
import java.util.UUID

/** No entity index or scheduled sweeper: only proven death drops receive native age. */
internal class MobEquipmentCleanup(
    bus: EventBus,
    private var settings: EntityCleanupSettings,
    private var nativeLifetime: (org.bukkit.World, Material) -> Int?,
    private val currentTick: () -> Int,
) : AutoCloseable {
    private val events = EventScope(bus)
    private val session = UUID.randomUUID().toString()
    private val sourceKey = NamespacedKey("arc", "cleanup_source")
    private val dropKey = NamespacedKey("arc", "cleanup_drop")
    private var revision = 0L
    var agedItems: Long = 0
        private set
    var unknownLifetimeItems: Long = 0
        private set

    fun start() {
        check(events.count() == 0) { "Cleanup listeners already started" }
        events.on<CreatureSpawnEvent>(EventPriority.MONITOR, true) { event ->
            val rule = settings.equipment
            if (active() && event.entity is Mob && event.entityType in rule.entityTypes &&
                event.spawnReason in rule.spawnReasons && rule.includesWorld(event.location.world.name)
            ) {
                event.entity.persistentDataContainer.set(
                    sourceKey, PersistentDataType.STRING, "$session:${event.spawnReason.name}",
                )
            }
        }
        // Revoking proof protects the whole mob, including gear acquired before later reloads.
        events.on<EntityPickupItemEvent>(EventPriority.MONITOR, true) { revoke(it.entity) }
        events.on<BlockDispenseArmorEvent>(EventPriority.MONITOR, true) { revoke(it.targetEntity) }
        events.on<PlayerInteractEntityEvent>(EventPriority.MONITOR, true) { revoke(it.rightClicked) }
        events.on<EntityDeathEvent>(EventPriority.HIGHEST, true, ::onDeath)
        events.on<EntityDeathEvent>(EventPriority.MONITOR) { event ->
            if (event.isCancelled) event.drops.forEach(::stripTicket)
        }
        events.on<ItemSpawnEvent>(EventPriority.HIGHEST, false, ::onItemSpawn)
    }

    fun reload(next: EntityCleanupSettings, lifetime: (org.bukkit.World, Material) -> Int?) {
        revision++
        settings = next
        nativeLifetime = lifetime
    }

    private fun active() = settings.enabled && settings.equipment.enabled

    private fun revoke(entity: Entity) {
        if (entity is Mob) entity.persistentDataContainer.remove(sourceKey)
    }

    private fun onDeath(event: EntityDeathEvent) {
        if (!active()) return
        val mob = event.entity as? Mob ?: return
        val rule = settings.equipment
        if (mob.type !in rule.entityTypes || !rule.includesWorld(mob.world.name)) return
        val source = mob.persistentDataContainer.get(sourceKey, PersistentDataType.STRING) ?: return
        if (rule.spawnReasons.none { source == "$session:${it.name}" }) return
        if (rule.protectNamedMobs && mob.customName() != null) return
        if (rule.protectForeignMobPdc && mob.persistentDataContainer.keys.any { it != sourceKey }) return
        if (rule.protectedMetadata.any(mob::hasMetadata)) return
        if (rule.protectedScoreboardTags.any { it in mob.scoreboardTags }) return
        val ticket = "$session:$revision:${currentTick()}"
        for (stack in event.drops) {
            if (!ordinaryEquipment(stack, rule)) continue
            // Mutate the existing stack, preserving Paper DefaultDrop's native spawn consumer.
            val meta = stack.itemMeta
            meta.persistentDataContainer.set(dropKey, PersistentDataType.STRING, ticket)
            stack.itemMeta = meta
        }
    }

    private fun stripTicket(stack: ItemStack): String? {
        if (!stack.hasItemMeta()) return null
        val meta = stack.itemMeta
        val ticket = meta.persistentDataContainer.get(dropKey, PersistentDataType.STRING) ?: return null
        meta.persistentDataContainer.remove(dropKey)
        stack.itemMeta = meta
        return ticket
    }

    private fun onItemSpawn(event: ItemSpawnEvent) {
        val item = event.entity
        val stack = item.itemStack
        val ticket = stripTicket(stack) ?: return
        // A ticket never survives on a physical item, even after cancellation or stale provenance.
        item.itemStack = stack
        if (event.isCancelled || !active() || ticket != "$session:$revision:${currentTick()}") return
        val rule = settings.equipment
        if (!rule.includesWorld(item.world.name) || !ordinaryEquipment(stack, rule)) return
        if (!item.willAge() || item.isUnlimitedLifetime || item.owner != null || item.thrower != null) return
        val nativeTicks = nativeLifetime(item.world, stack.type)
        if (nativeTicks == null || nativeTicks <= 0) {
            unknownLifetimeItems++
            return
        }
        val age = nativeTicks - rule.lifetime(stack.type)
        // Never lengthen a shorter native lifetime or move an already aged item backwards.
        if (age <= item.ticksLived || age < 1) return
        item.ticksLived = age
        agedItems++
    }

    override fun close() = events.unregisterAll()

    companion object {
        internal fun ordinaryEquipment(stack: ItemStack, rule: MobEquipmentRule): Boolean {
            if (stack.type !in rule.materials || stack.amount != 1) return false
            val normalized = stack.clone()
            val meta = normalized.itemMeta
            if (meta is Damageable) meta.damage = 0
            if (meta is Repairable) meta.repairCost = 0
            if (!rule.protectEnchanted) meta.enchants.keys.toList().forEach(meta::removeEnchant)
            normalized.itemMeta = meta
            // This also protects future/custom components, lore, PDC, names, models and attributes.
            return normalized.isSimilar(ItemStack(stack.type))
        }
    }
}
