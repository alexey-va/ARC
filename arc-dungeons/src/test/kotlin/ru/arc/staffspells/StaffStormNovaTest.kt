package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import org.mockbukkit.mockbukkit.world.WorldMock
import org.bukkit.Location
import org.bukkit.entity.LightningStrike
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.event.player.PlayerQuitEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class StaffStormNovaTest : FreeSpec({
    "CHAIN nova hits rear and side targets as the radial front arrives, once, while cover blocks" {
        withStaffHarness(settings = staffSettings(range = 12.0, chainTargets = 4)) { h ->
            val world = h.paper.addSimpleWorld("staff-chain-nova")
            for (y in 64..66) world.getBlockAt(0, y, 3).type = Material.STONE
            val player = h.player("staff-chain-nova", world, StaffSpell.CHAIN).apply { isSneaking = true }
            val rear = h.zombie(world, x = 0.5, z = -2.5)
            val side = h.zombie(world, x = 3.5, z = 0.5)
            val covered = h.zombie(world, x = 0.5, z = 5.5)
            val edge = h.zombie(world, x = 8.5, z = 0.5)
            val outside = h.zombie(world, x = 9.5, z = 0.5)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(4)
            h.hits shouldBe emptyList()
            h.scheduler.tick(4)
            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(rear.uniqueId, side.uniqueId)
            h.hits.size shouldBe 2
            (covered in h.hits) shouldBe false
            h.damageScales.all { it.first == 0.75 && it.second == 4.5 } shouldBe true
            h.projectileMoves shouldBe emptyList()

            h.scheduler.tick(4)
            h.hits.size shouldBe 2
            h.scheduler.tick(2)
            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(rear.uniqueId, side.uniqueId, edge.uniqueId)
            (outside in h.hits) shouldBe false
            h.scheduler.tick(10)
            h.hits.size shouldBe 3
            world.getBlockAt(0, 64, 3).type shouldBe Material.STONE
        }
    }

    "sky strikes follow the contact front and use only silent effect lightning before cancellation" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-chain-native-sky")
            val player = h.player("staff-chain-native-sky", world, StaffSpell.CHAIN).apply { isSneaking = true }
            val strikes = mutableListOf<Pair<Location, LightningStrike>>()
            every { anyConstructed<WorldMock>().strikeLightningEffect(any()) } answers {
                mockk<LightningStrike>(relaxed = true).also { strikes += firstArg<Location>().clone() to it }
            }
            h.controller.start()
            h.controller.cast(player)
            h.scheduler.tick(4)
            strikes.size shouldBe 0
            h.scheduler.tick(2)
            strikes.size shouldBe 3
            strikes.forEach { (_, lightning) ->
                verify(exactly = 1) { lightning.isSilent = true }
                verify(exactly = 1) { lightning.flashes = 0 }
            }
            verify(exactly = 0) { anyConstructed<WorldMock>().strikeLightning(any()) }
            h.controller.onQuit(PlayerQuitEvent(player, Component.empty()))
            h.scheduler.tick(20)
            strikes.size shouldBe 3
        }
        val limits = MutableList(STAFF_STORM_NOVA_RAY_COUNT) { 8.0 }
        val early = staffStormNovaStrikes(6, 8.0, limits)
        val late = staffStormNovaStrikes(14, 8.0, limits)
        early.size shouldBe 3
        late.size shouldBe 3
        (early.maxOf { it.length() } < late.minOf { it.length() }) shouldBe true
        limits[0] = .5
        staffStormNovaStrikes(6, 8.0, limits).size shouldBe 2
        staffStormNovaStrikes(8, 8.0, limits) shouldBe emptyList()
    }

    "CHAIN nova clips its bounded native-particle branches to neighboring cover rays" {
        val limits = MutableList(STAFF_STORM_NOVA_RAY_COUNT) { 8.0 }
        limits[0] = 1.5
        val frame = staffStormNovaFrame(ageTicks = 14, radius = 8.0, rayLimits = limits)
        val banned = setOf(
            Particle.CLOUD,
            Particle.GUST,
            Particle.GUST_EMITTER_LARGE,
            Particle.GUST_EMITTER_SMALL,
            Particle.EXPLOSION,
            Particle.EXPLOSION_EMITTER,
            Particle.FLASH,
        )

        (frame.size <= 192) shouldBe true
        frame.isNotEmpty() shouldBe true
        frame.all { sample ->
            sample.particle !in banned && sample.offset.x.isFinite() && sample.offset.y.isFinite() &&
                sample.offset.z.isFinite() && hypot(sample.offset.x, sample.offset.z) <= 8.0 + 1.0e-9
        } shouldBe true
        val coveredSector = frame.filter { sample -> abs(atan2(sample.offset.z, sample.offset.x)) <= 0.28 }
        coveredSector.isNotEmpty() shouldBe true
        coveredSector.all { hypot(it.offset.x, it.offset.z) <= 1.5 + 1.0e-9 } shouldBe true
    }

    "CHAIN nova reach obeys a shorter global range" {
        withStaffHarness(settings = staffSettings(range = 4.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-chain-nova-short-range")
            val player = h.player("staff-chain-nova-short-range", world, StaffSpell.CHAIN).apply { isSneaking = true }
            val inside = h.zombie(world, x = 4.5, z = 0.5)
            val outside = h.zombie(world, x = 5.5, z = 0.5)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(14)

            h.hits shouldBe listOf(inside)
            (outside in h.hits) shouldBe false
        }
    }

    "quitting during nova buildup cancels its pending wave" {
        withStaffHarness(settings = staffSettings(range = 12.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-chain-nova-cancel")
            val player = h.player("staff-chain-nova-cancel", world, StaffSpell.CHAIN).apply { isSneaking = true }
            h.zombie(world, x = 0.5, z = 4.0)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(2)
            h.controller.onQuit(PlayerQuitEvent(player, Component.empty()))
            h.scheduler.tick(20)

            h.hits shouldBe emptyList()
            verify(exactly = 1) { h.effects.cancel(player.uniqueId) }
        }
    }
})
