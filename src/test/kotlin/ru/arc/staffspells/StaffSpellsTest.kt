package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkConstructor
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
        withStaffHarness(settings = staffSettings(range = 5.0, aimDegrees = 15.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-aim")
            val player = h.player("staff-aim", world, StaffSpell.CHAIN)
            val moving = h.zombie(world, x = 4.0, z = 4.0)
            h.zombie(world, x = 0.5, z = -3.0)
            h.zombie(world, x = 0.5, z = 6.0)
            moving.teleport(Location(world, 1.1, 64.0, 4.4))

            h.controller.cast(player)

            h.hits.map { it.uniqueId } shouldBe listOf(moving.uniqueId)
        }
    }

    "a solid wall blocks an otherwise valid direct target" {
        withStaffHarness(settings = staffSettings(range = 8.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-wall")
            val player = h.player("staff-wall", world, StaffSpell.CHAIN)
            val target = h.zombie(world, x = 0.5, z = 5.0)
            for (x in -1..1) for (y in 64..67) world.getBlockAt(x, y, 3).type = Material.STONE

            h.controller.cast(player)

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

            h.controller.cast(player)

            cancelled.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            landed.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe true
        }
    }

    "all staff spells share cooldown across an item swap" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-cooldown")
            val player = h.player("staff-cooldown", world, StaffSpell.CHAIN)
            val target = h.zombie(world, x = 0.5, z = 4.0)

            h.controller.cast(player)
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

    "nova respects its radius and occluding blocks" {
        withStaffHarness(settings = staffSettings(novaRadius = 6.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-nova")
            val player = h.player("staff-nova", world, StaffSpell.NOVA)
            val near = h.zombie(world, x = 0.5, z = 4.5)
            val hiddenInsideRadius = h.zombie(world, x = 3.0, z = 3.0)
            val outside = h.zombie(world, x = 0.5, z = 8.0)
            world.getBlockAt(2, 65, 2).type = Material.STONE

            h.controller.cast(player)

            h.hits.map { it.uniqueId } shouldBe listOf(near.uniqueId)
            hiddenInsideRadius.isValid shouldBe true
            outside.isValid shouldBe true
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

            h.controller.cast(player)

            h.hits.map { it.uniqueId } shouldBe listOf(first.uniqueId, second.uniqueId, third.uniqueId)
            h.hits.map { it.uniqueId }.distinct().size shouldBe h.hits.size

            h.hits.clear()
            val blockedWorld = h.paper.addSimpleWorld("staff-chain-cancelled")
            val cancelledPlayer = h.player("staff-chain-cancelled", blockedWorld, StaffSpell.CHAIN)
            val cancelled = h.zombie(blockedWorld, x = 0.5, z = 4.0)
            h.zombie(blockedWorld, x = 2.0, z = 5.0)
            cancelledTargets += cancelled.uniqueId

            h.controller.cast(cancelledPlayer)

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

            val hitCountBeforeCancelledMarks = h.hits.size
            h.scheduler.tick(6)
            h.hits.size shouldBe hitCountBeforeCancelledMarks

            val closingPlayer = h.player("mark-close", world, StaffSpell.MARK)
            h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(closingPlayer)
            closingPlayer.inventory.setItemInMainHand(ItemStack(Material.AIR))
            val hitCountBeforeClose = h.hits.size
            h.controller.close()
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
    val captures: MutableList<Player>,
    val hits: MutableList<LivingEntity>,
    val hitLocations: MutableList<Location>,
) {
    fun player(name: String, world: World, spell: StaffSpell): Player = paper.addPlayer(name).apply {
        val spawn = Location(world, 0.5, 64.0, 0.5).apply { setDirection(Vector(0.0, 0.0, 1.0)) }
        teleport(spawn)
        inventory.setItemInMainHand(config.item(spell))
    }

    fun zombie(world: World, x: Double, z: Double): Zombie =
        world.spawn(Location(world, x, 64.0, z), Zombie::class.java)
}

private fun withStaffHarness(
    settings: StaffSpellSettings = staffSettings(),
    hit: (Player, LivingEntity) -> Boolean = { _, _ -> true },
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
                val steps = (distance / 0.1).toInt().coerceAtLeast(1)
                (1..steps).firstNotNullOfOrNull { step ->
                    val hitPosition = start.toVector().add(direction.clone().multiply(distance * step / steps))
                    val block = world.getBlockAt(hitPosition.blockX, hitPosition.blockY, hitPosition.blockZ)
                    if (block.type.isAir) null else RayTraceResult(hitPosition, block, BlockFace.NORTH)
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
                        hit(player, target)
                    }
                    val controller = StaffSpellController(config, settings, damage)
                    val harness = StaffHarness(paper, scheduler, config, controller, captures, hits, hitLocations)
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
    lanceWidth: Double = 0.22,
    lanceTargets: Int = 3,
    emberSpeed: Double = 1.2,
    emberRadius: Double = 2.8,
    novaRadius: Double = 6.0,
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
    maxAreaTargets = 8,
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
