package ru.arc.misc

import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import ru.arc.core.TestTaskScheduler
import java.util.UUID

class BuilderSurvivalGuardTest {
    @Test
    fun `clears builder creative and flight on survival after forty ticks`() {
        val fixture = Fixture()
        fixture.guard.checkLater(fixture.player)

        fixture.scheduler.tick(39)
        fixture.verifyUntouched()
        fixture.scheduler.tick(1)

        verify(fixture.player).gameMode = GameMode.SURVIVAL
        verify(fixture.player).isFlying = false
        verify(fixture.player).allowFlight = false
    }

    @Test
    fun `leaves spawn and nonbuilder players untouched`() {
        for (fixture in listOf(Fixture(server = "spawn"), Fixture(builder = false))) {
            fixture.guard.checkLater(fixture.player)
            fixture.scheduler.tick(40)
            fixture.verifyUntouched()
        }
    }

    @Test
    fun `rechecks online status runtime membership and session after the delay`() {
        val offline = Fixture()
        val moved = Fixture()
        val removedBuilder = Fixture()
        val reconnected = Fixture()
        val closed = Fixture()
        val fixtures = listOf(offline, moved, removedBuilder, reconnected, closed)
        fixtures.forEach { it.guard.checkLater(it.player) }
        whenever(offline.player.isOnline).thenReturn(false)
        moved.server = "spawn"
        removedBuilder.builder = false
        reconnected.current = mock()
        closed.guard.close()

        fixtures.forEach {
            it.scheduler.tick(40)
            it.verifyUntouched()
        }
    }

    @Test
    fun `a late sync schedules another delayed correction`() {
        val fixture = Fixture().apply { whenever(player.gameMode).thenReturn(GameMode.SURVIVAL) }
        fixture.guard.checkLater(fixture.player)
        fixture.scheduler.tick(80)
        fixture.verifyUntouched()

        whenever(fixture.player.gameMode).thenReturn(GameMode.CREATIVE)
        fixture.guard.checkLater(fixture.player)
        fixture.scheduler.tick(39)
        fixture.verifyUntouched()
        fixture.scheduler.tick(1)

        verify(fixture.player).gameMode = GameMode.SURVIVAL
        verify(fixture.player).isFlying = false
        verify(fixture.player).allowFlight = false
    }

    @Test
    fun `keeps an existing survival players legitimate flight unchanged`() {
        val fixture = Fixture().apply { whenever(player.gameMode).thenReturn(GameMode.SURVIVAL) }
        fixture.guard.checkLater(fixture.player)
        fixture.scheduler.tick(40)
        fixture.verifyUntouched()
    }

    private class Fixture(var server: String = "survival", var builder: Boolean = true) {
        val scheduler = TestTaskScheduler()
        val player = mock<Player>()
        var current: Player? = player
        val guard = BuilderSurvivalGuard(
            serverName = { server },
            isBuilder = { builder },
            currentPlayer = { current },
            runLater = { ticks, action -> scheduler.runLater(ticks, action) },
        )

        init {
            whenever(player.uniqueId).thenReturn(UUID.randomUUID())
            whenever(player.isOnline).thenReturn(true)
            whenever(player.gameMode).thenReturn(GameMode.CREATIVE)
        }

        fun verifyUntouched() {
            verify(player, never()).gameMode = any()
            verify(player, never()).isFlying = any()
            verify(player, never()).allowFlight = any()
        }
    }
}
