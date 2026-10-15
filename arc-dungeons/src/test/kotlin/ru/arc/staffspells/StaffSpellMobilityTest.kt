package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.Vector
import ru.arc.staffspells.api.StaffSpellDuelContext
import java.util.UUID

class StaffSpellMobilityTest : FreeSpec({
    "physical releases cap horizontal displacement at the authored range" {
        val world = mockk<org.bukkit.World>()
        val anchor = Location(world, 0.0, 64.0, 0.0)
        val start = Location(world, 0.0, 64.0, 0.0)
        val capped = staffMobilityBoundVelocity(anchor, start, Vector(2.0, 0.4, 0.0), 1.5)

        capped?.x shouldBe 1.5
        capped?.y shouldBe 0.4
        capped?.z shouldBe 0.0
        staffMobilityBoundVelocity(anchor, Location(world, 1.5, 64.0, 0.0), Vector(0.2, 0.4, 0.0), 1.5) shouldBe null
    }

    "all available staves have bounded profiles and distinct runtime silhouettes" {
        StaffSpell.available shouldHaveSize 16
        StaffSpell.available.forEach { spell ->
            val profile = staffMobilityProfile(spell)
            (profile != null) shouldBe true
            requireNotNull(profile).let {
                (it.durationTicks in 10..26) shouldBe true
                (it.range in 8.0..14.0) shouldBe true
                (it.forwardImpulse in 0.0..1.6) shouldBe true
                (it.verticalImpulse in 0.0..0.94) shouldBe true
                (it.steering in 0.0..0.38) shouldBe true
                (it.horizontalSpeedLimit in 0.0..1.65) shouldBe true
            }

            val direction = Vector(0.6, 0.0, 0.8)
            val frames = listOf(0, profile!!.durationTicks / 2, profile.durationTicks).map { age ->
                staffMobilityDisplayParts(spell, age, profile.durationTicks, direction)
            }
            frames.forEach { parts ->
                (parts.isNotEmpty()) shouldBe true
                (parts.size <= 16) shouldBe true
                parts.mapNotNull { it.identity }.distinct().size shouldBe parts.size
                parts.all { part ->
                    listOf(part.center.x, part.center.y, part.center.z,
                        part.scale.x, part.scale.y, part.scale.z).all(Float::isFinite) &&
                        part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f
                } shouldBe true
            }
            frames.map { parts -> parts.map { it.identity } }.distinct().size shouldBe 1
        }

        val silhouettes = StaffSpell.available.map { spell ->
            staffMobilityDisplayParts(spell, 8, 18, Vector(0.6, 0.0, 0.8)).joinToString("|") {
                "${it.material}:${it.center.x},${it.center.y},${it.center.z}:${it.scale.x},${it.scale.y},${it.scale.z}"
            }
        }
        silhouettes.distinct().size shouldBe StaffSpell.available.size
        StaffSpell.INFERNO.let { staffMobilityProfile(it) shouldBe null }

        // BLOCK particle samples carry native BlockData, which needs an initialized Bukkit runtime.
        withStaffHarness { _ ->
            StaffSpell.available.forEach { spell ->
                val burst = staffMobilityParticleBurst(spell)
                (burst.size in 1..3) shouldBe true
                burst.all { it.count in 1..3 &&
                    listOf(it.offset.x, it.offset.y, it.offset.z).all(Double::isFinite) } shouldBe true
            }
        }
    }

    "physical rush rejects a blocked sweep without moving the caster" {
        val settings = staffSettings().copy(aimAssist = StaffAimAssistSettings(enabled = false))
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-mobility-body-collision")
            val player = h.player("staff-mobility-body-collision", world, StaffSpell.FAULT)
            val wall = (64..65).map { y -> world.getBlockAt(0, y, 1).apply { type = Material.STONE } }
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val effects = mockk<StaffSpellDisplayEffects>(relaxed = true)
            val visuals = mockk<StaffSpellVisuals>(relaxed = true)
            val mobility = StaffSpellMobility(settings, damage, effects, visuals, StaffSpellTargeting(settings, damage))
            val velocityBefore = player.velocity.clone()
            mobility.start()

            mobility.cast(player, StaffSpell.FAULT,
                StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false),
                StaffSpellTuning(1.0, 1.0, 160)) shouldBe false
            player.velocity shouldBe velocityBefore
            wall.all { it.type == Material.STONE } shouldBe true

            mobility.close()
        }
    }

    "duel policy can stop a running mobility impulse at the next bounded step" {
        val settings = staffSettings().copy(aimAssist = StaffAimAssistSettings(enabled = false))
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-mobility-duel-boundary")
            val player = h.player("staff-mobility-duel-boundary", world, StaffSpell.FAULT)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val access = mockk<StaffSpellDuelAccess>()
            val context = mockk<StaffSpellDuelContext>(relaxed = true)
            val authorization = DuelSpellAuthorization(UUID.randomUUID(), context)
            var allowedSteps = 1
            every { access.inputAllowed(player) } returns true
            every { access.mobilityAllowed(player, authorization, any()) } answers { allowedSteps-- > 0 }
            val scene = UUID.randomUUID()
            val effects = mockk<StaffSpellDisplayEffects>(relaxed = true)
            every { effects.custom(any(), any(), any(), any(), any(), any()) } returns scene
            every { effects.custom(any(), any(), any(), any(), any()) } answers { UUID.randomUUID() }
            val visuals = mockk<StaffSpellVisuals>(relaxed = true)
            val mobility = StaffSpellMobility(settings, damage, effects, visuals, StaffSpellTargeting(settings, damage))
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false,
                authorization, access)
            mobility.start()

            mobility.cast(player, StaffSpell.FAULT, cast, StaffSpellTuning(1.0, 1.0, 160)) shouldBe true
            (player.velocity.z > 0.0) shouldBe true
            h.scheduler.tick(1)

            player.velocity.x shouldBe 0.0
            player.velocity.z shouldBe 0.0
            verify(exactly = 2) { access.mobilityAllowed(player, authorization, any()) }
            verify(exactly = 1) { effects.remove(scene) }
            mobility.close()
        }
    }

    "running duel mobility rechecks the cast lease and stops horizontal carry when it expires" {
        val settings = staffSettings().copy(aimAssist = StaffAimAssistSettings(enabled = false))
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-mobility-duel-cast-expiry")
            val player = h.player("staff-mobility-duel-cast-expiry", world, StaffSpell.FAULT)
            val damage = mockk<StaffSpellDamage>()
            var current = true
            every { damage.isCurrent(any(), any()) } answers { current }
            val access = mockk<StaffSpellDuelAccess>()
            val context = mockk<StaffSpellDuelContext>(relaxed = true)
            val authorization = DuelSpellAuthorization(UUID.randomUUID(), context)
            every { access.inputAllowed(player) } returns true
            every { access.mobilityAllowed(player, authorization, any()) } returns true
            val effects = mockk<StaffSpellDisplayEffects>(relaxed = true)
            val scene = UUID.randomUUID()
            every { effects.custom(any(), any(), any(), any(), any(), any()) } returns scene
            every { effects.custom(any(), any(), any(), any(), any()) } answers { UUID.randomUUID() }
            val visuals = mockk<StaffSpellVisuals>(relaxed = true)
            val mobility = StaffSpellMobility(settings, damage, effects, visuals, StaffSpellTargeting(settings, damage))
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false,
                authorization, access)
            mobility.start()

            mobility.cast(player, StaffSpell.FAULT, cast, StaffSpellTuning(1.0, 1.0, 160)) shouldBe true
            (player.velocity.z > 0.0) shouldBe true
            current = false
            h.scheduler.tick(1)

            player.velocity.x shouldBe 0.0
            player.velocity.z shouldBe 0.0
            verify(exactly = 2) { damage.isCurrent(player, cast) }
            verify(exactly = 1) { effects.remove(scene) }
            mobility.close()
        }
    }

    "normal mobility expiry removes its horizontal carry but preserves fall and sideways motion" {
        val settings = staffSettings().copy(aimAssist = StaffAimAssistSettings(enabled = false))
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-mobility-normal-expiry")
            val player = h.player("staff-mobility-normal-expiry", world, StaffSpell.FAULT)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val scene = UUID.randomUUID()
            val effects = mockk<StaffSpellDisplayEffects>(relaxed = true)
            every { effects.custom(any(), any(), any(), any(), any(), any()) } returns scene
            every { effects.custom(any(), any(), any(), any(), any()) } answers { UUID.randomUUID() }
            val visuals = mockk<StaffSpellVisuals>(relaxed = true)
            val mobility = StaffSpellMobility(settings, damage, effects, visuals, StaffSpellTargeting(settings, damage))
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val duration = requireNotNull(staffMobilityProfile(StaffSpell.FAULT)).durationTicks
            mobility.start()

            mobility.cast(player, StaffSpell.FAULT, cast, StaffSpellTuning(1.0, 1.0, 160)) shouldBe true
            (player.velocity.z > 0.0) shouldBe true
            h.scheduler.tick((duration - 1).toLong())

            // Simulate a downward fall and unrelated lateral motion at the final active tick.
            player.velocity = Vector(0.45, -0.28, 0.72)
            h.scheduler.tick(1)

            player.velocity.x shouldBe 0.45
            player.velocity.y shouldBe -0.28
            player.velocity.z shouldBe 0.0
            verify(exactly = 1) { effects.remove(scene) }
            mobility.close()
        }
    }

    "blink chooses a shorter supported landing if the far edge is obstructed" {
        val settings = staffSettings().copy(aimAssist = StaffAimAssistSettings(enabled = false))
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-mobility-blink-landing")
            val player = h.player("staff-mobility-blink-landing", world, StaffSpell.MARK)
            // Keep the center ray in block x=0 while the player's right edge reaches x=1.
            player.teleport(Location(world, 0.75, 64.0, 0.5, player.location.yaw, player.location.pitch))
            for (x in -1..1) for (z in 0..13) world.getBlockAt(x, 63, z).type = Material.STONE
            // The side overhang is outside the downward center ray but intersects a player's width at range.
            val ceiling = world.getBlockAt(1, 65, 12)
            ceiling.type = Material.STONE
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val scene = UUID.randomUUID()
            val effects = mockk<StaffSpellDisplayEffects>(relaxed = true)
            every { effects.custom(any(), any(), any(), any(), any(), any()) } returns scene
            every { effects.custom(any(), any(), any(), any(), any()) } answers { UUID.randomUUID() }
            val visuals = mockk<StaffSpellVisuals>(relaxed = true)
            val mobility = StaffSpellMobility(settings, damage, effects, visuals, StaffSpellTargeting(settings, damage))
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            mobility.start()

            val origin = player.location.clone()
            mobility.cast(player, StaffSpell.MARK, cast, StaffSpellTuning(1.0, 1.0, 160)) shouldBe true
            (player.location.z in 11.4..11.6) shouldBe true
            (player.location.distance(origin) <= requireNotNull(staffMobilityProfile(StaffSpell.MARK)).range) shouldBe true

            val blockedPlayer = h.player("staff-mobility-blink-ceiling", world, StaffSpell.MARK)
            blockedPlayer.teleport(Location(world, 0.75, 64.0, 0.5))
            for (z in 2..13) world.getBlockAt(1, 65, z).type = Material.STONE
            mobility.cast(blockedPlayer, StaffSpell.MARK,
                StaffSpellCast(blockedPlayer.uniqueId, UUID.randomUUID(), 1, null, null, false),
                StaffSpellTuning(1.0, 1.0, 160)) shouldBe false
            blockedPlayer.location.z shouldBe 0.5
            (2..13).all { world.getBlockAt(1, 65, it).type == Material.STONE } shouldBe true
            mobility.cancel(player.uniqueId)
            mobility.close()
            verifyRemove(effects, scene)
        }
    }
})

private fun verifyRemove(effects: StaffSpellDisplayEffects, scene: UUID) {
    io.mockk.verify(exactly = 1) { effects.remove(scene) }
}
