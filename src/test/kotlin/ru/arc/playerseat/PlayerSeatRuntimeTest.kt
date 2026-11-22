package ru.arc.playerseat

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector
import ru.arc.core.TestEventBus
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
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

    "a crouching carrier can be mounted directly and at the base of a tower" {
        SeatFixture().use { f ->
            every { f.carrier.isSneaking } returns true
            f.click().isCancelled shouldBe true
            f.click(f.other, f.carrier).isCancelled shouldBe true
            f.rider.vehicle shouldBe f.carrier
            f.other.vehicle shouldBe f.rider
        }
    }

    "each carrier hides only their direct rider and restores them on dismount or quit" {
        val view = mockk<SeatPassengerView>(relaxed = true)
        Tasks.withScheduler(TestTaskScheduler()) {
            SeatFixture(passengerView = view).use { f ->
                f.click()
                f.click(f.other, f.carrier)
                verify(exactly = 1) { view.setHidden(f.carrier, f.rider, true) }
                verify(exactly = 1) { view.setHidden(f.rider, f.other, true) }
                verify(exactly = 0) { view.setHidden(f.carrier, f.other, any()) }
                f.bus.fire(PlayerToggleSneakEvent(f.rider, true))
                verify(exactly = 1) { view.setHidden(f.carrier, f.rider, false) }
                verify(exactly = 0) { view.setHidden(f.rider, f.other, false) }
                f.bus.fire(mockk<PlayerQuitEvent> { every { player } returns f.other })
                verify(exactly = 1) { view.setHidden(f.rider, f.other, false) }
            }
        }
        verify { view.close() }
    }

    "losing the native mount restores the carrier view before the rider has used the compass" {
        val scheduler = TestTaskScheduler()
        val view = mockk<SeatPassengerView>(relaxed = true)
        Tasks.withScheduler(scheduler) {
            SeatFixture(passengerView = view).use { f ->
                f.click()
                f.riderVehicle = null
                scheduler.tick()
                verify(exactly = 1) { view.setHidden(f.carrier, f.rider, false) }
                scheduler.timerCount() shouldBe 0
            }
        }
    }

    "crouching does not override the carrier's ride preference" {
        SeatFixture().use { f ->
            every { f.carrier.isSneaking } returns true
            f.canCarry = false
            f.click().isCancelled shouldBe false
            verify(exactly = 0) { f.carrier.addPassenger(any()) }
        }
    }

    "the next click recovers either end of a seat lost before ARC saw the native dismount" {
        SeatFixture().use { f ->
            f.click()
            f.riderVehicle = null
            f.carrierPassengers.clear()

            f.click(f.other, f.carrier).isCancelled shouldBe true
            f.click(f.rider, f.other).isCancelled shouldBe true
            f.other.vehicle shouldBe f.carrier
            f.rider.vehicle shouldBe f.other
            f.nameTags shouldBe listOf(f.rider to f.carrier.uniqueId, f.rider to null,
                f.other to f.carrier.uniqueId, f.rider to f.other.uniqueId)
        }
        SeatFixture().use { f ->
            f.click()
            f.riderVehicle = null
            f.carrierPassengers.clear()
            f.click(f.rider, f.other).isCancelled shouldBe true
            f.rider.vehicle shouldBe f.other
        }
    }

    "only a current seat rider is protected against wall suffocation" {
        SeatFixture().use { f ->
            fun damage(who: Player, cause: EntityDamageEvent.DamageCause): Boolean {
                val event = mockk<EntityDamageEvent>(relaxed = true)
                var cancelled = false
                every { event.entity } returns who
                every { event.cause } returns cause
                every { event.isCancelled } answers { cancelled }
                every { event.isCancelled = any() } answers { cancelled = firstArg() }
                f.bus.fire(event)
                return cancelled
            }
            val wall = EntityDamageEvent.DamageCause.SUFFOCATION
            f.click()
            f.click(f.other, f.rider)
            damage(f.rider, wall) shouldBe true
            damage(f.other, wall) shouldBe true
            damage(f.carrier, wall) shouldBe false
            damage(f.rider, EntityDamageEvent.DamageCause.ENTITY_ATTACK) shouldBe false
            damage(f.rider, EntityDamageEvent.DamageCause.FALL) shouldBe false
            f.bus.fire(PlayerToggleSneakEvent(f.rider, true))
            damage(f.rider, wall) shouldBe false
            damage(f.other, wall) shouldBe true
            f.riderVehicle = f.other
            damage(f.rider, wall) shouldBe false
        }
    }

    "one swing starts continuous steering and further swings change the signal without consuming attacks" {
        val scheduler = TestTaskScheduler()
        val display = mockk<SeatDirections>(relaxed = true)
        Tasks.withScheduler(scheduler) {
            SeatFixture(display).use { f ->
                f.click()
                every { f.rider.ticksLived } returns 2
                every { f.rider.yaw } returns 90f
                f.gesture(Action.LEFT_CLICK_AIR, cancelled = true)
                f.gesture(Action.LEFT_CLICK_BLOCK)
                verify(exactly = 1) { display.show(f.carrier, f.rider, 90f, "Туда Нахуй!") }
                scheduler.tick(100)
                every { f.rider.yaw } returns -45f
                scheduler.tick()
                verify(exactly = 1) { display.turn(f.carrier, f.rider, -45f) }
                verify(exactly = 1) { display.show(any(), any(), any(), any()) }
                every { f.rider.ticksLived } returns 101
                val hit = PrePlayerAttackEntityEvent(f.rider, f.other, true)
                f.bus.fire(hit)
                hit.isCancelled shouldBe false
                verify { display.show(f.carrier, f.rider, -45f, "Пиздуй туда!") }
                scheduler.tick(100)
                scheduler.timerCount() shouldBe 1
                verify(exactly = 0) { display.hide(any()) }
                f.riderVehicle = null
                scheduler.tick()
                scheduler.timerCount() shouldBe 0
                val riderId = f.rider.uniqueId
                verify(exactly = 1) { display.hide(riderId) }
            }
        }
    }

    "right clicks, a held item and a foreign vehicle do not point" {
        val display = mockk<SeatDirections>(relaxed = true)
        Tasks.withScheduler(TestTaskScheduler()) {
            SeatFixture(display).use { f ->
                f.gesture(Action.LEFT_CLICK_AIR)
                f.click()
                f.gesture(Action.LEFT_CLICK_AIR)
                f.click()
                f.gesture(Action.RIGHT_CLICK_AIR)
                f.gesture(Action.RIGHT_CLICK_BLOCK)
                every { f.rider.ticksLived } returns 1
                f.gesture(Action.LEFT_CLICK_AIR)
                f.gesture(Action.LEFT_CLICK_BLOCK)
                every { f.rider.ticksLived } returns 5
                f.bus.fire(mockk<PlayerDropItemEvent>(relaxed = true) { every { player } returns f.rider })
                f.gesture(Action.LEFT_CLICK_AIR)
                f.gesture(Action.LEFT_CLICK_AIR, EquipmentSlot.OFF_HAND)
                every { f.rider.inventory.itemInMainHand.type } returns Material.STICK
                f.gesture(Action.LEFT_CLICK_BLOCK)
                every { f.rider.inventory.itemInMainHand.type } returns Material.AIR
                f.riderVehicle = f.other
                f.gesture(Action.LEFT_CLICK_AIR)
                verify(exactly = 0) { display.show(any(), any(), any(), any()) }
            }
        }
    }

    "a block hit by the upper rider points for the immediate carrier and dismount clears it" {
        val scheduler = TestTaskScheduler()
        val display = mockk<SeatDirections>(relaxed = true)
        Tasks.withScheduler(scheduler) {
            SeatFixture(display).use { f ->
                f.click()
                f.click(f.other, f.carrier)
                every { f.other.ticksLived } returns 2
                f.gesture(Action.LEFT_CLICK_BLOCK, who = f.other)
                verify { display.show(f.rider, f.other, any(), any()) }
                f.bus.fire(PlayerToggleSneakEvent(f.other, true))
                val upperRiderId = f.other.uniqueId
                verify(exactly = 1) { display.hide(upperRiderId) }
                scheduler.tick(80)
                verify(exactly = 1) { display.hide(any()) }
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

    "Shift dismounts the rider and leaves unrelated passengers alone" {
        SeatFixture().use { f ->
            f.click()
            f.carrierPassengers += f.other

            f.bus.fire(PlayerToggleSneakEvent(f.carrier, true))
            f.riderVehicle shouldBe f.carrier
            f.bus.fire(PlayerToggleSneakEvent(f.rider, true))

            f.riderVehicle shouldBe null
            f.carrierPassengers shouldBe listOf(f.other)
            verify(exactly = 1) { f.carrier.removePassenger(f.rider) }
            verify(exactly = 0) { f.carrier.removePassenger(f.other) }
            verify(exactly = 0) { f.carrier.eject() }
        }
    }

    "clicks on the base or the top extend a tower, middle Shift takes the upper group along" {
        SeatFixture().use { f ->
            val fourth = f.player()
            f.click().isCancelled shouldBe true
            f.click(f.other, f.carrier).isCancelled shouldBe true
            f.click(f.other, f.carrier).isCancelled shouldBe true
            verify(exactly = 1) { f.rider.addPassenger(f.other) }
            f.click(fourth, f.other).isCancelled shouldBe true

            f.bus.fire(PlayerToggleSneakEvent(f.carrier, true))
            f.rider.vehicle shouldBe f.carrier
            f.other.vehicle shouldBe f.rider
            fourth.vehicle shouldBe f.other

            f.bus.fire(PlayerToggleSneakEvent(f.rider, true))
            f.bus.fire(mockk<PlayerTeleportEvent>(relaxed = true) {
                every { player } returns f.rider
                every { cause } returns PlayerTeleportEvent.TeleportCause.DISMOUNT
            })

            f.rider.vehicle shouldBe null
            f.other.vehicle shouldBe f.rider
            fourth.vehicle shouldBe f.other
            f.click(f.rider, fourth).isCancelled shouldBe false
            verify(exactly = 0) { fourth.addPassenger(any()) }
            f.click(f.rider, f.carrier).isCancelled shouldBe true
            f.rider.vehicle shouldBe f.carrier
            f.other.vehicle shouldBe f.rider
        }
    }

    "joining an occupied tower respects the actual top player's ride preference and visibility" {
        SeatFixture().use { f ->
            f.click()
            f.canCarry = false
            f.click(f.other, f.carrier).isCancelled shouldBe false
            f.canCarry = true
            every { f.other.canSee(f.rider) } returns false
            f.click(f.other, f.carrier).isCancelled shouldBe false
            f.other.vehicle shouldBe null
            verify(exactly = 0) { f.rider.addPassenger(any()) }
        }
    }

    "only the direct rider receives a nametag update on a successful mount or dismount" {
        SeatFixture().use { f ->
            f.mountSucceeds = false
            f.click()
            f.nameTags shouldBe emptyList()
            f.mountSucceeds = true
            f.click()
            f.click(f.other, f.carrier)
            f.click(f.other, f.carrier)
            f.bus.fire(PlayerToggleSneakEvent(f.carrier, true))
            f.dismountSucceeds = false
            f.bus.fire(PlayerToggleSneakEvent(f.rider, true))
            f.dismountSucceeds = true
            f.bus.fire(PlayerToggleSneakEvent(f.rider, true))
            f.bus.fire(EntityDismountEvent(f.rider, f.carrier))

            f.nameTags shouldBe listOf(f.rider to f.carrier.uniqueId, f.other to f.rider.uniqueId, f.rider to null)
        }
    }

    "the proxy payload is one big-endian UUID, or empty to restore the tag" {
        val id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        seatNameTagPayload(id).toHexString() shouldBe "00112233445566778899aabbccddeeff"
        seatNameTagPayload(null).size shouldBe 0
    }

    "when a middle player quits only the two adjacent seats are cleared" {
        SeatFixture().use { f ->
            val fourth = f.player()
            f.click()
            f.click(f.other, f.carrier)
            f.click(fourth, f.other)

            f.bus.fire(mockk<PlayerQuitEvent> { every { player } returns f.rider })

            f.rider.vehicle shouldBe null
            f.other.vehicle shouldBe null
            fourth.vehicle shouldBe f.other
        }
    }

    "keeps ownership when another plugin cancels native dismount" {
        SeatFixture().use { f ->
            f.click()
            f.dismountSucceeds = false
            val native = EntityDismountEvent(f.rider, f.carrier).also { it.isCancelled = true }
            f.bus.fire(native)

            f.bus.fire(PlayerToggleSneakEvent(f.rider, true))
            f.riderVehicle shouldBe f.carrier
            f.dismountSucceeds = true
            f.bus.fire(PlayerToggleSneakEvent(f.rider, true))

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
            val sneak = PlayerToggleSneakEvent(f.rider, true).also { it.isCancelled = true }

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

private class SeatFixture(directions: SeatDirections? = null, passengerView: SeatPassengerView? = null) : AutoCloseable {
    private val vehicles = mutableMapOf<Player, Entity?>()
    private val passengers = mutableMapOf<Player, MutableList<Entity>>()
    val rider = player()
    val carrier = player()
    val other = player()
    val bus = TestEventBus()
    val carrierPassengers get() = passengers.getValue(carrier)
    var riderVehicle: Entity?
        get() = vehicles[rider]
        set(value) { vehicles[rider] = value }
    var hand = EquipmentSlot.HAND
    var mountSucceeds = true
    var dismountSucceeds = true
    var canCarry = true
    val nameTags = mutableListOf<Pair<Player, UUID?>>()
    private val runtime = PlayerSeatRuntime(bus, { who, carrierId -> nameTags += who to carrierId }, directions, passengerView) { canCarry }

    init { runtime.start() }

    fun player(): Player = seatPlayer().also { carrier ->
        val aboard = mutableListOf<Entity>()
        passengers[carrier] = aboard
        every { carrier.vehicle } answers { vehicles[carrier] }
        every { carrier.passengers } answers { aboard.toList() }
        every { carrier.addPassenger(any()) } answers {
            val rider = firstArg<Player>()
            if (mountSucceeds) {
                aboard += rider
                vehicles[rider] = carrier
            }
            mountSucceeds
        }
        every { carrier.removePassenger(any()) } answers {
            val rider = firstArg<Player>()
            if (dismountSucceeds) {
                aboard.remove(rider)
                if (vehicles[rider] == carrier) vehicles.remove(rider)
            }
            dismountSucceeds
        }
    }

    fun click(actor: Player = rider, target: Player = carrier): PlayerInteractEntityEvent =
        PlayerInteractEntityEvent(actor, target, hand).also(bus::fire)

    fun gesture(action: Action, hand: EquipmentSlot = EquipmentSlot.HAND, who: Player = rider, cancelled: Boolean = false) {
        val click = mockk<PlayerInteractEvent>()
        every { click.action } returns action
        every { click.hand } returns hand
        every { click.player } returns who
        every { click.isCancelled } returns cancelled
        bus.fire(click)
    }

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
