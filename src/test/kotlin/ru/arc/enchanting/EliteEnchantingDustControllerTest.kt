package ru.arc.enchanting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.eliteloot.isEliteEnchantmentBook
import ru.arc.eliteloot.presentEliteItem
import ru.arc.paper.testing.MockBukkitTestRuntime

class EliteEnchantingDustControllerTest : StringSpec({
    "one dust boosts one EliteMobs book and consumes one from a cursor stack" {
        MockBukkitTestRuntime.open().use { runtime ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            enableEnchantingProviders(runtime)
            val fixture = dustFixture(runtime, "Dust", BookApplicationChances(40, 1))
            val controller = newDustController()
            try {
                mockDust(fixture)
                controller.onDustDrop(fixture.event)
                verify { fixture.event.isCancelled = true }

                scheduler.tick(1)

                readEliteBookChances(fixture.player.inventory.getItem(fixture.bookSlot)!!) shouldBe
                    BookApplicationChances(55, 0)
                fixture.player.itemOnCursor.amount shouldBe 1
                fixture.player.openInventory shouldBe fixture.view
                verify { presentEliteItem(any(), fixture.player) }
            } finally {
                controller.close()
                unmockDust()
                Tasks.reset()
            }
        }
    }

    "a maxed book keeps dust, including a residual destruction chance" {
        MockBukkitTestRuntime.open().use { runtime ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            enableEnchantingProviders(runtime)
            val controller = newDustController()
            try {
                for ((index, chances) in listOf(BookApplicationChances(100, 0), BookApplicationChances(100, 1)).withIndex()) {
                    val fixture = dustFixture(runtime, "DustCap$index", chances)
                    mockDust(fixture)
                    controller.onDustDrop(fixture.event)
                    scheduler.tick(1)

                    readEliteBookChances(fixture.player.inventory.getItem(fixture.bookSlot)!!) shouldBe chances
                    fixture.player.itemOnCursor.amount shouldBe 2
                }
            } finally {
                controller.close()
                unmockDust()
                Tasks.reset()
            }
        }
    }

    "a stacked book is rejected without changing either stack" {
        MockBukkitTestRuntime.open().use { runtime ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            enableEnchantingProviders(runtime)
            val fixture = dustFixture(runtime, "StackedBook", BookApplicationChances(40, 1), bookAmount = 2)
            val config = mockk<EnchantingConfig>()
            val controller = newDustController(config)
            try {
                mockDust(fixture)
                controller.onDustDrop(fixture.event)
                scheduler.tick(1)

                fixture.player.inventory.getItem(fixture.bookSlot)?.amount shouldBe 2
                readEliteBookChances(fixture.player.inventory.getItem(fixture.bookSlot)!!) shouldBe
                    BookApplicationChances(40, 1)
                fixture.player.itemOnCursor.amount shouldBe 2
                verify { config.text("messages.dust-single-book") }
            } finally {
                controller.close()
                unmockDust()
                Tasks.reset()
            }
        }
    }

    "stale book or dust input prevents boosting and preserves the changed inputs" {
        MockBukkitTestRuntime.open().use { runtime ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            enableEnchantingProviders(runtime)
            val controller = newDustController()
            try {
                val changedBookFixture = dustFixture(runtime, "StaleBook", BookApplicationChances(40, 1))
                mockDust(changedBookFixture)
                controller.onDustDrop(changedBookFixture.event)
                val replacementBook = ItemStack(Material.ENCHANTED_BOOK)
                writeEliteBookChances(replacementBook, BookApplicationChances(25, 0))
                changedBookFixture.player.inventory.setItem(changedBookFixture.bookSlot, replacementBook)
                scheduler.tick(1)

                readEliteBookChances(changedBookFixture.player.inventory.getItem(changedBookFixture.bookSlot)!!) shouldBe
                    BookApplicationChances(25, 0)
                changedBookFixture.player.itemOnCursor.amount shouldBe 2

                val changedDustFixture = dustFixture(runtime, "StaleDust", BookApplicationChances(40, 1))
                mockDust(changedDustFixture)
                controller.onDustDrop(changedDustFixture.event)
                val changedDust = changedDustFixture.dust.clone().apply { amount = 1 }
                changedDustFixture.player.setItemOnCursor(changedDust)
                scheduler.tick(1)

                readEliteBookChances(changedDustFixture.player.inventory.getItem(changedDustFixture.bookSlot)!!) shouldBe
                    BookApplicationChances(40, 1)
                changedDustFixture.player.itemOnCursor shouldBe changedDust
            } finally {
                controller.close()
                unmockDust()
                Tasks.reset()
            }
        }
    }

    "creative dust is first admitted to inventory, then one is consumed with the boost" {
        MockBukkitTestRuntime.open().use { runtime ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            enableEnchantingProviders(runtime)
            val fixture = dustFixture(runtime, "CreativeDust", BookApplicationChances(40, 1), creative = true)
            val controller = newDustController()
            try {
                mockDust(fixture)
                controller.onDustDrop(fixture.event)
                verify { fixture.event.isCancelled = true }
                val admittedDustSlot = (0 until fixture.player.inventory.storageContents.size).single {
                    fixture.player.inventory.getItem(it)?.isSimilar(fixture.dust) == true
                }

                scheduler.tick(1)

                readEliteBookChances(fixture.player.inventory.getItem(fixture.bookSlot)!!) shouldBe
                    BookApplicationChances(55, 0)
                fixture.player.inventory.getItem(admittedDustSlot)?.amount shouldBe 1
                fixture.player.itemOnCursor.type shouldBe Material.AIR
                fixture.player.openInventory shouldBe fixture.view
            } finally {
                controller.close()
                unmockDust()
                Tasks.reset()
            }
        }
    }
})

