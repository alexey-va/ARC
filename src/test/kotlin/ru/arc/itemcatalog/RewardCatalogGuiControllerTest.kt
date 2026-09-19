package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import ru.arc.util.TextUtil
import java.util.UUID

class RewardCatalogGuiControllerTest : StringSpec({
    "contextualizes native right-click hints without changing ordinary lore" {
        val native = TextUtil.mm("<#8c8c8c>[<#92bed8>▶<#8c8c8c>] <#92bed8>ПКМ<#e6fff3> — открыть", true)
        val contextualized = contextualizeRewardCatalogNativeLore(native)

        PlainTextComponentSerializer.plainText().serialize(contextualized) shouldBe "После получения: ПКМ — открыть"
        PlainTextComponentSerializer.plainText().serialize(native) shouldBe "[▶] ПКМ — открыть"
        contextualized.color() shouldBe native.color()

        val ordinary = Component.text("Описание [важное]")
        contextualizeRewardCatalogNativeLore(ordinary) shouldBe ordinary

        val quotedHint = Component.text("Описание [важное]: подсказка [▶] ПКМ — открыть")
        contextualizeRewardCatalogNativeLore(quotedHint) shouldBe quotedHint
    }

    "case item grants keep the native item name and encode quantity in the stack amount" {
        MockBukkitTestRuntime.open().use {
            val poolId = "native-case-item-${UUID.randomUUID()}"
            val nativeName = Component.text("Книга остроты III")
            val template = ItemStack(Material.BOOK).also { stack ->
                stack.editMeta { meta -> meta.displayName(nativeName) }
            }
            val treasure = Treasure.Item(template, min = 3, max = 3, id = "three_books")
            val entry = RewardCatalogEntry(
                id = treasure.id,
                name = "Три книги случайных чар",
                description = listOf("Карточка награды"),
                rarity = null,
                requires = emptyList(),
                source = RewardCatalogSource.Treasure(poolId, treasure.id),
                icon = CatalogIconStyle(Material.BOOK.name),
                weight = 10_000,
            )
            val settings = RewardCatalogSettings(
                enabled = true,
                title = "Кейсы",
                categories = listOf(
                    RewardCatalogCategory(
                        id = "case_test",
                        name = "Тест",
                        description = emptyList(),
                        icon = CatalogIconStyle(Material.CHEST.name),
                        entries = listOf(entry),
                        rolls = 1,
                    ),
                ),
                messages = RewardCatalogMessages.DEFAULT,
            )
            mockkObject(Treasures)
            every { Treasures.getPool(poolId) } returns TreasurePool(poolId, listOf(treasure))
            try {
                val controller = RewardCatalogGuiController(settings, "arc.test")
                val reference = requireNotNull(controller.prepareCaseReward("case_test", treasure.id))
                val granted = requireNotNull(controller.materializeCaseReward(reference)).single()

                granted.amount shouldBe 3
                granted.itemMeta?.displayName() shouldBe nativeName
                PlainTextComponentSerializer.plainText().serialize(granted.itemMeta?.displayName() ?: Component.empty()) shouldBe
                    "Книга остроты III"
            } finally {
                unmockkObject(Treasures)
            }
        }
    }
})
