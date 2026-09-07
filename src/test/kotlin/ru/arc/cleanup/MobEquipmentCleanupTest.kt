package ru.arc.cleanup

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.entity.Zombie
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.TestEventBus
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class MobEquipmentCleanupTest : FreeSpec({
    "native death handoff ages only ordinary gear and strips its temporary ticket" {
        fixture { f ->
            val mob = f.mob()
            val ordinary = ItemStack(Material.IRON_SWORD).also { stack ->
                val meta = stack.itemMeta as Damageable
                meta.damage = 170
                stack.itemMeta = meta
            }
            val named = ItemStack(Material.IRON_SWORD).also { stack ->
                val meta = stack.itemMeta
                meta.displayName(Component.text("Player sword"))
                stack.itemMeta = meta
            }
            val custom = ItemStack(Material.IRON_SWORD).also { stack ->
                val meta = stack.itemMeta
                meta.persistentDataContainer.set(NamespacedKey("test", "item"), PersistentDataType.BYTE, 1)
                stack.itemMeta = meta
            }
            f.death(mob, ordinary, named, custom, ItemStack(Material.ROTTEN_FLESH))
            val aged = f.spawn(ordinary)
            aged.ticksLived shouldBe 4200
            aged.itemStack shouldBe ItemStack(Material.IRON_SWORD).also { stack ->
                val meta = stack.itemMeta as Damageable
                meta.damage = 170
                stack.itemMeta = meta
            }
            f.spawn(named).ticksLived shouldBe 0
            f.spawn(custom).ticksLived shouldBe 0
            f.spawn(ItemStack(Material.IRON_SWORD)).ticksLived shouldBe 0
            f.cleanup.agedItems shouldBe 1
        }
    }

    "unknown mobs, pickup history and a restarted listener never acquire proof retroactively" {
        fixture { f ->
            val old = f.mob(known = false)
            val oldDrop = ItemStack(Material.IRON_SWORD)
            f.death(old, oldDrop)
            f.spawn(oldDrop).ticksLived shouldBe 0

            val picker = f.mob()
            f.bus.fire(EntityPickupItemEvent(picker, f.item(ItemStack(Material.IRON_SWORD)), 0))
            val pickedDrop = ItemStack(Material.IRON_SWORD)
            f.death(picker, pickedDrop)
            f.spawn(pickedDrop).ticksLived shouldBe 0

            val prior = f.mob()
            f.cleanup.close()
            f.bus.handlerCount() shouldBe 0
            f.cleanup = MobEquipmentCleanup(f.bus, f.settings, { _, _ -> 6000 }, { 100 }).also { it.start() }
            val priorDrop = ItemStack(Material.IRON_SWORD)
            f.death(prior, priorDrop)
            f.spawn(priorDrop).ticksLived shouldBe 0
        }
    }

    "reload updates the TTL without losing pickup protection or adding listeners" {
        fixture { f ->
            val trusted = f.mob()
            val picker = f.mob()
            f.bus.fire(EntityPickupItemEvent(picker, f.item(ItemStack(Material.BOW)), 0))
            val count = f.bus.handlerCount()
            f.cleanup.reload(f.settings.copy(equipment = f.settings.equipment.copy(lifetimeTicks = 400)), { _, _ -> 1200 })
            f.bus.handlerCount() shouldBe count
            val drop = ItemStack(Material.IRON_SWORD)
            f.death(trusted, drop)
            f.spawn(drop).ticksLived shouldBe 800
            val protected = ItemStack(Material.IRON_SWORD)
            f.death(picker, protected)
            f.spawn(protected).ticksLived shouldBe 0
        }
    }

    "cancelled death removes tickets and cancelled pickup does not revoke provenance" {
        fixture { f ->
            val mob = f.mob()
            val pickup = EntityPickupItemEvent(mob, f.item(ItemStack(Material.BOW)), 0)
            pickup.isCancelled = true
            f.bus.fire(pickup)
            val drop = ItemStack(Material.IRON_SWORD)
            f.death(mob, drop)
            f.spawn(drop).ticksLived shouldBe 4200
            f.bus.register(EntityDeathEvent::class, EventPriority.HIGHEST) { it.isCancelled = true }
            val cancelled = ItemStack(Material.IRON_SWORD)
            f.death(mob, cancelled)
            cancelled.itemMeta.persistentDataContainer.keys shouldBe emptySet()
            f.spawn(cancelled).ticksLived shouldBe 0
        }
    }

    "world exclusions and unknown or shorter native lifetimes leave drops unchanged" {
        fixture { f ->
            val mob = f.mob()
            f.cleanup.reload(f.settings, { _, _ -> null })
            val unknown = ItemStack(Material.IRON_SWORD)
            f.death(mob, unknown)
            f.spawn(unknown).ticksLived shouldBe 0
            f.cleanup.unknownLifetimeItems shouldBe 1
            f.cleanup.reload(f.settings, { _, _ -> 600 })
            val short = ItemStack(Material.IRON_SWORD)
            f.death(mob, short)
            f.spawn(short).ticksLived shouldBe 0
            f.cleanup.reload(f.settings.copy(equipment = f.settings.equipment.copy(excludedWorlds = setOf("farm"))), { _, _ -> 6000 })
            val excluded = ItemStack(Material.IRON_SWORD)
            f.death(mob, excluded)
            excluded.itemMeta.persistentDataContainer.keys shouldBe emptySet()
        }
    }
})

private fun fixture(test: (CleanupFixture) -> Unit) {
    val directory = Files.createTempDirectory("arc-cleanup-test")
    try {
        MockBukkitTestRuntime.open().use { paper ->
            val defaults = EntityCleanupConfig.load(directory).settings
            CleanupFixture(paper, defaults.copy(enabled = true)).use(test)
        }
    } finally {
        ru.arc.config.ConfigManager.clear()
        directory.toFile().deleteRecursively()
    }
}

private class CleanupFixture(paper: MockBukkitTestRuntime, val settings: EntityCleanupSettings) : AutoCloseable {
    val world = paper.addSimpleWorld("farm")
    val bus = TestEventBus()
    var cleanup = MobEquipmentCleanup(bus, settings, { _, _ -> 6000 }, { 100 }).also { it.start() }

    fun mob(known: Boolean = true): Zombie = world.spawn(world.spawnLocation, Zombie::class.java).also {
        if (known) bus.fire(CreatureSpawnEvent(it, SpawnReason.NATURAL))
    }

    fun death(mob: Zombie, vararg drops: ItemStack) {
        bus.fire(EntityDeathEvent(mob, mockk(), drops.toMutableList(), 0))
    }

    fun item(original: ItemStack): Item {
        var stack = original.clone()
        var age = 0
        return mockk<Item>(relaxed = true).also { item ->
            every { item.world } returns world
            every { item.location } returns world.spawnLocation
            every { item.itemStack } answers { stack.clone() }
            every { item.itemStack = any() } answers { stack = firstArg<ItemStack>().clone() }
            every { item.ticksLived } answers { age }
            every { item.ticksLived = any() } answers { age = firstArg() }
            every { item.willAge() } returns true
            every { item.isUnlimitedLifetime } returns false
            every { item.owner } returns null
            every { item.thrower } returns null
        }
    }

    fun spawn(stack: ItemStack): Item = item(stack).also { bus.fire(ItemSpawnEvent(it)) }

    override fun close() = cleanup.close()
}
