package ru.arc.staffspells

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.EventManager
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemType
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot as PacketEquipmentSlot
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.mapper.MappedEntity
import com.github.retrooper.packetevents.util.mappings.IRegistry
import com.github.retrooper.packetevents.settings.PacketEventsSettings
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.EntityEquipment
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StaffMobEquipmentVisualsTest : FreeSpec({
    "native equipment updates replace only main hand from an isolated snapshot without Bukkit reads" {
        withMobHandFixture { h ->
            val source = ItemStack(Material.BLAZE_ROD, 1)
            h.visual(source)
            source.amount = 12
            h.converted.single().amount = 30
            clearMocks(h.mob, h.equipment, answers = false)

            val offHand = Equipment(PacketEquipmentSlot.OFF_HAND, packetItem(ItemTypes.SHIELD))
            val head = Equipment(PacketEquipmentSlot.HELMET, packetItem(ItemTypes.DIAMOND_HELMET))
            val native = listOf(Equipment(PacketEquipmentSlot.MAIN_HAND, packetItem(ItemTypes.NETHERITE_SWORD)), offHand, head)
            val event = h.event(WrapperPlayServerEntityEquipment(h.entityId, native))
            Executors.newSingleThreadExecutor().use { worker ->
                worker.submit { h.send(event) }.get(5, TimeUnit.SECONDS)
            }
            val adjusted = (event.lastUsedWrapper as WrapperPlayServerEntityEquipment).equipment
            adjusted.associate { it.slot to it.item.type } shouldBe mapOf(
                PacketEquipmentSlot.MAIN_HAND to ItemTypes.BLAZE_ROD,
                PacketEquipmentSlot.OFF_HAND to ItemTypes.SHIELD,
                PacketEquipmentSlot.HELMET to ItemTypes.DIAMOND_HELMET,
            )
            adjusted.single { it.slot == PacketEquipmentSlot.MAIN_HAND }.item.amount shouldBe 1
            native.first().item.type shouldBe ItemTypes.NETHERITE_SWORD
            verify { event.markForReEncode(true) }
            verify { h.mob wasNot Called; h.equipment wasNot Called }

            adjusted.single { it.slot == PacketEquipmentSlot.MAIN_HAND }.item.amount = 20
            val armorOnly = h.event(WrapperPlayServerEntityEquipment(h.entityId, listOf(head)))
            h.send(armorOnly)
            (armorOnly.lastUsedWrapper as WrapperPlayServerEntityEquipment).equipment
                .single { it.slot == PacketEquipmentSlot.MAIN_HAND }.item.amount shouldBe 1

            val unrelated = h.event(WrapperPlayServerEntityEquipment(h.entityId + 1, native))
            h.send(unrelated)
            verify(exactly = 0) { unrelated.markForReEncode(any()) }
            val cancelled = h.event(WrapperPlayServerEntityEquipment(h.entityId, native))
            every { cancelled.isCancelled } returns true
            h.send(cancelled)
            verify(exactly = 0) { cancelled.markForReEncode(any()) }
        }
    }

    "existing and newly tracking viewers see the staff while final close restores current native gear" {
        withMobHandFixture { h ->
            val first = h.visual()
            h.sent.map { it.second.type } shouldBe listOf(Material.BLAZE_ROD)
            first.update(h.mob, null)
            h.sent.size shouldBe 1
            val newcomer = h.viewer()
            h.tracking += newcomer
            first.update(h.mob, null)
            h.sent.size shouldBe 2

            val replacement = h.visual(ItemStack(Material.DIAMOND))
            val otherUuid = UUID.randomUUID()
            val otherMob = mockk<Mob> {
                every { entityId } returns h.entityId + 1
                every { uniqueId } returns otherUuid
                every { isValid } returns true
                every { isDead } returns false
                every { trackedBy } returns emptySet()
                every { equipment } returns h.equipment
            }
            val independent = StaffMobEquipmentVisuals(otherMob, ItemStack(Material.BLAZE_ROD)).also(h.visuals::add)
            first.isValid shouldBe false
            replacement.isValid shouldBe true
            h.registrations shouldBe 1
            val beforeStaleClose = h.sent.size
            first.close()
            h.sent.size shouldBe beforeStaleClose
            h.listeners.size shouldBe 1

            h.nativeHand = ItemStack(Material.IRON_AXE)
            replacement.close()
            replacement.close()
            h.sent.takeLast(2).map { it.second.type } shouldBe listOf(Material.IRON_AXE, Material.IRON_AXE)
            h.nativeHand.type shouldBe Material.IRON_AXE
            independent.isValid shouldBe true
            h.listeners.size shouldBe 1
            independent.close()
            h.listeners.size shouldBe 0
            verify(exactly = 2) { h.equipment.itemInMainHand }
            confirmVerified(h.equipment)
        }
    }

    "empty handed spawn and retrack replay after spawn and stale callbacks cannot replace a new owner" {
        withMobHandFixture { h ->
            val first = h.visual()
            repeat(2) {
                val spawn = h.spawn()
                h.send(spawn)
                h.replayed.size shouldBe it
                spawn.tasksAfterSend.single().run()
                h.replayed.size shouldBe it + 1
                h.replayed.last().equipment.single().slot shouldBe PacketEquipmentSlot.MAIN_HAND
                h.replayed.last().equipment.single().item.type shouldBe ItemTypes.BLAZE_ROD
            }
            val pending = h.spawn()
            h.send(pending)
            val replacement = h.visual(ItemStack(Material.DIAMOND))
            first.close()
            pending.tasksAfterSend.single().run()
            h.replayed.size shouldBe 2

            val current = h.spawn()
            h.send(current)
            current.tasksAfterSend.single().run()
            h.replayed.last().equipment.single().item.type shouldBe ItemTypes.DIAMOND
            val cancelled = h.spawn()
            h.send(cancelled)
            every { cancelled.isCancelled } returns true
            cancelled.tasksAfterSend.single().run()
            h.replayed.size shouldBe 3

            val unrelated = h.spawn(UUID.randomUUID())
            h.send(unrelated)
            unrelated.tasksAfterSend.size shouldBe 0

            val closing = h.spawn()
            h.send(closing)
            replacement.close()
            closing.tasksAfterSend.single().run()
            h.replayed.size shouldBe 3
        }
    }
})

