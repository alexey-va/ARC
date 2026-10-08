package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.potion.PotionEffectType
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.config.TestConfig
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.util.CooldownManager
import java.util.UUID

class StaffSpellsTest : FreeSpec({
    "staff identity stays on its ARC marker and right-click air still consumes the prototype" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-input")
            val player = h.player("staff-input", world, StaffSpell.CHAIN)
            val target = h.zombie(world, x = 0.5, z = 4.0)
            h.controller.start()

            StaffSpell.from(h.config.item(StaffSpell.CHAIN)) shouldBe StaffSpell.CHAIN
            StaffSpell.from(ItemStack(Material.STICK)) shouldBe null

            player.inventory.setItemInMainHand(ItemStack(Material.STICK))
            h.controller.onInteract(interact(player, player.inventory.itemInMainHand))
            h.hits.map { it.uniqueId } shouldBe emptyList()

            val prototype = h.config.item(StaffSpell.CHAIN)
            player.inventory.setItemInMainHand(prototype)
            val airClick = interact(player, prototype)
            airClick.useInteractedBlock() shouldBe Event.Result.DENY
            airClick.useItemInHand() shouldBe Event.Result.DEFAULT
            h.controller.onInteract(airClick)

            h.hits shouldBe emptyList()
            h.scheduler.tick(60)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            airClick.useItemInHand() shouldBe Event.Result.DENY
            airClick.useInteractedBlock() shouldBe Event.Result.DENY

            val explicitlyDeniedPlayer = h.player("staff-denied", world, StaffSpell.CHAIN)
            val explicitlyDenied = interact(explicitlyDeniedPlayer, explicitlyDeniedPlayer.inventory.itemInMainHand).apply {
                setUseItemInHand(Event.Result.DENY)
            }
            h.controller.onInteract(explicitlyDenied)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
        }
    }

    "a later missing skin leaves a multi-spell grant untouched" {
        val config = spyk(StaffSpellConfig(TestConfig()))
        every { config.item(StaffSpell.FROST) } throws
            StaffSpellSkinUnavailableException("missing:frost_staff", IllegalStateException("skin not registered"))

        withStaffHarness(config = config) { h ->
            val player = h.paper.addPlayer("missing-skin")
            player.inventory.setItem(0, ItemStack(Material.DIAMOND))
            val before = player.inventory.storageContents.map { it?.clone() }

            h.controller.give(player, listOf(StaffSpell.CHAIN, StaffSpell.FROST)) shouldBe false

            player.inventory.storageContents.toList() shouldBe before
        }
    }

    "all six prototype items can be granted without optional ItemsAdder skins" {
        withStaffHarness { h ->
            val player = h.paper.addPlayer("all-prototypes")

            h.controller.give(player, StaffSpell.entries) shouldBe true

            player.inventory.storageContents.filterNotNull().mapNotNull { StaffSpell.from(it) } shouldBe
                StaffSpell.entries
        }
    }

    "auto-aim follows a moved off-axis mob and excludes mobs behind or beyond range" {
        withStaffHarness(settings = staffSettings(range = 48.0, aimDegrees = 20.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-aim")
            val player = h.player("staff-aim", world, StaffSpell.CHAIN)
            val moving = h.zombie(world, x = 2.0, z = 40.0)
            h.zombie(world, x = 0.5, z = -3.0)
            h.zombie(world, x = 0.5, z = 52.0)
            val castOrigin = player.eyeLocation.clone()
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(1)
            moving.teleport(Location(world, 3.0, 64.0, 41.0))
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(moving.uniqueId)
            h.trailMoves.any { move ->
                move.points.size in 2..17 && move.points.last().distance(moving.location.clone().add(0.0, 1.0, 0.0)) < 1.5
            } shouldBe true
            h.trailMoves.all { it.points.size <= 17 } shouldBe true
            h.trailMoves.last().points.size shouldBe 17
            h.trailMoves.all { move ->
                (move.points.size == 17 || move.points.first().distance(castOrigin) < 0.001) &&
                    move.points.zipWithNext().all { (from, to) -> from.distance(to) <= 2.001 }
            } shouldBe true
            h.finishedTrails shouldBe listOf(h.visualPlays.single().id)
            h.trailMoves.map { it.id }.distinct() shouldBe listOf(h.visualPlays.single().id)
            h.removedEffects.filterNotNull() shouldBe emptyList()
        }
    }

    "a solid wall blocks an otherwise valid direct target" {
        withStaffHarness(settings = staffSettings(range = 8.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-wall")
            val player = h.player("staff-wall", world, StaffSpell.CHAIN)
            val target = h.zombie(world, x = 0.5, z = 8.0)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(1)
            for (x in -2..2) for (y in 63..67) world.getBlockAt(x, y, 3).type = Material.STONE
            h.scheduler.tick(12)

            h.hits.map { it.uniqueId } shouldBe emptyList()
            target.isValid shouldBe true
        }
    }

    "frost applies slow only when damage changes the target" {
        val cancelledTargets = mutableSetOf<UUID>()
        withStaffHarness(
            settings = staffSettings(frostRange = 7.0, frostDegrees = 50.0),
            hit = { _, target -> target.uniqueId !in cancelledTargets },
        ) { h ->
            val world = h.paper.addSimpleWorld("staff-frost")
            val player = h.player("staff-frost", world, StaffSpell.FROST)
            val cancelled = h.zombie(world, x = 0.5, z = 4.0)
            val landed = h.zombie(world, x = 0.5, z = 5.0)
            cancelledTargets += cancelled.uniqueId
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(8)

            cancelled.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            landed.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            h.hits.map { it.uniqueId } shouldBe listOf(cancelled.uniqueId)

            h.scheduler.tick(2)
            landed.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe true
            h.hits.map { it.uniqueId } shouldBe listOf(cancelled.uniqueId, landed.uniqueId)
            h.scheduler.tick(12)
            h.hits.map { it.uniqueId }.distinct().size shouldBe h.hits.size
        }
    }

    "frost wave rechecks sight at its front and does not admit late arrivals" {
        withStaffHarness(settings = staffSettings(frostRange = 7.0, frostDegrees = 50.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-frost-wall")
            val player = h.player("staff-frost-wall", world, StaffSpell.FROST)
            h.zombie(world, x = 0.5, z = 4.5)
            h.controller.start()

            h.controller.cast(player)
            val lateArrival = h.zombie(world, x = 0.5, z = 4.0)
            for (y in 64..66) world.getBlockAt(0, y, 2).type = Material.STONE
            h.scheduler.tick(20)

            h.hits shouldBe emptyList()
            lateArrival.isValid shouldBe true
        }
    }

    "frost side-angle damage arrives by radial distance rather than forward projection" {
        withStaffHarness(settings = staffSettings(frostRange = 7.0, frostDegrees = 50.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-frost-side")
            val player = h.player("staff-frost-side", world, StaffSpell.FROST)
            val diagonal = h.zombie(world, x = 2.975, z = 2.975)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(6)
            h.hits shouldBe emptyList()

            h.scheduler.tick(2)
            h.hits.map { it.uniqueId } shouldBe listOf(diagonal.uniqueId)
            h.scheduler.tick(16)
            h.hits.map { it.uniqueId }.distinct().size shouldBe 1
        }
    }

    "all staff spells share cooldown across an item swap" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-cooldown")
            val player = h.player("staff-cooldown", world, StaffSpell.CHAIN)
            val target = h.zombie(world, x = 0.5, z = 4.0)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(60)
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.FROST))
            h.controller.cast(player)

            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
        }
    }

    "all six spells cast into empty space and use the shared cooldown" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-empty-casts")
            val player = h.player("staff-empty-casts", world, StaffSpell.CHAIN)
            val spells = StaffSpell.entries
            h.controller.start()

            spells.forEachIndexed { index, spell ->
                player.inventory.setItemInMainHand(h.config.item(spell))
                h.controller.cast(player)
                h.captures.size shouldBe index + 1

                player.inventory.setItemInMainHand(h.config.item(spells[(index + 1) % spells.size]))
                h.controller.cast(player)
                h.captures.size shouldBe index + 1
                h.scheduler.tick(20)
            }

            h.hits shouldBe emptyList()
        }
    }

    "manual lance and ember do not acquire an off-axis mob" {
        withStaffHarness(settings = staffSettings(range = 10.0)) { h ->
            h.controller.start()
            for (spell in listOf(StaffSpell.LANCE, StaffSpell.EMBER)) {
                val world = h.paper.addSimpleWorld("staff-manual-${spell.id}")
                val player = h.player("staff-manual-${spell.id}", world, spell)
                // Within the 12-degree soft aim cone, but outside the manual beam/projectile path.
                h.zombie(world, x = 1.5, z = 7.0)

                h.controller.cast(player)
                if (spell == StaffSpell.EMBER) h.scheduler.tick(12)

                h.hits shouldBe emptyList()
            }
        }
    }

    "lance pierces up to its target limit" {
        withStaffHarness(settings = staffSettings(lanceTargets = 3)) { h ->
            val world = h.paper.addSimpleWorld("staff-lance-pierce")
            val player = h.player("staff-lance-pierce", world, StaffSpell.LANCE)
            val first = h.zombie(world, x = 0.5, z = 4.0)
            val second = h.zombie(world, x = 0.5, z = 6.0)
            val third = h.zombie(world, x = 0.5, z = 8.5)
            val beyondTargetLimit = h.zombie(world, x = 0.5, z = 11.0)

            h.controller.cast(player)

            h.hits.map { it.uniqueId } shouldBe listOf(first.uniqueId, second.uniqueId, third.uniqueId)
            beyondTargetLimit.isValid shouldBe true
        }
    }

    "lance wall collision stops the beam while target budget remains" {
        withStaffHarness(settings = staffSettings(lanceTargets = 8)) { h ->
            val world = h.paper.addSimpleWorld("staff-lance-wall")
            val player = h.player("staff-lance-wall", world, StaffSpell.LANCE)
            val inFront = h.zombie(world, x = 0.5, z = 4.0)
            val behindWall = h.zombie(world, x = 0.5, z = 12.0)
            for (x in -1..1) for (y in 64..67) world.getBlockAt(x, y, 10).type = Material.STONE

            h.controller.cast(player)

            h.hits.map { it.uniqueId } shouldBe listOf(inFront.uniqueId)
            behindWall.isValid shouldBe true
        }
    }

    "ember swept segment hits a mob and quit cancels a pending projectile" {
        withStaffHarness(settings = staffSettings(range = 10.0, emberSpeed = 2.5)) { h ->
            h.controller.start()
            val hitWorld = h.paper.addSimpleWorld("staff-ember-hit")
            val player = h.player("staff-ember-hit", hitWorld, StaffSpell.EMBER)
            val target = h.zombie(hitWorld, x = 0.5, z = 3.2)

            h.controller.cast(player)
            h.scheduler.tick(1)

            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            verify(exactly = 1) { h.effects.impact(any(), any(), any(), any()) }

            h.hits.clear()
            val wallWorld = h.paper.addSimpleWorld("staff-ember-wall")
            val wallPlayer = h.player("staff-ember-wall", wallWorld, StaffSpell.EMBER)
            h.zombie(wallWorld, x = 0.5, z = 7.0)
            for (x in -1..1) for (y in 64..67) wallWorld.getBlockAt(x, y, 3).type = Material.STONE

            h.controller.cast(wallPlayer)
            h.scheduler.tick(1)

            h.hits shouldBe emptyList()

            h.hits.clear()
            val cancelledWorld = h.paper.addSimpleWorld("staff-ember-quit")
            val quitting = h.player("staff-ember-quit", cancelledWorld, StaffSpell.EMBER)
            h.zombie(cancelledWorld, x = 0.5, z = 3.2)
            h.controller.cast(quitting)
            h.controller.onQuit(PlayerQuitEvent(quitting, Component.empty()))
            h.scheduler.tick(10)

            h.hits shouldBe emptyList()
            verify(exactly = 1) { h.effects.cancel(quitting.uniqueId) }

            val flightWorld = h.paper.addSimpleWorld("staff-ember-flight")
            val flying = h.player("staff-ember-flight", flightWorld, StaffSpell.EMBER)
            h.controller.cast(flying)
            h.scheduler.tick(1)
            h.movedEffects.isNotEmpty() shouldBe true
            h.controller.onQuit(PlayerQuitEvent(flying, Component.empty()))
            verify(exactly = 1) { h.effects.cancel(flying.uniqueId) }
        }
    }

    "mark without an acquired target blasts its aimed point" {
        withStaffHarness(settings = staffSettings(range = 8.0, markTicks = 4)) { h ->
            val world = h.paper.addSimpleWorld("staff-mark-point")
            val player = h.player("staff-mark-point", world, StaffSpell.MARK)
            h.controller.start()

            h.controller.cast(player)
            h.captures.size shouldBe 1
            h.hits shouldBe emptyList()
            val targetNearRangePoint = h.zombie(world, x = 0.5, z = 8.0)

            h.scheduler.tick(4)

            h.hits.map { it.uniqueId } shouldBe listOf(targetNearRangePoint.uniqueId)
        }
    }

    "nova stages an eight-block ground wave, including close mobs, and respects occlusion" {
        withStaffHarness(settings = staffSettings(novaRadius = 8.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-nova")
            val player = h.player("staff-nova", world, StaffSpell.NOVA)
            val near = h.zombie(world, x = 0.5, z = 1.0)
            val hiddenInsideRadius = h.zombie(world, x = 3.0, z = 3.0)
            val farInsideRadius = h.zombie(world, x = 0.5, z = 8.0)
            val outside = h.zombie(world, x = 0.5, z = 9.5)
            world.getBlockAt(2, 64, 2).type = Material.STONE
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(2)
            h.hits.map { it.uniqueId } shouldBe listOf(near.uniqueId)

            h.scheduler.tick(16)
            h.hits.map { it.uniqueId } shouldBe listOf(near.uniqueId, farInsideRadius.uniqueId)
            h.scheduler.tick(12)

            hiddenInsideRadius.isValid shouldBe true
            outside.isValid shouldBe true
            h.hits.map { it.uniqueId }.distinct().size shouldBe h.hits.size
        }
    }

    "staged nova keeps the existing per-cast target cap" {
        withStaffHarness(settings = staffSettings(novaRadius = 8.0, maxAreaTargets = 2)) { h ->
            val world = h.paper.addSimpleWorld("staff-nova-cap")
            val player = h.player("staff-nova-cap", world, StaffSpell.NOVA)
            val closest = h.zombie(world, x = 0.5, z = 1.0)
            val middle = h.zombie(world, x = 0.5, z = 4.0)
            h.zombie(world, x = 0.5, z = 8.0)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(30)

            h.hits.map { it.uniqueId } shouldBe listOf(closest.uniqueId, middle.uniqueId)
        }
    }

    "shift right-click launches three independent homing chain bolts without a fourth jump" {
        withStaffHarness(settings = staffSettings(range = 12.0, chainTargets = 4, aimDegrees = 20.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-chain-secondary")
            val player = h.player("staff-chain-secondary", world, StaffSpell.CHAIN).apply { isSneaking = true }
            val left = h.zombie(world, x = -2.3, z = 6.0)
            val center = h.zombie(world, x = 0.5, z = 6.0)
            val right = h.zombie(world, x = 3.3, z = 6.0)
            val fourth = h.zombie(world, x = 4.0, z = 6.0)
            h.controller.start()

            val event = interact(player, player.inventory.itemInMainHand)
            h.controller.onInteract(event)
            event.useItemInHand() shouldBe Event.Result.DENY
            h.hits shouldBe emptyList()
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(left.uniqueId, center.uniqueId, right.uniqueId)
            h.hits.size shouldBe 3
            (fourth.uniqueId in h.hits.map { it.uniqueId }) shouldBe false
        }
    }

    "secondary frost grows radially to eight blocks, including lateral targets" {
        withStaffHarness(settings = staffSettings(range = 12.0, novaRadius = 8.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-frost-secondary")
            val player = h.player("staff-frost-secondary", world, StaffSpell.FROST).apply { isSneaking = true }
            val near = h.zombie(world, x = 0.5, z = 1.5)
            val far = h.zombie(world, x = 0.5, z = 8.0)
            val lateral = h.zombie(world, x = 7.0, z = 0.5)
            val outside = h.zombie(world, x = 0.5, z = 9.5)
            h.controller.start()

            h.controller.onInteract(interact(player, player.inventory.itemInMainHand))
            h.scheduler.tick(2)
            h.hits.map { it.uniqueId } shouldBe listOf(near.uniqueId)
            h.scheduler.tick(16)

            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(near.uniqueId, far.uniqueId, lateral.uniqueId)
            (outside.uniqueId in h.hits.map { it.uniqueId }) shouldBe false
            h.visualPlays.any { it.spell == StaffSpell.FROST && it.secondary } shouldBe true
        }
    }

    "secondary lance fans three reduced-power beams and de-duplicates their hits" {
        withStaffHarness(settings = staffSettings(range = 10.0, lanceTargets = 3)) { h ->
            val world = h.paper.addSimpleWorld("staff-lance-secondary")
            val player = h.player("staff-lance-secondary", world, StaffSpell.LANCE).apply { isSneaking = true }
            val left = h.zombie(world, x = -1.65, z = 8.0)
            val center = h.zombie(world, x = 0.5, z = 8.0)
            val right = h.zombie(world, x = 2.65, z = 8.0)

            h.controller.onInteract(interact(player, player.inventory.itemInMainHand))

            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(left.uniqueId, center.uniqueId, right.uniqueId)
            h.hits.size shouldBe 3
            h.damageScales.map { it.first }.distinct() shouldBe listOf(0.65)
            h.damageScales.all { kotlin.math.abs(it.second - 3.9) < 0.000001 } shouldBe true
            h.visualPlays.count { it.spell == StaffSpell.LANCE && it.secondary } shouldBe 3
        }
    }

    "secondary nova travels forward twelve blocks inside its narrow corridor" {
        withStaffHarness(settings = staffSettings(range = 14.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-nova-secondary")
            val player = h.player("staff-nova-secondary", world, StaffSpell.NOVA).apply { isSneaking = true }
            val near = h.zombie(world, x = 0.5, z = 4.0)
            val far = h.zombie(world, x = 0.5, z = 11.5)
            val outsideWidth = h.zombie(world, x = 5.5, z = 8.0)
            val behind = h.zombie(world, x = 0.5, z = -2.0)
            val beyondRange = h.zombie(world, x = 0.5, z = 14.0)
            h.controller.start()

            h.controller.onInteract(interact(player, player.inventory.itemInMainHand))
            h.scheduler.tick(8)
            h.hits.map { it.uniqueId } shouldBe listOf(near.uniqueId)
            h.scheduler.tick(10)

            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(near.uniqueId, far.uniqueId)
            (outsideWidth.uniqueId in h.hits.map { it.uniqueId }) shouldBe false
            (behind.uniqueId in h.hits.map { it.uniqueId }) shouldBe false
            (beyondRange.uniqueId in h.hits.map { it.uniqueId }) shouldBe false
            h.visualPlays.any { it.spell == StaffSpell.NOVA && it.secondary } shouldBe true
        }
    }

    "secondary mark stays fixed, pulses four times, and only pulls after damage lands" {
        var deniedId: UUID? = null
        withStaffHarness(
            settings = staffSettings(range = 12.0),
            hit = { _, target -> target.uniqueId != deniedId },
        ) { h ->
            val world = h.paper.addSimpleWorld("staff-mark-secondary")
            val player = h.player("staff-mark-secondary", world, StaffSpell.MARK).apply { isSneaking = true }
            val original = h.zombie(world, x = 0.5, z = 5.0)
            h.controller.start()

            h.controller.onInteract(interact(player, player.inventory.itemInMainHand))
            original.teleport(Location(world, 0.5, 64.0, 12.0))
            val pulled = h.zombie(world, x = 1.0, z = 5.0)
            val denied = h.zombie(world, x = 1.3, z = 5.0)
            deniedId = denied.uniqueId

            h.scheduler.tick(7)
            h.hits shouldBe emptyList()
            h.scheduler.tick(1)
            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(pulled.uniqueId, denied.uniqueId)
            (pulled.velocity.lengthSquared() > 0.0) shouldBe true
            denied.velocity.lengthSquared() shouldBe 0.0
            h.damageScales.map { it.first }.distinct() shouldBe listOf(0.12)

            h.scheduler.tick(24)
            h.hits.count { it.uniqueId == pulled.uniqueId } shouldBe 4
            h.hits.count { it.uniqueId == denied.uniqueId } shouldBe 4
            h.hits.any { it.uniqueId == original.uniqueId } shouldBe false
            h.scheduler.tick(8)
            h.damageScales.last().first shouldBe 0.65
            h.visualPlays.any { it.spell == StaffSpell.MARK && it.secondary } shouldBe true
        }
    }

    "secondary ember staggers three reduced-power meteors between a floor and low ceiling" {
        withStaffHarness(settings = staffSettings(range = 12.0, aimDegrees = 30.0, maxAreaTargets = 8),
            rayStep = 0.1) { h ->
            val world = h.paper.addSimpleWorld("staff-ember-secondary")
            world.loadChunk(0, 0)
            world.isChunkLoaded(0, 0) shouldBe true
            val player = h.player("staff-ember-secondary", world, StaffSpell.EMBER).apply { isSneaking = true }
            for (x in 0..10) for (z in 6..15) {
                world.getBlockAt(x, 63, z).type = Material.STONE
                world.getBlockAt(x, 70, z).type = Material.STONE
            }
            val targets = (0 until 9).map { index ->
                world.spawn(Location(world, 4.5 + (index % 3) * 0.12, 64.0, 10.5 + (index / 3) * 0.12), Zombie::class.java)
            }
            h.controller.start()

            h.controller.onInteract(interact(player, player.inventory.itemInMainHand))
            h.visualPlays.count { it.spell == StaffSpell.EMBER && it.secondary } shouldBe 0
            h.scheduler.tick(1)
            h.visualPlays.count { it.spell == StaffSpell.EMBER && it.secondary } shouldBe 1
            h.scheduler.tick(6)
            h.visualPlays.count { it.spell == StaffSpell.EMBER && it.secondary } shouldBe 2
            h.scheduler.tick(6)
            h.visualPlays.count { it.spell == StaffSpell.EMBER && it.secondary } shouldBe 3
            h.visualPlays.filter { it.spell == StaffSpell.EMBER && it.secondary }
                .all { it.from.y > 64.0 && it.from.y < 70.0 } shouldBe true
            h.scheduler.tick(18)

            h.hits.size shouldBe 8
            h.hits.map { it.uniqueId }.distinct().size shouldBe 8
            h.hits.all { it in targets } shouldBe true
            h.damageScales.map { it.first }.distinct() shouldBe listOf(0.65)
        }
    }

    "secondary ember quit invalidates delayed meteor spawns" {
        withStaffHarness(settings = staffSettings(range = 10.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-ember-secondary-quit")
            val player = h.player("staff-ember-secondary-quit", world, StaffSpell.EMBER).apply { isSneaking = true }
            h.controller.start()

            h.controller.onInteract(interact(player, player.inventory.itemInMainHand))
            h.controller.onQuit(PlayerQuitEvent(player, Component.empty()))
            h.scheduler.tick(20)

            h.visualPlays shouldBe emptyList()
            h.hits shouldBe emptyList()
            verify(exactly = 1) { h.effects.cancel(player.uniqueId) }
        }
    }

    "secondary cooldowns use one and a half or two times the primary duration" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-secondary-cooldown")
            val quick = h.player("staff-secondary-quick", world, StaffSpell.FROST).apply { isSneaking = true }
            h.controller.onInteract(interact(quick, quick.inventory.itemInMainHand))
            quick.inventory.setItemInMainHand(h.config.item(StaffSpell.NOVA))
            h.scheduler.tick(20)
            h.controller.onInteract(interact(quick, quick.inventory.itemInMainHand))
            h.captures.size shouldBe 1
            h.scheduler.tick(10)
            h.controller.onInteract(interact(quick, quick.inventory.itemInMainHand))
            h.captures.size shouldBe 2

            val long = h.player("staff-secondary-long", world, StaffSpell.MARK).apply { isSneaking = true }
            h.controller.onInteract(interact(long, long.inventory.itemInMainHand))
            long.inventory.setItemInMainHand(h.config.item(StaffSpell.CHAIN))
            h.scheduler.tick(30)
            h.controller.onInteract(interact(long, long.inventory.itemInMainHand))
            h.captures.size shouldBe 3
            h.scheduler.tick(10)
            h.controller.onInteract(interact(long, long.inventory.itemInMainHand))
            h.captures.size shouldBe 4
        }
    }

    "frost waves stop on caster quit, death, world change, and controller close" {
        withStaffHarness(settings = staffSettings(frostRange = 7.0, frostDegrees = 50.0)) { h ->
            h.controller.start()
            val quitWorld = h.paper.addSimpleWorld("staff-frost-quit")
            val quitting = h.player("staff-frost-quit", quitWorld, StaffSpell.FROST)
            h.zombie(quitWorld, x = 0.5, z = 4.0)
            h.controller.cast(quitting)
            h.controller.onQuit(PlayerQuitEvent(quitting, Component.empty()))

            val deathWorld = h.paper.addSimpleWorld("staff-frost-death")
            val dying = h.player("staff-frost-death", deathWorld, StaffSpell.FROST)
            h.zombie(deathWorld, x = 0.5, z = 4.0)
            h.controller.cast(dying)
            val deathEvent = mockk<PlayerDeathEvent>()
            every { deathEvent.entity } returns dying
            h.controller.onDeath(deathEvent)

            val oldWorld = h.paper.addSimpleWorld("staff-frost-world-change")
            val newWorld = h.paper.addSimpleWorld("staff-frost-world-change-to")
            val changing = h.player("staff-frost-world-change", oldWorld, StaffSpell.FROST)
            h.zombie(oldWorld, x = 0.5, z = 4.0)
            h.controller.cast(changing)
            changing.teleport(Location(newWorld, 0.5, 64.0, 0.5))
            h.controller.onWorldChange(PlayerChangedWorldEvent(changing, oldWorld))

            verify(exactly = 1) { h.effects.cancel(quitting.uniqueId) }
            verify(exactly = 1) { h.effects.cancel(dying.uniqueId) }
            verify(exactly = 1) { h.effects.cancel(changing.uniqueId) }
            h.scheduler.tick(16)
            h.hits shouldBe emptyList()

            val closingWorld = h.paper.addSimpleWorld("staff-frost-close")
            val closing = h.player("staff-frost-close", closingWorld, StaffSpell.FROST)
            h.zombie(closingWorld, x = 0.5, z = 4.0)
            h.controller.cast(closing)
            h.controller.close()
            h.scheduler.tick(16)
            h.hits shouldBe emptyList()
        }
    }

    "chain reaches each mob once and stops when an impact is cancelled" {
        val cancelledTargets = mutableSetOf<UUID>()
        withStaffHarness(
            settings = staffSettings(chainTargets = 4),
            hit = { _, target -> target.uniqueId !in cancelledTargets },
        ) { h ->
            val world = h.paper.addSimpleWorld("staff-chain")
            val player = h.player("staff-chain", world, StaffSpell.CHAIN)
            val first = h.zombie(world, x = 0.5, z = 4.0)
            val second = h.zombie(world, x = 2.0, z = 5.0)
            val third = h.zombie(world, x = 4.0, z = 6.0)
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(first.uniqueId, second.uniqueId, third.uniqueId)
            h.hits.map { it.uniqueId }.distinct().size shouldBe h.hits.size

            h.hits.clear()
            val blockedWorld = h.paper.addSimpleWorld("staff-chain-cancelled")
            val cancelledPlayer = h.player("staff-chain-cancelled", blockedWorld, StaffSpell.CHAIN)
            val cancelled = h.zombie(blockedWorld, x = 0.5, z = 4.0)
            h.zombie(blockedWorld, x = 2.0, z = 5.0)
            cancelledTargets += cancelled.uniqueId

            h.controller.cast(cancelledPlayer)
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(cancelled.uniqueId)
        }
    }

    "marks follow a moved target and cancel for invalid, dead, quit, world change, and close" {
        withStaffHarness(settings = staffSettings(markTicks = 6)) { h ->
            val world = h.paper.addSimpleWorld("staff-mark")
            val otherWorld = h.paper.addSimpleWorld("staff-mark-other")
            h.controller.start()

            val follower = h.player("mark-follow", world, StaffSpell.MARK)
            val moving = h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(follower)
            follower.inventory.setItemInMainHand(ItemStack(Material.AIR))
            moving.teleport(Location(world, 0.5, 64.0, 6.0))
            h.scheduler.tick(6)
            h.hitLocations.single().z shouldBe 6.0
            h.movedEffects.any { it.second.z == 6.0 } shouldBe true
            verify(exactly = 1) { h.effects.impact(any(), any(), any(), any()) }

            val invalidPlayer = h.player("mark-invalid", world, StaffSpell.MARK)
            val invalidTarget = h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(invalidPlayer)
            invalidPlayer.inventory.setItemInMainHand(ItemStack(Material.AIR))
            invalidTarget.remove()

            val deadPlayer = h.player("mark-dead", world, StaffSpell.MARK)
            val deadTarget = h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(deadPlayer)
            deadPlayer.inventory.setItemInMainHand(ItemStack(Material.AIR))
            deadTarget.health = 0.0

            val dyingPlayer = h.player("mark-death", world, StaffSpell.MARK)
            h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(dyingPlayer)
            dyingPlayer.inventory.setItemInMainHand(ItemStack(Material.AIR))
            val deathEvent = mockk<PlayerDeathEvent>()
            every { deathEvent.entity } returns dyingPlayer
            h.controller.onDeath(deathEvent)

            val quitting = h.player("mark-quit", world, StaffSpell.MARK)
            h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(quitting)
            quitting.inventory.setItemInMainHand(ItemStack(Material.AIR))
            h.controller.onQuit(PlayerQuitEvent(quitting, Component.empty()))

            val changingWorld = h.player("mark-world", world, StaffSpell.MARK)
            h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(changingWorld)
            changingWorld.inventory.setItemInMainHand(ItemStack(Material.AIR))
            changingWorld.teleport(Location(otherWorld, 0.5, 64.0, 0.5))
            h.controller.onWorldChange(PlayerChangedWorldEvent(changingWorld, world))

            verify(exactly = 1) { h.effects.cancel(dyingPlayer.uniqueId) }
            verify(exactly = 1) { h.effects.cancel(quitting.uniqueId) }
            verify(exactly = 1) { h.effects.cancel(changingWorld.uniqueId) }

            val hitCountBeforeCancelledMarks = h.hits.size
            h.scheduler.tick(6)
            h.hits.size shouldBe hitCountBeforeCancelledMarks

            val closingPlayer = h.player("mark-close", world, StaffSpell.MARK)
            h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(closingPlayer)
            closingPlayer.inventory.setItemInMainHand(ItemStack(Material.AIR))
            val hitCountBeforeClose = h.hits.size
            h.controller.close()
            verify(exactly = 1) { h.effects.close() }
            h.scheduler.tick(10)
            h.hits.size shouldBe hitCountBeforeClose
        }
    }
})

private data class StaffHarness(
    val paper: MockBukkitTestRuntime,
    val scheduler: TestTaskScheduler,
    val config: StaffSpellConfig,
    val controller: StaffSpellController,
    val effects: StaffSpellDisplayEffects,
    val movedEffects: MutableList<Pair<UUID, Location>>,
    val trailMoves: MutableList<StaffTrailMove>,
    val finishedTrails: MutableList<UUID?>,
    val visualPlays: MutableList<StaffVisualPlay>,
    val removedEffects: MutableList<UUID?>,
    val captures: MutableList<Player>,
    val hits: MutableList<LivingEntity>,
    val hitLocations: MutableList<Location>,
    val damageScales: MutableList<Pair<Double, Double>>,
) {
    fun player(name: String, world: World, spell: StaffSpell): Player = paper.addPlayer(name).apply {
        val spawn = Location(world, 0.5, 64.0, 0.5).apply { setDirection(Vector(0.0, 0.0, 1.0)) }
        teleport(spawn)
        inventory.setItemInMainHand(config.item(spell))
    }

    fun zombie(world: World, x: Double, z: Double): Zombie =
        world.spawn(Location(world, x, 64.0, z), Zombie::class.java)
}

private data class StaffTrailMove(val id: UUID?, val points: List<Location>)

private data class StaffVisualPlay(
    val id: UUID,
    val spell: StaffSpell,
    val from: Location,
    val to: Location,
    val secondary: Boolean,
)

private fun withStaffHarness(
    settings: StaffSpellSettings = staffSettings(),
    hit: (Player, LivingEntity) -> Boolean = { _, _ -> true },
    rayStep: Double = 0.1,
    config: StaffSpellConfig = StaffSpellConfig(TestConfig()),
    block: (StaffHarness) -> Unit,
) {
    failOnUnsupportedMockBukkitOperation {
        val scheduler = TestTaskScheduler()
        mockkConstructor(WorldMock::class)
        try {
            every {
                anyConstructed<WorldMock>().rayTraceBlocks(
                    any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>(),
                )
            } answers {
                val start = firstArg<Location>()
                val direction = secondArg<Vector>().clone().normalize()
                val distance = thirdArg<Double>()
                val world = requireNotNull(start.world)
                val steps = (distance / rayStep).toInt().coerceAtLeast(1)
                (1..steps).firstNotNullOfOrNull { step ->
                    val hitPosition = start.toVector().add(direction.clone().multiply(distance * step / steps))
                    val block = world.getBlockAt(hitPosition.blockX, hitPosition.blockY, hitPosition.blockZ)
                    if (block.type.isAir) null else BoundingBox(
                        block.x.toDouble(), block.y.toDouble(), block.z.toDouble(),
                        block.x + 1.0, block.y + 1.0, block.z + 1.0,
                    ).rayTrace(start.toVector(), direction, distance)
                        ?.let { hit -> RayTraceResult(hit.hitPosition, block, hit.hitBlockFace ?: BlockFace.NORTH) }
                }
            }
            MockBukkitTestRuntime.open().use { paper ->
                Tasks.withScheduler(scheduler) {
                    CooldownManager.stop()
                    CooldownManager.clearAll()
                    CooldownManager.setupTask(5, scheduler)
                    val damage = mockk<StaffSpellDamage>()
                    val captures = mutableListOf<Player>()
                    val hits = mutableListOf<LivingEntity>()
                    val hitLocations = mutableListOf<Location>()
                    val damageScales = mutableListOf<Pair<Double, Double>>()
                    every { damage.eligible(any(), any()) } answers {
                        val target = secondArg<LivingEntity>()
                        target !is Player && target.isValid && !target.isDead && target.health > 0.0
                    }
                    every { damage.capture(any()) } answers {
                        val player = firstArg<Player>()
                        captures += player
                        StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
                    }
                    every { damage.hit(any(), any(), any(), any(), any()) } answers {
                        val player = firstArg<Player>()
                        val target = secondArg<LivingEntity>()
                        hits += target
                        hitLocations += target.location.clone()
                        damageScales += arg<Double>(3) to arg<Double>(4)
                        hit(player, target)
                    }
                    val effects = mockk<StaffSpellDisplayEffects>(relaxed = true)
                    val visualPlays = mutableListOf<StaffVisualPlay>()
                    every { effects.play(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                        val id = UUID.randomUUID()
                        visualPlays += StaffVisualPlay(id, arg<StaffSpell>(1), arg<Location>(2).clone(),
                            arg<Location>(3).clone(), arg<Boolean>(7))
                        id
                    }
                    val movedEffects = mutableListOf<Pair<UUID, Location>>()
                    val trailMoves = mutableListOf<StaffTrailMove>()
                    val finishedTrails = mutableListOf<UUID?>()
                    val removedEffects = mutableListOf<UUID?>()
                    every { effects.move(any(), any()) } answers {
                        movedEffects += firstArg<UUID>() to secondArg<Location>().clone()
                    }
                    every { effects.moveTrail(any(), any()) } answers {
                        trailMoves += StaffTrailMove(arg<UUID?>(0), arg<List<Location>>(1).map { it.clone() })
                    }
                    every { effects.finishTrail(any()) } answers { finishedTrails += arg<UUID?>(0) }
                    every { effects.remove(any()) } answers { removedEffects += firstArg<UUID?>() }
                    val controller = StaffSpellController(config, settings, damage, effects)
                    val harness = StaffHarness(paper, scheduler, config, controller, effects,
                        movedEffects, trailMoves, finishedTrails, visualPlays, removedEffects, captures, hits, hitLocations,
                        damageScales)
                    try {
                        block(harness)
                    } finally {
                        controller.close()
                        CooldownManager.stop()
                        CooldownManager.clearAll()
                    }
                }
            }
        } finally {
            unmockkConstructor(WorldMock::class)
        }
    }
}

private fun staffSettings(
    range: Double = 24.0,
    aimDegrees: Double = 12.0,
    chainTargets: Int = 1,
    markTicks: Int = 18,
    frostRange: Double = 7.0,
    frostDegrees: Double = 50.0,
    maxAreaTargets: Int = 8,
    lanceWidth: Double = 0.22,
    lanceTargets: Int = 3,
    emberSpeed: Double = 1.2,
    emberRadius: Double = 2.8,
    novaRadius: Double = 8.0,
) = StaffSpellSettings(
    range = range,
    aimDegrees = aimDegrees,
    chainRadius = 6.0,
    chainTargets = chainTargets,
    chainDecay = 0.7,
    markTicks = markTicks,
    markRadius = 3.5,
    frostRange = frostRange,
    frostDegrees = frostDegrees,
    frostSlowTicks = 40,
    maxAreaTargets = maxAreaTargets,
    lanceWidth = lanceWidth,
    lanceTargets = lanceTargets,
    emberSpeed = emberSpeed,
    emberRadius = emberRadius,
    novaRadius = novaRadius,
    tuning = StaffSpell.entries.associateWith { StaffSpellTuning(power = 1.0, vanillaDamage = 6.0, cooldownTicks = 20) },
)

private fun interact(player: Player, item: ItemStack) = PlayerInteractEvent(
    player,
    Action.RIGHT_CLICK_AIR,
    item,
    null,
    BlockFace.SELF,
    EquipmentSlot.HAND,
)
