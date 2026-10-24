package ru.arc.staffspells

import com.destroystokyo.paper.entity.Pathfinder
import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalType
import com.destroystokyo.paper.entity.ai.MobGoals
import com.magmaguy.elitemobs.easyminecraftgoals.NMSAdapter
import com.magmaguy.elitemobs.easyminecraftgoals.TransientMovementOverride
import com.magmaguy.elitemobs.easyminecraftgoals.events.WanderBackToPointEndEvent
import com.magmaguy.elitemobs.easyminecraftgoals.events.WanderBackToPointStartEvent
import com.magmaguy.elitemobs.easyminecraftgoals.internal.AbstractWanderBackToPoint
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import java.util.Optional
import java.util.UUID

class StaffDungeonMageBehaviorTest : FreeSpec({
    "native hold is transient and restores only its own speed modifier without touching aggro or AI" {
        withStaffHarness { h ->
            val f = MageFixture(h)
            f.controller.use { behavior ->
                f.distance(8.0)
                behavior.tick(f.player, false)
                f.holding shouldBe true
                f.modifiers.getValue(HOLD_KEY).operation shouldBe AttributeModifier.Operation.MULTIPLY_SCALAR_1
                f.modifiers.getValue(HOLD_KEY).amount shouldBe -1.0
                repeat(120) { behavior.tick(f.player, false) }
                verify(exactly = 1) { f.speed.addTransientModifier(any()) }
                f.distance(12.0)
                behavior.tick(f.player, false)
                f.holding shouldBe false
                behavior.tick(f.player, true)
                f.holding shouldBe true
                behavior.tick(null, false)
                f.holding shouldBe false
                f.modifiers.keys shouldBe setOf("other:effect")
                verify(exactly = 0) { f.speed.addModifier(any()) }
                verify(exactly = 0) { f.speed.baseValue = any() }
                verify(exactly = 0) { f.body.target = any() }
                verify(exactly = 0) { f.body.setAI(any()) }
                verify(exactly = 0) { f.body.isAware = any() }
            }
        }
    }

    "native retreat holds its movement lease until preferred range then releases it" {
        withStaffHarness { h ->
            val f = MageFixture(h)
            val flee = f.flee()
            every { f.adapter.beginFlee(f.body, any(), any()) } returns Optional.of(flee)
            f.controller.use { behavior ->
                f.distance(4.0)
                behavior.tick(f.player, false)
                f.holding shouldBe false
                f.distance(6.5)
                behavior.tick(f.player, false)
                verify(exactly = 0) { flee.close() }
                f.distance(8.0)
                behavior.tick(f.player, false)
                f.holding shouldBe true
                verify(exactly = 1) { flee.close() }
            }
            f.holding shouldBe false
            verify(exactly = 1) { flee.close() }
        }
    }

    "failed and stalled native paths hold position with bounded retries instead of retaining a dead movement lease" {
        withStaffHarness { h ->
            val f = MageFixture(h)
            f.controller.use { behavior ->
                f.distance(4.0)
                behavior.tick(f.player, false)
                f.holding shouldBe true
                repeat(19) { behavior.tick(f.player, false) }
                verify(exactly = 1) { f.adapter.beginFlee(f.body, any(), any()) }
                val flee = f.flee()
                every { f.adapter.beginFlee(f.body, any(), any()) } returns Optional.of(flee)
                behavior.tick(f.player, false)
                f.holding shouldBe false
                repeat(20) { behavior.tick(f.player, false) }
                verify(exactly = 1) { flee.close() }
                f.holding shouldBe true
                repeat(19) { behavior.tick(f.player, false) }
                verify(exactly = 2) { f.adapter.beginFlee(f.body, any(), any()) }
                behavior.tick(f.player, false)
                verify(exactly = 3) { f.adapter.beginFlee(f.body, any(), any()) }
            }
        }
    }

    "native actor generation target and dungeon permission changes release the movement lease" {
        withStaffHarness { h ->
            val f = MageFixture(h)
            f.controller.use { behavior ->
                f.distance(8.0)
                behavior.tick(f.player, false)
                f.current = false
                behavior.tick(f.player, false)
                f.holding shouldBe false
                f.current = true
                behavior.tick(f.player, false)
                f.holding shouldBe true
                f.permitted = false
                behavior.tick(f.player, false)
                f.holding shouldBe false
                f.permitted = true
                behavior.tick(f.player, false)
                f.nativeTarget = null
                behavior.tick(f.player, false)
                f.holding shouldBe false
            }
        }
    }

    "overlapping native soft and hard leash returns immediately release hold and resume only after both end" {
        withStaffHarness { h ->
            val f = MageFixture(h)
            val soft = mockk<AbstractWanderBackToPoint>()
            val hard = mockk<AbstractWanderBackToPoint>()
            f.controller.use { behavior ->
                f.distance(8.0)
                behavior.tick(f.player, false)
                behavior.onLeashStart(WanderBackToPointStartEvent(false, f.body, soft))
                behavior.onLeashStart(WanderBackToPointStartEvent(true, f.body, hard))
                f.holding shouldBe false
                behavior.tick(f.player, true)
                f.holding shouldBe false
                behavior.onLeashEnd(WanderBackToPointEndEvent(false, f.body, soft))
                behavior.isReturning shouldBe true
                behavior.tick(f.player, false)
                f.holding shouldBe false
                behavior.onLeashEnd(WanderBackToPointEndEvent(true, f.body, hard))
                behavior.isReturning shouldBe false
                behavior.tick(f.player, false)
                f.holding shouldBe true
            }
        }
    }

    "vanilla retreat uses an owned MOVE LOOK goal and reachable loaded paths while preserving target goals" {
        withStaffHarness { h ->
            val f = MageFixture(h, nativeMind = false)
            val goal = f.goal.captured
            goal.types shouldBe setOf(GoalType.MOVE, GoalType.LOOK)
            val path = mockk<Pathfinder.PathResult>()
            every { path.canReachFinalPoint() } returns true
            every { path.finalPoint } returns Location(f.world, .5, 64.0, -3.5)
            every { f.pathfinder.findPath(any<Location>()) } returns path
            every { f.pathfinder.moveTo(path, any()) } returns true
            f.controller.use { behavior ->
                f.distance(4.0)
                behavior.tick(f.player, false)
                goal.shouldActivate() shouldBe true
                goal.start()
                goal.tick()
                verify(exactly = 1) { f.pathfinder.moveTo(path, any()) }
                verify(exactly = 0) { f.adapter.beginFlee(any(), any(), any()) }
                behavior.tick(f.player, true)
                f.holding shouldBe true
                f.distance(12.0)
                behavior.tick(f.player, false)
                goal.shouldActivate() shouldBe false
            }
            verify(exactly = 1) { f.goals.removeGoal(f.body, goal) }
            verify(exactly = 0) { f.goals.removeAllGoals(any<Mob>()) }
            verify(exactly = 0) { f.body.target = any() }
            f.holding shouldBe false
        }
    }
})