private fun packetItem(type: ItemType, amount: Int = 1) = PacketItemStack.builder().type(type).amount(amount).build()

/** Real PacketEvents 2.12.1 codecs; Bukkit item conversion is the sole unavailable CraftBukkit boundary. */
private fun withMobHandFixture(block: (MobHandFixture) -> Unit) {
    MockBukkitTestRuntime.open().use {
        val previousApi = PacketEvents.getAPI()
        val h = MobHandFixture()
        var conversionMocked = false
        AutoCloseable {
            try {
                h.visuals.asReversed().forEach { it.close() }
            } finally {
                try {
                    h.buffers.forEach { ByteBufHelper.release(it) }
                } finally {
                    try {
                        if (conversionMocked) unmockkStatic(SpigotConversionUtil::class)
                    } finally {
                        PacketEvents.setAPI(previousApi)
                    }
                }
            }
        }.use {
            PacketEvents.setAPI(h.api)
            // Registry loading queries these getters per entry; recording their stack traces can exhaust the test heap.
            excludeRecords { h.api.serverManager; h.api.settings; h.api.nettyManager }
            // Surface a real codec/classpath failure before MockK tries to instrument its return type.
            check(PacketItemStack.EMPTY.isEmpty)
            mockkStatic(SpigotConversionUtil::class)
            conversionMocked = true
            every { SpigotConversionUtil.fromBukkitItemStack(any()) } answers {
                val item = firstArg<ItemStack>()
                packetItem(if (item.type == Material.DIAMOND) ItemTypes.DIAMOND else ItemTypes.BLAZE_ROD,
                    item.amount).also(h.converted::add)
            }
            block(h)
        }
    }
}

