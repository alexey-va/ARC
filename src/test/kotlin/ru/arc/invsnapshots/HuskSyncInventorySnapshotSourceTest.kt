package ru.arc.invsnapshots

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.william278.husksync.HuskSync
import net.william278.husksync.api.BukkitHuskSyncAPI
import net.william278.husksync.data.BukkitData
import net.william278.husksync.data.DataSnapshot
import net.william278.husksync.data.Identifier
import net.william278.husksync.database.Database
import net.william278.husksync.libraries.desertwell.util.Version
import net.william278.husksync.user.BukkitUser
import net.william278.husksync.user.User
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

class HuskSyncInventorySnapshotSourceTest : FunSpec({
    test("offline name lookup reads all retained snapshots in newest first order using native API only") {
        MockBukkitTestRuntime.open().use {
            val api = mockk<BukkitHuskSyncAPI>()
            val user = User(UUID.randomUUID(), "OfflineOwner")
            val lookup = CompletableFuture<Optional<User>>()
            every { api.getUser("OfflineOwner") } returns lookup
            val husk = mockk<HuskSync>()
            every { api.plugin } returns husk
            every { husk.minecraftVersion } returns Version.fromString("1.21.11")
            val originals = List(21) { index ->
                val inventory = mockk<BukkitData.Items.Inventory>()
                every { inventory.heldItemSlot } returns 6
                every { inventory.contents } returns Array(41) { if (it == 40) ItemStack(Material.DIAMOND, index + 1) else null }
                mockk<DataSnapshot.Unpacked>().also { snapshot ->
                    every { snapshot.id } returns UUID.randomUUID()
                    every { snapshot.minecraftVersion } returns Version.fromString(if (index == 0) "1.21.8" else "1.21.11")
                    every { snapshot.timestamp } returns OffsetDateTime.parse("2026-11-10T08:30:00Z").plusSeconds(index.toLong())
                    every { snapshot.serverName } returns "Survival"
                    every { snapshot.saveCause } returns DataSnapshot.SaveCause.WORLD_SAVE
                    every { snapshot.isPinned } returns (index == 0)
                    every { snapshot.inventory } returns Optional.of(inventory)
                }
            }
            every { api.getSnapshots(user) } returns CompletableFuture.completedFuture(originals)
            val result = HuskSyncInventorySnapshotSource(api).find("OfflineOwner").toCompletableFuture()
            result.isDone shouldBe false
            verify(exactly = 0) { api.getSnapshots(any()) }
            lookup.complete(Optional.of(user))
            val history = requireNotNull(result.join())
            history.playerId shouldBe user.uuid
            history.snapshots.size shouldBe 21
            history.snapshots.map { it.item(40)!!.amount } shouldBe (21 downTo 1).toList()
            history.snapshots.last().pinned shouldBe true
            history.snapshots.last().exactSlotOrder shouldBe false
            history.snapshots.first().exactSlotOrder shouldBe true
            originals.first().inventory.get().let { (it as BukkitData.Items.Inventory).contents[40]!!.amount = 9 }
            history.snapshots.last().item(40)!!.amount shouldBe 1
            verify(exactly = 1) { api.getUser("OfflineOwner"); api.getSnapshots(user); api.plugin }
            confirmVerified(api)
        }
    }

    test("backup acknowledges only the viewer's pinned snapshot with the original persisted inventory") {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Inspector")
            val api = mockk<BukkitHuskSyncAPI>()
            val husk = mockk<HuskSync>()
            val database = mockk<Database>(relaxUnitFun = true)
            val user = mockk<BukkitUser>()
            val captured = mockk<DataSnapshot.Packed>(relaxUnitFun = true)
            val id = UUID.randomUUID()
            fun payload(items: String) = """{"data":{"${Identifier.INVENTORY}":"$items"}}"""
            every { api.plugin } returns husk
            every { husk.database } returns database
            every { api.getUser(viewer) } returns user
            every { user.isLocked } returns false
            every { user.createSnapshot(DataSnapshot.SaveCause.API) } returns captured
            every { captured.id } returns id
            every { captured.asJson(husk) } returns payload("original")
            val saved = mockk<DataSnapshot.Packed>()
            every { saved.id } returns id
            every { saved.isInvalid } returns false
            every { saved.isPinned } returns true
            every { saved.asJson(husk) } returns payload("original")
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                for (state in listOf("missing", "changed", "unpinned", "saved")) {
                    every { saved.isPinned } returns (state != "unpinned")
                    every { saved.asJson(husk) } returns payload(if (state == "changed") "changed" else "original")
                    every { database.getSnapshot(match<User> { it.uuid == viewer.uniqueId }, id) } returns
                        if (state == "missing") Optional.empty() else Optional.of(saved)
                    val result = HuskSyncInventorySnapshotSource(api).backup(viewer).toCompletableFuture()
                    result.isDone shouldBe false
                    scheduler.executeImmediate()
                    result.isCompletedExceptionally shouldBe (state != "saved")
                    if (state == "saved") result.join() shouldBe id
                }
            }
            verify(exactly = 4) { captured.isPinned = true }
            verify(exactly = 4) { database.addSnapshot(match<User> { it.uuid == viewer.uniqueId }, captured) }
        }
    }

    test("an unknown name returns no target without any snapshot access") {
        val api = mockk<BukkitHuskSyncAPI>()
        every { api.getUser("Missing") } returns CompletableFuture.completedFuture(Optional.empty())
        HuskSyncInventorySnapshotSource(api).find("Missing").toCompletableFuture().join() shouldBe null
        verify(exactly = 1) { api.getUser("Missing") }
        confirmVerified(api)
    }
})
