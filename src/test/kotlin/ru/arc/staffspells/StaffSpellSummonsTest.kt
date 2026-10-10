package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.AbstractSkeleton
import org.bukkit.entity.Skeleton
import org.bukkit.entity.WitherSkeleton
import org.bukkit.event.player.PlayerQuitEvent
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.SkeletonMock
import org.mockbukkit.mockbukkit.entity.WitherSkeletonMock
import java.util.UUID

class StaffSpellSummonsTest : FreeSpec({
    "primary summons a native squad and waits through its attack windup" {
        withStaffHarness(summonActorFactory = ::spawnPickupSafeActor) { h ->
            val world = h.paper.addSimpleWorld("summon-windup")
            loadSummonChunks(world)
            val owner = h.player("summon-owner", world, StaffSpell.NECROMANCER)
            h.zombie(world, x = 0.5, z = 2.35).isInvulnerable = true
            val target = h.zombie(world, x = 0.5, z = 3.0)
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)
            val soldiers = world.entities.filterIsInstance<Skeleton>()
            soldiers.size shouldBe 2
            soldiers.filterIsInstance<WitherSkeleton>().size shouldBe 0
            soldiers.all { !it.hasAI() && it.isInvulnerable && !it.isPersistent && !it.canPickupItems &&
                !it.shouldBurnInDay() } shouldBe true
            soldiers.all { mob ->
                mob.equipment?.let { gear ->
                    gear.itemInMainHand.type.isAir && gear.itemInOffHand.type.isAir &&
                        gear.helmet.type.isAir && gear.chestplate.type.isAir &&
                        gear.itemInMainHandDropChance == 0.0f && gear.helmetDropChance == 0.0f
                } == true
            } shouldBe true

            h.scheduler.tick(19)
            h.hits shouldBe emptyList()
            h.scheduler.tick(1)
            h.hits.all { it === target } shouldBe true
            (h.hits.size in 1..2) shouldBe true

            h.controller.onQuit(PlayerQuitEvent(owner, Component.empty()))
            soldiers.all { !it.isValid } shouldBe true
        }
    }

    "full charge is globally capped and secondary replaces the squad with one commander" {
        withStaffHarness(summonActorFactory = ::spawnPickupSafeActor) { h ->
            val world = h.paper.addSimpleWorld("summon-cap")
            loadSummonChunks(world)
            h.controller.start()
            val owners = mutableListOf<org.bukkit.entity.Player>()
            var firstSession: List<Skeleton> = emptyList()
            repeat(5) { index ->
                val owner = h.player("summon-cap-$index", world, StaffSpell.NECROMANCER)
                owners += owner
                h.controller.cast(owner, StaffCastInput.PRIMARY, 1.0)
                if (index == 0) firstSession = world.entities.filterIsInstance<Skeleton>()
            }

            val fullSquad = world.entities.filterIsInstance<Skeleton>()
            fullSquad.size shouldBe 16
            fullSquad.filterIsInstance<WitherSkeleton>().size shouldBe 0
            firstSession.all { !it.isValid } shouldBe true

            h.scheduler.tick(21)
            val commanderOwner = owners.last()
            h.controller.cast(commanderOwner, StaffCastInput.SECONDARY, 1.0)
            val replacement = world.entities.filterIsInstance<WitherSkeleton>().single()
            (fullSquad + replacement).all { !it.canPickupItems } shouldBe true
            world.entities.filterIsInstance<Skeleton>().size shouldBe 12
            val gear = requireNotNull(replacement.equipment)
            gear.helmet.type.isAir shouldBe false
            gear.chestplate.type.isAir shouldBe false
            gear.helmetDropChance shouldBe 0.0f
            gear.chestplateDropChance shouldBe 0.0f
            replacement.shouldBurnInDay() shouldBe false

            h.controller.onQuit(PlayerQuitEvent(commanderOwner, Component.empty()))
            replacement.isValid shouldBe false
        }
    }

    "ordinary player targets and stale casts never pass the damage gate; sessions expire" {
        withStaffHarness(summonActorFactory = ::spawnPickupSafeActor) { h ->
            val world = h.paper.addSimpleWorld("summon-pvp")
            loadSummonChunks(world)
            val owner = h.player("summon-pvp-owner", world, StaffSpell.NECROMANCER)
            h.paper.addPlayer("summon-opponent").teleport(Location(world, 0.5, 64.0, 3.0))
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.SECONDARY, 1.0)
            val commander = world.entities.filterIsInstance<WitherSkeleton>().single()
            commander.canPickupItems shouldBe false
            h.scheduler.tick(40)
            h.hits shouldBe emptyList()
            commander.isValid shouldBe true
            h.scheduler.tick(160)
            commander.isValid shouldBe false
        }

        var current = true
        withStaffHarness(currentCast = { _, _ -> current }, summonActorFactory = ::spawnPickupSafeActor) { h ->
            val world = h.paper.addSimpleWorld("summon-stale")
            loadSummonChunks(world)
            val owner = h.player("summon-stale-owner", world, StaffSpell.NECROMANCER)
            h.zombie(world, x = 0.5, z = 3.0)
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)
            val actors = world.entities.filterIsInstance<Skeleton>()
            actors.size shouldBe 2
            actors.all { !it.canPickupItems } shouldBe true
            current = false
            h.scheduler.tick(12)

            h.hits shouldBe emptyList()
            actors.all { !it.isValid } shouldBe true
        }
    }

    "summons require solid floor and never target an invulnerable summoned actor" {
        withStaffHarness(summonActorFactory = ::spawnPickupSafeActor) { h ->
            val world = h.paper.addSimpleWorld("summon-floor")
            loadSummonChunks(world)
            val owner = h.player("summon-floor-owner", world, StaffSpell.NECROMANCER)
            h.controller.start()
            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)
            val actors = world.entities.filterIsInstance<Skeleton>()
            actors.size shouldBe 2
            actors.all { !it.canPickupItems } shouldBe true

            h.scheduler.tick(40)
            h.hits shouldBe emptyList()
            actors.all { it.isInvulnerable && it.isValid } shouldBe true
        }

        withStaffHarness(summonActorFactory = ::spawnPickupSafeActor) { h ->
            val world = h.paper.addSimpleWorld("summon-no-floor")
            loadSummonChunks(world)
            for (x in -4..4) for (z in -4..4) world.getBlockAt(x, 63, z).type = Material.AIR
            val owner = h.player("summon-no-floor-owner", world, StaffSpell.NECROMANCER)
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)

            world.entities.filterIsInstance<AbstractSkeleton>() shouldBe emptyList()
        }
    }

    "summon sigil and plume are staged, bounded native particles with a distinct commander frame" {
        val soldierFrames = (0..9).map { staffSummonFrameSamples(it, commander = false) }
        val commanderFrames = (0..9).map { staffSummonFrameSamples(it, commander = true) }

        soldierFrames.mapIndexedNotNull { tick, samples -> tick.takeIf { samples.isNotEmpty() } } shouldBe listOf(0, 2, 4, 6, 8)
        commanderFrames.mapIndexedNotNull { tick, samples -> tick.takeIf { samples.isNotEmpty() } } shouldBe listOf(0, 2, 4, 6, 8)
        (soldierFrames + commanderFrames).filterNot { it.isEmpty() }.all { frame ->
            frame.size <= 12 && frame.all { sample ->
                sample.count == 1 && sample.particle in setOf(Particle.DUST, Particle.SOUL,
                    Particle.SOUL_FIRE_FLAME, Particle.SMOKE) &&
                    (sample.particle != Particle.DUST || sample.data is Particle.DustOptions)
            }
        } shouldBe true
        commanderFrames[0].size shouldBe 11
        soldierFrames[0].size shouldBe 8
    }
})

