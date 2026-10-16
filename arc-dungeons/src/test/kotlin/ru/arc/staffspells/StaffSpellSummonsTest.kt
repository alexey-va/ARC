package ru.arc.staffspells

import io.mockk.every
import io.mockk.clearConstructorMockk
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
import org.bukkit.util.Vector
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Arrow
import org.bukkit.entity.AbstractSkeleton
import org.bukkit.entity.Player
import org.bukkit.entity.Skeleton
import org.bukkit.entity.WitherSkeleton
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.WorldMock
import org.mockbukkit.mockbukkit.entity.SkeletonMock
import org.mockbukkit.mockbukkit.entity.WitherSkeletonMock
import ru.arc.staffspells.api.StaffSpellDuelContext
import ru.arc.core.LifecycleTaskScope
import java.util.UUID

class StaffSpellSummonsTest : FreeSpec({
    "primary summons killable native bow skeletons and arrows hit only after telegraphed flight" {
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
            soldiers.all { it.hasAI() && it.isAware && !it.isInvulnerable && !it.isPersistent && !it.canPickupItems &&
                !it.shouldBurnInDay() } shouldBe true
            soldiers.all { it.health == 4.0 } shouldBe true
            soldiers.all { mob ->
                mob.equipment?.let { gear ->
                    gear.itemInMainHand.type == Material.BOW && gear.itemInOffHand.type.isAir &&
                        gear.helmet.type.isAir && gear.chestplate.type.isAir &&
                        gear.itemInMainHandDropChance == 0.0f && gear.helmetDropChance == 0.0f
                } == true
            } shouldBe true

            h.scheduler.tick(19)
            h.hits shouldBe emptyList()
            h.scheduler.tick(1)
            val arrows = world.entities.filterIsInstance<Arrow>()
            (arrows.size in 1..2) shouldBe true
            arrows.all { it.damage == 0.0 && it.knockbackStrength == 0 && !it.hasGravity() &&
                it.isInvulnerable && it.pickupStatus == org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED &&
                soldiers.any { soldier -> it.shooter === soldier } } shouldBe true
            h.hits shouldBe emptyList()
            h.scheduler.tick(8)
            h.hits.all { it === target } shouldBe true
            (h.hits.size in 1..2) shouldBe true

            h.controller.onQuit(PlayerQuitEvent(owner, Component.empty()))
            soldiers.all { !it.isValid } shouldBe true
        }
    }

    "focus and rally commands reuse the active squad without adding actors or resetting an attack windup" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-q-commands")
            loadSummonChunks(world)
            val owner = h.player("summon-q-commands-owner", world, StaffSpell.NECROMANCER)
            h.zombie(world, x = 0.5, z = 2.35).isInvulnerable = true
            val target = h.zombie(world, x = 0.5, z = 3.0)
            val cast = StaffSpellCast(owner.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val summons = h.controller.summons
            h.controller.start()
            summons.castResult(owner, cast, staffSettings().tuning.getValue(StaffSpell.NECROMANCER),
                StaffCastInput.PRIMARY, 1.0) shouldBe StaffSummonCastResult.CAST

            val actorsBefore = world.entities.filterIsInstance<AbstractSkeleton>().filter { it.isValid }.toSet()
            actorsBefore.size shouldBe 2
            h.scheduler.tick(19)
            world.entities.filterIsInstance<Arrow>().count { it.isValid } shouldBe 0

            summons.commandFocus(owner, target, cast) shouldBe true
            summons.commandRally(owner, Location(world, 0.5, 64.0, 1.5), cast) shouldBe true
            world.entities.filterIsInstance<AbstractSkeleton>().filter { it.isValid }.toSet() shouldBe actorsBefore
            world.entities.filterIsInstance<Arrow>().count { it.isValid } shouldBe 0

            // The command did not reset either actor's ten-tick windup or produce another squad.
            h.scheduler.tick(1)
            world.entities.filterIsInstance<AbstractSkeleton>().filter { it.isValid }.toSet() shouldBe actorsBefore
            (world.entities.filterIsInstance<Arrow>().count { it.isValid } in 1..2) shouldBe true
            h.hits shouldBe emptyList()
        }
    }

    "archers keep dealing damage after their old shot and session hit budgets" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-sustained-attacks")
            loadSummonChunks(world)
            val owner = h.player("summon-sustained-owner", world, StaffSpell.NECROMANCER)
            h.zombie(world, x = 0.5, z = 3.0)
            h.controller.start()
            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)
            val actors = world.entities.filterIsInstance<Skeleton>().filter { it.isValid }
            actors.size shouldBe 2

            h.scheduler.tick(899)

            (h.hits.size > 6) shouldBe true
            actors.all { it.isValid } shouldBe true
            h.scheduler.tick(1)
            actors.all { !it.isValid } shouldBe true
        }
    }

    "global actor cap stays bounded and secondary adds a commander without replacing archers" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-cap")
            loadSummonChunks(world)
            h.controller.start()
            val owners = mutableListOf<org.bukkit.entity.Player>()
            var firstSession: List<Skeleton> = emptyList()
            repeat(17) { index ->
                val owner = h.player("summon-cap-$index", world, StaffSpell.NECROMANCER)
                owners += owner
                h.controller.cast(owner, StaffCastInput.PRIMARY, 1.0)
                if (index == 0) firstSession = world.entities.filterIsInstance<Skeleton>()
            }

            val fullSquad = world.entities.filterIsInstance<AbstractSkeleton>().filter { it.isValid }
            fullSquad.size shouldBe 32
            firstSession.all { !it.isValid } shouldBe true

            val commanderOwner = owners.last()
            val tuning = staffSettings().tuning.getValue(StaffSpell.NECROMANCER)
            h.controller.summons.castResult(
                commanderOwner,
                StaffSpellCast(commanderOwner.uniqueId, UUID.randomUUID(), 1, null, null, false),
                tuning,
                StaffCastInput.SECONDARY,
                1.0,
            ) shouldBe StaffSummonCastResult.CAST
            val replacement = world.entities.filterIsInstance<WitherSkeleton>().single()
            (fullSquad + replacement).all { !it.canPickupItems } shouldBe true
            world.entities.filterIsInstance<Skeleton>().count { it.isValid } shouldBe 30
            world.entities.filterIsInstance<AbstractSkeleton>().count { it.isValid } shouldBe 31
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

    "two full caster armies coexist under the global actor cap" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-two-armies")
            loadSummonChunks(world)
            val owners = listOf(
                h.player("summon-army-one", world, StaffSpell.NECROMANCER),
                h.player("summon-army-two", world, StaffSpell.NECROMANCER),
            )
            val tuning = staffSettings().tuning.getValue(StaffSpell.NECROMANCER)
            val summons = h.controller.summons
            h.controller.start()

            fun cast(owner: Player, input: StaffCastInput) = summons.castResult(
                owner,
                StaffSpellCast(owner.uniqueId, UUID.randomUUID(), 1, null, null, false),
                tuning,
                input,
                1.0,
            )

            val firstOwner = owners.first()
            repeat(4) { cast(firstOwner, StaffCastInput.PRIMARY) shouldBe StaffSummonCastResult.CAST }
            repeat(2) { cast(firstOwner, StaffCastInput.SECONDARY) shouldBe StaffSummonCastResult.CAST }
            val firstArmy = world.entities.filterIsInstance<AbstractSkeleton>().filter { it.isValid }
            firstArmy.size shouldBe 10

            val secondOwner = owners.last()
            repeat(4) { cast(secondOwner, StaffCastInput.PRIMARY) shouldBe StaffSummonCastResult.CAST }
            repeat(2) { cast(secondOwner, StaffCastInput.SECONDARY) shouldBe StaffSummonCastResult.CAST }

            val actors = world.entities.filterIsInstance<AbstractSkeleton>().filter { it.isValid }
            actors.size shouldBe 20
            actors.filterIsInstance<WitherSkeleton>().size shouldBe 4
            firstArmy.all { it.isValid } shouldBe true
        }
    }

    "stacked summon squads do not target or damage the same caster's other actors" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-friendly-squads")
            loadSummonChunks(world)
            val owner = h.player("summon-friendly-owner", world, StaffSpell.NECROMANCER)
            val tuning = staffSettings().tuning.getValue(StaffSpell.NECROMANCER)
            val summons = h.controller.summons
            h.controller.start()

            fun cast() = summons.castResult(
                owner,
                StaffSpellCast(owner.uniqueId, UUID.randomUUID(), 1, null, null, false),
                tuning,
                StaffCastInput.PRIMARY,
                1.0,
            )

            cast() shouldBe StaffSummonCastResult.CAST
            owner.teleport(Location(world, 3.5, 64.0, 0.5).apply { setDirection(Vector(0.0, 0.0, 1.0)) })
            cast() shouldBe StaffSummonCastResult.CAST
            world.entities.filterIsInstance<Skeleton>().filter { it.isValid }.size shouldBe 4

            h.scheduler.tick(40)

            h.hits shouldBe emptyList()
            world.entities.filterIsInstance<Arrow>().filter { it.isValid } shouldBe emptyList()
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
            h.scheduler.tick(859)
            commander.isValid shouldBe true
            h.scheduler.tick(1)
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
            h.controller.summons.castFor(actors.first())?.casterId shouldBe owner.uniqueId
            h.controller.summons.castFor(h.zombie(world, x = 4.5, z = 5.0)) shouldBe null
            current = false
            h.controller.summons.castFor(actors.first()) shouldBe null
            h.scheduler.tick(12)

            h.hits shouldBe emptyList()
            actors.all { !it.isValid } shouldBe true
        }
    }

    "owned archer actors expose only their live cast and leave no loot or experience" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-ownership")
            loadSummonChunks(world)
            val owner = h.player("summon-ownership-owner", world, StaffSpell.NECROMANCER)
            h.controller.start()
            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)
            val actor = world.entities.filterIsInstance<Skeleton>().first()
            val drops = mutableListOf(ItemStack(Material.BONE))
            val death = EntityDeathEvent(actor, DamageSource.builder(DamageType.GENERIC).build(), drops, 7)

            h.controller.summons.onTrackedActorDeath(death)

            drops shouldBe emptyList()
            death.droppedExp shouldBe 0
        }
    }

    "duel skeletons accept only current opposing player damage" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-counterplay")
            loadSummonChunks(world)
            val owner = h.player("summon-counterplay-owner", world, StaffSpell.NECROMANCER)
            val opponent = h.player("summon-counterplay-opponent", world, StaffSpell.NECROMANCER)
            val outsider = h.player("summon-counterplay-outsider", world, StaffSpell.NECROMANCER)
            val context = StaffSpellDuelContext(UUID.randomUUID(), 1, 0.4)
            val authorization = DuelSpellAuthorization(UUID.randomUUID(), context)
            val access = mockk<StaffSpellDuelAccess>()
            every { access.isCurrent(authorization, owner) } returns true
            every { access.canHit(authorization, owner, any()) } answers { thirdArg<Player>() === opponent }
            h.controller.start()
            val cast = StaffSpellCast(owner.uniqueId, UUID.randomUUID(), 1, null, null, false, authorization, access)
            h.controller.summons.cast(
                owner, cast, staffSettings().tuning.getValue(StaffSpell.NECROMANCER), StaffCastInput.PRIMARY, 0.0,
            ) shouldBe true
            val actor = world.entities.filterIsInstance<Skeleton>().first()

            fun damageEvent(attacker: org.bukkit.entity.Entity, target: org.bukkit.entity.Entity = actor) = EntityDamageByEntityEvent(
                attacker, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0,
            )
            val friendly = damageEvent(owner)
            h.controller.summons.onTrackedActorDamage(friendly)
            friendly.isCancelled shouldBe true
            val unauthorized = damageEvent(outsider)
            h.controller.summons.onTrackedActorDamage(unauthorized)
            unauthorized.isCancelled shouldBe true
            val opposing = damageEvent(opponent)
            h.controller.summons.onTrackedActorDamage(opposing)
            opposing.isCancelled shouldBe false

            val projectile = world.spawnArrow(opponent.eyeLocation, opponent.eyeLocation.direction, 1.0f, 0.0f)
            projectile.shooter = opponent
            val projectileHit = EntityDamageByEntityEvent(
                projectile, actor, EntityDamageEvent.DamageCause.PROJECTILE, 1.0,
            )
            h.controller.summons.onTrackedActorDamage(projectileHit)
            projectileHit.isCancelled shouldBe false

            val unrelated = h.zombie(world, x = 4.5, z = 4.0)
            val unrelatedEvent = damageEvent(opponent, unrelated)
            h.controller.summons.onTrackedActorDamage(unrelatedEvent)
            unrelatedEvent.isCancelled shouldBe false
        }
    }

    "killable archers reject invulnerable targets and summons require solid floor" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-floor")
            loadSummonChunks(world)
            val owner = h.player("summon-floor-owner", world, StaffSpell.NECROMANCER)
            h.zombie(world, x = 0.5, z = 3.0).isInvulnerable = true
            h.controller.start()
            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)
            val actors = world.entities.filterIsInstance<Skeleton>()
            actors.size shouldBe 2
            actors.all { !it.canPickupItems } shouldBe true
            actors.all { !it.isInvulnerable && it.equipment?.itemInMainHand?.type == Material.BOW } shouldBe true

            h.scheduler.tick(40)
            h.hits shouldBe emptyList()
            actors.all { !it.isInvulnerable && it.isValid } shouldBe true
        }

        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-no-floor")
            loadSummonChunks(world)
            // Clear the full supported 64-block aerial search, including its lowest support block.
            for (x in -4..4) for (z in -4..4) for (y in maxOf(world.minHeight, -1)..63) world.getBlockAt(x, y, z).type = Material.AIR
            val owner = h.player("summon-no-floor-owner", world, StaffSpell.NECROMANCER)
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)

            world.entities.filterIsInstance<AbstractSkeleton>() shouldBe emptyList()
        }
    }

    "launched arrows stop at cover without dealing damage" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-arrow-cover")
            loadSummonChunks(world)
            val owner = h.player("summon-arrow-cover-owner", world, StaffSpell.NECROMANCER)
            h.zombie(world, x = 0.5, z = 5.0)
            h.controller.start()
            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)

            h.scheduler.tick(20)
            val arrows = world.entities.filterIsInstance<Arrow>()
            (arrows.size in 1..2) shouldBe true
            h.hits shouldBe emptyList()

            for (x in -2..2) for (y in 64..66) world.getBlockAt(x, y, 2).type = Material.STONE
            h.scheduler.tick(16)

            h.hits shouldBe emptyList()
            world.entities.filterIsInstance<Arrow>() shouldBe emptyList()
        }
    }

    "failed terrain cast preserves prior squads and stacked sessions expire" {
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

                // Clear the full supported 64-block aerial search, including its lowest support block.
                for (x in -4..4) for (z in -4..4) for (y in maxOf(world.minHeight, -1)..63) world.getBlockAt(x, y, z).type = Material.AIR
                cast() shouldBe false
                firstSquad.all { it.isValid } shouldBe true

                for (x in -4..4) for (z in -4..4) world.getBlockAt(x, 63, z).type = Material.STONE
                cast() shouldBe true
                firstSquad.all { it.isValid } shouldBe true
                currentSquad = world.entities.filterIsInstance<Skeleton>().filter { it.isValid }
                currentSquad.size shouldBe 4

                h.scheduler.tick(900)
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

    "per-caster cap rejects whole groups and does not refresh older squads" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-caster-cap")
            loadSummonChunks(world)
            val owner = h.player("summon-caster-cap-owner", world, StaffSpell.NECROMANCER)
            val tuning = staffSettings().tuning.getValue(StaffSpell.NECROMANCER)
            val summons = h.controller.summons
            h.controller.start()

            fun cast(input: StaffCastInput): StaffSummonCastResult = summons.castResult(
                owner,
                StaffSpellCast(owner.uniqueId, UUID.randomUUID(), 1, null, null, false),
                tuning,
                input,
                1.0,
            )
            fun liveActors() = world.entities.filterIsInstance<AbstractSkeleton>().filter { it.isValid && !it.isDead }

            cast(StaffCastInput.PRIMARY) shouldBe StaffSummonCastResult.CAST
            val oldestSquad = liveActors()
            oldestSquad.size shouldBe 2

            h.scheduler.tick(450)
            repeat(3) { cast(StaffCastInput.PRIMARY) shouldBe StaffSummonCastResult.CAST }
            cast(StaffCastInput.SECONDARY) shouldBe StaffSummonCastResult.CAST
            liveActors().size shouldBe 9

            cast(StaffCastInput.PRIMARY) shouldBe StaffSummonCastResult.CAPACITY
            liveActors().size shouldBe 9
            cast(StaffCastInput.SECONDARY) shouldBe StaffSummonCastResult.CAST
            liveActors().size shouldBe 10
            summons.countFor(owner.uniqueId) shouldBe 10
            cast(StaffCastInput.SECONDARY) shouldBe StaffSummonCastResult.CAPACITY
            liveActors().size shouldBe 10

            h.scheduler.tick(449)
            oldestSquad.all { it.isValid } shouldBe true
            h.scheduler.tick(1)
            oldestSquad.all { !it.isValid } shouldBe true
            liveActors().size shouldBe 8
            summons.countFor(owner.uniqueId) shouldBe 8
            h.scheduler.tick(450)
            liveActors() shouldBe emptyList()
            summons.countFor(owner.uniqueId) shouldBe 0
        }
    }

    "quit cleanup removes every stacked summon session" {
        withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-stacked-quit")
            loadSummonChunks(world)
            val owner = h.player("summon-stacked-quit-owner", world, StaffSpell.NECROMANCER)
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(21)
            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)
            val actors = world.entities.filterIsInstance<AbstractSkeleton>().filter { it.isValid }
            actors.size shouldBe 4

            h.controller.onQuit(PlayerQuitEvent(owner, Component.empty()))

            actors.all { !it.isValid } shouldBe true
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

    "airborne summons project to loaded ground within sixty-four blocks and count only live owned actors" {
        for (height in listOf(80.0, 128.0, 129.0)) withSummonHarness { h ->
            val world = h.paper.addSimpleWorld("summon-airborne-$height")
            loadSummonChunks(world)
            val owner = h.player("summon-airborne-owner", world, StaffSpell.NECROMANCER)
            owner.teleport(Location(world, .5, height, .5).setDirection(Vector(0.0, 0.0, 1.0)))
            owner.allowFlight = true
            owner.isFlying = true
            h.controller.start()

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)

            val actors = world.entities.filterIsInstance<AbstractSkeleton>()
            val expected = if (height <= 128.0) 2 else 0
            actors.size shouldBe expected
            actors.all { it.location.y == 64.0 } shouldBe true
            h.controller.summons.countFor(owner.uniqueId) shouldBe expected
            h.controller.summons.countFor(UUID.randomUUID()) shouldBe 0
            if (actors.isNotEmpty()) {
                actors.first().remove()
                h.controller.summons.countFor(owner.uniqueId) shouldBe 1
            }
            h.controller.onQuit(PlayerQuitEvent(owner, Component.empty()))
            h.controller.summons.countFor(owner.uniqueId) shouldBe 0
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
        every { anyConstructed<WorldMock>().spawnArrow(any(), any(), any(), any()) } answers {
            val at = firstArg<Location>()
            requireNotNull(at.world).spawn(at, Arrow::class.java)
        }
        val goals = mockk<MobGoals>(relaxed = true)
        // These tests assert world state and damage, never WorldMock call counts.
        // Long summon lifetimes otherwise retain millions of collision-query records.
        val recordingCleanup = LifecycleTaskScope(harness.scheduler)
        recordingCleanup.runTimer(20, 20) {
            clearConstructorMockk(WorldMock::class, answers = false, childMocks = false)
        }
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
            recordingCleanup.close()
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