private data class DustFixture(
    val player: Player,
    val view: InventoryView,
    val bookSlot: Int,
    val book: ItemStack,
    val dust: ItemStack,
    val event: InventoryClickEvent,
)

private fun dustFixture(
    runtime: MockBukkitTestRuntime,
    name: String,
    chances: BookApplicationChances,
    bookAmount: Int = 1,
    dustAmount: Int = 2,
    creative: Boolean = false,
): DustFixture {
    val player = runtime.addPlayer(name)
    if (creative) player.gameMode = GameMode.CREATIVE
    val view = requireNotNull(player.openInventory(runtime.server.createInventory(null, 9)))
    val bookSlot = 11
    val book = ItemStack(Material.ENCHANTED_BOOK, bookAmount)
    writeEliteBookChances(book, chances)
    player.inventory.setItem(bookSlot, book.clone())
    val dust = ItemStack(Material.GLOWSTONE_DUST, dustAmount)
    if (!creative) player.setItemOnCursor(dust.clone())

    val event = if (creative) mockk<InventoryCreativeEvent>(relaxed = true) else mockk<InventoryClickEvent>(relaxed = true)
    every { event.whoClicked } returns player
    every { event.clickedInventory } returns player.inventory
    every { event.cursor } returns dust
    every { event.currentItem } returns book
    every { event.click } returns ClickType.LEFT
    every { event.slot } returns bookSlot
    every { event.view } returns view
    return DustFixture(player, view, bookSlot, book, dust, event)
}

private fun enableEnchantingProviders(runtime: MockBukkitTestRuntime) {
    for (name in listOf("EliteMobs", "AdvancedEnchantments")) {
        val plugin = runtime.createSimplePlugin(name)
        if (!plugin.isEnabled) runtime.server.pluginManager.enablePlugin(plugin)
    }
}

private fun newDustController(config: EnchantingConfig = mockk()): EliteEnchantingController {
    every { config.text(any()) } returns Component.text("test")
    return EliteEnchantingController(config)
}

private fun mockDust(fixture: DustFixture) {
    mockkStatic("ru.arc.enchanting.AdvancedBookPresentationKt")
    mockkStatic("ru.arc.eliteloot.EliteEnchantmentBookPresentationKt")
    mockkStatic("ru.arc.eliteloot.EliteLootPresentationKt")
    every { readAdvancedMagicDust(fixture.dust) } returns AdvancedMagicDust(15, true)
    every { isEliteEnchantmentBook(fixture.book) } returns true
    every { presentEliteItem(any(), fixture.player) } answers { firstArg() }
}

private fun unmockDust() {
    unmockkStatic("ru.arc.eliteloot.EliteLootPresentationKt")
    unmockkStatic("ru.arc.eliteloot.EliteEnchantmentBookPresentationKt")
    unmockkStatic("ru.arc.enchanting.AdvancedBookPresentationKt")
}
