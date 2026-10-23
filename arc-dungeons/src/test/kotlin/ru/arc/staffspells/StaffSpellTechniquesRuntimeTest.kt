package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
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
            inside.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 7
            inside.getPotionEffect(PotionEffectType.SLOWNESS)?.duration shouldBe 24
            repeat(24) { h.paper.performTicks(1); h.scheduler.tick(1) }

            h.hits shouldBe listOf(inside)
            inside.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            activeScenes shouldBe emptySet<UUID>()
        }
    }

    "Icicle Gravity and Prism Q cannot add crowd control after rejected damage" {
        for (spell in listOf(StaffSpell.ICICLE, StaffSpell.GRAVITY, StaffSpell.PRISM)) {
            withTechniqueScenes(hit = { _, _ -> false }) { h, _ ->
                val world = h.paper.addSimpleWorld("staff-q-rejected-cc-${spell.id}")
                world.getBlockAt(0, 63, 14).type = Material.STONE
                val player = h.player("staff-q-rejected-cc-${spell.id}", world, spell)
                val target = h.zombie(world, .5, if (spell == StaffSpell.PRISM) 13.5 else 4.5)
                target.setGravity(true)
                h.controller.start()

                h.controller.hotkey(player, spell, StaffHotkey.Q)
                h.scheduler.tick(12)

                h.hits shouldBe listOf(target)
                target.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
                target.hasGravity() shouldBe true
            }
        }
    }

    "Prism ward slows a crossing enemy once without replacing a stronger effect" {
        withTechniqueScenes { h, _ ->
            val world = h.paper.addSimpleWorld("staff-prism-q-slow")
            world.getBlockAt(0, 63, 14).type = Material.STONE
            val player = h.player("staff-prism-q-slow", world, StaffSpell.PRISM)
            val target = h.zombie(world, .5, 13.5)
            val stronger = h.zombie(world, 1.0, 13.5)
            stronger.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 80, 3))
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.PRISM, StaffHotkey.Q)
            h.scheduler.tick(1)

            target.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 1
            target.getPotionEffect(PotionEffectType.SLOWNESS)?.duration shouldBe 40
            stronger.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 3
            h.scheduler.tick(60)
            h.hits.count { it == target } shouldBe 1
            h.hits.count { it == stronger } shouldBe 1
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
        for (spell in listOf(StaffSpell.ICICLE, StaffSpell.LANCE, StaffSpell.GRAVITY, StaffSpell.NECROMANCER, StaffSpell.GALE)) {
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

    "Chain proximity fuse catches an off-axis enemy and bursts once across its visible area" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-chain-q-proximity")
            val player = h.player("staff-chain-q-proximity", world, StaffSpell.CHAIN)
            val nearMiss = h.zombie(world, 2.5, 7.0)
            val areaTarget = h.zombie(world, 4.0, 7.5)
            val outside = h.zombie(world, 6.0, 7.5)
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.CHAIN, StaffHotkey.Q)
            h.hits shouldBe emptyList<LivingEntity>()
            h.scheduler.tick(8)

            h.hits.toSet() shouldBe setOf(nearMiss, areaTarget)
            h.hits.size shouldBe 2
            h.damageScales shouldBe listOf(1.0 to 6.0, 1.0 to 6.0)
            h.scheduler.tick(14)
            h.hits.count { it == nearMiss } shouldBe 1
            (outside in h.hits) shouldBe false
            activeScenes shouldBe emptySet<UUID>()
        }
    }

    "Chain automatically bursts before cover without damaging a fully hidden enemy" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-chain-q-collision")
            val player = h.player("staff-chain-q-collision", world, StaffSpell.CHAIN)
            val target = h.zombie(world, .5, 4.5)
            for (x in -2..2) for (y in 63..67) world.getBlockAt(x, y, 3).type = Material.STONE
            h.controller.start()

            h.controller.hotkey(player, StaffSpell.CHAIN, StaffHotkey.Q)
            val orb = activeScenes.single()
            h.scheduler.tick(2)
            val impact = h.movedEffects.last { it.first == orb }.second
            (impact.z in 2.90..2.999) shouldBe true
            activeScenes.size shouldBe 1 // Short visible fracture stays at the wall; no second damage phase.
            h.scheduler.tick(STAFF_CHAIN_BURST_TICKS.toLong())

            h.hits shouldBe emptyList<LivingEntity>()
            (orb in activeScenes) shouldBe false
            activeScenes shouldBe emptySet<UUID>()
            target.isValid shouldBe true
        }
    }

    "Chain manual detonation stays available during flight and cannot repeat during its burst" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-chain-q-recast")
            val player = h.player("staff-chain-q-recast", world, StaffSpell.CHAIN)
            h.controller.start()
            h.controller.hotkey(player, StaffSpell.CHAIN, StaffHotkey.Q)
            h.scheduler.tick(4)
            val target = h.zombie(world, .5, 7.5)

            h.controller.hotkey(player, StaffSpell.CHAIN, StaffHotkey.Q)
            h.hits shouldBe listOf(target)
            h.scheduler.tick(4)
            h.controller.hotkey(player, StaffSpell.CHAIN, StaffHotkey.Q)
            h.scheduler.tick(STAFF_CHAIN_BURST_TICKS.toLong())

            h.hits shouldBe listOf(target)
            activeScenes shouldBe emptySet<UUID>()
        }
    }

    "Chain reaches its bounded terminal range and bursts in empty air without acquiring ineligible players" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-chain-q-empty")
            val player = h.player("staff-chain-q-empty", world, StaffSpell.CHAIN)
            h.player("staff-chain-q-ineligible", world, StaffSpell.CHAIN)
                .teleport(Location(world, .5, 64.0, 6.5))
            h.controller.start()
            h.controller.hotkey(player, StaffSpell.CHAIN, StaffHotkey.Q)
            val orb = activeScenes.single()
            h.scheduler.tick(27)

            val impact = h.movedEffects.last { it.first == orb }.second
            (impact.z in 29.24..29.26) shouldBe true
            activeScenes.size shouldBe 1
            h.hits shouldBe emptyList<LivingEntity>()
            h.scheduler.tick(STAFF_CHAIN_BURST_TICKS.toLong())
            activeScenes shouldBe emptySet<UUID>()
        }
    }

    "GALE hurricane remains at its airborne cast point and delivers only its bounded sustained budget" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-gale-q-air")
            val player = h.player("staff-gale-q-air", world, StaffSpell.GALE)
            player.teleport(player.location.add(0.0, 20.0, 0.0))
            val target = h.zombie(world, .5, 5.5).apply { teleport(location.add(0.0, 20.0, 0.0)) }
            h.zombie(world, 8.2, .5).apply { teleport(location.add(0.0, 20.0, 0.0)) }
            h.controller.start()
            h.controller.hotkey(player, StaffSpell.GALE, StaffHotkey.Q)

            activeScenes.size shouldBe 1
            val scene = h.visualPlays.single { it.spell == StaffSpell.GALE }
            scene.from.y shouldBe 84.0
            h.scheduler.tick((STAFF_GALE_HURRICANE_BUILD_TICKS - 1).toLong())
            h.hits shouldBe emptyList<LivingEntity>()
            h.scheduler.tick(1)
            h.hits shouldBe listOf(target)
            player.teleport(player.location.add(14.0, 0.0, 0.0))
            h.scheduler.tick(STAFF_GALE_HURRICANE_TICKS.toLong())

            h.hits shouldBe List(STAFF_GALE_HURRICANE_MAX_TARGET_HITS) { target }
            h.damageScales.sumOf { it.first } shouldBe 2.0
            h.damageScales.sumOf { it.second } shouldBe 12.0
            h.movedEffects.filter { it.first == scene.id } shouldBe emptyList<Pair<UUID, Location>>()
            activeScenes shouldBe emptySet<UUID>()
        }
    }

    "GALE hurricane never hits through cover and caps its entire cast at eight admitted targets" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-gale-q-limits")
            val player = h.player("staff-gale-q-limits", world, StaffSpell.GALE)
            val hidden = h.zombie(world, .5, 5.5)
            for (x in -1..1) for (y in 64..77) world.getBlockAt(x, y, 2).type = Material.STONE
            val visible = (0..8).map { index -> h.zombie(world, 2.5 + index * .35, -.5) }
            h.controller.start()
            h.controller.hotkey(player, StaffSpell.GALE, StaffHotkey.Q)
            h.scheduler.tick(STAFF_GALE_HURRICANE_TICKS.toLong())

            (hidden in h.hits) shouldBe false
            h.hits.toSet() shouldBe visible.take(8).toSet()
            h.hits.groupingBy { it }.eachCount().values.toSet() shouldBe setOf(STAFF_GALE_HURRICANE_MAX_TARGET_HITS)
            h.hits.size shouldBe 64
            activeScenes shouldBe emptySet<UUID>()
        }
    }

    "GALE Q has a separate thirty-second deadline for ordinary and duel-marked staves" {
        for (duelItem in listOf(false, true)) withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-gale-q-cooldown-$duelItem")
            val player = h.player("staff-gale-q-cooldown-$duelItem", world, StaffSpell.GALE)
            if (duelItem) player.inventory.setItemInMainHand(h.controller.duelItem(StaffSpell.GALE))
            h.controller.start()
            h.controller.hotkey(player, StaffSpell.GALE, StaffHotkey.Q)
            activeScenes.size shouldBe 1
            h.scheduler.tick(STAFF_GALE_HURRICANE_TICKS.toLong())
            activeScenes shouldBe emptySet<UUID>()
            h.controller.canHotkey(player, StaffSpell.GALE, StaffHotkey.Q) shouldBe false
            h.controller.canHotkey(player, StaffSpell.GALE, StaffHotkey.F) shouldBe true
            h.scheduler.tick(499)
            h.controller.canHotkey(player, StaffSpell.GALE, StaffHotkey.Q) shouldBe false
            h.scheduler.tick(1)
            h.controller.canHotkey(player, StaffSpell.GALE, StaffHotkey.Q) shouldBe true
        }
    }

    "Q flight and hurricane scenes clean up on quit death world change reset eviction and close" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-q-lifecycle")
            val quitting = h.player("staff-q-quit", world, StaffSpell.GALE)
            val dying = h.player("staff-q-death", world, StaffSpell.GALE)
            val changing = h.player("staff-q-world", world, StaffSpell.GALE)
            val resetting = h.player("staff-q-reset", world, StaffSpell.CHAIN)
            h.controller.start()
            listOf(quitting, dying, changing).forEach { h.controller.hotkey(it, StaffSpell.GALE, StaffHotkey.Q) }
            h.controller.hotkey(resetting, StaffSpell.CHAIN, StaffHotkey.Q)
            activeScenes.size shouldBe 4

            h.controller.onQuit(PlayerQuitEvent(quitting, Component.empty()))
            h.controller.onDeath(mockk<PlayerDeathEvent> { every { entity } returns dying })
            changing.teleport(Location(h.paper.addSimpleWorld("staff-q-other-world"), .5, 64.0, .5))
            h.controller.onWorldChange(PlayerChangedWorldEvent(changing, world))
            h.controller.resetPlayer(resetting.uniqueId)
            activeScenes shouldBe emptySet<UUID>()

            val evicted = h.player("staff-q-evicted", world, StaffSpell.GALE)
            h.controller.hotkey(evicted, StaffSpell.GALE, StaffHotkey.Q)
            activeScenes.clear()
            h.zombie(world, .5, 4.5)
            h.scheduler.tick(14)
            h.hits shouldBe emptyList<LivingEntity>()

            val closing = h.player("staff-q-closing", world, StaffSpell.GALE)
            h.controller.hotkey(closing, StaffSpell.GALE, StaffHotkey.Q)
            activeScenes.size shouldBe 1
            h.controller.close()
            activeScenes shouldBe emptySet<UUID>()
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
            target.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 7
            activeScenes.clear()
            // Scene eviction restores control on the next owner tick, without a new input through the GCD.
            h.scheduler.tick(1)

            target.hasGravity() shouldBe true
            target.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
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
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-gravity-q-one-hit")
            val player = h.player("staff-gravity-q-one-hit", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, .5, 4.5)
            target.setGravity(true)
            target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 80, 1))
            val unrelated = h.zombie(world, 4.5, 4.5)
            val unrelatedCast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            h.controller.start()
            h.controller.conjurations.rootTarget(player, unrelated, unrelatedCast, 70) shouldBe true

            h.controller.hotkey(player, StaffSpell.GRAVITY, StaffHotkey.Q)
            target.hasGravity() shouldBe false
            target.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 7
            h.scheduler.tick(5)
            h.controller.hotkey(player, StaffSpell.GRAVITY, StaffHotkey.Q)

            h.hits shouldBe listOf(target)
            h.damageScales shouldBe listOf(1.0 to 6.0)
            target.hasGravity() shouldBe true
            target.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 1
            h.controller.conjurations.rootActive(unrelated.uniqueId, unrelatedCast.attackId) shouldBe true
            (target.velocity.lengthSquared() > 0.0) shouldBe true
            activeScenes.size shouldBe 1 // The released impulse is visible for real victims too.
            h.visualPlays.size shouldBe 2
        }
    }

    "NECROMANCER order marker follows its target and disappears when cover cancels the order" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-necromancer-q-marker-cover")
            val player = h.player("staff-necromancer-q-marker-cover", world, StaffSpell.NECROMANCER)
            val target = h.zombie(world, .5, 5.5)
            h.controller.start()
            h.controller.hotkey(player, StaffSpell.NECROMANCER, StaffHotkey.Q)
            activeScenes.size shouldBe 1
            val scene = activeScenes.single()
            target.teleport(target.location.clone().add(1.0, 0.0, 0.0))
            h.scheduler.tick(4)
            h.movedEffects.last { it.first == scene }.second shouldBe target.location
            h.hits shouldBe emptyList()
            for (x in -1..3) for (y in 64..67) world.getBlockAt(x, y, 3).type = Material.STONE
            h.scheduler.tick(10)
            activeScenes shouldBe emptySet<UUID>()
            h.hits shouldBe emptyList()
        }
    }

    "EMBER display lifetime includes the final six ticks of a maximum length late-recorded trail" {
        withTechniqueScenes { h, activeScenes ->
            var lifetime = 0
            var elapsed = 0
            every { h.effects.custom(any(), any(), any(), any(), any()) } answers {
                lifetime = arg(3)
                UUID.randomUUID().also(activeScenes::add)
            }
            fun advance(ticks: Int) = repeat(ticks) {
                elapsed++
                if (elapsed >= lifetime) activeScenes.clear() // The real display pool expires its scene.
                h.scheduler.tick(1)
            }
            val world = h.paper.addSimpleWorld("staff-ember-q-full-afterglow")
            for (x in -1..2) for (z in 0..15) world.getBlockAt(x, 63, z).type = Material.STONE
            val player = h.player("staff-ember-q-full-afterglow", world, StaffSpell.EMBER)
            h.controller.start()
            h.controller.hotkey(player, StaffSpell.EMBER, StaffHotkey.Q)
            activeScenes.size shouldBe 1
            advance(29)
            repeat(11) {
                player.teleport(player.location.clone().add(0.0, 0.0, 1.0))
                advance(1)
            }
            advance(27) // Tick67: all12nodes have ignited and the final glow must remain visible.
            elapsed shouldBe 67
            activeScenes.size shouldBe 1
            h.hits shouldBe emptyList()
            advance(3)
            activeScenes shouldBe emptySet<UUID>()
        }
    }

    "NECROMANCER empty order is bounded and reset closes its presentation before the volley cue" {
        withTechniqueScenes { h, activeScenes ->
            val world = h.paper.addSimpleWorld("staff-necromancer-q-marker-reset")
            val player = h.player("staff-necromancer-q-marker-reset", world, StaffSpell.NECROMANCER)
            h.controller.start()
            h.controller.hotkey(player, StaffSpell.NECROMANCER, StaffHotkey.Q)
            h.scheduler.tick(6)
            activeScenes.size shouldBe 1
            h.controller.resetPlayer(player.uniqueId)
            activeScenes shouldBe emptySet<UUID>()
            h.scheduler.tick(35)
            h.hits shouldBe emptyList()
            h.controller.hotkey(player, StaffSpell.NECROMANCER, StaffHotkey.Q)
            activeScenes.size shouldBe 1
            h.scheduler.tick(32)
            activeScenes shouldBe emptySet<UUID>()
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
    hit: (Player, LivingEntity) -> Boolean = { _, _ -> true },
    block: (StaffHarness, MutableSet<UUID>) -> Unit,
) {
    withStaffHarness(settings = settings, hit = hit) { h ->
        val activeScenes = mutableSetOf<UUID>()
        every { h.effects.custom(any(), any(), any(), any(), any()) } answers {
            UUID.randomUUID().also { id ->
                activeScenes += id
                val at = arg<Location>(2).clone()
                h.visualPlays += StaffVisualPlay(id, arg(1), at, at.clone(), false, false, false, null)
            }
        }
        every { h.effects.updateCustom(any(), any(), any()) } answers {
            firstArg<UUID?>()?.let { h.movedEffects += it to secondArg<Location>().clone() }
            Unit
        }
        every { h.effects.isActive(any()) } answers { firstArg<UUID?>()?.let(activeScenes::contains) == true }
        every { h.effects.remove(any()) } answers {
            activeScenes.remove(firstArg<UUID?>())
            Unit
        }
        block(h, activeScenes)
    }
}
