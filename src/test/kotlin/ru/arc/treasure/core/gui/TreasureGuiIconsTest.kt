package ru.arc.treasure.core.gui

import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.util.customModelDataOrNull

@Suppress("DEPRECATION")
class TreasureGuiIconsTest :
    KotestTestBase({
        describe("TreasureGuiIcons") {
            it("should preserve custom model data for item treasures in pool list") {
                val stack =
                    ItemStack(Material.PAPER).apply {
                        editMeta { it.setCustomModelData(4242) }
                    }
                val treasure = Treasure.Item(stack = stack)

                TreasureGuiIcons.listIconStack(treasure).customModelDataOrNull shouldBe 4242
            }

            it("should use chest icon for empty pool preview") {
                val pool = TreasurePool(id = "empty")

                TreasureGuiIcons.poolPreviewIcon(pool).type shouldBe Material.CHEST
            }

            it("should use first treasure icon for non-empty pool preview") {
                val stack =
                    ItemStack(Material.STICK).apply {
                        editMeta { it.setCustomModelData(777) }
                    }
                val pool =
                    TreasurePool(
                        id = "loot",
                        treasures = listOf(Treasure.Item(stack = stack)),
                    )

                TreasureGuiIcons.poolPreviewIcon(pool).customModelDataOrNull shouldBe 777
            }

            it("should label money treasure icon with localized display name") {
                val treasure = Treasure.Money(min = 10.0, max = 50.0)
                val icon = TreasureGuiIcons.iconStack(treasure)

                icon.type shouldBe Material.GOLD_INGOT
                val display =
                    icon.itemMeta
                        ?.displayName()
                        ?.let {
                            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                .plainText()
                                .serialize(it)
                        }
                display shouldBe "Деньги"
            }

            it("should show slimefun item id on fallback icon") {
                val treasure = Treasure.Slimefun(itemId = "ELECTRIC_MOTOR", min = 1, max = 2)
                val icon = TreasureGuiIcons.iconStack(treasure)

                val lore =
                    icon.itemMeta
                        ?.lore()
                        ?.map {
                            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                .plainText()
                                .serialize(it)
                        }
                        ?: emptyList()
                lore.any { it.contains("ELECTRIC_MOTOR") } shouldBe true
            }
        }
    })
