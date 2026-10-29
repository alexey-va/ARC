package ru.arc.playerseat

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector
import ru.arc.core.TestEventBus
import java.util.UUID

class PlayerSeatRuntimeTest : StringSpec({
    "mounts airborne players without changing flight or gliding state" {
        SeatFixture().use { f ->
            for (player in listOf(f.rider, f.carrier)) {
                every { player.isOnGround } returns false
                every { player.isFlying } returns true
                every { player.allowFlight } returns true
                every { player.isGliding } returns true
            }

            f.click().isCancelled shouldBe true

            f.riderVehicle shouldBe f.carrier
            f.carrierPassengers shouldBe listOf(f.rider)
            for (player in listOf(f.rider, f.carrier)) {
                player.isFlying shouldBe true
                player.allowFlight shouldBe true
                player.isGliding shouldBe true
                verify(exactly = 0) { player.isFlying = any() }
                verify(exactly = 0) { player.allowFlight = any() }
                verify(exactly = 0) { player.isGliding = any() }
            }
        }
    }

    "mounts a player on the ground" {
        SeatFixture().use { f ->
            every { f.rider.isOnGround } returns true
            every { f.carrier.isOnGround } returns true

            f.click().isCancelled shouldBe true

            f.riderVehicle shouldBe f.carrier
            verify(exactly = 1) { f.carrier.addPassenger(f.rider) }
        }
    }

    val denied = listOf<Pair<String, SeatFixture.() -> Unit>>(
        "offhand" to { hand = EquipmentSlot.OFF_HAND },
        "held item" to { every { rider.inventory.itemInMainHand.type } returns Material.STONE },
        "NPC rider" to { every { rider.hasMetadata("NPC") } returns true },
        "NPC carrier" to { every { carrier.hasMetadata("NPC") } returns true },
        "spectator rider" to { every { rider.gameMode } returns GameMode.SPECTATOR },
        "spectator carrier" to { every { carrier.gameMode } returns GameMode.SPECTATOR },
        "offline rider" to { every { rider.isOnline } returns false },
        "offline carrier" to { every { carrier.isOnline } returns false },
        "dead rider" to { every { rider.isDead } returns true },
        "dead carrier" to { every { carrier.isDead } returns true },
        "sneaking rider" to { every { rider.isSneaking } returns true },
        "sneaking carrier" to { every { carrier.isSneaking } returns true },
        "hidden carrier" to { every { rider.canSee(carrier) } returns false },
        "hidden rider" to { every { carrier.canSee(rider) } returns false },
        "rider already riding" to { riderVehicle = other },
        "carrier already riding" to { every { carrier.vehicle } returns other },
        "rider already carrying" to { every { rider.passengers } returns listOf(other) },
        "occupied carrier" to { carrierPassengers += other },
        "carrier preference disabled" to { canCarry = false },
    )
    denied.forEach { (reason, arrange) ->
        "does not mount with $reason" {
            SeatFixture().use { f ->
                f.arrange()

                f.click().isCancelled shouldBe false

                verify(exactly = 0) { f.carrier.addPassenger(any()) }
            }
        }
    }

    "preserves a cancelled interaction" {
        SeatFixture().use { f ->
            val event = PlayerInteractEntityEvent(f.rider, f.carrier, EquipmentSlot.HAND)
            event.isCancelled = true

            f.bus.fire(event)

            event.isCancelled shouldBe true
            verify(exactly = 0) { f.carrier.addPassenger(any()) }
        }
    }

    "rejects self interaction" {
        SeatFixture().use { f ->
            val event = PlayerInteractEntityEvent(f.rider, f.rider, EquipmentSlot.HAND)

            f.bus.fire(event)

            event.isCancelled shouldBe false
            verify(exactly = 0) { f.rider.addPassenger(any()) }
        }
    }

    "leaves the interaction available when the native mount fails" {
        SeatFixture().use { f ->
            f.mountSucceeds = false

            f.click().isCancelled shouldBe false
            f.riderVehicle shouldBe null
            f.mountSucceeds = true
            f.click().isCancelled shouldBe true
        }
    }

    "handles a precise and paired generic click with one native mount" {
        SeatFixture().use { f ->
            val precise = PlayerInteractAtEntityEvent(f.rider, f.carrier, Vector(0.0, 1.5, 0.0), EquipmentSlot.HAND)

            f.bus.fire(precise)
            val generic = f.click()

            precise.isCancelled shouldBe true
            generic.isCancelled shouldBe true
            verify(exactly = 1) { f.carrier.addPassenger(f.rider) }
        }
    }

    for (side in listOf("rider", "carrier")) {
        "Shift by the $side removes only the owned passenger" {
            SeatFixture().use { f ->
                f.click()
                val player = if (side == "rider") f.rider else f.carrier
                f.carrierPassengers += f.other

                f.bus.fire(PlayerToggleSneakEvent(player, true))

                f.riderVehicle shouldBe null
                f.carrierPassengers shouldBe listOf(f.other)
                verify(exactly = 1) { f.carrier.removePassenger(f.rider) }
                verify(exactly = 0) { f.carrier.removePassenger(f.other) }
                verify(exactly = 0) { f.carrier.eject() }
            }
        }
    }

    "keeps ownership when another plugin cancels native dismount" {
        SeatFixture().use { f ->
            f.click()
            f.dismountSucceeds = false
            val native = EntityDismountEvent(f.rider, f.carrier).also { it.isCancelled = true }
            f.bus.fire(native)

            f.bus.fire(PlayerToggleSneakEvent(f.carrier, true))
            f.riderVehicle shouldBe f.carrier
            f.dismountSucceeds = true
            f.bus.fire(PlayerToggleSneakEvent(f.carrier, true))

            f.riderVehicle shouldBe null
            verify(exactly = 2) { f.carrier.removePassenger(f.rider) }
        }
    }

    "forgets an uncancelled native dismount without dismounting a later unrelated ride" {
        SeatFixture().use { f ->
            f.click()
            f.bus.fire(EntityDismountEvent(f.rider, f.carrier))
            f.riderVehicle = f.other
            f.carrierPassengers.clear()

            f.bus.fire(PlayerToggleSneakEvent(f.rider, true))
            f.close()

            f.riderVehicle shouldBe f.other
            verify(exactly = 0) { f.carrier.removePassenger(any()) }
            verify(exactly = 0) { f.rider.leaveVehicle() }
        }
    }

    val cleanups = listOf<Pair<String, SeatFixture.() -> Unit>>(
        "rider quit" to { bus.fire(mockk<PlayerQuitEvent> { every { player } returns rider }) },
        "carrier death" to {
            bus.fire(mockk<PlayerDeathEvent>(relaxed = true) { every { entity } returns carrier })
        },
        "carrier teleport" to {
            bus.fire(mockk<PlayerTeleportEvent>(relaxed = true) { every { player } returns carrier })
        },
        "module close" to { close() },
    )
    cleanups.forEach { (reason, cleanup) ->
        "cleans the owned relation on $reason" {
            SeatFixture().use { f ->
                f.click()

                f.cleanup()

                f.riderVehicle shouldBe null
                f.carrierPassengers shouldBe emptyList()
                verify(exactly = 1) { f.carrier.removePassenger(f.rider) }
            }
        }
    }

    "keeps a ride for cancelled teleport and sneak events" {
        SeatFixture().use { f ->
            f.click()
            val teleport = mockk<PlayerTeleportEvent>(relaxed = true) {
                every { player } returns f.carrier
                every { isCancelled } returns true
            }
            val sneak = PlayerToggleSneakEvent(f.carrier, true).also { it.isCancelled = true }

            f.bus.fire(teleport)
            f.bus.fire(sneak)

            f.riderVehicle shouldBe f.carrier
            verify(exactly = 0) { f.carrier.removePassenger(any()) }
        }
    }

    "reports blocked close and retains ownership for a cleanup retry" {
        SeatFixture().use { f ->
            f.click()
            f.dismountSucceeds = false

            shouldThrow<IllegalStateException> { f.close() }.message shouldBe
                "Player seats could not dismount 1 owned rider(s)"
            f.riderVehicle shouldBe f.carrier
            f.bus.handlerCount() shouldBe 0

            f.dismountSucceeds = true
            f.close()

            f.riderVehicle shouldBe null
            verify(exactly = 2) { f.carrier.removePassenger(f.rider) }
        }
    }

    "closing unregisters the old runtime before a fresh generation starts" {
        SeatFixture().use { f ->
            f.click()
            f.close()
            f.bus.handlerCount() shouldBe 0
            f.click().isCancelled shouldBe false

            PlayerSeatRuntime(f.bus).use { fresh ->
                fresh.start()
                f.click().isCancelled shouldBe true
            }

            f.riderVehicle shouldBe null
            f.bus.handlerCount() shouldBe 0
        }
    }
})

