package ru.arc.backwings

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture

class BackWingsRuntimeTest : FreeSpec({
    "plain F uses actual main hand, toggles and switches one display without touching inventory or either event hand" {
        wingScenario { f ->
            val dread = f.item(BackWing.DREADKNIGHT)
            val galaxy = f.item(BackWing.GALAXY)
            f.inventory.setItemInMainHand(dread)
            f.inventory.setItemInOffHand(ItemStack(Material.SHIELD))
            f.inventory.setItem(17, galaxy)
            val before = f.inventory.contents.map { it?.clone() }
            val first = f.swap()
            first.isCancelled shouldBe true
            first.mainHandItem shouldBe before[40]
            first.offHandItem shouldBe dread
            f.inventory.contents.toList() shouldBe before
            f.selection() shouldBe BackWing.DREADKNIGHT.itemId
            val display = f.spawned.single()
            verify { display.attachTo(f.player); display.pauseWhenNotObserved = false }

            f.inventory.setItemInMainHand(galaxy)
            f.inventory.setItem(17, dread)
            val switched = f.inventory.contents.map { it?.clone() }
            f.swap().isCancelled shouldBe true
            f.inventory.contents.toList() shouldBe switched
            f.selection() shouldBe BackWing.GALAXY.itemId
            f.spawned.size shouldBe 1
            verify(exactly = 1) { display.itemStack = any() }
            verify(exactly = 0) { display.remove() }

            f.swap().isCancelled shouldBe true
            f.selection() shouldBe null
            f.inventory.contents.toList() shouldBe switched
            verify(exactly = 1) { display.remove() }
            verify(exactly = 0) { f.player.addPassenger(any()) }
        }
    }

    "shift F earlier cancellations ordinary items and offhand-only wings keep their existing controls" {
        wingScenario { f ->
            f.inventory.setItemInMainHand(f.item(BackWing.DREADKNIGHT))
            f.sneaking = true
            f.swap().isCancelled shouldBe false
            f.sneaking = false
            f.swap(cancelled = true).isCancelled shouldBe true
            f.inventory.setItemInOffHand(f.inventory.itemInMainHand)
            f.inventory.setItemInMainHand(ItemStack(Material.STONE))
            f.swap().isCancelled shouldBe false
            f.inventory.setItemInMainHand(f.itemId("another:wings"))
            f.swap().isCancelled shouldBe false
            f.spawned.size shouldBe 0
            f.selection() shouldBe null
        }
    }

    "equip-time gaze cannot rotate the native renderer pose again after walking and crouched turns" {
        wingScenario { f ->
            f.locationYaw = 180f
            f.locationPitch = 10f
            f.bodyYaw = 180f
            f.join()
            f.inventory.setItemInMainHand(f.item(BackWing.DREADKNIGHT))
            f.swap()
            val origin = f.spawnLocations.single()
            origin.yaw shouldBe 0f
            origin.pitch shouldBe 0f
            f.player.location.yaw shouldBe 180f
            f.player.location.pitch shouldBe 10f
            val display = f.spawned.single()
            val radians = (Math.PI / 180.0).toFloat()

            // Exact 26.3 DisplayRenderer FIXED order: entity rotationYXZ(-yaw, +pitch, 0),
            // then the display transformation. Passenger positionRider changes XYZ only.
            // The native failure was visible at 270° walking and 330° crouching after equip at 180°.
            for ((yaw, crouching) in listOf(180f to false, 270f to false, 330f to true, 47f to false)) {
                f.locationYaw = yaw
                f.locationPitch = -35f
                f.sneaking = crouching
                f.walk(yaw, 40)
                val pose = f.poses.getValue(display)
                val entityRotation = Quaternionf().rotationYXZ(-origin.yaw * radians, origin.pitch * radians, 0f)
                val toBody = Quaternionf().rotationY(yaw * radians)
                val anchor = toBody.transform(entityRotation.transform(Vector3f(pose.translation)).add(0f, f.height, 0f))
                anchor.x shouldBe (0f plusOrMinus 0.0001f)
                anchor.y shouldBe ((if (crouching) 0.82f else 1.02f) plusOrMinus 0.0001f)
                anchor.z shouldBe ((if (crouching) -0.19f else -0.31f) plusOrMinus 0.0001f)
                val meshRotation = Quaternionf(toBody).mul(entityRotation).mul(pose.leftRotation)
                // Original Dreadknight span follows model Z. In body axes it must remain horizontal.
                val span = meshRotation.transform(Vector3f(0f, 0f, 1f))
                span.x shouldBe (1f plusOrMinus 0.0001f)
                span.y shouldBe (0f plusOrMinus 0.0001f)
                span.z shouldBe (0f plusOrMinus 0.0001f)
                val up = meshRotation.transform(Vector3f(0f, 1f, 0f))
                up.x shouldBe (0f plusOrMinus 0.0001f)
                up.y shouldBe ((if (crouching) 0.877583f else 1f) plusOrMinus 0.0001f)
                up.z shouldBe ((if (crouching) 0.479426f else 0f) plusOrMinus 0.0001f)
            }
            f.spawned.size shouldBe 1
            verify(exactly = 0) { display.teleport(any()); display.remove() }
        }
    }

    "walking before equip and after a turn uses native movement body history instead of the stale Paper getter" {
        wingScenario { f ->
            f.bodyYaw = 50f
            f.join()
            f.walk(0f, 10)
            f.inventory.setItemInMainHand(f.item(BackWing.DREADKNIGHT))
            f.swap()
            val display = f.spawned.single()
            fun expectBody(yaw: Float) {
                val pose = f.poses.getValue(display)
                val width = pose.leftRotation.transform(Vector3f(0f, 0f, 1f))
                val radians = Math.toRadians(yaw.toDouble())
                width.x shouldBe (kotlin.math.cos(radians).toFloat() plusOrMinus 0.0001f)
                width.z shouldBe (kotlin.math.sin(radians).toFloat() plusOrMinus 0.0001f)
            }
            expectBody(1.412376f)
            f.bodyYaw = -40f // Captured live Paper value while the native client settles at -90.
            f.walk(270f, 40)
            expectBody(-90f)
            f.sneaking = true
            f.walk(330f, 40)
            expectBody(-30f)
            val crouched = f.poses.getValue(display)
            f.locationYaw = 315f // A small head-only look must leave the wings on the torso.
            f.advance(2)
            f.runtime.reconfigure(BackWingsSettings())
            f.poses.getValue(display) shouldBe crouched
            f.spawned.size shouldBe 1
            verify(exactly = 0) { display.teleport(any()); display.remove() }
        }
    }

    "any storage slot retains ownership and loss removes the cosmetic without creating a replacement item" {
        wingScenario { f ->
            val wing = f.item(BackWing.DECEASE)
            f.inventory.setItemInMainHand(wing)
            f.swap()
            val display = f.spawned.single()
            f.inventory.setItem(35, wing)
            f.inventory.setItemInMainHand(ItemStack(Material.STONE))
            f.advance(12)
            f.selection() shouldBe BackWing.DECEASE.itemId
            verify(exactly = 0) { display.remove() }
            f.inventory.setItem(35, null)
            f.inventory.setItemInOffHand(wing)
            val before = f.inventory.contents.map { it?.clone() }
            f.advance(12)
            f.selection() shouldBe null
            verify(exactly = 1) { display.remove() }
            f.inventory.contents.toList() shouldBe before
            f.spawned.size shouldBe 1
        }
    }

    "world and config changes retain identity, death and quit remove it, restore and asynchronous close own cleanup" {
        wingScenario { f ->
            f.inventory.setItemInMainHand(f.item(BackWing.DREADKNIGHT))
            f.swap()
            val first = f.spawned.single()
            f.runtime.onWorldChange(PlayerChangedWorldEvent(f.player, f.player.world))
            f.runtime.reconfigure(BackWingsSettings())
            f.spawned.size shouldBe 1
            verify(exactly = 0) { first.remove() }
            f.runtime.onDeath(mockk<PlayerDeathEvent> { every { entity } returns f.player })
            verify(exactly = 1) { first.remove() }
            f.selection() shouldBe BackWing.DREADKNIGHT.itemId
            f.runtime.restore(f.player)
            f.spawned.size shouldBe 2
            f.runtime.onQuit(mockk<PlayerQuitEvent> { every { player } returns f.player })
            verify(exactly = 1) { f.spawned[1].remove() }
            f.runtime.restore(f.player)
            f.spawned.size shouldBe 3
            val drained = f.runtime.closeAsync()
            drained.isDone shouldBe false
            f.scope.trackedTaskCount() shouldBe 0
            f.runtime.closeAsync().isDone shouldBe false
            f.runtime.restore(f.player)
            f.swap().isCancelled shouldBe false
            f.spawned.size shouldBe 3
            f.cleanup.complete(null)
            drained.isDone shouldBe true
            verify(exactly = 1) { f.displays.closeAsync() }
            f.selection() shouldBe BackWing.DREADKNIGHT.itemId
        }
    }
})