private const val HOLD_KEY = "arcdungeons:staff_mage_hold"

private class MageFixture(h: StaffHarness, nativeMind: Boolean = true) {
    val world = h.paper.addSimpleWorld("em_mage_fixture")
    val player = h.paper.addPlayer("mage-target")
    val body = mockk<Mob>(relaxed = true)
    val speed = mockk<AttributeInstance>(relaxed = true)
    val pathfinder = mockk<Pathfinder>(relaxed = true)
    val adapter = mockk<NMSAdapter>(relaxed = true)
    val goals = mockk<MobGoals>(relaxed = true)
    val goal = slot<Goal<Mob>>()
    val modifiers = linkedMapOf("other:effect" to
        AttributeModifier(NamespacedKey("other", "effect"), .25, AttributeModifier.Operation.ADD_SCALAR))
    val holding get() = HOLD_KEY in modifiers
    var current = true
    var permitted = true
    var nativeTarget: Player? = player
    val controller: StaffDungeonMageBehavior

    init {
        val id = UUID.randomUUID()
        every { body.uniqueId } returns id
        every { body.isValid } returns true
        every { body.isDead } returns false
        every { body.hasMetadata(any()) } returns false
        every { body.world } returns world
        every { body.location } answers { Location(world, .5, 64.0, .5) }
        every { body.pathfinder } returns pathfinder
        every { body.getAttribute(Attribute.MOVEMENT_SPEED) } returns speed
        every { speed.getModifier(any<Key>()) } answers { modifiers[firstArg<Key>().asString()] }
        every { speed.addTransientModifier(any()) } answers {
            val modifier = firstArg<AttributeModifier>()
            modifiers[modifier.key.asString()] = modifier
        }
        every { speed.removeModifier(any<Key>()) } answers { modifiers.remove(firstArg<Key>().asString()); Unit }
        every { adapter.beginFlee(any(), any(), any()) } returns Optional.empty()
        every { goals.addGoal(body, 0, capture(goal)) } returns Unit
        val actor = object : StaffDungeonMob {
            override val body = this@MageFixture.body
            override fun isCurrent() = current
            override fun currentTarget() = nativeTarget
            override fun permitsTarget(player: Player) = permitted
            override fun damage(player: Player, multiplier: Double) = false
        }
        distance(8.0)
        controller = StaffDungeonMageBehavior(actor, 8.0, 2.0, adapter, nativeMind, goals)
        controller.start(h.paper.createSimplePlugin("MageBehaviorFixture"))
    }

    fun distance(blocks: Double) { player.teleport(Location(world, .5, 64.0, .5 + blocks)) }

    fun flee() = mockk<TransientMovementOverride>(relaxed = true) {
        every { isActive } returns true
        every { retarget(any()) } returns true
    }
}
