package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.AeKind
import ru.arc.treasure.core.AeNativeItems
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import ru.arc.util.TextUtil
import java.util.UUID

class RewardCatalogGuiControllerTest : StringSpec({
    "native AE consumables freeze their actual stack without a second voucher or reroll" {
        MockBukkitTestRuntime.open().use {
            val native = ItemStack(Material.IRON_INGOT, 32).also { stack ->
                stack.editMeta { it.displayName(Component.text("Магическая пыль +3%")) }
            }
            val reward = Treasure.Ae(AeKind.ITEM, "magic", amount = 32, id = "dust")
            val entry = RewardCatalogEntry("dust", "Пыль ×32", emptyList(), null, emptyList(),
                RewardCatalogSource.Treasure("dust_pool", "dust"), CatalogIconStyle("IRON_INGOT"), 10000,
                tooltipStyle = "lzblocks:tooltip/rare")
            val settings = RewardCatalogSettings(true, "Кейс", listOf(
                RewardCatalogCategory("case_test", "Тест", emptyList(), CatalogIconStyle("CHEST"), listOf(entry), rolls = 1),
            ), RewardCatalogMessages.DEFAULT)
            mockkObject(Treasures, AeNativeItems, RewardItemPresentation)
            try {
                every { Treasures.getPool("dust_pool") } returns TreasurePool("dust_pool", listOf(reward))
                every { AeNativeItems.supports(reward) } returns true
                every { AeNativeItems.create(reward, preview = false) } returns listOf(native)
                // Assert the real adapter output before MockBukkit's clone loses modern
                // components; the exact clone limitation is documented in RewardItemPresentationTest.
                every { RewardItemPresentation.tooltip(any(), entry) } answers {
                    (callOriginal() as ItemStack).also {
                        it.getData(DataComponentTypes.TOOLTIP_STYLE) shouldBe Key.key("lzblocks", "tooltip/rare")
                    }
                }
                val physical = CatalogPhysicalRewards(settings)
                physical.isVoucherSource(entry) shouldBe false
                val controller = RewardCatalogGuiController(settings, "arc.test", physicalSource = physical::isVoucherSource)
                val reference = requireNotNull(controller.prepareCaseReward("case_test", "dust"))
                repeat(2) {
                    val issued = requireNotNull(controller.materializeCaseReward(reference)).single()
                    issued.amount shouldBe 32
                    issued.itemMeta.displayName() shouldBe native.itemMeta.displayName()
                    PhysicalRewardVoucher.identity(issued) shouldBe null
                    issued.amount = 1
                }
                verify(exactly = 1) { AeNativeItems.create(reward, preview = false) }
                verify(exactly = 1) { RewardItemPresentation.tooltip(any(), entry) }
                native.getData(DataComponentTypes.TOOLTIP_STYLE) shouldBe null
            } finally {
                unmockkObject(Treasures, AeNativeItems, RewardItemPresentation)
            }
        }
    }

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