private fun wingScenario(body: (WingFixture) -> Unit) {
    MockBukkitTestRuntime.open().use { paper -> WingFixture(paper).use(body) }
}

private class WingFixture(private val paper: MockBukkitTestRuntime) : AutoCloseable {
    private val plugin = paper.createSimplePlugin("BackWingsTest")
    private val native = object : PlayerMock(paper.server, "WingHolder") {
        override fun getBodyYaw(): Float = this@WingFixture.bodyYaw
    }.also(paper.server::addPlayer)
    val inventory = native.inventory
    private val scheduler = TestTaskScheduler()
    private val queueScope = LifecycleTaskScope(scheduler, true)
    val scope = LifecycleTaskScope(scheduler, true)
    private val work = BudgetedTaskQueue(queueScope, 64, 4_000_000L, { 0L }, 32)
    val displays = mockk<PaperPacketDisplays>()
    val spawned = mutableListOf<PacketItemDisplay>()
    val spawnLocations = mutableListOf<Location>()
    val poses = mutableMapOf<PacketItemDisplay, Transformation>()
    val cleanup = CompletableFuture<Void>()
    var sneaking = false
    var locationYaw = 0f
    var locationPitch = 0f
    var bodyYaw = 0f
    var locationX = native.location.x
    var locationZ = native.location.z
    val height: Float get() = if (sneaking) 1.5f else 1.8f
    // MockBukkit does not simulate native body-yaw/stance or packet displays. Keep that seam explicit;
    // its real inventory and PDC still establish the no-mutation and persistence invariants.
    val player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns native.uniqueId
        every { inventory } returns this@WingFixture.inventory
        every { persistentDataContainer } returns native.persistentDataContainer
        every { location } answers { native.location.clone().apply {
            x = locationX; z = locationZ; yaw = locationYaw; pitch = locationPitch
        } }
        every { world } returns native.world
        every { isOnline } returns true
        every { isDead } returns false
        every { isSneaking } answers { sneaking }
        every { bodyYaw } answers { this@WingFixture.bodyYaw }
        every { pose } answers { if (sneaking) Pose.SNEAKING else Pose.STANDING }
        every { height } answers { this@WingFixture.height.toDouble() }
    }
    val runtime: BackWingsRuntime

    init {
        every { displays.spawnItem(any(), any()) } answers {
            spawnLocations += firstArg<Location>().clone()
            mockk<PacketItemDisplay>(relaxed = true).also { display ->
                every { display.transformation = any() } answers { poses[display] = firstArg(); Unit }
                spawned += display
            }
        }
        every { displays.closeAsync() } returns cleanup
        runtime = BackWingsRuntime(plugin, BackWingsSettings(), displays, work, scope) {
            it.itemMeta?.persistentDataContainer?.get(ITEM_ID, PersistentDataType.STRING)
        }
        runtime.start()
    }

    fun item(wing: BackWing): ItemStack = itemId(wing.itemId)
    fun itemId(id: String): ItemStack = ItemStack(Material.PAPER).apply {
        editMeta { it.persistentDataContainer.set(ITEM_ID, PersistentDataType.STRING, id) }
    }

    fun swap(cancelled: Boolean = false): PlayerSwapHandItemsEvent = PlayerSwapHandItemsEvent(
        player, inventory.itemInOffHand.clone(), inventory.itemInMainHand.clone(),
    ).also { it.isCancelled = cancelled; paper.callEvent(it) }

    fun selection(): String? = native.persistentDataContainer.get(BackWingsRuntime.SELECTION_KEY, PersistentDataType.STRING)
    fun join() { runtime.onJoin(mockk<PlayerJoinEvent> { every { player } returns this@WingFixture.player }) }
    fun walk(yaw: Float, ticks: Int) {
        locationYaw = yaw
        val radians = Math.toRadians(yaw.toDouble())
        repeat(ticks) {
            val before = player.location
            locationX -= kotlin.math.sin(radians) * 0.2
            locationZ += kotlin.math.cos(radians) * 0.2
            paper.callEvent(PlayerMoveEvent(player, before, player.location))
            advance(1)
        }
    }
    fun advance(ticks: Int) { repeat(ticks) { paper.performTicks(1L); scheduler.tick(1L) } }

    override fun close() {
        runtime.close()
        cleanup.complete(null)
        work.close()
        queueScope.close()
    }

    companion object { private val ITEM_ID = NamespacedKey("test", "wing-id") }
}
