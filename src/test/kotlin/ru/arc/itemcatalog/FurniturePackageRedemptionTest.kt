package ru.arc.itemcatalog

import dev.lone.itemsadder.api.CustomStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.persistence.PersistentDataType
import ru.arc.mounts.MountWallet
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

private const val PACKAGE_ID = "medieval_furniture"
private const val PACKAGE_ENTRY_ID = "medieval_furniture_entry"
private val NATIVE_MARKER = NamespacedKey("test", "native_furniture_id")

class FurniturePackageRedemptionTest : StringSpec({
    "a 30 item package becomes two numbered shulkers with native item data" {
        MockBukkitTestRuntime.open().use { paper ->
            val itemIds = (1..30).map { "decor:item_$it" }
            withMockItems(itemIds) { nativeItems ->
                val service = CatalogPhysicalRewards(packageSettings(itemIds), mockk<MountWallet>(relaxed = true))
                val key = "package:$PACKAGE_ID"
                val spec = checkNotNull(service.resolve(key))
                val player = paper.addPlayer("package-owner")

                service.redeem(player, spec, UUID.randomUUID()).join() shouldBe PhysicalRewardOutcome.Applied

                val boxes = player.inventory.storageContents.filterNotNull()
                    .filter { it.type == Material.PURPLE_SHULKER_BOX }
                boxes shouldHaveSize 2
                boxes.forEachIndexed { boxIndex, box ->
                    val meta = checkNotNull(box.itemMeta as? BlockStateMeta)
                    val shulker = checkNotNull(meta.blockState as? org.bukkit.block.ShulkerBox)
                    val stored = shulker.inventory.storageContents.filterNotNull()
                    stored shouldHaveSize if (boxIndex == 0) 27 else 3
                    stored.forEachIndexed { itemIndex, storedItem ->
                        val sourceId = itemIds[boxIndex * 27 + itemIndex]
                        storedItem.itemMeta?.persistentDataContainer?.get(NATIVE_MARKER, PersistentDataType.STRING) shouldBe sourceId
                        storedItem.itemMeta?.displayName() shouldBe nativeItems.getValue(sourceId).itemMeta?.displayName()
                    }
                }
            }
        }
    }

    "missing ItemsAdder item refuses a stale package without adding anything" {
        MockBukkitTestRuntime.open().use { paper ->
            val itemIds = (1..30).map { "decor:item_$it" }
            withMockItems(itemIds, missing = setOf(itemIds.last())) {
                val service = CatalogPhysicalRewards(packageSettings(itemIds), mockk<MountWallet>(relaxed = true))
                val key = "package:$PACKAGE_ID"
                service.resolve(key) shouldBe null

                val player = paper.addPlayer("missing-package-item")
                val before = player.inventory.storageContents.map { it?.clone() }
                val stale = PhysicalRewardSpec(
                    key = key,
                    fingerprint = OneTimeUseFingerprint.sha256(byteArrayOf(1)),
                    preview = ItemStack(Material.PAPER),
                )

                service.redeem(player, stale, UUID.randomUUID()).join() shouldBe
                    PhysicalRewardOutcome.Rejected("<red>Награда сейчас недоступна. Предмет сохранён.")
                player.inventory.storageContents.map { it?.clone() } shouldBe before
            }
        }
    }

    "a full inventory refuses the complete package without adding shulkers" {
        MockBukkitTestRuntime.open().use { paper ->
            val itemIds = (1..30).map { "decor:item_$it" }
            withMockItems(itemIds) {
                val service = CatalogPhysicalRewards(packageSettings(itemIds), mockk<MountWallet>(relaxed = true))
                val spec = checkNotNull(service.resolve("package:$PACKAGE_ID"))
                val player = paper.addPlayer("full-package-inventory")
                player.inventory.storageContents = Array(36) { ItemStack(Material.STONE, 64) }
                val before = player.inventory.storageContents.map { it?.clone() }

                service.canRedeem(player, spec) shouldBe "<red>Освободите 2 яч. инвентаря для награды."
                service.redeem(player, spec, UUID.randomUUID()).join() shouldBe
                    PhysicalRewardOutcome.Rejected("<red>В инвентаре не хватает места.")
                player.inventory.storageContents.map { it?.clone() } shouldBe before
            }
        }
    }
})

private fun packageSettings(itemIds: List<String>): RewardCatalogSettings =
    RewardCatalogSettings(
        enabled = true,
        title = "Каталог",
        categories = listOf(
            RewardCatalogCategory(
                id = "furniture",
                name = "Мебель",
                description = emptyList(),
                icon = CatalogIconStyle(Material.CHEST.name),
                entries = listOf(
                    RewardCatalogEntry(
                        id = PACKAGE_ENTRY_ID,
                        name = "Средневеклый набор",
                        description = listOf("Полный набор мебели"),
                        rarity = null,
                        requires = emptyList(),
                        source = RewardCatalogSource.FurniturePackage(PACKAGE_ID),
                        icon = CatalogIconStyle(Material.CHEST.name),
                    ),
                ),
            ),
        ),
        messages = RewardCatalogMessages.DEFAULT,
        packages = mapOf(PACKAGE_ID to RewardFurniturePackage("Средневеклый набор", itemIds)),
    )

private fun <T> withMockItems(
    itemIds: List<String>,
    missing: Set<String> = emptySet(),
    block: (Map<String, ItemStack>) -> T,
): T {
    mockkStatic(CustomStack::class)
    val nativeItems = itemIds.filterNot { it in missing }.associateWith { id ->
        ItemStack(Material.DIAMOND).apply {
            editMeta { meta ->
                meta.displayName(Component.text("Native $id"))
                meta.persistentDataContainer.set(NATIVE_MARKER, PersistentDataType.STRING, id)
            }
        }
    }
    val handles = nativeItems.mapValues { (_, stack) ->
        mockk<CustomStack>(relaxed = true).also { handle ->
            every { handle.itemStack } returns stack
        }
    }
    every { CustomStack.getInstance(any()) } answers { handles[firstArg()] }
    return try {
        block(nativeItems)
    } finally {
        unmockkStatic(CustomStack::class)
    }
}
