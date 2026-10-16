package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import java.util.UUID

class StaffSpellTechniquesRuntimeTest : FreeSpec({
    "Icicle Q casts without a previous volley and hits only visible targets within its radial boundary at contact" {
        withTechniqueScenes(staffSettings(range = 12.0)) { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-icicle-q-radial")
            val player = h.player("staff-icicle-q-radial", world, StaffSpell.ICICLE)
            val inside = h.zombie(world, .5, 4.5)
            h.zombie(world, .5, 5.1) // Beyond the 4.5-block horizontal radius.
            h.zombie(world, 3.5, .5)
            for (y in 64..66) world.getBlockAt(2, y, 0).type = Material.STONE
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.ICICLE, StaffHotkey.Q)
            activeScenes.size shouldBe 1
            h.hits shouldBe emptyList<LivingEntity>()
            h.scheduler.tick((STAFF_ICICLE_RADIAL_CONTACT_TICK - 1).toLong())
            h.hits shouldBe emptyList<LivingEntity>()
            h.scheduler.tick(1)
            h.hits shouldBe listOf(inside)
            h.scheduler.tick(STAFF_ICICLE_RADIAL_TICKS.toLong())

            h.hits shouldBe listOf(inside)
            activeScenes shouldBe emptySet<UUID>()
        }
    }

    "empty ICICLE LANCE GRAVITY and NECROMANCER Q casts create visible scenes without synthetic damage" {
        for (spell in listOf(StaffSpell.ICICLE, StaffSpell.LANCE, StaffSpell.GRAVITY, StaffSpell.NECROMANCER)) {
            withTechniqueScenes { h, activeScenes ->
                val world = h.paper.addSimpleWorld("staff-empty-q-${spell.id}")
                val player = h.player("staff-empty-q-${spell.id}", world, spell)
                h.controller.start()

                h.controller.hotkey(player, spell, StaffHotkey.Q)

                activeScenes.size shouldBe 1
                h.cooldowns.remainingTicks(player.uniqueId) shouldBe 4L
                h.hits shouldBe emptyList<LivingEntity>()
                if (spell == StaffSpell.GRAVITY) {
                    val heldScene = activeScenes.single()
                    h.scheduler.tick(4)
                    h.controller.hotkey(player, spell, StaffHotkey.Q)
                    (heldScene in activeScenes) shouldBe false
                    activeScenes.size shouldBe 1 // The same continuation now throws the empty brackets.
                }
                h.scheduler.tick(30)
                h.hits shouldBe emptyList<LivingEntity>()
            }
        }
    }

    "an empty Q cast without a display allocation does not consume its admission budget" {
        for (spell in listOf(StaffSpell.ICICLE, StaffSpell.LANCE, StaffSpell.GRAVITY, StaffSpell.NECROMANCER)) {
            withTechniqueScenes { h, activeScenes ->
                every { h.effects.custom(any(), any(), any(), any(), any()) } returns null
                val world = h.paper.addSimpleWorld("staff-empty-q-budget-${spell.id}")
                val player = h.player("staff-empty-q-budget-${spell.id}", world, spell)
                h.controller.start()
                h.controller.hotkey(player, spell, StaffHotkey.Q)
                h.cooldowns.remainingTicks(player.uniqueId) shouldBe 0L
                every { h.effects.custom(any(), any(), any(), any(), any()) } answers {
                    UUID.randomUUID().also(activeScenes::add)
                }

                h.controller.hotkey(player, spell, StaffHotkey.Q)

                activeScenes.size shouldBe 1
                h.cooldowns.remainingTicks(player.uniqueId) shouldBe 4L
                h.hits shouldBe emptyList<LivingEntity>()
            }
        }
    }

    "a zero-charge crown release has no projectile capture or cooldown" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-icicle-zero-release")
            val player = h.player("staff-icicle-zero-release", world, StaffSpell.ICICLE)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.visualPlays shouldBe emptyList<StaffVisualPlay>()
            h.captures shouldBe emptyList<org.bukkit.entity.Player>()
            h.cooldowns.remainingTicks(player.uniqueId) shouldBe 0L
        }
    }

    "Chain recast is admitted for its own orb, and impact stops at cover" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-chain-q-collision")
            val player = h.player("staff-chain-q-collision", world, StaffSpell.CHAIN)
            val target = h.zombie(world, .5, 4.5)
            for (x in -2..2) for (y in 63..67) world.getBlockAt(x, y, 3).type = Material.STONE
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.CHAIN, StaffHotkey.Q)
            val orb = activeScenes.single()
            h.scheduler.tick(5)
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.GRAVITY))
            h.controller.hotkey(player, StaffSpell.GRAVITY, StaffHotkey.Q)
            activeScenes.size shouldBe 2
            (orb in activeScenes) shouldBe true
            h.scheduler.tick(4)
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.CHAIN))
            h.controller.hotkey(player, StaffSpell.CHAIN, StaffHotkey.Q)

            h.hits shouldBe emptyList<LivingEntity>()
            (orb in activeScenes) shouldBe false
            activeScenes.size shouldBe 1
            target.isValid shouldBe true
        }
    }

    "Gravity restores the target when its visible scene is evicted" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-gravity-q-eviction")
            val player = h.player("staff-gravity-q-eviction", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, .5, 4.5)
            target.setGravity(true)
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.GRAVITY, StaffHotkey.Q)
            target.hasGravity() shouldBe false
            activeScenes.clear()
            // Scene eviction restores control on the next owner tick, without a new input through the GCD.
            h.scheduler.tick(1)

            target.hasGravity() shouldBe true
            target.velocity.lengthSquared() shouldBe 0.0
            h.hits shouldBe listOf(target)
        }
    }

    "Gravity rejects a second caster before opening damage on an already held target" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-gravity-q-exclusive-target")
            val firstCaster = h.player("staff-gravity-q-first-caster", world, StaffSpell.GRAVITY)
            val secondCaster = h.player("staff-gravity-q-second-caster", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, .5, 4.5)
            target.setGravity(true)
            h.controller.start()

            h.controller.hotkey(firstCaster, StaffSpell.GRAVITY, StaffHotkey.Q)
            target.hasGravity() shouldBe false
            h.hits shouldBe listOf(target)
            activeScenes.size shouldBe 1

            h.controller.hotkey(secondCaster, StaffSpell.GRAVITY, StaffHotkey.Q)
            h.hits shouldBe listOf(target)
            target.hasGravity() shouldBe false
            activeScenes.size shouldBe 1

            activeScenes.clear()
            h.scheduler.tick(1)
            target.hasGravity() shouldBe true
        }
    }

    "Gravity cannot suspend a target when its control scene cannot be allocated" {
        withStaffHarness { h ->
            every { h.effects.custom(any(), any(), any(), any(), any()) } returns null
            val world = h.paper.addSimpleWorld("staff-gravity-q-no-scene")
            val player = h.player("staff-gravity-q-no-scene", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, .5, 4.5)
            target.setGravity(true)
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.GRAVITY, StaffHotkey.Q)
            h.scheduler.tick(2)

            h.hits shouldBe listOf(target)
            target.hasGravity() shouldBe true
            target.velocity.lengthSquared() shouldBe 0.0
        }
    }

    "Gravity does not hold a target after the opening hit invalidates its cast" {
        var castCurrent = true
        withStaffHarness(
            currentCast = { _, _ -> castCurrent },
            hit = { _, _ -> castCurrent = false; true },
        ) { h ->
            val world = h.paper.addSimpleWorld("staff-gravity-q-stale-hit")
            val player = h.player("staff-gravity-q-stale-hit", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, .5, 4.5)
            target.setGravity(true)
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.GRAVITY, StaffHotkey.Q)
            h.scheduler.tick(2)

            target.hasGravity() shouldBe true
            target.velocity.lengthSquared() shouldBe 0.0
            h.hits shouldBe listOf(target)
        }
    }

    "Gravity throw recast cannot damage the same target a second time" {
        withTechniqueScenes { h, _ ->
            val world = h.paper.addSimpleWorld("staff-gravity-q-one-hit")
            val player = h.player("staff-gravity-q-one-hit", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, .5, 4.5)
            target.setGravity(true)
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.GRAVITY, StaffHotkey.Q)
            target.hasGravity() shouldBe false
            h.scheduler.tick(5)
            h.controller.hotkey(player, StaffSpell.GRAVITY, StaffHotkey.Q)

            h.hits shouldBe listOf(target)
            target.hasGravity() shouldBe true
            (target.velocity.lengthSquared() > 0.0) shouldBe true
        }
    }

    "Lance does not pin a target after its opening hit invalidates the cast" {
        var castCurrent = true
        withStaffHarness(
            currentCast = { _, _ -> castCurrent },
            hit = { _, _ -> castCurrent = false; true },
        ) { h ->
            val world = h.paper.addSimpleWorld("staff-lance-q-stale-hit")
            val player = h.player("staff-lance-q-stale-hit", world, StaffSpell.LANCE)
            val target = h.zombie(world, .5, 4.5)
            for (y in 63..67) world.getBlockAt(0, y, 6).type = Material.STONE
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.LANCE, StaffHotkey.Q)

            h.hits shouldBe listOf(target)
            target.velocity.lengthSquared() shouldBe 0.0
            target.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS) shouldBe false
        }
    }

    "Lance does not apply pin control when its scene cannot be allocated" {
        withStaffHarness { h ->
            every { h.effects.custom(any(), any(), any(), any(), any()) } returns null
            val world = h.paper.addSimpleWorld("staff-lance-q-no-scene")
            val player = h.player("staff-lance-q-no-scene", world, StaffSpell.LANCE)
            val target = h.zombie(world, .5, 4.5)
            for (y in 63..67) world.getBlockAt(0, y, 6).type = Material.STONE
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.LANCE, StaffHotkey.Q)

            h.hits shouldBe listOf(target)
            target.velocity.lengthSquared() shouldBe 0.0
            target.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS) shouldBe false
        }
    }

    "Prism removes its crossing detector when its visible scene is evicted" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-prism-q-eviction")
            world.getBlockAt(0, 63, 14).type = Material.STONE
            val player = h.player("staff-prism-q-eviction", world, StaffSpell.PRISM)
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.PRISM, StaffHotkey.Q)
            activeScenes.clear()
            val target = h.zombie(world, .5, 13.5)
            h.scheduler.tick(1)

            h.hits shouldBe emptyList<LivingEntity>()
            target.isValid shouldBe true
        }
    }
})

private fun withTechniqueScenes(
    settings: StaffSpellSettings = staffSettings(),
    block: (StaffHarness, MutableSet<UUID>) -> Unit,
) {
    withStaffHarness(settings = settings) { h ->
        val activeScenes = mutableSetOf<UUID>()
        every { h.effects.custom(any(), any(), any(), any(), any()) } answers {
            UUID.randomUUID().also(activeScenes::add)
        }
        every { h.effects.isActive(any()) } answers { firstArg<UUID?>()?.let(activeScenes::contains) == true }
        every { h.effects.remove(any()) } answers {
            activeScenes.remove(firstArg<UUID?>())
            Unit
        }
        block(h, activeScenes)
    }
}
