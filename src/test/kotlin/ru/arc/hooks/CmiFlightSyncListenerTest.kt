package ru.arc.hooks

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.william278.husksync.data.Data
import net.william278.husksync.data.DataSnapshot
import net.william278.husksync.data.Identifier
import net.william278.husksync.event.BukkitPreSyncEvent
import net.william278.husksync.user.BukkitUser
import org.bukkit.GameMode
import org.bukkit.entity.Player
import java.util.Optional
import java.util.function.Consumer

class CmiFlightSyncListenerTest : StringSpec({
    "preserves destination flight permission and only removes the stale flight field" {
        val fixture = FlightSyncFixture()

        fixture.listener.onPreSync(fixture.event)

        fixture.fields.keys.shouldBe(setOf(Identifier.INVENTORY))
        verify(exactly = 0) { fixture.player.allowFlight = any() }
    }

    "leaves the snapshot intact when destination flight is forbidden or the charge is inactive" {
        val forbidden = FlightSyncFixture(allowFlight = false)
        val inactive = FlightSyncFixture(activeCharge = false)
        val noPermission = FlightSyncFixture(hasPermission = false)

        for (fixture in listOf(forbidden, inactive, noPermission)) {
            fixture.listener.onPreSync(fixture.event)
            verify(exactly = 0) { fixture.event.editData(any()) }
        }
    }

    "does not replace incoming flight or interfere with same-server, unlocked, or game-mode-changing restores" {
        val incomingFlight = FlightSyncFixture(incomingFlight = true)
        val sameServer = FlightSyncFixture(source = "survival")
        val unlocked = FlightSyncFixture(locked = false)
        val creative = FlightSyncFixture(incomingGameMode = GameMode.CREATIVE.name)

        for (fixture in listOf(incomingFlight, sameServer, unlocked, creative)) {
            fixture.listener.onPreSync(fixture.event)
            fixture.fields.containsKey(Identifier.FLIGHT_STATUS) shouldBe true
        }
    }
})

private class FlightSyncFixture(
    allowFlight: Boolean = true,
    activeCharge: Boolean = true,
    hasPermission: Boolean = true,
    locked: Boolean = true,
    source: String = "spawn",
    incomingFlight: Boolean = false,
    incomingGameMode: String = GameMode.SURVIVAL.name,
) {
    val player = mockk<Player> {
        every { gameMode } returns GameMode.SURVIVAL
        every { this@mockk.allowFlight } returns allowFlight
        every { hasPermission("cmi.command.flyc") } returns hasPermission
    }
    private val user = mockk<BukkitUser> {
        every { player } returns this@FlightSyncFixture.player
        every { isLocked } returns locked
    }
    private val packed = mockk<DataSnapshot.Packed> {
        every { serverName } returns source
    }
    private val flight = mockk<Data.FlightStatus> {
        every { isAllowFlight } returns incomingFlight
    }
    val fields = mutableMapOf<Identifier, Data>(Identifier.FLIGHT_STATUS to flight, Identifier.INVENTORY to mockk<Data>())
    private val snapshot = mockk<DataSnapshot.Unpacked> {
        every { data } returns fields
        every { flightStatus } returns Optional.of(flight)
        every { gameMode } returns Optional.of(mockk<Data.GameMode> { every { gameMode } returns incomingGameMode })
    }
    val event = mockk<BukkitPreSyncEvent> {
        every { saveCause } returns DataSnapshot.SaveCause.DISCONNECT
        every { data } returns packed
        every { user } returns this@FlightSyncFixture.user
        every { editData(any()) } answers { firstArg<Consumer<DataSnapshot.Unpacked>>().accept(snapshot) }
    }
    val listener = CmiFlightSyncListener("survival") { activeCharge }
}
