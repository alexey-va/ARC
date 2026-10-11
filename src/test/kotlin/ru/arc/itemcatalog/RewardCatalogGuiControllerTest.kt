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
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import org.bukkit.inventory.ItemStack
import ru.arc.gui.ArcMenus
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.AeKind
import ru.arc.treasure.core.AeNativeItems
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import ru.arc.util.TextUtil
import java.nio.file.Files
import java.util.Comparator
import java.util.UUID

class RewardCatalogGuiControllerTest : StringSpec({
    "native crate key preview contributes odds but cannot mint a key even for an operator" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("NativeKeyPreviewGui")
            val provider = paper.createSimplePlugin("ExcellentCrates")
            val player = paper.addPlayer("NativeKeyViewer")
            player.addAttachment(plugin, "arc.test", true)
            val entry = RewardCatalogEntry("furniture_key", "Furniture Key", emptyList(), null, emptyList(),
                RewardCatalogSource.NativeCrateKey("case_furniture"), CatalogIconStyle("TRIPWIRE_HOOK"), 200)
            val category = RewardCatalogCategory("case_test", "Test Case", emptyList(), CatalogIconStyle("CHEST"),
                listOf(entry, entry.copy(id = "other", source = RewardCatalogSource.Planned("other"), weight = 9800)), rolls = 1)
            val settings = RewardCatalogSettings(true, "Rewards", listOf(category), RewardCatalogMessages.DEFAULT)
            val root = Files.createTempDirectory("native-key-preview")
            ArcMenus.initialize(plugin, root)
            try {
                val controller = RewardCatalogGuiController(settings, "arc.test")
                controller.prepareCaseReward("case_test", "furniture_key") shouldBe null
                controller.issueCaseReward(player, "case_test", "furniture_key") shouldBe CaseRewardIssueResult.PROVIDER_UNAVAILABLE
                controller.openRoot(player)
                val plain = PlainTextComponentSerializer.plainText()
                val rootView = player.openInventory
                val categorySlot = rootView.topInventory.contents.indexOfFirst { stack ->
                    stack?.itemMeta?.displayName()?.let(plain::serialize) == "Test Case"
                }
                (categorySlot >= 0) shouldBe true
                paper.callEvent(InventoryClickEvent(rootView, InventoryType.SlotType.CONTAINER, categorySlot,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL))
                paper.performTicks(1)
                val view = player.openInventory
                val keySlot = view.topInventory.contents.indexOfFirst { stack ->
                    stack?.itemMeta?.displayName()?.let(plain::serialize) == "Furniture Key"
                }
                (keySlot >= 0) shouldBe true
                val lore = view.topInventory.getItem(keySlot)!!.itemMeta.lore().orEmpty().joinToString("\n", transform = plain::serialize)
                lore.contains("2%") shouldBe true
                lore.contains("Ключ выдаётся при открытии кейса") shouldBe true
                lore.contains("ЛКМ — получить") shouldBe false
                paper.callEvent(InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, keySlot,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL))
                paper.performTicks(1)
                player.inventory.contents.filterNotNull().any { it.type == Material.TRIPWIRE_HOOK } shouldBe false
                val health = controller.healthSnapshot()["categories"] as List<*>
                (health.single() as Map<*, *>)["unavailable"] shouldBe emptyList<String>()

                plugin.server.pluginManager.disablePlugin(provider)
                controller.openRoot(player)
                paper.callEvent(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, categorySlot,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL))
                paper.performTicks(1)
                val unavailableLore = player.openInventory.topInventory.getItem(keySlot)!!.itemMeta.lore().orEmpty()
                    .joinToString("\n", transform = plain::serialize)
                unavailableLore.contains("Сейчас недоступно") shouldBe true
                unavailableLore.contains("Ключ выдаётся при открытии кейса") shouldBe false
                val unavailableHealth = controller.healthSnapshot()["categories"] as List<*>
                (unavailableHealth.single() as Map<*, *>)["unavailable"] shouldBe listOf("furniture_key")
            } finally {
                ArcMenus.close()
                root.toFile().deleteRecursively()
            }
        }
    }

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

    "retired case outcomes stay hidden while pending reward ids remain issuable" {
        MockBukkitTestRuntime.open().use { paper ->
            val poolId = "retired-case-${UUID.randomUUID()}"
            val retiredTreasure = Treasure.Item(ItemStack(Material.DIAMOND), min = 1, max = 1, id = "retired")
            val activeTreasure = Treasure.Item(ItemStack(Material.EMERALD), min = 1, max = 1, id = "active")
            val retired = RewardCatalogEntry(
                id = "retired",
                name = "Retired Reward",
                description = emptyList(),
                rarity = null,
                requires = emptyList(),
                source = RewardCatalogSource.Treasure(poolId, retiredTreasure.id),
                icon = CatalogIconStyle(Material.DIAMOND.name),
                weight = 0,
            )
            val active = retired.copy(
                id = "active",
                name = "Active Reward",
                source = RewardCatalogSource.Treasure(poolId, activeTreasure.id),
                icon = CatalogIconStyle(Material.EMERALD.name),
                weight = 5,
            )
            val category = RewardCatalogCategory(
                id = "case_test",
                name = "Test Case",
                description = emptyList(),
                icon = CatalogIconStyle(Material.CHEST.name),
                entries = listOf(retired, active),
                rolls = 1,
            )
            val settings = RewardCatalogSettings(true, "Rewards", listOf(category), RewardCatalogMessages.DEFAULT)
            val player = paper.addPlayer("RetiredRewardHolder")
            val plugin = paper.createSimplePlugin("RetiredRewardGuiTest")
            val root = Files.createTempDirectory("retired-reward-menu")
            mockkObject(Treasures)
            every { Treasures.getPool(poolId) } returns TreasurePool(poolId, listOf(retiredTreasure, activeTreasure))
            ArcMenus.initialize(plugin, root)
            try {
                val controller = RewardCatalogGuiController(settings, "arc.test")
                controller.openRoot(player)

                val plainText = PlainTextComponentSerializer.plainText()
                val rootView = player.openInventory
                val categorySlot = rootView.topInventory.contents.indexOfFirst { stack ->
                    stack?.itemMeta?.displayName()?.let(plainText::serialize) == "Test Case"
                }
                (categorySlot >= 0) shouldBe true
                paper.callEvent(
                    InventoryClickEvent(
                        rootView,
                        InventoryType.SlotType.CONTAINER,
                        categorySlot,
                        ClickType.LEFT,
                        InventoryAction.PICKUP_ALL,
                    ),
                )
                paper.performTicks(1)

                val visibleNames = player.openInventory.topInventory.contents.filterNotNull().mapNotNull { stack ->
                    stack.itemMeta?.displayName()?.let(plainText::serialize)
                }
                visibleNames.any { "Retired Reward" in it } shouldBe false
                visibleNames.any { "Active Reward" in it } shouldBe true

                controller.issueCaseReward(player, "case_test", "retired") shouldBe CaseRewardIssueResult.INVENTORY
                player.inventory.contents.filterNotNull().sumOf { if (it.type == Material.DIAMOND) it.amount else 0 } shouldBe 1
            } finally {
                ArcMenus.close()
                unmockkObject(Treasures)
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
})
