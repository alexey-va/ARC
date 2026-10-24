package ru.arc.staffspells

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.damage.DamageSource
import org.bukkit.entity.EntityType
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Wolf
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.particle.PaperViewerParticles
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class StaffMobCasterTest : FreeSpec({
    "dungeon caster settings retain their snapshot and reread the next config generation" {
        val raw = StaffTestConfig()
        val config = StaffSpellConfig(raw)
        val original = config.mobCasters
        original shouldBe StaffMobCasterSettings(true, 50.0, 1.0, 16.0, 32, emptySet())

        raw["dungeon-casters.enabled"] = false
        raw["dungeon-casters.spawn-chance-percent"] = 100.0
        raw["dungeon-casters.difficulty"] = 2.0
        raw["dungeon-casters.range-blocks"] = 24.0
        raw["dungeon-casters.max-casters"] = 12
        raw["dungeon-casters.worlds"] = listOf("world", "world_nether", "world")
        raw["dungeon-casters.entity-types"] = listOf("WOLF", "ZOMBIE", "WOLF")

        config.mobCasters shouldBe StaffMobCasterSettings(false, 100.0, 2.0, 24.0, 12,
            setOf("world", "world_nether"), setOf(EntityType.WOLF, EntityType.ZOMBIE))
        original shouldBe StaffMobCasterSettings(true, 50.0, 1.0, 16.0, 32, emptySet())
        (EntityType.WOLF in original.allowedEntityTypes) shouldBe false
    }

    "entity type whitelist accepts native mobs and wildcard but preserves empty and invalid input" {
        val raw = StaffTestConfig(mapOf("dungeon-casters.entity-types" to listOf("WOLF")))
        val config = StaffSpellConfig(raw)
        config.mobCasters.allowedEntityTypes shouldBe setOf(EntityType.WOLF)
        raw["dungeon-casters.entity-types"] = listOf("*")
        val allMobs = config.mobCasters.allowedEntityTypes
        listOf(EntityType.WOLF, EntityType.ZOMBIE, EntityType.SLIME, EntityType.ENDER_DRAGON).forEach {
            (it in allMobs) shouldBe true
        }
        listOf(EntityType.PLAYER, EntityType.ARMOR_STAND, EntityType.ITEM_DISPLAY, EntityType.UNKNOWN).forEach {
            (it in allMobs) shouldBe false
        }
        raw["dungeon-casters.entity-types"] = emptyList<String>()
        config.mobCasters.allowedEntityTypes shouldBe emptySet()
        listOf("WOLFF", "PLAYER", "ITEM_DISPLAY").forEach { invalid ->
            raw["dungeon-casters.entity-types"] = listOf("*", invalid)
            val failure = shouldThrow<IllegalArgumentException> { config.mobCasters }
            failure.message shouldContain "dungeon-casters.entity-types"
            failure.message shouldContain invalid
        }
    }

    "legacy natural mob settings cannot disable or configure dungeon casters" {
        val config = StaffSpellConfig(StaffTestConfig(mapOf(
            "mob-casters.enabled" to false,
            "mob-casters.spawn-chance-percent" to 0.0,
            "mob-casters.difficulty" to 4.0,
            "mob-casters.range-blocks" to 32.0,
            "mob-casters.max-casters" to 1,
            "mob-casters.worlds" to listOf("world"),
        )))
        config.mobCasters shouldBe StaffMobCasterSettings(true, 50.0, 1.0, 16.0, 32, emptySet())
    }

    "dungeon caster limits clamp finite input and reject nonfinite combat values" {
        val raw = StaffTestConfig(mapOf(
            "dungeon-casters.spawn-chance-percent" to -1.0,
            "dungeon-casters.difficulty" to 0.0,
            "dungeon-casters.range-blocks" to 0.0,
            "dungeon-casters.max-casters" to 0,
        ))
        val config = StaffSpellConfig(raw)
        config.mobCasters shouldBe StaffMobCasterSettings(true, 0.0, 0.25, 4.0, 1, emptySet())

        raw["dungeon-casters.spawn-chance-percent"] = 101.0
        raw["dungeon-casters.difficulty"] = 10.0
        raw["dungeon-casters.range-blocks"] = 100.0
        raw["dungeon-casters.max-casters"] = 100
        config.mobCasters shouldBe StaffMobCasterSettings(true, 100.0, 4.0, 32.0, 32, emptySet())

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            raw["dungeon-casters.spawn-chance-percent"] = invalid
            raw["dungeon-casters.difficulty"] = invalid
            raw["dungeon-casters.range-blocks"] = invalid
            withClue("nonfinite input $invalid") {
                config.mobCasters.spawnChancePercent shouldBe 50.0
                config.mobCasters.difficulty shouldBe 1.0
                config.mobCasters.range shouldBe 16.0
            }
        }
    }

    "difficulty keeps a dodge warning while scaling dungeon spell cadence" {
        val easy = StaffSpellConfig(StaffTestConfig(mapOf("dungeon-casters.difficulty" to 0.25))).mobCasters
        val hard = StaffSpellConfig(StaffTestConfig(mapOf("dungeon-casters.difficulty" to 4.0))).mobCasters
        easy.recoveryTicks shouldBe 200
        hard.recoveryTicks shouldBe 50
        easy.telegraphTicks shouldBe 48
        hard.telegraphTicks shouldBe 12
        easy.markTelegraphTicks shouldBe 48
        hard.markTelegraphTicks shouldBe 32
    }

    "dungeon cosmetic staff never resolves as a usable player staff" {
        MockBukkitTestRuntime.open().use {
            val config = StaffSpellConfig(StaffTestConfig())
            listOf(StaffSpell.EMBER, StaffSpell.ICICLE, StaffSpell.LANCE, StaffSpell.MARK).forEach { spell ->
                val item = config.mobItem(spell)
                val pdc = item.itemMeta.persistentDataContainer
                item.type shouldBe Material.BLAZE_ROD
                pdc.get(StaffMobCasters.staffKey, PersistentDataType.STRING) shouldBe spell.id
                pdc.has(StaffSpell.itemKey) shouldBe false
                pdc.has(StaffEliteItems.itemKey) shouldBe false
                pdc.has(StaffSpell.duelOnlyKey) shouldBe false
                StaffSpell.from(item) shouldBe null
                StaffSpell.isDuelOnly(item) shouldBe false
            }
        }
    }

    "natural world spawns and expired dungeon actors cannot enroll" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("world")
            val mob = h.zombie(world, .5, .5)
            val actor = dungeonActor(mob)
            every { actor.isCurrent() } returns false
            StaffMobCasters(h.config, StaffMobCasterSettings(spawnChancePercent = 100.0), h.effects,
                mockk<PaperViewerParticles>(relaxed = true)).use { casters ->
                h.paper.server.pluginManager.registerEvents(casters, h.paper.createSimplePlugin("DungeonCasterTest"))
                casters.start()
                h.paper.callEvent(CreatureSpawnEvent(mob, CreatureSpawnEvent.SpawnReason.NATURAL))
                h.scheduler.tick(2)
                casters.enrollFromDungeon(actor)
                verify(exactly = 0) { h.effects.createMobStaff(any(), any()) }
                mob.equipment.itemInMainHand.type.isAir shouldBe true
            }
        }
    }

    "zero and hundred percent dungeon chance respect capacity and preserve armed boss equipment" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("dungeon-caster-admission")
            listOf(0.0, 100.0).forEachIndexed { index, chance ->
                val mob = h.zombie(world, index * 4.0, .5).apply {
                    customName(Component.text("Native dungeon boss"))
                    setAI(false)
                    equipment.setItemInMainHand(ItemStack(Material.NETHERITE_SWORD))
                    equipment.setItemInOffHand(ItemStack(Material.SHIELD))
                    equipment.itemInMainHandDropChance = .17f
                }
                val mainHand = mob.equipment.itemInMainHand.clone()
                val offHand = mob.equipment.itemInOffHand.clone()
                val cosmetic = mockk<PacketItemDisplay>(relaxed = true)
                every { cosmetic.isValid } returns true
                every { h.effects.createMobStaff(any(), any()) } returns cosmetic
                val actor = dungeonActor(mob)
                val overflow = dungeonActor(h.zombie(world, index * 4.0 + 1, .5))
                StaffMobCasters(h.config, StaffMobCasterSettings(spawnChancePercent = chance, maxCasters = 1),
                    h.effects, mockk<PaperViewerParticles>(relaxed = true)).use { casters ->
                    casters.start()
                    casters.enrollFromDungeon(actor)
                    casters.enrollFromDungeon(actor)
                    casters.enrollFromDungeon(overflow)
                    h.scheduler.tick(2)
                    verify(exactly = if (chance == 0.0) 0 else 1) { h.effects.createMobStaff(any(), any()) }
                    mob.equipment.itemInMainHand shouldBe mainHand
                    mob.equipment.itemInOffHand shouldBe offHand
                    mob.equipment.itemInMainHandDropChance shouldBe .17f
                }
                verify(exactly = if (chance == 0.0) 0 else 1) { cosmetic.remove() }
                mob.equipment.itemInMainHand shouldBe mainHand
                mob.equipment.itemInOffHand shouldBe offHand
                mob.equipment.itemInMainHandDropChance shouldBe .17f
            }
        }
    }

    "default whitelist rejects wolves and an explicit empty whitelist also rejects humanoids" {
        listOf(false, true).forEach { empty ->
            withStaffHarness { h ->
                val world = h.paper.addSimpleWorld("dungeon-caster-whitelist-$empty")
                val wolf = world.spawn(Location(world, .5, 64.0, .5), Wolf::class.java)
                val actors = listOf(dungeonActor(wolf)) + if (empty) listOf(dungeonActor(h.zombie(world, 2.5, .5))) else emptyList()
                val settings = StaffMobCasterSettings(spawnChancePercent = 100.0).let {
                    if (empty) it.copy(allowedEntityTypes = emptySet()) else it
                }
                StaffMobCasters(h.config, settings, h.effects, mockk<PaperViewerParticles>(relaxed = true)).use { casters ->
                    casters.start()
                    actors.forEach(casters::enrollFromDungeon)
                    h.scheduler.tick(22)
                    verify(exactly = 0) { h.effects.createMobStaff(any(), any()) }
                    actors.forEach { actor -> verify(exactly = 0) { actor.currentTarget() } }
                }
            }
        }
    }

    "explicit and wildcard wolves telegraph without staff handles and still consume caster capacity" {
        listOf(listOf("WOLF"), listOf("*")).forEachIndexed { index, types ->
            withStaffHarness { h ->
                val world = h.solidCollisionWorld("dungeon-caster-wolf-$index")
                for (x in -1..1) for (z in 0..6) world.getBlockAt(x, 63, z).type = Material.STONE
                val wolf = world.spawn(Location(world, .5, 64.0, .5), Wolf::class.java)
                val target = h.paper.addPlayer("dungeon-wolf-target").apply { teleport(Location(world, .5, 64.0, 4.5)) }
                val actor = dungeonActor(wolf, target)
                val overflow = dungeonActor(world.spawn(Location(world, 1.5, 64.0, .5), Wolf::class.java), target)
                val scene = UUID.randomUUID()
                every { h.effects.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns scene
                every { h.effects.custom(any(), any(), any(), any(), any(), any(), any()) } returns scene
                every { h.effects.isActive(scene) } returns true
                every { h.effects.hasAudience(scene, target.uniqueId) } returns true
                val settings = StaffSpellConfig(StaffTestConfig(mapOf(
                    "dungeon-casters.entity-types" to types,
                    "dungeon-casters.spawn-chance-percent" to 100.0,
                    "dungeon-casters.max-casters" to 1,
                ))).mobCasters
                StaffMobCasters(h.config, settings, h.effects, mockk<PaperViewerParticles>(relaxed = true)).use { casters ->
                    casters.start()
                    casters.enrollFromDungeon(actor)
                    casters.enrollFromDungeon(actor)
                    casters.enrollFromDungeon(overflow)
                    h.scheduler.tick(22)
                    verify(atLeast = 1) { h.effects.hasAudience(scene, target.uniqueId) }
                    verify(exactly = 0) { h.effects.createMobStaff(any(), any()) }
                    verify(exactly = 0) { h.effects.cancel(wolf.uniqueId) }
                    verify(exactly = 0) { overflow.currentTarget() }
                    every { actor.isCurrent() } returns false
                    h.scheduler.tick(1)
                    verify(exactly = 1) { h.effects.cancel(wolf.uniqueId) }
                }
            }
        }
    }

    "run expiration death and module close remove cosmetic handles while preserving native loot and hands" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("dungeon-caster-cleanup")
            val mobs = (0..2).map { index ->
                h.zombie(world, index + .5, .5).apply {
                    equipment.setItemInMainHand(ItemStack(Material.IRON_SWORD))
                    equipment.itemInMainHandDropChance = .23f
                }
            }
            val actors = mobs.map { dungeonActor(it) }
            val handles = List(3) { mockk<PacketItemDisplay>(relaxed = true) }
            handles.forEach { every { it.isValid } returns true }
            every { h.effects.createMobStaff(any(), any()) } returnsMany handles
            StaffMobCasters(h.config, StaffMobCasterSettings(spawnChancePercent = 100.0), h.effects,
                mockk<PaperViewerParticles>(relaxed = true)).use { casters ->
                casters.start()
                actors.forEach(casters::enrollFromDungeon)
                every { actors[0].isCurrent() } returns false
                h.scheduler.tick(1)
                verify(exactly = 1) { handles[0].remove() }
                val loot = mutableListOf(ItemStack(Material.ROTTEN_FLESH, 2), ItemStack(Material.IRON_SWORD))
                val originalLoot = loot.map(ItemStack::clone)
                val death = EntityDeathEvent(mobs[1], mockk<DamageSource>(relaxed = true), loot, 7)
                casters.onDeath(death)
                loot shouldBe originalLoot
                death.droppedExp shouldBe 7
                verify(exactly = 1) { handles[1].remove() }
                casters.close()
                verify(exactly = 1) { handles[2].remove() }
                mobs.forEach {
                    it.equipment.itemInMainHand shouldBe ItemStack(Material.IRON_SWORD)
                    it.equipment.itemInMainHandDropChance shouldBe .23f
                }
            }
        }
    }

    "changing the native dungeon target during windup cancels the captured attack" {
        withStaffHarness { h ->
            val world = h.solidCollisionWorld("dungeon-caster-windup")
            for (x in -1..1) for (z in 0..6) world.getBlockAt(x, 63, z).type = Material.STONE
            val mob = h.zombie(world, .5, .5)
            val target = h.paper.addPlayer("dungeon-windup-target").apply { teleport(Location(world, .5, 64.0, 4.5)) }
            val other = h.paper.addPlayer("dungeon-windup-other").apply { teleport(target.location) }
            val actor = dungeonActor(mob, target)
            val cosmetic = mockk<PacketItemDisplay>(relaxed = true)
            every { cosmetic.isValid } returns true
            every { h.effects.createMobStaff(any(), any()) } returns cosmetic
            val scene = UUID.randomUUID()
            every { h.effects.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns scene
            every { h.effects.custom(any(), any(), any(), any(), any(), any(), any()) } returns scene
            every { h.effects.isActive(scene) } returns true
            every { h.effects.hasAudience(scene, target.uniqueId) } returns true
            StaffMobCasters(h.config, StaffMobCasterSettings(spawnChancePercent = 100.0), h.effects,
                mockk<PaperViewerParticles>(relaxed = true)).use { casters ->
                casters.start()
                casters.enrollFromDungeon(actor)
                h.scheduler.tick(20)
                verify(atLeast = 1) { h.effects.hasAudience(scene, target.uniqueId) }
                every { actor.currentTarget() } returns other
                h.scheduler.tick(1)
                verify(exactly = 1) { h.effects.cancel(mob.uniqueId) }
                h.scheduler.tick(12)
                verify(exactly = 0) { actor.damage(any(), any()) }
            }
        }
    }

    "current dungeon actors allow named armed native minds without taking over equipment" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("dungeon-caster-native-body")
            val mob = h.zombie(world, .5, .5).apply {
                customName(Component.text("Dungeon boss"))
                setAI(false)
                equipment.setItemInMainHand(ItemStack(Material.NETHERITE_SWORD))
                equipment.setItemInOffHand(ItemStack(Material.SHIELD))
            }
            val actor = dungeonActor(mob)
            staffMobOwnerAllowed(actor) shouldBe true
            every { actor.isCurrent() } returns false
            staffMobOwnerAllowed(actor) shouldBe false
            every { actor.isCurrent() } returns true
            mob.remove()
            staffMobOwnerAllowed(actor) shouldBe false
        }
    }

    "dungeon spells require the current native actor target and current membership permission" {
        withStaffHarness { h ->
            val world = h.solidCollisionWorld("dungeon-caster-target-policy")
            val mob = h.zombie(world, .5, .5).apply {
                customName(Component.text("NativeMind dungeon boss"))
                setAI(false)
                equipment.setItemInMainHand(ItemStack(Material.NETHERITE_SWORD))
            }
            val target = h.paper.addPlayer("dungeon-caster-target").apply {
                teleport(Location(world, .5, 64.0, 4.5))
                gameMode = GameMode.SURVIVAL
            }
            val other = h.paper.addPlayer("dungeon-caster-other").apply { teleport(target.location) }
            val actor = dungeonActor(mob, target)
            // A NativeMind can own its target while the Bukkit body has no vanilla AI target.
            mob.target shouldBe null
            staffMobTargetAllowed(actor, target, 4.0) shouldBe true
            staffMobTargetAllowed(actor, target, 3.99) shouldBe false
            every { actor.currentTarget() } returns other
            staffMobTargetAllowed(actor, target, 16.0) shouldBe false
            every { actor.currentTarget() } returns target
            every { actor.permitsTarget(target) } returns false
            staffMobTargetAllowed(actor, target, 16.0) shouldBe false
            every { actor.permitsTarget(target) } returns true
            target.gameMode = GameMode.ADVENTURE
            staffMobTargetAllowed(actor, target, 16.0) shouldBe true
            listOf(GameMode.CREATIVE, GameMode.SPECTATOR).forEach {
                target.gameMode = it
                staffMobTargetAllowed(actor, target, 16.0) shouldBe false
            }
            target.gameMode = GameMode.SURVIVAL
            target.isInvulnerable = true
            staffMobTargetAllowed(actor, target, 16.0) shouldBe false
            target.isInvulnerable = false
            target.disconnect()
            staffMobTargetAllowed(actor, target, 16.0) shouldBe false
        }
    }

    "dungeon native damage receives only a finite multiplier after final target and cover checks" {
        withStaffHarness { h ->
            val world = h.solidCollisionWorld("dungeon-caster-damage-boundary")
            val mob = h.zombie(world, .5, .5)
            val target = h.paper.addPlayer("dungeon-caster-damage-target").apply {
                teleport(Location(world, .5, 64.0, 4.5))
                gameMode = GameMode.SURVIVAL
            }
            val actor = dungeonActor(mob, target)
            every { actor.damage(target, 2.5) } returns false
            staffMobHit(actor, target, 2.5) shouldBe false
            verify(exactly = 1) { actor.damage(target, 2.5) }
            every { actor.damage(target, 1.75) } returns true
            staffMobHit(actor, target, 1.75) shouldBe true
            verify(exactly = 1) { actor.damage(target, 1.75) }
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0).forEach {
                staffMobHit(actor, target, it) shouldBe false
            }
            every { actor.currentTarget() } returns null
            staffMobHit(actor, target, 3.0) shouldBe false
            every { actor.currentTarget() } returns target
            every { actor.permitsTarget(target) } returns false
            staffMobHit(actor, target, 3.0) shouldBe false
            every { actor.permitsTarget(target) } returns true
            for (x in -1..1) for (y in 64..67) world.getBlockAt(x, y, 2).type = Material.STONE
            staffMobHit(actor, target, 3.0) shouldBe false
            verify(exactly = 0) { actor.damage(target, 3.0) }
        }
    }
})

private fun dungeonActor(body: Mob, target: Player? = null): StaffDungeonMob = mockk {
    every { this@mockk.body } returns body
    every { isCurrent() } returns true
    every { currentTarget() } returns target
    every { permitsTarget(any()) } returns true
    every { damage(any(), any()) } returns false
}
