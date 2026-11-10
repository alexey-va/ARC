package ru.arc.invsnapshots

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.william278.husksync.api.BukkitHuskSyncAPI
import net.william278.husksync.data.BukkitData
import net.william278.husksync.data.DataSnapshot
import net.william278.husksync.user.User
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
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
            val originals = List(21) { index ->
                val inventory = mockk<BukkitData.Items.Inventory>()
                every { inventory.contents } returns Array(41) { if (it == 40) ItemStack(Material.DIAMOND, index + 1) else null }
                mockk<DataSnapshot.Unpacked>().also { snapshot ->
                    every { snapshot.id } returns UUID.randomUUID()
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
            originals.first().inventory.get().let { (it as BukkitData.Items.Inventory).contents[40]!!.amount = 9 }
            history.snapshots.last().item(40)!!.amount shouldBe 1
            verify(exactly = 1) { api.getUser("OfflineOwner"); api.getSnapshots(user) }
            confirmVerified(api)
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
