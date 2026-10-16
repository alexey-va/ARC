package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.world.WorldMock
import java.util.UUID

class StaffIcicleVolleyTest : FreeSpec({
    "airborne and delayed icicles share the replacement after the first focused enemy dies" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("icicle-volley-death")
            val player = h.player("icicle-volley-death", world, StaffSpell.ICICLE)
            val first = h.zombie(world, .5, 14.0)
            val next = h.zombie(world, 1.8, 18.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            val shots = h.visualPlays.filter { it.spell == StaffSpell.ICICLE }
            h.scheduler.tick(8)
            h.hits shouldBe emptyList()
            h.projectileMoves.count { it.id == shots.first().id } shouldBe 5

            first.health = 0.0
            h.scheduler.tick(55)

            h.hits.map { it.uniqueId } shouldBe listOf(next.uniqueId)
            h.damageScales shouldBe listOf(1.45 to 8.7)
            shots.size shouldBe 7
            // The oldest shard redirects too; this is not merely a new aim for later launches.
            val firstArrival = h.projectileMoves.last { it.id == shots.first().id }.at
            (firstArrival.distance(center(next)) < 1.1) shouldBe true
            verify(exactly = 7) { h.effects.impact(any(), any(), .9, 10) }
        }
    }

    "a killing hit hands the remaining volley to the next enemy without multiplying damage" {
        withStaffHarness(hit = { _, target -> target.health = 0.0; true }) { h ->
            val world = h.paper.addSimpleWorld("icicle-volley-kills")
            val player = h.player("icicle-volley-kills", world, StaffSpell.ICICLE)
            val first = h.zombie(world, .5, 12.0)
            val second = h.zombie(world, 1.1, 16.0)
            val third = h.zombie(world, -.3, 20.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(first.uniqueId, second.uniqueId, third.uniqueId)
            h.damageScales shouldBe List(3) { 1.45 to 8.7 }
            h.visualPlays.count { it.spell == StaffSpell.ICICLE } shouldBe 7
        }
    }

    "direct reticle acquisition and replacement still work with aim assist disabled" {
        withStaffHarness(settings = staffSettings().copy(aimAssist = StaffAimAssistSettings(enabled = false)),
            hit = { _, target -> target.health = 0.0; true }) { h ->
            val world = h.paper.addSimpleWorld("icicle-direct-without-assist")
            val player = h.player("icicle-direct-without-assist", world, StaffSpell.ICICLE)
            val first = h.zombie(world, .5, 12.0)
            val next = h.zombie(world, .5, 18.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(first.uniqueId, next.uniqueId)
            h.damageScales shouldBe List(2) { 1.45 to 8.7 }
        }
    }

    "one covered wing cannot replace the volley focus with another visible enemy" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("icicle-covered-wing")
            val player = h.player("icicle-covered-wing", world, StaffSpell.ICICLE)
            val focus = h.zombie(world, .5, 14.0)
            val neighbor = h.zombie(world, 1.5, 18.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            val wing = h.visualPlays.filter { it.spell == StaffSpell.ICICLE }[1]
            h.scheduler.tick(4)
            for (y in 64..68) world.getBlockAt(1, y, 7).type = Material.STONE

            visible(player.eyeLocation, focus) shouldBe true
            staffVisibleTargetPoint(wing.from, focus, player.eyeLocation) shouldBe null
            (staffVisibleTargetPoint(wing.from, neighbor, player.eyeLocation) != null) shouldBe true
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(focus.uniqueId)
        }
    }

    "a newly covered or invalid focus hands over only to an exposed enemy near the reticle" {
        for (loss in listOf("cover", "invalid", "world")) withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("icicle-volley-$loss")
            val player = h.player("icicle-volley-$loss", world, StaffSpell.ICICLE)
            val first = h.zombie(world, .5, 12.0)
            val next = h.zombie(world, 2.0, 14.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            h.scheduler.tick(4)
            when (loss) {
                "cover" -> for (y in 64..66) world.getBlockAt(0, y, 10).type = Material.STONE
                "invalid" -> first.remove()
                else -> first.teleport(Location(h.paper.addSimpleWorld("icicle-target-other"), .5, 64.0, 12.0))
            }
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(next.uniqueId)
        }
    }

    "reacquisition respects assist switches angle and maximum miss" {
        val assists = listOf(
            StaffAimAssistSettings(enabled = false),
            StaffAimAssistSettings(strength = 0.0),
            StaffAimAssistSettings(excludedSpells = setOf(StaffSpell.ICICLE.id)),
            StaffAimAssistSettings(halfAngleDegrees = 2.0),
            StaffAimAssistSettings(maxMiss = .2),
        )
        assists.forEachIndexed { index, assist ->
            withStaffHarness(settings = staffSettings().copy(aimAssist = assist)) { h ->
                val world = h.paper.addSimpleWorld("icicle-reacquire-limit-$index")
                val player = h.player("icicle-reacquire-limit-$index", world, StaffSpell.ICICLE)
                val first = h.zombie(world, .5, 14.0)
                h.zombie(world, 1.8, 18.0)
                h.zombie(world, .5, -8.0)
                h.controller.start()
                h.controller.cast(player, StaffCastInput.PRIMARY, .6)
                val firstShot = h.visualPlays.first { it.spell == StaffSpell.ICICLE }.id
                h.scheduler.tick(8)
                val before = h.projectileMoves.last { it.id == firstShot }.direction
                first.health = 0.0
                h.scheduler.tick(1)

                // Losing focus cannot revive the obsolete point or snap to a soft target.
                val after = h.projectileMoves.last { it.id == firstShot }.direction
                (after.distanceSquared(before) < 1e-8) shouldBe true
            }
        }
    }

    "retargeting uses the current aim area and never reverses an airborne shard" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("icicle-retarget-forward")
            val player = h.player("icicle-retarget-forward", world, StaffSpell.ICICLE)
            val first = h.zombie(world, .5, 14.0)
            h.zombie(world, .5, -8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            val firstShot = h.visualPlays.first { it.spell == StaffSpell.ICICLE }.id
            h.scheduler.tick(8)
            first.remove()
            player.teleport(player.location.apply { yaw = 180f })
            h.scheduler.tick(60)

            h.hits shouldBe emptyList()
            h.projectileMoves.filter { it.id == firstShot }.all { it.direction.z > 0.0 } shouldBe true
        }
    }

    "a clear diagonal corrects a column-edge collision before the normal launch hold ends" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("icicle-column-edge")
            val player = h.player("icicle-column-edge", world, StaffSpell.ICICLE).apply {
                teleport(location.apply { x = 1.25 })
            }
            val target = h.zombie(world, 1.25, 12.0)
            world.getBlockAt(-2, 65, 5).type = Material.STONE
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            val firstShot = h.visualPlays.first { it.spell == StaffSpell.ICICLE }.id
            h.scheduler.tick(4)
            val firstStep = h.projectileMoves.single { it.id == firstShot }
            val forwardHit = staffBlockHit(firstStep.at, firstStep.direction, staffIcicleStep(2), ignoreVegetation = true)
            (forwardHit.hit != null) shouldBe true
            (staffVisibleTargetPoint(firstStep.at, target, player.eyeLocation) != null) shouldBe true
            h.scheduler.tick(1)

            val corrected = h.projectileMoves.last { it.id == firstShot }
            (corrected.direction.x > .2) shouldBe true
            (corrected.direction.angle(firstStep.direction) <= Math.toRadians(45.0)) shouldBe true
            (kotlin.math.abs(corrected.at.distance(firstStep.at) - staffIcicleStep(2)) < 1e-6) shouldBe true
            (corrected.at.z > 5.0) shouldBe true
            h.scheduler.tick(55)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            (h.projectileMoves.last { it.id == firstShot }.at.distance(center(target)) < 1.1) shouldBe true
        }
    }

    "icicles pursue the exposed head instead of the covered body center" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("icicle-exposed-head")
            val player = h.player("icicle-exposed-head", world, StaffSpell.ICICLE)
            val target = h.zombie(world, .5, 12.0)
            for (x in -4..4) world.getBlockAt(x, 64, 11).type = Material.STONE
            visible(player.eyeLocation, center(target)) shouldBe false
            (staffVisibleTargetPoint(player.eyeLocation, target) != null) shouldBe true
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            val firstShot = h.visualPlays.first { it.spell == StaffSpell.ICICLE }.id
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            val arrival = h.projectileMoves.last { it.id == firstShot }.at
            target.boundingBox.expand(.380001).contains(arrival.toVector()) shouldBe true
            (arrival.y > 65.0) shouldBe true
        }
    }

    "solid cover and a magic shield still stop every shard when no visible replacement exists" {
        for (shield in listOf(false, true)) withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("icicle-retarget-shield-$shield")
            val player = h.player("icicle-retarget-shield-$shield", world, StaffSpell.ICICLE)
            val first = h.zombie(world, .5, 12.0)
            h.zombie(world, 1.5, 14.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            h.scheduler.tick(4)
            first.remove()
            if (shield) {
                (StaffMagicBarriers.register(UUID.randomUUID(), Location(world, .5, 64.0, 8.0),
                    Vector(0.0, 0.0, 1.0), 4.0, 4.0) != null) shouldBe true
            } else {
                for (x in -4..4) for (y in 64..68) world.getBlockAt(x, y, 8).type = Material.STONE
            }
            h.scheduler.tick(60)

            h.hits shouldBe emptyList()
            h.projectileMoves.all { it.at.z < 8.0 } shouldBe true
        }
    }

    "unloaded target paths and caster world changes do not leave a searching volley" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("icicle-unloaded-replacement")
            val player = h.player("icicle-unloaded-replacement", world, StaffSpell.ICICLE)
            val first = h.zombie(world, .5, 12.0)
            h.zombie(world, 1.5, 20.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            h.scheduler.tick(4)
            first.remove()
            every { anyConstructed<WorldMock>().isChunkLoaded(any<Int>(), any<Int>()) } answers {
                secondArg<Int>() < 1
            }
            h.scheduler.tick(60)
            h.hits shouldBe emptyList()
            h.projectileMoves.all { it.at.z < 16.0 } shouldBe true
        }
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("icicle-caster-world")
            val player = h.player("icicle-caster-world", world, StaffSpell.ICICLE)
            h.zombie(world, .5, 12.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            h.scheduler.tick(6)
            val moves = h.projectileMoves.size
            player.teleport(Location(h.paper.addSimpleWorld("icicle-caster-new-world"), .5, 64.0, .5))
            h.controller.onWorldChange(PlayerChangedWorldEvent(player, world))
            h.scheduler.tick(60)
            h.projectileMoves.size shouldBe moves
            h.hits shouldBe emptyList()
        }
    }

    "zero charge owns no volley and denied damage does not apply slow" {
        withStaffHarness(hit = { _, _ -> false }) { h ->
            val world = h.paper.addSimpleWorld("icicle-no-hit")
            val player = h.player("icicle-no-hit", world, StaffSpell.ICICLE)
            val target = h.zombie(world, .5, 12.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(20)
            h.visualPlays shouldBe emptyList()
            h.captures shouldBe emptyList()
            h.projectileMoves shouldBe emptyList()

            h.controller.cast(player, StaffCastInput.PRIMARY, .6)
            h.scheduler.tick(60)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            target.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
        }
    }
})
