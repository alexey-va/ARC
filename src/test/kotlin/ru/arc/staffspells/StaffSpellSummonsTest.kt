package ru.arc.staffspells

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import com.destroystokyo.paper.entity.Pathfinder
import com.destroystokyo.paper.entity.ai.MobGoals
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.AbstractSkeleton
import org.bukkit.entity.Skeleton
import org.bukkit.entity.WitherSkeleton
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.WorldMock
import org.mockbukkit.mockbukkit.entity.SkeletonMock
import org.mockbukkit.mockbukkit.entity.WitherSkeletonMock
import java.util.UUID

class StaffSpellSummonsTest : FreeSpec({
    "primary summons a native squad and waits through its attack windup" {
        var configuredActors: List<AbstractSkeleton> = emptyList()
        withSummonHarness(verifyGoals = { goals ->
            configuredActors.forEach { mob ->
                verify(exactly = 1) { goals.removeAllGoals(mob) }
                verify(exactly = 1) { mob.pathfinder.stopPathfinding() }
            }
        }) { h ->
            val world = h.paper.addSimpleWorld("summon-windup")
            loadSummonChunks(world)
            val owner = h.player("summon-owner", world, StaffSpell.NECROMANCER)
            h.zombie(world, x = 0.5, z = 2.35).isInvulnerable = true
            val target = h.zombie(world, x = 0.5, z = 3.0)
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)
            val soldiers = world.entities.filterIsInstance<Skeleton>()
            configuredActors = soldiers
            soldiers.size shouldBe 2
            soldiers.filterIsInstance<WitherSkeleton>().size shouldBe 0
            soldiers.all { it.hasAI() && it.isAware && it.isInvulnerable && !it.isPersistent && !it.canPickupItems &&
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
        withSummonHarness { h ->
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
        withSummonHarness { h ->
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
        withSummonHarness(currentCast = { _, _ -> current }) { h ->
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
        withSummonHarness { h ->
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

        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-no-floor")
            loadSummonChunks(world)
            for (x in -4..4) for (z in -4..4) world.getBlockAt(x, 63, z).type = Material.AIR
            val owner = h.player("summon-no-floor-owner", world, StaffSpell.NECROMANCER)
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)

            world.entities.filterIsInstance<AbstractSkeleton>() shouldBe emptyList()
        }
    }

    "failed terrain replacement preserves the active squad and expiry permits recasting" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-terrain-retry")
            loadSummonChunks(world)
            val owner = h.player("summon-terrain-retry-owner", world, StaffSpell.NECROMANCER)
            val settings = staffSettings()
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val targeting = mockk<StaffSpellTargeting>()
            every { targeting.nearby(any(), any(), any(), any()) } returns emptyList()
            val summons = StaffSpellSummons(
                settings,
                damage,
                mockk(relaxed = true),
                targeting,
                ::spawnPickupSafeActor,
            )
            val tuning = settings.tuning.getValue(StaffSpell.NECROMANCER)
            var currentSquad: List<Skeleton> = emptyList()
            fun cast() = summons.cast(
                owner,
                StaffSpellCast(owner.uniqueId, UUID.randomUUID(), 1, null, null, false),
                tuning,
                StaffCastInput.PRIMARY,
                0.0,
            )

            summons.start()
            try {
                cast() shouldBe true
                val firstSquad = world.entities.filterIsInstance<Skeleton>().filter { it.isValid }
                firstSquad.size shouldBe 2

                for (x in -4..4) for (z in -4..4) world.getBlockAt(x, 63, z).type = Material.AIR
                cast() shouldBe false
                firstSquad.all { it.isValid } shouldBe true

                for (x in -4..4) for (z in -4..4) world.getBlockAt(x, 63, z).type = Material.STONE
                cast() shouldBe true
                firstSquad.all { !it.isValid } shouldBe true
                currentSquad = world.entities.filterIsInstance<Skeleton>().filter { it.isValid }
                currentSquad.size shouldBe 2

                h.scheduler.tick(200)
                currentSquad.all { !it.isValid } shouldBe true
                cast() shouldBe true
                currentSquad = world.entities.filterIsInstance<Skeleton>().filter { it.isValid }
                currentSquad.size shouldBe 2
            } finally {
                summons.close()
            }
            currentSquad.all { !it.isValid } shouldBe true
        }
    }

    "summons resolve bottom slabs to their collision top" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-bottom-slab")
            loadSummonChunks(world)
            for (x in -4..4) for (z in -4..4) {
                world.getBlockAt(x, 63, z).type = Material.ANDESITE_SLAB
            }
            val owner = h.player("summon-bottom-slab-owner", world, StaffSpell.NECROMANCER)
            owner.teleport(Location(world, 0.5, 63.5, 0.5).setDirection(org.bukkit.util.Vector(0.0, 0.0, 1.0)))
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)

            val actors = world.entities.filterIsInstance<Skeleton>()
            actors.size shouldBe 2
            actors.all { kotlin.math.abs(it.location.y - 63.5) < 0.001 } shouldBe true
        }
    }

    "summons resolve mixed full-block and bottom-slab terrain without clipping" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-mixed-height")
            loadSummonChunks(world)
            for (x in -4..4) for (z in -4..4) {
                world.getBlockAt(x, 63, z).type = Material.ANDESITE_SLAB
            }
            for (z in 0..1) {
                world.getBlockAt(1, 63, z).type = Material.AIR
                world.getBlockAt(1, 62, z).type = Material.STONE
            }
            val owner = h.player("summon-mixed-height-owner", world, StaffSpell.NECROMANCER)
            owner.teleport(Location(world, 0.5, 63.5, 0.5).setDirection(org.bukkit.util.Vector(0.0, 0.0, 1.0)))
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)

            val actors = world.entities.filterIsInstance<Skeleton>()
            actors.size shouldBe 2
            actors.map { it.location.y }.sorted() shouldBe listOf(63.0, 63.5)
            actors.all { mob ->
                (mob.location.y == 63.0 || mob.location.y == 63.5) && mob.isValid
            } shouldBe true
        }
    }

    "low headroom blocks summons instead of clipping their bodies" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-low-headroom")
            loadSummonChunks(world)
            for (x in -4..4) for (z in -4..4) world.getBlockAt(x, 65, z).type = Material.STONE
            val owner = h.player("summon-low-headroom-owner", world, StaffSpell.NECROMANCER)
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)

            world.entities.filterIsInstance<AbstractSkeleton>() shouldBe emptyList()
        }
    }

    "summons reject liquid body space and never load chunks to find a spawn" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-liquid")
            loadSummonChunks(world)
            for (x in -4..4) for (z in -4..4) world.getBlockAt(x, 64, z).type = Material.WATER
            val owner = h.player("summon-liquid-owner", world, StaffSpell.NECROMANCER)
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)

            world.entities.filterIsInstance<AbstractSkeleton>() shouldBe emptyList()
        }

        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-unloaded")
            loadSummonChunks(world)
            val owner = h.player("summon-unloaded-owner", world, StaffSpell.NECROMANCER)
            owner.teleport(Location(world, 32.5, 64.0, 0.5).setDirection(org.bukkit.util.Vector(0.0, 0.0, 1.0)))
            world.isChunkLoaded(2, 0) shouldBe false
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)

            world.entities.filterIsInstance<AbstractSkeleton>() shouldBe emptyList()
            world.isChunkLoaded(2, 0) shouldBe false
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

