package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.potion.PotionEffectType
import java.util.UUID

class StaffRootGaleBehaviorTest : FreeSpec({
    "NOVA fan sweeps once and each accepted victim keeps its own seventy-tick hold" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("nova-fan-contact")
            for (x in -12..12) for (z in 0..20) world.getBlockAt(x, 63, z).type = Material.STONE
            val player = h.player("nova-fan-contact", world, StaffSpell.NOVA)
            val near = h.zombie(world, .5, 4.0)
            val far = h.zombie(world, .5, 16.5)
            val outside = h.zombie(world, 7.5, 8.5)
            val above = h.zombie(world, .5, 10.0).apply { teleport(location.add(0.0, 4.0, 0.0)) }
            val covered = h.zombie(world, 4.5, 12.5)
            for (x in 3..5) for (y in 64..67) world.getBlockAt(x, y, 11).type = Material.STONE
            fun tick(count: Int) = repeat(count) { h.paper.performTicks(1); h.scheduler.tick(1) }
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            tick(2)
            h.hits shouldBe emptyList()
            tick(2)
            h.hits shouldBe listOf(near)
            tick(10)
            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(near.uniqueId, far.uniqueId)
            listOf(outside, above, covered).none { it in h.hits } shouldBe true
            tick(62)
            near.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            far.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 7
            h.hits.count { it == near } shouldBe 1
            h.hits.count { it == far } shouldBe 1
            tick(10)
            far.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
        }
    }

    "NOVA does not bridge missing ground and never wraps rejected damage" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("nova-floor-gap")
            for (x in -12..12) for (z in 0..20) world.getBlockAt(x, 63, z).type = Material.STONE
            for (x in -2..2) for (z in 6..8) world.getBlockAt(x, 63, z).type = Material.AIR
            val player = h.player("nova-floor-gap", world, StaffSpell.NOVA)
            h.zombie(world, .5, 12.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(30)
            h.hits shouldBe emptyList()
        }
        withStaffHarness(hit = { _, target -> target.location.x < 1.0 }) { h ->
            val world = h.paper.addSimpleWorld("nova-accepted-wrap")
            for (x in -6..6) for (z in 0..16) world.getBlockAt(x, 63, z).type = Material.STONE
            val player = h.player("nova-accepted-wrap", world, StaffSpell.NOVA)
            val accepted = h.zombie(world, .5, 8.0)
            val rejected = h.zombie(world, 2.0, 8.0)
            val scenes = mutableListOf<Pair<UUID, (Int) -> List<StaffDisplayPart>>>()
            every { h.effects.custom(any(), any(), any(), any(), any()) } answers {
                UUID.randomUUID().also { scenes += it to arg<(Int) -> List<StaffDisplayPart>>(4) }
            }
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(12)
            h.hits shouldBe emptyList()
            scenes.size shouldBe 1
            h.scheduler.tick(2)
            scenes.size shouldBe 2
            accepted.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 7
            rejected.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            val wrap = scenes.last()
            wrap.second(0).all { it.identity!!.startsWith("root:wrap:${accepted.uniqueId}:") } shouldBe true
            wrap.second(0).size shouldBe 6
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.GALE))
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(10) // Wait out the normal global cooldown before the third admitted attack.
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.PRISM))
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.visualPlays.count { it.spell in setOf(StaffSpell.GALE, StaffSpell.PRISM) } shouldBe 2
            (scenes.first().first in h.removedEffects) shouldBe true
            (wrap.first in h.removedEffects) shouldBe false
            wrap.second(4).size shouldBe 6
        }
    }

    "GALE secondary follows upward aim into a roof target and bursts at airborne contact" {
        withStaffHarness(settings = staffSettings(range = 48.0)) { h ->
            val world = h.paper.addSimpleWorld("gale-roof-target")
            for (x in -3..3) for (z in 0..30) world.getBlockAt(x, 63, z).type = Material.STONE
            for (x in -2..2) for (z in 13..17) world.getBlockAt(x, 69, z).type = Material.STONE
            val player = h.player("gale-roof-target", world, StaffSpell.GALE)
            val target = h.zombie(world, .5, 15.5).apply { teleport(location.add(0.0, 6.0, 0.0)) }
            val direction = center(target).toVector().subtract(player.eyeLocation.toVector()).normalize()
            player.teleport(player.location.setDirection(direction))
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, .5)
            val scene = h.visualPlays.single { it.spell == StaffSpell.GALE }
            (scene.to.y - scene.from.y > 10.0) shouldBe true
            val impact = slot<Location>()
            h.scheduler.tick(60)
            h.hits.count { it == target } shouldBe 1
            verify(exactly = 1) { h.effects.impact(scene.id, capture(impact), any(), any()) }
            (impact.captured.y > 69.5) shouldBe true
            (impact.captured.z in 12.0..16.0) shouldBe true
            (h.movedEffects.filter { it.first == scene.id }.maxOf { it.second.y } > 69.5) shouldBe true
        }
    }

    "GALE tornado moves continuously and its damage follows the moving center" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("gale-wander-contact")
            for (x in -8..8) for (z in 0..20) world.getBlockAt(x, 63, z).type = Material.STONE
            val player = h.player("gale-wander-contact", world, StaffSpell.GALE)
            val anchor = h.zombie(world, .5, 12.0)
            val onRoute = h.zombie(world, 4.7, 11.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            val scene = h.visualPlays.single { it.spell == StaffSpell.GALE }
            h.scheduler.tick(44)
            val positions = h.movedEffects.filter { it.first == scene.id }.map { it.second }
            (positions.maxOf { it.distance(scene.from) } > 1.5) shouldBe true
            positions.zipWithNext().all { (a, b) -> a.distance(b) <= 1.051 } shouldBe true
            (onRoute.location.distance(scene.from) > 4.0) shouldBe true
            h.hits.count { it == anchor } shouldBe 1
            h.hits.count { it == onRoute } shouldBe 1
        }
    }

    "Arsenal keeps a target whose center is covered but whose head is exposed" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("arsenal-exposed-head")
            for (x in -3..3) for (z in 0..14) world.getBlockAt(x, if (z < 7) 63 else 62, z).type = Material.STONE
            for (x in -2..2) world.getBlockAt(x, 64, 6).type = Material.STONE
            val player = h.player("arsenal-exposed-head", world, StaffSpell.GALE)
            val target = h.zombie(world, .5, 10.0).apply { teleport(location.subtract(0.0, 1.0, 0.0)) }
            player.teleport(player.location.setDirection(center(target).toVector().subtract(player.eyeLocation.toVector())))
            visible(player.eyeLocation, center(target)) shouldBe false
            visible(player.eyeLocation, target) shouldBe true
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.visualPlays.count { it.spell == StaffSpell.GALE } shouldBe 1
        }
    }
})
