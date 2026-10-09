package ru.arc.enchanting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.advancedplugins.ae.api.AEAPI
import net.advancedplugins.ae.api.EnchantApplyEvent
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime

class AdvancedBookSafetyListenerTest : StringSpec({
    "missing provider bridge blocks application and preserves the proposed creative book" {
        MockBukkitTestRuntime.open().use { runtime ->
            clearAdvancedBookPresentation()
            Tasks.install(TestTaskScheduler())
            val player = runtime.addPlayer("Creative")
            player.gameMode = GameMode.CREATIVE
            val book = ItemStack(Material.ENCHANTED_BOOK)
            book.editMeta {
                it.persistentDataContainer.set(
                    NamespacedKey("advancedenchantments", "ae_book"),
                    PersistentDataType.STRING,
                    "sharpness",
                )
            }
            val event = mockk<InventoryCreativeEvent>(relaxed = true)
            every { event.whoClicked } returns player
            every { event.cursor } returns book
            val config = mockk<EnchantingConfig>()
            every { config.text("messages.unavailable") } returns Component.text("Unavailable")
            val listener = AdvancedBookSafetyListener(config)
            try {
                listener.onBookClick(event)
                verify { event.isCancelled = true }
                player.inventory.storageContents.filterNotNull().single() shouldBe book
            } finally {
                listener.close()
                Tasks.reset()
            }
        }
    }

    "zero success and zero destruction consume one book, preserve target, and open no GUI" {
        MockBukkitTestRuntime.open().use { runtime ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            val aePlugin = runtime.createSimplePlugin("AdvancedEnchantments")
            if (!aePlugin.isEnabled) runtime.server.pluginManager.enablePlugin(aePlugin)
            val fixture = fixture(runtime, "Failure", success = 0, destroy = 0)
            val config = mockk<EnchantingConfig>()
            every { config.text(any()) } returns Component.text("result")
            val listener = AdvancedBookSafetyListener(config)
            try {
                mockBridge(fixture.book, fixture.player, BookApplicationChances(0, 0))
                // Initialize the API's obfuscated constant table outside MockK's recording scope.
                Class.forName(AEAPI::class.java.name, true, AEAPI::class.java.classLoader)
                mockkStatic(AEAPI::class)
                listener.onBookClick(fixture.click)
                listener.onNativeApply(fixture.nativeApply)
                verify { fixture.nativeApply.isCancelled = true }
                verify { fixture.click.isCancelled = true }

                scheduler.tick(1)

                fixture.player.inventory.getItem(0) shouldBe fixture.target
                fixture.player.inventory.getItem(0)?.itemMeta?.persistentDataContainer?.get(
                    fixture.nativeValidationKey,
                    PersistentDataType.BYTE,
                ) shouldBe null
                fixture.player.itemOnCursor.amount shouldBe 1
                fixture.player.openInventory.topInventory shouldBe fixture.topInventory
                verify(exactly = 0) { AEAPI.applyEnchant(any(), any(), any(), any(), any()) }
                verify(exactly = 0) { protectedAdvancedBookTarget(any()) }
            } finally {
                listener.close()
                unmockkStatic(AEAPI::class)
                unmockBridge()
                Tasks.reset()
            }
        }
    }

    "one hundred percent success applies through AE once and consumes one book" {
        MockBukkitTestRuntime.open().use { runtime ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            val aePlugin = runtime.createSimplePlugin("AdvancedEnchantments")
            if (!aePlugin.isEnabled) runtime.server.pluginManager.enablePlugin(aePlugin)
            val fixture = fixture(runtime, "Success", success = 100, destroy = 0)
            val config = mockk<EnchantingConfig>()
            every { config.text(any()) } returns Component.text("result")
            val listener = AdvancedBookSafetyListener(config)
            val appliedKey = NamespacedKey("test", "native_apply")
            val applied = fixture.target.clone().apply {
                editMeta { it.persistentDataContainer.set(appliedKey, PersistentDataType.BYTE, 1) }
            }
            try {
                mockBridge(fixture.book, fixture.player, BookApplicationChances(100, 0))
                // Initialize the API's obfuscated constant table outside MockK's recording scope.
                Class.forName(AEAPI::class.java.name, true, AEAPI::class.java.classLoader)
                mockkStatic(AEAPI::class)
                every { AEAPI.getBookEnchantment(any()) } returns "sharpness"
                every { AEAPI.getBookEnchantmentLevel(any()) } returns 1
                every { AEAPI.getEnchantLevel("sharpness", any()) } returns 0
                every {
                    AEAPI.applyEnchant(
                        "sharpness",
                        1,
                        false,
                        true,
                        match {
                            it.itemMeta?.persistentDataContainer?.get(
                                fixture.nativeValidationKey,
                                PersistentDataType.BYTE,
                            ) == 1.toByte()
                        },
                    )
                } returns applied

                listener.onBookClick(fixture.click)
                listener.onNativeApply(fixture.nativeApply)
                scheduler.tick(1)

                fixture.player.inventory.getItem(0)?.itemMeta?.persistentDataContainer?.get(
                    appliedKey,
                    PersistentDataType.BYTE,
                ) shouldBe 1.toByte()
                fixture.player.itemOnCursor.amount shouldBe 1
                fixture.player.openInventory.topInventory shouldBe fixture.topInventory
                verify { fixture.nativeApply.isCancelled = true }
                verify(exactly = 1) {
                    AEAPI.applyEnchant(
                        "sharpness",
                        1,
                        false,
                        true,
                        match {
                            it.itemMeta?.persistentDataContainer?.get(
                                fixture.nativeValidationKey,
                                PersistentDataType.BYTE,
                            ) == 1.toByte()
                        },
                    )
                }
                verify(exactly = 0) { protectedAdvancedBookTarget(any()) }
            } finally {
                listener.close()
                unmockkStatic(AEAPI::class)
                unmockBridge()
                Tasks.reset()
            }
        }
    }
})