private fun loadSummonChunks(world: World) {
    for (x in -1..0) for (z in -1..0) world.loadChunk(x, z)
    for (x in -4..4) for (z in -4..4) world.getBlockAt(x, 63, z).type = Material.STONE
}

private fun spawnPickupSafeActor(world: World, at: Location, commander: Boolean): AbstractSkeleton {
    val server = Bukkit.getServer() as ServerMock
    val actor = if (commander) PickupSafeWitherSkeletonMock(server, UUID.randomUUID())
        else PickupSafeSkeletonMock(server, UUID.randomUUID())
    actor.setShouldBurnInDay(true)
    actor.location = at.clone().also { it.world = world }
    server.registerEntity(actor)
    return actor
}

private class PickupSafeSkeletonMock(server: ServerMock, uuid: UUID) : SkeletonMock(server, uuid) {
    private var pickupItems = true

    override fun setCanPickupItems(canPickup: Boolean) {
        pickupItems = canPickup
    }

    override fun getCanPickupItems() = pickupItems
}

private class PickupSafeWitherSkeletonMock(server: ServerMock, uuid: UUID) : WitherSkeletonMock(server, uuid) {
    private var pickupItems = true

    override fun setCanPickupItems(canPickup: Boolean) {
        pickupItems = canPickup
    }

    override fun getCanPickupItems() = pickupItems
}
