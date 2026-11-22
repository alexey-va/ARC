package ru.arc.playerseat

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.EventManager
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.attribute.Attributes
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.settings.PacketEventsSettings
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.Property
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.PropertyModifier
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.bukkit.NamespacedKey
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import java.util.UUID

class SeatPassengerViewTest : StringSpec({
    val previousApi = PacketEvents.getAPI()
    val api = mockk<PacketEventsAPI<Any>>(relaxed = true)
    val version = ServerVersion.V_1_21_11
    beforeSpec {
        every { api.settings } returns PacketEventsSettings()
        every { api.serverManager.version } returns version
        PacketEvents.setAPI(api)
    }
    afterSpec { PacketEvents.setAPI(previousApi) }

    "masking a copy leaves bystanders' pose, fire, glow and movement flags intact" {
        val native = listOf(
            EntityData(0, EntityDataTypes.BYTE, 0xdb.toByte()), // Fire, sneak, sprint, swim, glow, glide.
            EntityData(6, EntityDataTypes.ENTITY_POSE, EntityPose.STANDING),
            EntityData(8, EntityDataTypes.BYTE, 0.toByte()), // Living flags include the trident spin.
        )
        val masked = requireNotNull(maskedSeatMetadata(native))
        masked.map { it.value } shouldBe listOf(0xba.toByte(), EntityPose.SPIN_ATTACK, 0.toByte())
        native.map { it.value } shouldBe listOf(0xdb.toByte(), EntityPose.STANDING, 0.toByte())
        maskedSeatMetadata(masked) shouldBe null
        maskedSeatMetadata(native.takeLast(1)) shouldBe null
    }

    "only the rider scale is replaced and its current native base and named modifier can be restored" {
        val modifier = mockk<AttributeModifier> {
            every { key } returns NamespacedKey.minecraft("test_scale")
            every { amount } returns 0.5
            every { operation } returns AttributeModifier.Operation.MULTIPLY_SCALAR_1
        }
        val scale = mockk<AttributeInstance> {
            every { baseValue } returns 1.25
            every { modifiers } returns listOf(modifier)
        }
        val native = nativeSeatScale(scale)
        val health = Property(Attributes.MAX_HEALTH, 20.0, emptyList())
        val masked = requireNotNull(maskedSeatScale(listOf(health, native)))
        masked.first() shouldBe health
        masked.last().value shouldBe 0.0625
        masked.last().modifiers.size shouldBe 0
        native.value shouldBe 1.25
        native.modifiers.single().name.toString() shouldBe "minecraft:test_scale"
        native.modifiers.single().amount shouldBe 0.5
        native.modifiers.single().operation shouldBe PropertyModifier.Operation.MULTIPLY_TOTAL
        maskedSeatScale(listOf(health)) shouldBe null
        maskedSeatScale(masked) shouldBe null
    }

    "each carrier masks the full branch above it and releasing the middle link restores only the detached branch" {
        val listener = slot<PacketListenerAbstract>()
        val events = mockk<EventManager>(relaxed = true)
        every { events.registerListener(capture(listener)) } answers { listener.captured }
        every { api.eventManager } returns events
        fun player(id: Int): Player = mockk {
            every { uniqueId } returns UUID(0, id.toLong())
            every { entityId } returns id
            every { isOnline } returns false
            every { vehicle } returns null
        }
        fun checkScale(viewer: Player, target: Player, masked: Boolean) {
            val event = mockk<PacketSendEvent>(relaxed = true)
            every { event.packetType } returns PacketType.Play.Server.UPDATE_ATTRIBUTES
            every { event.user.uuid } returns viewer.uniqueId
            every { event.user.clientVersion } returns version.toClientVersion()
            every { event.serverVersion } returns version
            every { event.lastUsedWrapper } returns WrapperPlayServerUpdateAttributes(
                target.entityId, listOf(Property(Attributes.SCALE, 1.0, emptyList())),
            )
            listener.captured.onPacketSend(event)
            verify(exactly = if (masked) 1 else 0) { event.markForReEncode(true) }
        }
        val bottom = player(1)
        val middle = player(2)
        val top = player(3)
        val observer = player(4)
        every { middle.vehicle } returns bottom
        every { top.vehicle } returns middle
        SeatPassengerView().use { view ->
            view.setHidden(bottom, middle, true)
            view.setHidden(middle, top, true)
            checkScale(bottom, middle, true)
            checkScale(bottom, top, true)
            checkScale(middle, top, true)
            checkScale(top, middle, false)
            checkScale(observer, top, false)
            every { middle.vehicle } returns null
            view.setHidden(bottom, middle, false)
            checkScale(bottom, middle, false)
            checkScale(bottom, top, false)
            checkScale(middle, top, true)
        }
    }
})
