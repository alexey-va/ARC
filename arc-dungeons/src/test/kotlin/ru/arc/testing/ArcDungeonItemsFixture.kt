package ru.arc.testing

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.ServicePriority
import ru.arc.dungeons.api.ArcDungeonItems
import ru.arc.paper.testing.MockBukkitTestRuntime

/** Registers a small host-service double for child tests that exercise ARC item handoffs. */
internal class ArcDungeonItemsFixture(
    runtime: MockBukkitTestRuntime,
) : AutoCloseable {
    var magicDust: (ItemStack) -> ArcDungeonItems.MagicDust? = { null }

    private val owner = runtime.createSimplePlugin("ArcDungeonsTestHost")
    private val service = object : ArcDungeonItems {
        override fun resolvePreset(preset: String, amount: Int): List<ItemStack> = emptyList()

        override fun readMagicDust(item: ItemStack): ArcDungeonItems.MagicDust? = magicDust(item)

        override fun admitCreativeBook(player: Player, book: ItemStack): Int? {
            if (book.amount !in 1..book.maxStackSize) return null
            player.inventory.addItem(book.clone()).values.forEach { player.world.dropItem(player.location, it) }
            return player.inventory.storageContents.indices.firstOrNull { index ->
                player.inventory.getItem(index)?.isSimilar(book) == true
            }
        }

        override fun applyBook(
            player: Player,
            targetSlot: Int,
            expectedTarget: ItemStack,
            expectedBook: ItemStack,
            result: ItemStack?,
            bookInventorySlot: Int?,
        ) {
            require(expectedTarget.amount == 1 && expectedBook.amount > 0)
            require(bookInventorySlot == null || bookInventorySlot != targetSlot)
            val currentBook = if (bookInventorySlot == null) {
                player.itemOnCursor
            } else {
                player.inventory.getItem(bookInventorySlot)
            }
            check(player.inventory.getItem(targetSlot) == expectedTarget && currentBook == expectedBook) {
                "Enchantment inputs changed before test host application"
            }

            player.inventory.setItem(targetSlot, result?.clone()?.apply { amount = 1 })
            val remaining = expectedBook.takeIf { it.amount > 1 }?.clone()?.apply { amount-- }
            if (bookInventorySlot == null) {
                player.setItemOnCursor(remaining)
            } else {
                player.inventory.setItem(bookInventorySlot, remaining)
            }
        }
    }

    init {
        runtime.server.servicesManager.register(ArcDungeonItems::class.java, service, owner, ServicePriority.Normal)
    }

    override fun close() {
        owner.server.servicesManager.unregister(ArcDungeonItems::class.java, service)
    }
}