private data class SafetyFixture(
    val player: org.bukkit.entity.Player,
    val topInventory: Inventory,
    val book: ItemStack,
    val target: ItemStack,
    val nativeValidatedTarget: ItemStack,
    val nativeValidationKey: NamespacedKey,
    val click: InventoryClickEvent,
    val nativeApply: EnchantApplyEvent,
)

private fun fixture(
    runtime: MockBukkitTestRuntime,
    playerName: String,
    success: Int,
    destroy: Int,
): SafetyFixture {
    val player = runtime.addPlayer(playerName)
    val topInventory = runtime.server.createInventory(null, 9)
    val view = requireNotNull(player.openInventory(topInventory))
    val book = ItemStack(Material.ENCHANTED_BOOK, 2).apply {
        editMeta { meta ->
            meta.persistentDataContainer.set(
                NamespacedKey("advancedenchantments", "ae_book"),
                PersistentDataType.STRING,
                "sharpness",
            )
            meta.persistentDataContainer.set(
                NamespacedKey("advancedenchantments", "ae_book_level"),
                PersistentDataType.INTEGER,
                1,
            )
            meta.persistentDataContainer.set(
                NamespacedKey("advancedenchantments", "ae_book_success"),
                PersistentDataType.INTEGER,
                success,
            )
            meta.persistentDataContainer.set(
                NamespacedKey("advancedenchantments", "ae_book_failure"),
                PersistentDataType.INTEGER,
                destroy,
            )
        }
    }
    val target = ItemStack(Material.DIAMOND_SWORD)
    val nativeValidationKey = NamespacedKey("test", "ae_replaced_conflicting_enchant")
    val nativeValidatedTarget = target.clone().apply {
        editMeta { meta ->
            meta.persistentDataContainer.set(nativeValidationKey, PersistentDataType.BYTE, 1)
        }
    }
    player.inventory.setItem(0, target)
    player.setItemOnCursor(book.clone())

    val click = mockk<InventoryClickEvent>(relaxed = true)
    every { click.whoClicked } returns player
    every { click.cursor } returns book
    every { click.clickedInventory } returns player.inventory
    every { click.currentItem } returns target
    every { click.slot } returns 0
    every { click.view } returns view

    val nativeApply = mockk<EnchantApplyEvent>(relaxed = true)
    every { nativeApply.player } returns player
    every { nativeApply.book } returns book
    every { nativeApply.item } returns nativeValidatedTarget
    return SafetyFixture(player, topInventory, book, target, nativeValidatedTarget, nativeValidationKey, click, nativeApply)
}

private fun mockBridge(book: ItemStack, player: org.bukkit.entity.Player, chances: BookApplicationChances) {
    mockkStatic("ru.arc.enchanting.AdvancedBookPresentationKt")
    every { normalizeAdvancedBookRisk(book, player) } returns book
    every { presentAdvancedBook(book, player) } returns book
    every { isAdvancedBookRiskSafe(book) } returns true
    every { advancedBookChances(book) } returns chances
    every { protectedAdvancedBookTarget(any()) } returns null
}

private fun unmockBridge() {
    unmockkStatic("ru.arc.enchanting.AdvancedBookPresentationKt")
}
