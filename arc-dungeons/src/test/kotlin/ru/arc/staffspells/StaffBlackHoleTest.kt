package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs

class StaffBlackHoleTest : FreeSpec({
    "four admitted pulses shrink and feed the core, then survivors regain their current foreign scale" {
        withStaffHarness { h ->
            val world = h.solidCollisionWorld("black-hole-feed")
            val caster = h.player("black-hole-feed", world, StaffSpell.MARK)
            val mob = h.zombie(world, .5, 6.0)
            val attribute = requireNotNull(mob.getAttribute(Attribute.SCALE))
            attribute.baseValue = 1.4
            val foreign = AttributeModifier(NamespacedKey("test", "foreign-scale"), .2,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1)
            attribute.addTransientModifier(foreign)
            val original = attribute.value
            h.controller.start()
            h.controller.cast(caster, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(2)
            (attribute.value < original) shouldBe true
            attribute.getModifier(foreign.key) shouldBe foreign
            attribute.baseValue = 1.8 // A legitimate concurrent change must survive our cleanup.
            h.scheduler.tick(18)
            (abs(attribute.value - 1.8 * 1.2 * STAFF_BLACK_HOLE_MIN_SCALE) < 1e-6) shouldBe true
            verify(atLeast = 1) { h.effects.blackHoleGrowth(any(), match { it >= 1.08 }) }
            h.scheduler.tick(20)
            h.damageScales.map { it.first } shouldBe listOf(.12, .12, .12, .12, .65)
            val held = attribute.value
            h.scheduler.tick(4)
            (attribute.value > held && attribute.value < 1.8 * 1.2) shouldBe true
            h.scheduler.tick(6)
            (abs(attribute.value - 1.8 * 1.2) < 1e-6) shouldBe true
            attribute.modifiers.toList() shouldBe listOf(foreign)
            h.hits.size shouldBe 5
        }
    }

    "one cast reserves at most six unique victims and honors a smaller area cap" {
        listOf(2, 8).forEach { areaLimit -> withStaffHarness(settings = staffSettings(maxAreaTargets = areaLimit)) { h ->
            val world = h.solidCollisionWorld("black-hole-cap-$areaLimit")
            val caster = h.player("black-hole-cap-$areaLimit", world, StaffSpell.MARK)
            repeat(9) { h.zombie(world, .5 + it * .2, 6.0) }
            h.controller.start()
            h.controller.cast(caster, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(50)
            val cap = minOf(6, areaLimit)
            h.hits.distinctBy { it.uniqueId }.size shouldBe cap
            h.hits.size shouldBe cap * 5
            (abs(h.damageScales.sumOf { it.first } - cap * 1.13) < 1e-6) shouldBe true
            verify { h.effects.blackHoleGrowth(any(), match { it in 1.0..staffBlackHoleGrowth(cap) }) }
        } }
        staffBlackHoleGrowth(100) shouldBe 1.48
    }

    "denied damage and newly blocked line of sight never retain control or shrinking" {
        var accepted = false
        withStaffHarness(hit = { _, _ -> accepted }) { h ->
            val world = h.solidCollisionWorld("black-hole-denied")
            val caster = h.player("black-hole-denied", world, StaffSpell.MARK)
            val mob = h.zombie(world, .5, 6.0)
            h.controller.start()
            h.controller.cast(caster, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(2)
            mob.blackHoleModifiers().size shouldBe 0
            mob.velocity.lengthSquared() shouldBe 0.0
            accepted = true
            h.scheduler.tick(10)
            mob.blackHoleModifiers().size shouldBe 1
            accepted = false
            h.scheduler.tick(10)
            mob.blackHoleModifiers().size shouldBe 0
            accepted = true
            h.scheduler.tick(10)
            mob.blackHoleModifiers().size shouldBe 1
            for (x in -1..1) for (y in 64..67) world.getBlockAt(x, y, 3).type = Material.STONE
            h.scheduler.tick(2)
            mob.blackHoleModifiers().size shouldBe 0
            h.scheduler.tick(20)
            h.hits.size shouldBe 4
        }
    }

    "caster exit, world or context invalidation, mob death and module close remove only owned scale" {
        listOf("quit", "death", "world", "context", "mob-death", "mob-world", "close").forEach { exit ->
            var current = true
            withStaffHarness(currentCast = { _, _ -> current }) { h ->
                val world = h.solidCollisionWorld("black-hole-$exit")
                val caster = h.player("black-hole-$exit", world, StaffSpell.MARK)
                val mob = h.zombie(world, .5, 6.0)
                h.controller.start()
                h.controller.cast(caster, StaffCastInput.SECONDARY, 0.0)
                h.scheduler.tick(8)
                mob.blackHoleModifiers().size shouldBe 1
                when (exit) {
                    "quit" -> h.controller.onQuit(PlayerQuitEvent(caster, Component.empty()))
                    "death" -> h.controller.onDeath(mockk<PlayerDeathEvent> { every { entity } returns caster })
                    "world" -> {
                        caster.teleport(Location(h.solidCollisionWorld("black-hole-other"), .5, 64.0, .5))
                        h.controller.onWorldChange(PlayerChangedWorldEvent(caster, world))
                    }
                    "context" -> current = false
                    "mob-death" -> h.controller.onMarkedMobDeath(mockk<EntityDeathEvent> { every { entity } returns mob })
                    "mob-world" -> mob.teleport(Location(h.solidCollisionWorld("black-hole-other"), .5, 64.0, .5))
                    "close" -> h.controller.close()
                }
                if (exit in setOf("context", "mob-world")) h.scheduler.tick(2)
                mob.blackHoleModifiers().size shouldBe 0
                mob.getAttribute(Attribute.SCALE)!!.value shouldBe 1.0
            }
        }
    }

    "overlapping holes never stack scale and replacing a cast releases its previous captives" {
        withStaffHarness { h ->
            val world = h.solidCollisionWorld("black-hole-overlap")
            val first = h.player("black-hole-first", world, StaffSpell.MARK)
            val second = h.player("black-hole-second", world, StaffSpell.MARK)
            val mob = h.zombie(world, .5, 6.0)
            h.controller.start()
            h.controller.cast(first, StaffCastInput.SECONDARY, 0.0)
            h.controller.cast(second, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(2)
            val firstKey = mob.blackHoleModifiers().single().key
            h.hits.size shouldBe 1
            h.controller.onQuit(PlayerQuitEvent(first, Component.empty()))
            mob.blackHoleModifiers().size shouldBe 0
            h.scheduler.tick(10)
            val secondKey = mob.blackHoleModifiers().single().key
            (firstKey != secondKey) shouldBe true
            h.controller.onQuit(PlayerQuitEvent(first, Component.empty()))
            mob.blackHoleModifiers().single().key shouldBe secondKey
            h.scheduler.tick(28) // The fixture's secondary cooldown is 40 ticks.
            h.controller.cast(second, StaffCastInput.SECONDARY, 0.0)
            mob.blackHoleModifiers().size shouldBe 0
            h.scheduler.tick(2)
            (mob.blackHoleModifiers().single().key != secondKey) shouldBe true
        }
    }

    "full-size collision envelope rejects a shrink shortcut and unloaded chunks without teleport" {
        withStaffHarness { h ->
            val world = h.solidCollisionWorld("black-hole-body-clearance")
            val mob = h.zombie(world, .5, 6.0)
            val hole = StaffBlackHole(UUID.randomUUID())
            hole.capture(mob, center(mob).add(2.0, 0.0, 0.0))
            val captive = hole.captives.values.single()
            hole.resize(captive, .18)
            world.getBlockAt(1, 64, 6).type = Material.STONE
            captive.canMove(Vector(.48, 0.0, 0.0)) shouldBe false
            captive.canMove(Vector(-.48, .12, 0.0)) shouldBe true
            val before = mob.location.clone()
            hole.releaseAll()
            mob.location shouldBe before
            mob.getAttribute(Attribute.SCALE)!!.value shouldBe 1.0
        }
        val world = mockk<org.bukkit.World>()
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns false
        val mob = mockk<org.bukkit.entity.Mob>()
        every { mob.world } returns world
        every { mob.location } returns Location(world, 15.8, 64.0, 0.5)
        val attribute = mockk<org.bukkit.attribute.AttributeInstance>()
        every { attribute.value } returns 1.0
        StaffBlackHoleCaptive(mob, attribute, .6, 1.8, .6, Vector(1.0, 0.0, 0.0))
            .canMove(Vector(.48, .12, 0.0)) shouldBe false
        verify(exactly = 0) { world.hasCollisionsIn(any()) }
    }

    "an admitted player receives the pulse pull and never the mob scale modifier" {
        withStaffHarness(targetEligible = { caster, victim -> caster.uniqueId != victim.uniqueId }) { h ->
            val world = h.solidCollisionWorld("black-hole-admitted-player")
            val caster = h.player("black-hole-caster", world, StaffSpell.MARK)
            h.zombie(world, .5, 6.0)
            val opponent = h.player("black-hole-opponent", world, StaffSpell.MARK)
            if (opponent.getAttribute(Attribute.SCALE) == null) opponent.registerAttribute(Attribute.SCALE)
            opponent.teleport(Location(world, 1.5, 64.0, 6.0))
            h.controller.start()
            h.controller.cast(caster, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(2)
            (opponent in h.hits) shouldBe true
            (opponent.velocity.lengthSquared() > 0.0) shouldBe true
            opponent.blackHoleModifiers().size shouldBe 0
        }
    }

    "a damage callback that cancels the caster cannot leave a newly captured mob" {
        var controller: StaffSpellController? = null
        withStaffHarness(hit = { caster, _ ->
            controller!!.onQuit(PlayerQuitEvent(caster, Component.empty()))
            true
        }) { h ->
            controller = h.controller
            val world = h.solidCollisionWorld("black-hole-reentrant-cancel")
            val caster = h.player("black-hole-reentrant-cancel", world, StaffSpell.MARK)
            val mob = h.zombie(world, .5, 6.0)
            h.controller.start()
            h.controller.cast(caster, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(20)
            h.hits.size shouldBe 1
            mob.blackHoleModifiers().size shouldBe 0
            mob.velocity.lengthSquared() shouldBe 0.0
        }
    }

    "a rejected terminal hit still restores surviving mobs over the release phase" {
        var attempts = 0
        withStaffHarness(hit = { _, _ -> ++attempts < 5 }) { h ->
            val world = h.solidCollisionWorld("black-hole-denied-terminal")
            val caster = h.player("black-hole-denied-terminal", world, StaffSpell.MARK)
            val mob = h.zombie(world, .5, 6.0)
            h.controller.start()
            h.controller.cast(caster, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(40)
            mob.blackHoleModifiers().size shouldBe 1
            (mob.getAttribute(Attribute.SCALE)!!.value < .3) shouldBe true
            h.scheduler.tick(10)
            mob.blackHoleModifiers().size shouldBe 0
            attempts shouldBe 5
        }
    }

    "floor aim lifts the shared core while an empty air aim keeps its exact ray endpoint" {
        withStaffHarness(settings = staffSettings(range = 12.0)) { h ->
            val floor = h.solidCollisionWorld("black-hole-floor")
            for (x in -2..2) for (z in 0..14) floor.getBlockAt(x, 63, z).type = Material.STONE
            val caster = h.player("black-hole-floor", floor, StaffSpell.MARK)
            caster.teleport(caster.location.setDirection(Vector(0.0, -.5, 1.0)))
            h.controller.cast(caster, StaffCastInput.SECONDARY, 0.0)
            val core = h.visualPlays.last { it.spell == StaffSpell.MARK }.from
            (core.y > 65.0 && core.y < 65.3) shouldBe true
            visible(caster.eyeLocation, core) shouldBe true

            val air = h.solidCollisionWorld("black-hole-air")
            val airborne = h.player("black-hole-air", air, StaffSpell.MARK)
            h.controller.cast(airborne, StaffCastInput.SECONDARY, 0.0)
            val airCore = h.visualPlays.last { it.spell == StaffSpell.MARK }.from
            airCore shouldBe airborne.eyeLocation.clone().add(0.0, 0.0, 12.0)
        }
    }

    "fed sphere stays centered on gameplay core with bounded stable display topology" {
        val before = staffDisplayParts(StaffSpell.MARK, 24, 40, 1.0, 4.5, false, secondary = true)
        val grown = staffDisplayParts(StaffSpell.MARK, 24, 40, 1.0, 4.5, false,
            secondary = true, blackHoleGrowth = 1.48)
        grown.size shouldBe 29
        grown.map { it.material } shouldBe before.map { it.material }
        (abs(grown.take(7).map { it.center.y }.average()) < .01) shouldBe true
        grown.zip(before).forEach { (large, small) ->
            (abs(large.scale.x / small.scale.x - 1.48) < 1e-5) shouldBe true
        }
        staffBlackHoleReleaseScale(.18, 0) shouldBe .18
        staffBlackHoleReleaseScale(.18, 10) shouldBe 1.0
    }
})

private fun LivingEntity.blackHoleModifiers() = getAttribute(Attribute.SCALE)!!.modifiers
    .filter { it.key.namespace == "arcdungeons" && it.key.key.startsWith("staff_black_hole/") }