private class MobHandFixture {
    val entityId = 101
    val entityUuid: UUID = UUID.randomUUID()
    var nativeHand = ItemStack(Material.NETHERITE_SWORD)
    val equipment = mockk<EntityEquipment> { every { itemInMainHand } answers { nativeHand } }
    val sent = mutableListOf<Pair<Player, ItemStack>>()
    val tracking = linkedSetOf(viewer())
    val mob = mockk<Mob> {
        every { entityId } returns this@MobHandFixture.entityId
        every { uniqueId } returns entityUuid
        every { isValid } returns true
        every { isDead } returns false
        every { trackedBy } answers { tracking.toSet() }
        every { equipment } returns this@MobHandFixture.equipment
    }
    val converted = mutableListOf<PacketItemStack>()
    val visuals = mutableListOf<StaffMobEquipmentVisuals>()
    val buffers = mutableListOf<Any>()
    val listeners = mutableListOf<PacketListenerCommon>()
    var registrations = 0
    private val events = mockk<EventManager> {
        every { registerListener(any<PacketListenerCommon>()) } answers {
            registrations++
            firstArg<PacketListenerCommon>().also(listeners::add)
        }
        every { unregisterListener(any<PacketListenerCommon>()) } answers { listeners.remove(firstArg()); Unit }
    }
    private val netty = NettyManagerImpl()
    val api = mockk<PacketEventsAPI<Any>> {
        every { serverManager } returns ServerManager { ServerVersion.V_1_21_11 }
        every { nettyManager } returns netty
        every { eventManager } returns events
        every { settings } returns PacketEventsSettings()
    }
    val replayed = mutableListOf<WrapperPlayServerEntityEquipment>()
    private val user = mockk<User> {
        every { clientVersion } returns ClientVersion.V_1_21_11
        every { getRegistryOr(any<IRegistry<MappedEntity>>(), any<ClientVersion>()) } answers { firstArg() }
        every { sendPacket(any<PacketWrapper<*>>()) } answers {
            replayed += firstArg<WrapperPlayServerEntityEquipment>()
        }
    }

    fun viewer(): Player {
        val id = UUID.randomUUID()
        val player = mockk<Player> { every { uniqueId } returns id; every { isOnline } returns true }
        every { player.sendEquipmentChange(any(), EquipmentSlot.HAND, any<ItemStack>()) } answers {
            sent += player to thirdArg<ItemStack>().clone()
        }
        return player
    }

    fun visual(item: ItemStack = ItemStack(Material.BLAZE_ROD)) = StaffMobEquipmentVisuals(mob, item).also(visuals::add)

    fun spawn(uuid: UUID = entityUuid) = event(WrapperPlayServerSpawnEntity(entityId, Optional.of(uuid),
        EntityTypes.ZOMBIE, Vector3d.zero(), 0f, 0f, 0f, 0, Optional.empty()))

    fun send(event: PacketSendEvent) = listeners.toList().forEach { (it as PacketListenerAbstract).onPacketSend(event) }

    fun event(packet: PacketWrapper<*>): PacketSendEvent {
        val buffer = netty.byteBufAllocationOperator.buffer().also(buffers::add)
        packet.buffer = buffer
        packet.clientVersion = ClientVersion.V_1_21_11
        packet.write()
        var last: PacketWrapper<*>? = null
        val after = mutableListOf<Runnable>()
        return mockk(relaxed = true) {
            every { packetType } returns requireNotNull(packet.packetTypeData.packetType)
            every { packetId } returns packet.nativePacketId
            every { serverVersion } returns ServerVersion.V_1_21_11
            every { user } returns this@MobHandFixture.user
            every { byteBuf } returns buffer
            every { isCancelled } returns false
            every { lastUsedWrapper } answers { last }
            every { lastUsedWrapper = any() } answers { last = firstArg() }
            every { tasksAfterSend } returns after
        }
    }
}
