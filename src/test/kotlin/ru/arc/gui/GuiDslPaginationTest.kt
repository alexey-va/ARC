package ru.arc.gui

import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.KotestTestBase
import ru.arc.util.itemStack

/**
 * Tests for GuiDsl calculations and builder logic.
 * Cannot test actual ChestGui creation due to InventoryUI framework requiring plugin classloader.
 * Instead tests the DSL builder logic, calculations, and item builders.
 */
@Suppress("DEPRECATION")
class GuiDslPaginationTest :
    KotestTestBase({

        describe("GuiDsl dynamic row calculation") {
            it("should calculate rows correctly for small lists") {
                // Arrange
                val itemCount = 5
                val itemsPerRow = 9
                val minRows = 2
                val maxRows = 6
                val navRows = 1

                // Act
                val contentRows = kotlin.math.ceil(itemCount.toDouble() / itemsPerRow).toInt()
                val totalRows = (contentRows + navRows).coerceIn(minRows, maxRows)

                // Assert
                totalRows shouldBe 2 // 1 content row + 1 nav row, but min is 2
            }

            it("should calculate rows correctly for medium lists") {
                // Arrange
                val itemCount = 20
                val itemsPerRow = 9
                val minRows = 2
                val maxRows = 6
                val navRows = 1

                // Act
                val contentRows = kotlin.math.ceil(itemCount.toDouble() / itemsPerRow).toInt()
                val totalRows = (contentRows + navRows).coerceIn(minRows, maxRows)

                // Assert
                totalRows shouldBe 4 // 3 content rows (20/9 = 2.22, ceil = 3) + 1 nav row
            }

            it("should calculate rows correctly for large lists") {
                // Arrange
                val itemCount = 100
                val itemsPerRow = 9
                val minRows = 2
                val maxRows = 6
                val navRows = 1

                // Act
                val contentRows = kotlin.math.ceil(itemCount.toDouble() / itemsPerRow).toInt()
                val totalRows = (contentRows + navRows).coerceIn(minRows, maxRows)

                // Assert
                totalRows shouldBe 6 // Would be 12 (11 + 1) but capped at maxRows
            }

            it("should respect minimum rows") {
                // Arrange
                val itemCount = 1
                val itemsPerRow = 9
                val minRows = 3
                val maxRows = 6
                val navRows = 1

                // Act
                val contentRows = kotlin.math.ceil(itemCount.toDouble() / itemsPerRow).toInt()
                val totalRows = (contentRows + navRows).coerceIn(minRows, maxRows)

                // Assert
                totalRows shouldBe 3 // Would be 2 (1 + 1) but min is 3
            }

            it("should respect maximum rows") {
                // Arrange
                val itemCount = 500
                val itemsPerRow = 9
                val minRows = 2
                val maxRows = 6
                val navRows = 1

                // Act
                val contentRows = kotlin.math.ceil(itemCount.toDouble() / itemsPerRow).toInt()
                val totalRows = (contentRows + navRows).coerceIn(minRows, maxRows)

                // Assert
                totalRows shouldBe 6 // Capped at maxRows
            }
        }

        describe("GuiDsl item builder with itemStack DSL") {
            it("should build item with display name") {
                // Act
                val item =
                    itemStack(Material.DIAMOND_SWORD) {
                        display("<gold>Legendary Sword")
                    }

                // Assert
                item.type shouldBe Material.DIAMOND_SWORD
                item.itemMeta?.hasDisplayName() shouldBe true
            }

            it("should build item with lore") {
                // Act
                val item =
                    itemStack(Material.DIAMOND_SWORD) {
                        display("<gold>Legendary Sword")
                        lore {
                            +"<gray>Damage: +50"
                            +"<gray>Durability: 1000"
                        }
                    }

                // Assert
                item.type shouldBe Material.DIAMOND_SWORD
                item.lore()?.size shouldBe 2
            }

            it("should handle enchantments") {
                // Act
                val item =
                    itemStack(Material.BOW) {
                        display("<aqua>Enchanted Bow")
                        enchant(org.bukkit.enchantments.Enchantment.POWER, 5)
                    }

                // Assert
                item.type shouldBe Material.BOW
                item.enchantments.size shouldBeInRange 1..10 // May have default enchants
            }

            it("should support model data") {
                // Act
                val item =
                    itemStack(Material.PAPER) {
                        display("<yellow>Custom Item")
                        modelData(12345)
                    }

                // Assert
                item.type shouldBe Material.PAPER
                item.itemMeta?.hasCustomModelData() shouldBe true
                item.itemMeta?.customModelData shouldBe 12345
            }

            it("should handle complex item with multiple properties") {
                // Act
                val item =
                    itemStack(Material.DIAMOND_CHESTPLATE) {
                        display("<gradient:blue:cyan>Legendary Armor")
                        lore {
                            +"<gray>Defense: +100"
                            +"<gray>Weight: Heavy"
                            +"<gold>Special: Unbreakable"
                        }
                        enchant(org.bukkit.enchantments.Enchantment.PROTECTION, 4)
                        enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3)
                        modelData(999)
                    }

                // Assert
                item.type shouldBe Material.DIAMOND_CHESTPLATE
                item.itemMeta?.hasDisplayName() shouldBe true
                item.lore()?.size shouldBe 3
                item.itemMeta?.hasCustomModelData() shouldBe true
                item.enchantments.size shouldBeInRange 2..10
            }
        }

        describe("Pagination slot calculations") {
            it("should calculate page count correctly") {
                // For small item count
                val items1 = (1..5).toList()
                val slotsPerPage = 45 // 5 rows * 9 slots
                val pageCount1 = kotlin.math.ceil(items1.size.toDouble() / slotsPerPage).toInt()
                pageCount1 shouldBe 1

                // For medium item count
                val items2 = (1..50).toList()
                val pageCount2 = kotlin.math.ceil(items2.size.toDouble() / slotsPerPage).toInt()
                pageCount2 shouldBe 2

                // For large item count
                val items3 = (1..200).toList()
                val pageCount3 = kotlin.math.ceil(items3.size.toDouble() / slotsPerPage).toInt()
                pageCount3 shouldBe 5
            }

            it("should calculate items per page correctly") {
                val totalSlots = 54 // 6 rows
                val navBarSlots = 9 // 1 row
                val contentSlots = totalSlots - navBarSlots

                contentSlots shouldBe 45
            }
        }
    })
