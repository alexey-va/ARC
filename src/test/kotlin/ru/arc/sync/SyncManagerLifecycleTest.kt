package ru.arc.sync

import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.sync.base.Sync
import java.util.UUID
import java.util.function.Consumer

class SyncManagerLifecycleTest {
    private lateinit var scheduler: TestTaskScheduler

    @BeforeEach
    fun setUp() {
        scheduler = TestTaskScheduler()
        Tasks.install(scheduler)
        SyncManager.shutdown(save = false)
    }

    @AfterEach
    fun tearDown() {
        SyncManager.shutdown(save = false)
        Tasks.reset()
    }

    @Test
    fun `shutdown cancels periodic save and clears registered syncs`() {
        val sync = FakeSync()
        SyncManager.registerSync(FakeSync::class.java, sync)
        SyncManager.startSaveAllTasks()

        assertEquals(1, scheduler.timerCount())
        assertEquals(1, SyncManager.getSyncs().size)

        SyncManager.shutdown(save = false)

        assertEquals(0, scheduler.timerCount())
        assertEquals(0, SyncManager.getSyncs().size)
        assertEquals(1, sync.shutdownCount)
    }

    @Test
    fun `starting periodic save twice remains idempotent`() {
        SyncManager.startSaveAllTasks()
        SyncManager.startSaveAllTasks()

        assertEquals(1, scheduler.timerCount())
    }

    @Test
    fun `reload keeps joined sync ready and saving without another player join`() {
        val player = UUID.randomUUID()
        val sync = FakeSync()
        SyncManager.configureSync(FakeSync::class.java, true, listOf(player)) { sync }
        SyncManager.configureSync(FakeSync::class.java, true, listOf(player)) {
            error("Reload must retain the existing sync")
        }

        assertSame(sync, SyncManager.getSync(FakeSync::class.java))
        SyncManager.playerQuit(player)
        assertEquals(listOf(player), sync.joinedPlayers)
        assertEquals(listOf(player), sync.savedPlayers)
        assertEquals(0, sync.shutdownCount)
    }

    @Test
    fun `disabling sync saves online players before shutdown`() {
        val player = UUID.randomUUID()
        val sync = FakeSync()
        SyncManager.configureSync(FakeSync::class.java, true, listOf(player)) { sync }
        assertNull(SyncManager.configureSync(FakeSync::class.java, false, listOf(player)) {
            error("Disabled sync must not be created")
        })

        assertEquals(listOf(player), sync.savedPlayers)
        assertEquals(1, sync.shutdownCount)
        assertNull(SyncManager.getSync(FakeSync::class.java))
    }

    @Test
    fun `periodic saves visit all registered syncs and wrap around`() {
        val syncs = List(4) { FakeSync() }
        val visited = List(8) { SyncManager.SyncRoundRobin.getNext(syncs) }
        assertEquals(syncs + syncs, visited)
    }

    @Test
    fun `shared player snapshots use the existing round robin and stale delayed captures no-op after unregister`() {
        MockBukkitTestRuntime.open().use { paper ->
            val owner = paper.createSimplePlugin("ArcDungeons")
            val player = paper.addPlayer("RoundRobinSnapshot")
            val sync = FakeSync().also { it.playerJoin(player.uniqueId) }
            SyncManager.registerSync(FakeSync::class.java, sync)
            val snapshots = mutableListOf<Player>()
            val registration = SyncManager.registerPeriodicSave(owner, Consumer { snapshots += it })

            SyncManager.startSaveAllTasks()
            scheduler.tick(60)
            scheduler.tick(1)
            assertEquals(listOf(player.uniqueId), sync.savedPlayers)
            assertEquals(0, snapshots.size)

            scheduler.tick(59)
            scheduler.tick(1)
            assertEquals(1, snapshots.size)
            assertSame(player, snapshots.single())

            scheduler.tick(59)
            scheduler.tick(1)
            assertEquals(listOf(player.uniqueId, player.uniqueId), sync.savedPlayers)

            scheduler.tick(59)
            registration.close()
            scheduler.tick(1)
            assertEquals(1, snapshots.size)

            scheduler.tick(59)
            scheduler.tick(1)
            assertEquals(listOf(player.uniqueId, player.uniqueId, player.uniqueId), sync.savedPlayers)
        }
    }

    private class FakeSync : Sync {
        var shutdownCount = 0
        val joinedPlayers = mutableListOf<UUID>()
        val savedPlayers = mutableListOf<UUID>()

        override fun playerJoin(uuid: UUID) { joinedPlayers.add(uuid) }
        override fun forceSave(uuid: UUID) { if (uuid in joinedPlayers) savedPlayers.add(uuid) }
        override fun playerQuit(uuid: UUID) = forceSave(uuid)

        override fun shutdown() {
            shutdownCount++
            joinedPlayers.clear()
        }
    }
}
