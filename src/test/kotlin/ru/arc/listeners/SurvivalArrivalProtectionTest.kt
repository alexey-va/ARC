package ru.arc.listeners

import org.bukkit.Input
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import ru.arc.core.TestTaskScheduler
import java.util.UUID

class SurvivalArrivalProtectionTest {
    @Test
    fun `an idle Survival arrival stays protected after the timer`() {
        val fixture = Fixture()
        val player = TestPlayer()
        fixture.join(player)
        fixture.scheduler.tick(140)

        assertFalse(player.invulnerable)
        fixture.protection.input(PlayerInputEvent(player.handle, mock<Input>()))
        fixture.protection.interact(player.interaction(Action.PHYSICAL))
        fixture.assertDamageBlocked(player, DamageCause.FALL)
        fixture.assertDamageBlocked(player, DamageCause.DROWNING)
        fixture.assertDamageBlocked(player, DamageCause.VOID)
        fixture.assertDamageAllowed(player, DamageCause.KILL)

        val jump = mock<Input>()
        whenever(jump.isJump).thenReturn(true)
        fixture.protection.input(PlayerInputEvent(player.handle, jump))

        fixture.assertDamageAllowed(player, DamageCause.FALL)
    }

    @Test
    fun `early activity keeps the full ordinary grace period`() {
        val fixture = Fixture()
        val player = TestPlayer()
        fixture.join(player)
        val forward = mock<Input>()
        whenever(forward.isForward).thenReturn(true)
        fixture.protection.input(PlayerInputEvent(player.handle, forward))

        fixture.scheduler.tick(139)
        assertTrue(player.invulnerable)
        fixture.scheduler.tick(1)
        assertFalse(player.invulnerable)
        fixture.assertDamageAllowed(player, DamageCause.FALL)
    }

    @Test
    fun `deliberate stationary use and mining end the idle protection`() {
        val fixture = Fixture()
        for (action in listOf(Action.RIGHT_CLICK_AIR, Action.LEFT_CLICK_BLOCK)) {
            val player = TestPlayer()
            fixture.join(player)
            fixture.scheduler.tick(140)

            fixture.protection.interact(player.interaction(action))

            fixture.assertDamageAllowed(player, DamageCause.ENTITY_ATTACK)
        }
    }

    @Test
    fun `a previous login timer cannot end protection for a fast relog`() {
        val fixture = Fixture()
        val first = TestPlayer()
        val second = TestPlayer(id = first.handle.uniqueId)
        fixture.join(first)
        fixture.scheduler.tick(20)
        fixture.protection.leave(first.handle)
        fixture.join(second)
        fixture.protection.activity(second.handle)

        fixture.scheduler.tick(120)
        assertFalse(first.invulnerable)
        assertTrue(second.invulnerable)
        fixture.scheduler.tick(20)
        assertFalse(second.invulnerable)
        fixture.assertDamageAllowed(second, DamageCause.FALL)
    }

    @Test
    fun `close removes arrival protection and preserves a pre-existing invulnerability flag`() {
        val fixture = Fixture()
        val regular = TestPlayer()
        val god = TestPlayer(invulnerable = true)
        fixture.join(regular)
        fixture.join(god)

        fixture.protection.close()
        fixture.scheduler.tick(140)

        assertFalse(regular.invulnerable)
        assertTrue(god.invulnerable)
        fixture.assertDamageAllowed(regular, DamageCause.FALL)
        fixture.assertDamageAllowed(god, DamageCause.VOID)
    }

    @Test
    fun `the bypass and other backends retain their existing join policy`() {
        val fixture = Fixture()
        val bypassed = TestPlayer(bypass = true)
        val spawn = TestPlayer()
        fixture.join(bypassed)
        fixture.join(spawn, survival = false)

        assertFalse(bypassed.invulnerable)
        fixture.assertDamageAllowed(bypassed, DamageCause.FALL)
        assertTrue(spawn.invulnerable)
        fixture.assertDamageAllowed(spawn, DamageCause.VOID)
        fixture.scheduler.tick(140)
        assertFalse(spawn.invulnerable)
        fixture.assertDamageAllowed(spawn, DamageCause.FALL)
    }

    private class Fixture {
        val scheduler = TestTaskScheduler()
        val protection = SurvivalArrivalProtection(
            enabled = { true },
            runLater = { ticks, action -> scheduler.runLater(ticks, action) },
        )

        fun join(player: TestPlayer, survival: Boolean = true) = protection.join(player.handle, 140, survival)

        fun assertDamageAllowed(player: TestPlayer, cause: DamageCause) {
            verify(hit(player, cause), never()).isCancelled = true
        }

        fun assertDamageBlocked(player: TestPlayer, cause: DamageCause) {
            verify(hit(player, cause)).isCancelled = true
        }

        private fun hit(player: TestPlayer, cause: DamageCause): EntityDamageEvent {
            val event = mock<EntityDamageEvent>()
            whenever(event.entity).thenReturn(player.handle)
            whenever(event.cause).thenReturn(cause)
            protection.damage(event)
            return event
        }
    }

    private class TestPlayer(
        id: UUID = UUID.randomUUID(),
        var invulnerable: Boolean = false,
        bypass: Boolean = false,
    ) {
        val handle = mock<Player>()

        init {
            whenever(handle.uniqueId).thenReturn(id)
            whenever(handle.name).thenReturn("ArrivalTester")
            whenever(handle.isOnline).thenReturn(true)
            whenever(handle.isInvulnerable).thenAnswer { invulnerable }
            whenever(handle.hasPermission("arc.join.invulnerability.bypass")).thenReturn(bypass)
            doAnswer {
                invulnerable = it.getArgument(0)
                null
            }.whenever(handle).isInvulnerable = any()
        }

        fun interaction(action: Action) = PlayerInteractEvent(handle, action, null, null, BlockFace.SELF)
    }
}
