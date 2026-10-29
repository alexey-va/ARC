package ru.arc.backwings

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
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
    private val native = paper.addPlayer("WingHolder")
    val inventory = native.inventory
    private val scheduler = TestTaskScheduler()
    private val queueScope = LifecycleTaskScope(scheduler, true)
    val scope = LifecycleTaskScope(scheduler, true)
    private val work = BudgetedTaskQueue(queueScope, 64, 4_000_000L, { 0L }, 32)
    val displays = mockk<PaperPacketDisplays>()
    val spawned = mutableListOf<PacketItemDisplay>()
    val cleanup = CompletableFuture<Void>()
    var sneaking = false
    // MockBukkit does not simulate native body-yaw/stance or packet displays. Keep that seam explicit;
    // its real inventory and PDC still establish the no-mutation and persistence invariants.
    val player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns native.uniqueId
        every { inventory } returns this@WingFixture.inventory
        every { persistentDataContainer } returns native.persistentDataContainer
        every { location } answers { native.location.clone() }
        every { world } returns native.world
        every { isOnline } returns true
        every { isDead } returns false
        every { isSneaking } answers { sneaking }
        every { bodyYaw } returns 0f
        every { pose } returns Pose.STANDING
        every { height } returns 1.8
    }
    val runtime: BackWingsRuntime

    init {
        every { displays.spawnItem(any(), any()) } answers { mockk<PacketItemDisplay>(relaxed = true).also(spawned::add) }
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
    fun advance(ticks: Int) { repeat(ticks) { paper.performTicks(1L); scheduler.tick(1L) } }

    override fun close() {
        runtime.close()
        cleanup.complete(null)
        work.close()
        queueScope.close()
    }

    companion object { private val ITEM_ID = NamespacedKey("test", "wing-id") }
}