private fun withSummonHarness(
    currentCast: (org.bukkit.entity.Player, StaffSpellCast) -> Boolean = { _, _ -> true },
    summonActorFactory: ((World, Location, Boolean) -> AbstractSkeleton)? = ::spawnPickupSafeActor,
    verifyGoals: (MobGoals) -> Unit = {},
    block: (StaffHarness) -> Unit,
) {
    withStaffHarness(currentCast = currentCast, summonActorFactory = summonActorFactory) { harness ->
        every { anyConstructed<WorldMock>().isChunkLoaded(any<Int>(), any<Int>()) } answers { callOriginal() }
        every { anyConstructed<WorldMock>().getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            CollisionShapeBlock(callOriginal())
        }
        val goals = mockk<MobGoals>(relaxed = true)
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.getMobGoals() } returns goals
            every { Bukkit.getServer() } answers { callOriginal() }
            every { Bukkit.getPlayer(any<UUID>()) } answers { callOriginal() }
            every { Bukkit.getOnlinePlayers() } answers { callOriginal() }
            every { Bukkit.getPluginManager() } answers { callOriginal() }
            every { Bukkit.isPrimaryThread() } answers { callOriginal() }
            every { Bukkit.getCurrentTick() } answers { callOriginal() }
            block(harness)
            verifyGoals(goals)
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }
}

private class CollisionShapeBlock(block: Block) : BlockMock() {
    private var backingBlock: Block? = null

    init {
        backingBlock = block
    }

    override fun getType() = backingBlock?.type ?: Material.AIR
    override fun setType(type: Material) { backingBlock?.setType(type) ?: super.setType(type) }
    override fun getBlockData() = backingBlock?.blockData ?: super.getBlockData()
    override fun setBlockData(data: BlockData) { backingBlock?.setBlockData(data) ?: super.setBlockData(data) }
    override fun setBlockData(data: BlockData, applyPhysics: Boolean) {
        backingBlock?.setBlockData(data, applyPhysics) ?: super.setBlockData(data, applyPhysics)
    }
    override fun getWorld() = backingBlock?.world ?: super.getWorld()
    override fun getX() = backingBlock?.x ?: super.getX()
    override fun getY() = backingBlock?.y ?: super.getY()
    override fun getZ() = backingBlock?.z ?: super.getZ()
    override fun getLocation() = backingBlock?.location ?: super.getLocation()
    override fun isSolid() = backingBlock?.isSolid ?: super.isSolid
    override fun isLiquid() = backingBlock?.isLiquid ?: super.isLiquid
    override fun getCollisionShape() = backingBlock?.let(::collisionShapeFor) ?: super.getCollisionShape()
}

private fun collisionShapeFor(block: Block): VoxelShape {
    val boxes = when {
        block.type.isAir || block.isLiquid -> emptyList()
        block.type.name.endsWith("_SLAB") -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0))
        !block.type.isSolid -> emptyList()
        else -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
    }
    return object : VoxelShape {
        override fun getBoundingBoxes() = boxes
        override fun overlaps(other: BoundingBox) = boxes.any { it.overlaps(other) }
    }
}

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
    private val mockPathfinder = mockk<Pathfinder>(relaxed = true)

    override fun getPathfinder() = mockPathfinder

    override fun setCanPickupItems(canPickup: Boolean) {
        pickupItems = canPickup
    }

    override fun getCanPickupItems() = pickupItems
}

private class PickupSafeWitherSkeletonMock(server: ServerMock, uuid: UUID) : WitherSkeletonMock(server, uuid) {
    private var pickupItems = true
    private val mockPathfinder = mockk<Pathfinder>(relaxed = true)

    override fun getPathfinder() = mockPathfinder

    override fun setCanPickupItems(canPickup: Boolean) {
        pickupItems = canPickup
    }

    override fun getCanPickupItems() = pickupItems
}