private class SeatFixture : AutoCloseable {
    val rider = seatPlayer()
    val carrier = seatPlayer()
    val other = seatPlayer()
    val bus = TestEventBus()
    val carrierPassengers = mutableListOf<Entity>()
    var riderVehicle: Entity? = null
    var hand = EquipmentSlot.HAND
    var mountSucceeds = true
    var dismountSucceeds = true
    var canCarry = true
    private val runtime = PlayerSeatRuntime(bus) { canCarry }

    init {
        every { rider.vehicle } answers { riderVehicle }
        every { carrier.passengers } answers { carrierPassengers.toList() }
        every { carrier.addPassenger(rider) } answers {
            if (mountSucceeds) {
                carrierPassengers += rider
                riderVehicle = carrier
            }
            mountSucceeds
        }
        every { carrier.removePassenger(rider) } answers {
            if (dismountSucceeds) {
                carrierPassengers.remove(rider)
                if (riderVehicle == carrier) riderVehicle = null
            }
            dismountSucceeds
        }
        runtime.start()
    }

    fun click(): PlayerInteractEntityEvent = PlayerInteractEntityEvent(rider, carrier, hand).also(bus::fire)

    override fun close() = runtime.close()
}

private fun seatPlayer(): Player = mockk<Player>(relaxed = true).also { player ->
    every { player.uniqueId } returns UUID.randomUUID()
    every { player.isOnline } returns true
    every { player.isDead } returns false
    every { player.gameMode } returns GameMode.SURVIVAL
    every { player.isSneaking } returns false
    every { player.hasMetadata("NPC") } returns false
    every { player.vehicle } returns null
    every { player.isInsideVehicle } answers { player.vehicle != null }
    every { player.passengers } returns emptyList()
    every { player.canSee(any<Player>()) } returns true
    every { player.inventory.itemInMainHand.type } returns Material.AIR
}
