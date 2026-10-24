package ru.arc.enchanting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.InventoryView
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation

class CreativeWhiteScrollGuardTest : StringSpec({
    "creative scroll veto leaves vanilla replacement and displaced sword placement intact" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { runtime ->
                for (amount in listOf(1, 2)) {
                    val player = runtime.addPlayer("Creative$amount")
                    val sword = ItemStack(Material.NETHERITE_SWORD).apply {
                        editMeta { it.displayName(Component.text("Ender Katana")) }
                    }
                    val scroll = whiteScroll(amount)
                    player.inventory.setItem(0, sword.clone())
                    val view = inventoryView(player)
                    val click = InventoryCreativeEvent(view, InventoryType.SlotType.QUICKBAR, 36, scroll.clone())
                    val native = CreativeScrollApplyFixture(player, scroll.clone())
                    val config = mockk<EnchantingConfig>()
                    every { config.text(any()) } returns Component.text("Use survival")
                    val guard = CreativeWhiteScrollGuard(config, CreativeScrollApplyFixture::class.java)
                    try {
                        guard.start(runtime.createSimplePlugin("CreativeGuard$amount"))
                        guard.onClick(click)
                        runtime.server.pluginManager.callEvent(native)
                        native.isCancelled shouldBe true
                        click.isCancelled shouldBe false
                        player.inventory.getItem(0) shouldBe sword
                        // Exact Paper boundary: DEFAULT creative writes the proposed slot contents.
                        // Its client may already have queued placement of the displaced original sword.
                        player.inventory.setItem(click.slot, click.cursor.clone())
                        player.inventory.setItem(1, sword.clone())
                        player.inventory.storageContents.filterNotNull().count { it.type == Material.NETHERITE_SWORD } shouldBe 1
                        player.inventory.getItem(0) shouldBe scroll
                        verify(exactly = 1) { config.text("messages.white-scroll-survival-only") }
                        guard.onClickFinished(click)
                        val later = CreativeScrollApplyFixture(player, scroll.clone())
                        guard.onNativeApply(later)
                        later.isCancelled shouldBe false
                    } finally {
                        guard.close()
                    }
                }
            }
        }
    }

    "survival scroll application and unrelated creative items remain native" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { runtime ->
                val player = runtime.addPlayer("Survival")
                val scroll = whiteScroll(1)
                player.setItemOnCursor(scroll.clone())
                val click = InventoryClickEvent(
                    inventoryView(player), InventoryType.SlotType.QUICKBAR, 36,
                    ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR,
                )
                val config = mockk<EnchantingConfig>(relaxed = true)
                val guard = CreativeWhiteScrollGuard(config, CreativeScrollApplyFixture::class.java)
                try {
                    guard.onClick(click)
                    val native = CreativeScrollApplyFixture(player, scroll)
                    guard.onNativeApply(native)
                    native.isCancelled shouldBe false
                    click.isCancelled shouldBe false
                    val unrelated = ItemStack(Material.IRON_INGOT)
                    val creative = InventoryCreativeEvent(inventoryView(player), InventoryType.SlotType.QUICKBAR, 36, unrelated)
                    guard.onClick(creative)
                    val other = CreativeScrollApplyFixture(player, unrelated)
                    guard.onNativeApply(other)
                    other.isCancelled shouldBe false
                    verify(exactly = 0) { config.text(any()) }
                } finally {
                    guard.close()
                }
            }
        }
    }
})

// MockBukkit's default player view cannot convert raw slots. Only the Paper view boundary is mocked.
private fun inventoryView(actor: Player): InventoryView = mockk {
    every { player } returns actor
    every { convertSlot(36) } returns 0
}

private fun whiteScroll(amount: Int) = ItemStack(Material.IRON_INGOT, amount).apply {
    editMeta {
        it.persistentDataContainer.set(
            NamespacedKey("advancedenchantments", "whitescroll-item"), PersistentDataType.STRING, "true",
        )
    }
}

/** The verified 9.24.15 event shape; the compile-time 8.7.4 API has no ItemApplyEvent. */
class CreativeScrollApplyFixture(private val player: Player, private val scroll: ItemStack) : Event(), Cancellable {
    private var cancelled = false
    fun getPlayer(): Player = player
    fun getAeItemStack(): ItemStack = scroll
    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(value: Boolean) { cancelled = value }
    override fun getHandlers(): HandlerList = EVENT_HANDLERS

    companion object {
        private val EVENT_HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = EVENT_HANDLERS
    }
}
