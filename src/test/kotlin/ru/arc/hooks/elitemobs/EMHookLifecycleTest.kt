package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.playerdata.ElitePlayerInventory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.config.ConfigManager
import ru.arc.core.ScheduledTask
import ru.arc.hooks.elitemobs.guis.ShopHolder
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.UUID

class EMHookLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun clearConfigCache() {
        ConfigManager.clear()
    }

    @Test
    fun `constructor is inert and first reload starts one runtime`() {
        val wormholes = mockk<EMWormholes>(relaxed = true)
        val holder = mockk<ShopHolder>(relaxed = true)
        val task = FakeScheduledTask()
        var wormholeCreations = 0
        var holderCreations = 0
        var scheduledTasks = 0

        val hook =
            EMHook(
                config = config(),
                wormholesFactory = {
                    wormholeCreations++
                    wormholes
                },
                shopHolderFactory = {
                    holderCreations++
                    holder
                },
                scheduleShopReset = { _, _ ->
                    scheduledTasks++
                    task
                },
            )

        wormholeCreations shouldBeExactly 0
        holderCreations shouldBeExactly 0
        scheduledTasks shouldBeExactly 0

        hook.reload()

        wormholeCreations shouldBeExactly 1
        holderCreations shouldBeExactly 1
        scheduledTasks shouldBeExactly 1
        verify(exactly = 1) { wormholes.init() }
        verify(exactly = 1) { holder.deleteAll() }
        task.isCancelled.shouldBeFalse()
        hook.close()
    }

    @Test
    fun `reload replaces owned resources and close is idempotent`() {
        val firstWormholes = mockk<EMWormholes>(relaxed = true)
        val secondWormholes = mockk<EMWormholes>(relaxed = true)
        val holder = mockk<ShopHolder>(relaxed = true)
        val firstTask = FakeScheduledTask()
        val secondTask = FakeScheduledTask()
        val wormholes = ArrayDeque(listOf(firstWormholes, secondWormholes))
        val tasks = ArrayDeque(listOf(firstTask, secondTask))
        val hook =
            EMHook(
                config = config(),
                wormholesFactory = { wormholes.removeFirst() },
                shopHolderFactory = { holder },
                scheduleShopReset = { _, _ -> tasks.removeFirst() },
            )

        hook.reload()
        hook.reload()

        firstTask.isCancelled.shouldBeTrue()
        secondTask.isCancelled.shouldBeFalse()
        verify(exactly = 1) { firstWormholes.close() }
        verify(exactly = 0) { secondWormholes.close() }

        hook.close()
        hook.close()

        secondTask.isCancelled.shouldBeTrue()
        verify(exactly = 1) { secondWormholes.close() }
        shouldThrow<IllegalStateException> { hook.reload() }
    }

    @Test
    fun `failed reload keeps previous runtime active`() {
        val firstWormholes = mockk<EMWormholes>(relaxed = true)
        val replacementWormholes = mockk<EMWormholes>(relaxed = true)
        val holder = mockk<ShopHolder>(relaxed = true)
        val firstTask = FakeScheduledTask()
        val wormholes = ArrayDeque(listOf(firstWormholes, replacementWormholes))
        var scheduleAttempts = 0
        val hook =
            EMHook(
                config = config(),
                wormholesFactory = { wormholes.removeFirst() },
                shopHolderFactory = { holder },
                scheduleShopReset = { _, _ ->
                    scheduleAttempts++
                    if (scheduleAttempts == 2) error("scheduler unavailable")
                    firstTask
                },
            )
        hook.reload()
        clearMocks(firstWormholes, replacementWormholes, answers = false)

        shouldThrow<IllegalStateException> { hook.reload() }

        firstTask.isCancelled.shouldBeFalse()
        verify(exactly = 0) { firstWormholes.close() }
        verify(exactly = 1) { replacementWormholes.close() }

        hook.close()
        firstTask.isCancelled.shouldBeTrue()
        verify(exactly = 1) { firstWormholes.close() }
    }

    @Test
    fun `reload finishes replacing resources when old task cancellation fails`() {
        val firstWormholes = mockk<EMWormholes>(relaxed = true)
        val secondWormholes = mockk<EMWormholes>(relaxed = true)
        val holder = mockk<ShopHolder>(relaxed = true)
        val wormholes = ArrayDeque(listOf(firstWormholes, secondWormholes))
        val tasks = ArrayDeque<ScheduledTask>(listOf(ThrowingScheduledTask(), FakeScheduledTask()))
        val hook =
            EMHook(
                config = config(),
                wormholesFactory = { wormholes.removeFirst() },
                shopHolderFactory = { holder },
                scheduleShopReset = { _, _ -> tasks.removeFirst() },
            )
        hook.reload()
        clearMocks(firstWormholes, secondWormholes, answers = false)

        hook.reload()

        verify(exactly = 1) { firstWormholes.close() }
        verify(exactly = 0) { secondWormholes.close() }
        hook.close()
    }

    @Test
    fun `scheduled reset clears the active shop`() {
        val wormholes = mockk<EMWormholes>(relaxed = true)
        val holder = mockk<ShopHolder>(relaxed = true)
        var resetAction: (() -> Unit)? = null
        val hook =
            EMHook(
                config = config(),
                wormholesFactory = { wormholes },
                shopHolderFactory = { holder },
                scheduleShopReset = { _, action ->
                    resetAction = action
                    FakeScheduledTask()
                },
            )
        hook.reload()
        clearMocks(holder, answers = false)

        checkNotNull(resetAction).invoke()

        verify(exactly = 1) { holder.deleteAll() }
        hook.close()
    }

    @Test
    fun `close releases remaining resources when task cancellation fails`() {
        val wormholes = mockk<EMWormholes>(relaxed = true)
        val holder = mockk<ShopHolder>(relaxed = true)
        val hook =
            EMHook(
                config = config(),
                wormholesFactory = { wormholes },
                shopHolderFactory = { holder },
                scheduleShopReset = { _, _ -> ThrowingScheduledTask() },
            )
        hook.reload()
        clearMocks(wormholes, holder, answers = false)

        shouldThrow<IllegalStateException> { hook.close() }

        verify(exactly = 1) { wormholes.close() }
        verify(exactly = 1) { holder.deleteAll() }
        hook.close()
    }

    @Test
    fun `tier falls back while EliteMobs inventory registry is unavailable`() {
        val originalInventories = ElitePlayerInventory.playerInventories
        val player = mockk<Player>()
        every { player.uniqueId } returns UUID.randomUUID()
        val hook = inertHook()
        try {
            ElitePlayerInventory.playerInventories = null

            hook.tier(player) shouldBeExactly 1
        } finally {
            ElitePlayerInventory.playerInventories = originalInventories
            hook.close()
        }
    }

    @Test
    fun `tier falls back while player data is still loading`() {
        val originalInventories = ElitePlayerInventory.playerInventories
        val player = mockk<Player>()
        every { player.uniqueId } returns UUID.randomUUID()
        val hook = inertHook()
        try {
            ElitePlayerInventory.playerInventories = HashMap()

            hook.tier(player) shouldBeExactly 1
        } finally {
            ElitePlayerInventory.playerInventories = originalInventories
            hook.close()
        }
    }

    private fun inertHook() =
        EMHook(
            config = config(),
            wormholesFactory = { mockk(relaxed = true) },
            shopHolderFactory = { mockk(relaxed = true) },
            scheduleShopReset = { _, _ -> FakeScheduledTask() },
        )

    private fun config() =
        ConfigManager.of(tempDir, "elitemobs.yml").also {
            it.setInt("shop.reset-ticks", 100)
        }

    private class FakeScheduledTask : ScheduledTask {
        override val id: Int = 1
        override var isCancelled: Boolean = false
            private set

        override fun cancel() {
            isCancelled = true
        }
    }

    private class ThrowingScheduledTask : ScheduledTask {
        override val id: Int = 2
        override val isCancelled: Boolean = false

        override fun cancel() {
            error("cancel failed")
        }
    }
}
