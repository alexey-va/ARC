package ru.arc.staffspells

import com.magmaguy.elitemobs.entitytracker.EntityTracker
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.damage.DamageSource
import org.bukkit.entity.Skeleton
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.particle.PaperViewerParticles
import java.util.UUID

class StaffMobCasterTest : FreeSpec({
    "mob caster settings retain their snapshot and reread the next config generation" {
        val raw = StaffTestConfig()
        val config = StaffSpellConfig(raw)
        val original = config.mobCasters
        original shouldBe StaffMobCasterSettings(true, 3.0, 1.0, 16.0, 32, emptySet())

        raw["mob-casters.enabled"] = false
        raw["mob-casters.spawn-chance-percent"] = 100.0
        raw["mob-casters.difficulty"] = 2.0
        raw["mob-casters.range-blocks"] = 24.0
        raw["mob-casters.max-casters"] = 12
        raw["mob-casters.worlds"] = listOf("world", "world_nether", "world")

        config.mobCasters shouldBe StaffMobCasterSettings(false, 100.0, 2.0, 24.0, 12,
            setOf("world", "world_nether"))
        original shouldBe StaffMobCasterSettings(true, 3.0, 1.0, 16.0, 32, emptySet())
    }

    "mob caster limits clamp finite input and reject nonfinite combat values" {
        val raw = StaffTestConfig(mapOf(
            "mob-casters.spawn-chance-percent" to -1.0,
            "mob-casters.difficulty" to 0.0,
            "mob-casters.range-blocks" to 0.0,
            "mob-casters.max-casters" to 0,
        ))
        val config = StaffSpellConfig(raw)
        config.mobCasters shouldBe StaffMobCasterSettings(true, 0.0, 0.25, 4.0, 1, emptySet())

        raw["mob-casters.spawn-chance-percent"] = 101.0
        raw["mob-casters.difficulty"] = 10.0
        raw["mob-casters.range-blocks"] = 100.0
        raw["mob-casters.max-casters"] = 100
        config.mobCasters shouldBe StaffMobCasterSettings(true, 100.0, 4.0, 32.0, 64, emptySet())

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            raw["mob-casters.spawn-chance-percent"] = invalid
            raw["mob-casters.difficulty"] = invalid
            raw["mob-casters.range-blocks"] = invalid
            withClue("nonfinite input $invalid") {
                config.mobCasters.spawnChancePercent shouldBe 3.0
                config.mobCasters.difficulty shouldBe 1.0
                config.mobCasters.range shouldBe 16.0
            }
        }
    }

    "difficulty scales mob damage and recovery while retaining a dodge warning" {
        val easy = StaffSpellConfig(StaffTestConfig(mapOf("mob-casters.difficulty" to 0.25))).mobCasters
        val hard = StaffSpellConfig(StaffTestConfig(mapOf("mob-casters.difficulty" to 4.0))).mobCasters
        easy.damage shouldBe 1.0
        hard.damage shouldBe 16.0
        easy.recoveryTicks shouldBe 200
        hard.recoveryTicks shouldBe 50
        easy.telegraphTicks shouldBe 48
        hard.telegraphTicks shouldBe 12
        easy.markTelegraphTicks shouldBe 48
        hard.markTelegraphTicks shouldBe 32
    }

    "mob equipment carries only its cosmetic identity and never becomes a player staff" {
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

    "zero and hundred percent spawn chance honor delayed admission and the pending caster cap" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-mob-chance")
            listOf(0.0, 100.0).forEachIndexed { index, chance ->
                val mob = h.zombie(world, index * 4.0, .5).apply {
                    // MockBukkit leaves its drop-chance map empty until a native setter is called.
                    equipment.itemInMainHandDropChance = .17f
                }
                val overflow = h.zombie(world, index * 4.0 + 1, .5)
                val dropChance = mob.equipment.itemInMainHandDropChance
                val settings = StaffMobCasterSettings(spawnChancePercent = chance, maxCasters = 1)
                StaffMobCasters(h.config, settings, h.effects, mockk<PaperViewerParticles>(relaxed = true)).use { casters ->
                    casters.start()
                    casters.onSpawn(CreatureSpawnEvent(mob, CreatureSpawnEvent.SpawnReason.NATURAL))
                    casters.onSpawn(CreatureSpawnEvent(overflow, CreatureSpawnEvent.SpawnReason.NATURAL))
                    h.scheduler.tick(1)
                    mob.equipment.itemInMainHand.type.isAir shouldBe true
                    h.scheduler.tick(1)
                    val spell = mob.equipment.itemInMainHand.itemMeta?.persistentDataContainer
                        ?.get(StaffMobCasters.staffKey, PersistentDataType.STRING)
                    if (chance == 0.0) spell shouldBe null else
                        (spell in listOf("ember", "icicle", "lance", "mark")) shouldBe true
                    overflow.equipment.itemInMainHand.type.isAir shouldBe true
                    mob.equipment.itemInMainHandDropChance shouldBe dropChance
                }
                mob.equipment.itemInMainHand.type.isAir shouldBe true
                mob.equipment.itemInMainHandDropChance shouldBe dropChance
            }
        }
    }

    "cancelled custom baby armed and newly named spawns remain untouched" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-mob-spawn-rejections")
            val cancelled = h.zombie(world, .5, .5)
            val lateCancelled = h.zombie(world, 1.5, .5)
            val custom = h.zombie(world, 2.5, .5)
            val baby = h.zombie(world, 3.5, .5).apply { setBaby(true) }
            val armed = h.zombie(world, 4.5, .5).apply {
                equipment.setItemInMainHand(ItemStack(Material.IRON_SWORD))
            }
            val offhand = h.zombie(world, 5.5, .5).apply {
                equipment.setItemInOffHand(ItemStack(Material.SHIELD))
            }
            val named = h.zombie(world, 6.5, .5)
            val skeleton = world.spawn(Location(world, 7.5, 64.0, .5), Skeleton::class.java)
            val originals = listOf(cancelled, lateCancelled, custom, baby, armed, offhand, named).associateWith {
                it.equipment.itemInMainHand.clone() to it.equipment.itemInOffHand.clone()
            }
            StaffMobCasters(h.config, StaffMobCasterSettings(spawnChancePercent = 100.0), h.effects,
                mockk<PaperViewerParticles>(relaxed = true)).use { casters ->
                casters.start()
                casters.onSpawn(CreatureSpawnEvent(cancelled, CreatureSpawnEvent.SpawnReason.NATURAL).apply {
                    isCancelled = true
                })
                val pendingEvent = CreatureSpawnEvent(lateCancelled, CreatureSpawnEvent.SpawnReason.NATURAL)
                casters.onSpawn(pendingEvent)
                pendingEvent.isCancelled = true
                casters.onSpawn(CreatureSpawnEvent(custom, CreatureSpawnEvent.SpawnReason.CUSTOM))
                listOf(baby, armed, offhand, named).forEach {
                    casters.onSpawn(CreatureSpawnEvent(it, CreatureSpawnEvent.SpawnReason.NATURAL))
                }
                named.customName(Component.text("Claimed during spawn"))
                casters.onSpawn(CreatureSpawnEvent(skeleton, CreatureSpawnEvent.SpawnReason.NATURAL))
                h.scheduler.tick(2)
                originals.forEach { (mob, hands) ->
                    mob.equipment.itemInMainHand shouldBe hands.first
                    mob.equipment.itemInOffHand shouldBe hands.second
                }
                skeleton.equipment.itemInMainHand.type.isAir shouldBe true
            }
        }
    }

    "death unload transform pickup reload and orphan load remove only owned cosmetic equipment" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-mob-lifecycle")
            val mobs = (0..4).map { index ->
                h.zombie(world, index + .5, .5).apply {
                    equipment.itemInMainHandDropChance = .17f + index * .01f
                }
            }
            val dropSettings = mobs.associateWith { it.equipment.itemInMainHandDropChance }
            StaffMobCasters(h.config, StaffMobCasterSettings(spawnChancePercent = 100.0), h.effects,
                mockk<PaperViewerParticles>(relaxed = true)).use { casters ->
                casters.start()
                mobs.forEach { casters.onSpawn(CreatureSpawnEvent(it, CreatureSpawnEvent.SpawnReason.NATURAL)) }
                h.scheduler.tick(2)
                mobs.forEach { it.equipment.itemInMainHand.type shouldBe Material.BLAZE_ROD }

                val ordinaryLoot = ItemStack(Material.ROTTEN_FLESH, 2)
                val ordinaryRod = ItemStack(Material.BLAZE_ROD)
                val ownedRod = mobs[0].equipment.itemInMainHand.clone()
                val drops = mutableListOf(ownedRod, ordinaryLoot, ordinaryRod)
                val death = EntityDeathEvent(mobs[0], mockk<DamageSource>(relaxed = true), drops, 7)
                casters.onDeath(death)
                drops shouldBe listOf(ordinaryLoot, ordinaryRod)
                death.droppedExp shouldBe 7
                mobs[0].equipment.itemInMainHand.type.isAir shouldBe true

                val stray = world.dropItem(Location(world, .5, 64.0, 2.5), ownedRod)
                val straySpawn = ItemSpawnEvent(stray)
                casters.onItemSpawn(straySpawn)
                straySpawn.isCancelled shouldBe true
                val normalSpawn = ItemSpawnEvent(world.dropItem(Location(world, 1.5, 64.0, 2.5), ordinaryRod))
                casters.onItemSpawn(normalSpawn)
                normalSpawn.isCancelled shouldBe false

                casters.onUnload(EntitiesUnloadEvent(mobs[1].chunk, listOf(mobs[1])))
                mobs[1].equipment.itemInMainHand.type.isAir shouldBe true
                val transformed = h.zombie(world, 8.5, .5).apply {
                    equipment.setItemInMainHand(h.config.mobItem(StaffSpell.EMBER))
                }
                casters.onTransform(EntityTransformEvent(mobs[2], listOf(transformed), EntityTransformEvent.TransformReason.DROWNED))
                mobs[2].equipment.itemInMainHand.type.isAir shouldBe true
                transformed.equipment.itemInMainHand.type.isAir shouldBe true
                val pickup = EntityPickupItemEvent(mobs[3], normalSpawn.entity, 0)
                casters.onPickup(pickup)
                pickup.isCancelled shouldBe false
                mobs[3].equipment.itemInMainHand.type.isAir shouldBe true

                val orphan = h.zombie(world, 9.5, .5).apply {
                    equipment.setItemInMainHand(h.config.mobItem(StaffSpell.MARK))
                }
                val foreign = h.zombie(world, 10.5, .5).apply {
                    equipment.setItemInMainHand(ordinaryRod.clone())
                }
                casters.onLoad(EntitiesLoadEvent(orphan.chunk, listOf(orphan, foreign)))
                orphan.equipment.itemInMainHand.type.isAir shouldBe true
                foreign.equipment.itemInMainHand shouldBe ordinaryRod

                // A module reload closes the previous generation before creating its replacement.
                casters.close()
                mobs[4].equipment.itemInMainHand.type.isAir shouldBe true
                h.scheduler.tick(3)
                mobs.forEach { mob ->
                    mob.equipment.itemInMainHand.type.isAir shouldBe true
                    mob.equipment.itemInMainHandDropChance shouldBe dropSettings.getValue(mob)
                }
            }
        }
    }

    "a native target change cancels a mob's admitted windup before it can deal damage" {
        withStaffHarness { h ->
            val world = h.solidCollisionWorld("staff-mob-windup")
            for (x in -2..2) for (z in 2..6) world.getBlockAt(x, 63, z).type = Material.STONE
            val mob = h.zombie(world, .5, .5)
            val target = h.paper.addPlayer("staff-mob-windup-target").apply {
                teleport(Location(world, .5, 64.0, 4.5))
                gameMode = GameMode.SURVIVAL
            }
            every { h.effects.play(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()) } answers { UUID.randomUUID() }
            every { h.effects.custom(any(), any(), any(), any(), any(), any(), any()) } answers { UUID.randomUUID() }
            every { h.effects.isActive(any()) } returns true
            every { h.effects.hasAudience(any(), target.uniqueId) } returns true
            StaffMobCasters(h.config, StaffMobCasterSettings(spawnChancePercent = 100.0), h.effects,
                mockk<PaperViewerParticles>(relaxed = true)).use { casters ->
                casters.start()
                casters.onSpawn(CreatureSpawnEvent(mob, CreatureSpawnEvent.SpawnReason.NATURAL))
                mob.target = target
                val health = target.health
                h.scheduler.tick(24)
                verify(atLeast = 1) { h.effects.hasAudience(any(), target.uniqueId) }
                mob.target = null
                h.scheduler.tick(1)
                verify(exactly = 1) { h.effects.cancel(mob.uniqueId) }
                target.health shouldBe health
            }
        }
    }

    "mob caster admission preserves named NPC Elite and other plugin actors" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-mob-owner-policy")
            val mob = h.zombie(world, .5, .5)
            val plugin = h.paper.createSimplePlugin("StaffMobPolicy")
            staffMobOwnerAllowed(mob) shouldBe true

            mob.setAI(false)
            staffMobOwnerAllowed(mob) shouldBe false
            mob.setAI(true)
            mob.customName(Component.text("Player's zombie"))
            staffMobOwnerAllowed(mob) shouldBe false
            mob.customName(null)
            listOf("NPC", "Elitemob", "Elitemobs_NPC").forEach { key ->
                mob.setMetadata(key, FixedMetadataValue(plugin, true))
                withClue(key) { staffMobOwnerAllowed(mob) shouldBe false }
                mob.removeMetadata(key, plugin)
            }
            mob.addScoreboardTag("quest_actor")
            staffMobOwnerAllowed(mob) shouldBe false
            mob.removeScoreboardTag("quest_actor")

            mob.persistentDataContainer.set(NamespacedKey.minecraft("native_marker"), PersistentDataType.BYTE, 1.toByte())
            mob.persistentDataContainer.set(NamespacedKey("arc", "cleanup_source"), PersistentDataType.STRING, "spawn")
            staffMobOwnerAllowed(mob) shouldBe true
            mob.persistentDataContainer.set(NamespacedKey("quests", "owner"), PersistentDataType.STRING, "quest")
            staffMobOwnerAllowed(mob) shouldBe false
            mob.persistentDataContainer.remove(NamespacedKey("quests", "owner"))
            h.paper.createSimplePlugin("EliteMobs")
            mockkStatic(EntityTracker::class)
            try {
                every { EntityTracker.isEliteMob(mob) } returns true
                staffMobOwnerAllowed(mob) shouldBe false
                every { EntityTracker.isEliteMob(mob) } returns false
                staffMobOwnerAllowed(mob) shouldBe true
            } finally {
                unmockkStatic(EntityTracker::class)
            }
            mob.remove()
            staffMobOwnerAllowed(mob) shouldBe false
        }
    }

    "mob spells follow only their current native target in a playable online state" {
        withStaffHarness { h ->
            val world = h.solidCollisionWorld("staff-mob-target-policy")
            val mob = h.zombie(world, .5, .5)
            val target = h.paper.addPlayer("staff-mob-target").apply {
                teleport(Location(world, .5, 64.0, 4.5))
                gameMode = GameMode.SURVIVAL
            }
            val other = h.paper.addPlayer("staff-mob-other").apply { teleport(target.location) }
            staffMobTargetAllowed(mob, target, 16.0) shouldBe false
            mob.target = target
            staffMobTargetAllowed(mob, target, 4.0) shouldBe true
            staffMobTargetAllowed(mob, target, 3.99) shouldBe false
            listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0).forEach {
                staffMobTargetAllowed(mob, target, it) shouldBe false
            }
            target.gameMode = GameMode.ADVENTURE
            staffMobTargetAllowed(mob, target, 16.0) shouldBe true
            listOf(GameMode.CREATIVE, GameMode.SPECTATOR).forEach {
                target.gameMode = it
                staffMobTargetAllowed(mob, target, 16.0) shouldBe false
            }
            target.gameMode = GameMode.SURVIVAL
            target.isInvulnerable = true
            staffMobTargetAllowed(mob, target, 16.0) shouldBe false
            target.isInvulnerable = false
            val plugin = h.paper.createSimplePlugin("StaffMobTargetPolicy")
            target.setMetadata("NPC", FixedMetadataValue(plugin, true))
            staffMobTargetAllowed(mob, target, 16.0) shouldBe false
            target.removeMetadata("NPC", plugin)
            val position = target.location
            target.teleport(Location(h.paper.addSimpleWorld("staff-mob-other-world"), .5, 64.0, 4.5))
            staffMobTargetAllowed(mob, target, 16.0) shouldBe false
            target.teleport(position)
            mob.target = other
            staffMobTargetAllowed(mob, target, 16.0) shouldBe false
            val health = target.health
            staffMobHit(mob, target, 4.0) shouldBe false
            target.health shouldBe health
            mob.target = target
            target.disconnect() shouldBe true
            staffMobTargetAllowed(mob, target, 16.0) shouldBe false
        }
    }

    "cover range and duel protection are rechecked before mob spell damage" {
        withStaffHarness { h ->
            val world = h.solidCollisionWorld("staff-mob-final-policy")
            val mob = h.zombie(world, .5, .5)
            val target = h.paper.addPlayer("staff-mob-protected").apply {
                teleport(Location(world, .5, 64.0, 4.5))
                gameMode = GameMode.SURVIVAL
            }
            mob.target = target
            staffMobTargetAllowed(mob, target, 16.0) shouldBe true
            val health = target.health
            listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0).forEach {
                staffMobHit(mob, target, it) shouldBe false
            }
            val duel = mockk<StaffSpellDuelAccess>()
            every { duel.inputAllowed(target) } returns false
            StaffSpellsModule.installDuelAccess(duel)
            try {
                staffMobTargetAllowed(mob, target, 16.0) shouldBe false
                staffMobHit(mob, target, 4.0) shouldBe false
                every { duel.inputAllowed(target) } returns true
                every { duel.context(target) } returns DuelSpellAuthorization(UUID.randomUUID(), mockk(relaxed = true))
                staffMobTargetAllowed(mob, target, 16.0) shouldBe false
            } finally {
                StaffSpellsModule.installDuelAccess(null)
            }
            target.teleport(Location(world, .5, 64.0, 33.5))
            staffMobTargetAllowed(mob, target, 16.0) shouldBe false
            staffMobHit(mob, target, 4.0) shouldBe false
            target.teleport(Location(world, .5, 64.0, 4.5))
            for (x in -1..1) for (y in 64..67) world.getBlockAt(x, y, 2).type = Material.STONE
            staffMobTargetAllowed(mob, target, 16.0) shouldBe false
            staffMobHit(mob, target, 4.0) shouldBe false
            target.health shouldBe health
        }
    }

    // MockBukkit 4.116.3 LivingEntityMock.setCanPickupItems/getCanPickupItems throw
    // UnimplementedOperationException; native pickup policy preservation remains unverified.
    "mob staff admission and cleanup preserve native pickup policy".config(enabled = false) {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-mob-pickup-policy")
            val mob = h.zombie(world, .5, .5).apply { canPickupItems = true }
            val pickup = mob.canPickupItems
            StaffMobCasters(h.config, StaffMobCasterSettings(spawnChancePercent = 100.0), h.effects,
                mockk<PaperViewerParticles>(relaxed = true)).use { casters ->
                casters.start()
                casters.onSpawn(CreatureSpawnEvent(mob, CreatureSpawnEvent.SpawnReason.NATURAL))
                h.scheduler.tick(2)
                mob.canPickupItems shouldBe pickup
            }
            mob.canPickupItems shouldBe pickup
        }
    }

    // MockBukkit 4.116.3 LivingEntityMock.damage(double, DamageSource) throws
    // UnimplementedOperationException; retain this native-event scenario for platform support.
    "cancelled native mob magic preserves health and both damage-source entities".config(enabled = false) {
        withStaffHarness { h ->
            val world = h.solidCollisionWorld("staff-mob-native-damage")
            val mob = h.zombie(world, .5, .5)
            val target = h.paper.addPlayer("staff-mob-native-target").apply {
                teleport(Location(world, .5, 64.0, 4.5))
                gameMode = GameMode.SURVIVAL
            }
            mob.target = target
            var native: EntityDamageByEntityEvent? = null
            h.paper.server.pluginManager.registerEvents(object : Listener {
                @EventHandler(priority = EventPriority.HIGHEST)
                fun damage(event: EntityDamageByEntityEvent) {
                    if (event.entity != target) return
                    native = event
                    event.isCancelled = true
                }
            }, h.paper.createSimplePlugin("StaffMobDamagePolicy"))
            val health = target.health
            staffMobHit(mob, target, 4.0) shouldBe false
            target.health shouldBe health
            requireNotNull(native).damageSource.directEntity shouldBe mob
            requireNotNull(native).damageSource.causingEntity shouldBe mob
        }
    }
})
