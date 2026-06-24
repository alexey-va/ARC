package ru.arc.gui

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import ru.arc.KotestTestBase
import ru.arc.config.Config

/**
 * Tests for the GUI DSL components.
 *
 * Note: Creating actual ChestGui instances requires a full Bukkit environment.
 * These tests focus on testing the DSL builder components and GuiItem creation.
 */
@Suppress("DEPRECATION")
class GuiDslTest :
    KotestTestBase({

        beforeEach {
            GuiDefaults.reset()
        }

        describe("ItemBuilder") {

            it("should build item with correct material") {
                val builder = ItemBuilder.standalone()
                builder.material(Material.DIAMOND_SWORD)

                val item = builder.build()

                item.shouldNotBeNull()
                item.item.type shouldBe Material.DIAMOND_SWORD
            }

            it("should build item with model data") {
                val builder = ItemBuilder.standalone()
                builder.material(Material.PAPER)
                builder.modelData(12345)

                val item = builder.build()

                item.shouldNotBeNull()
                item.item.itemMeta?.customModelData shouldBe 12345
            }

            it("should build item with display name") {
                val builder = ItemBuilder.standalone()
                builder.material(Material.BOOK)
                builder.display("<gold>Magic Book")

                val item = builder.build()

                item.shouldNotBeNull()
                item.item.itemMeta
                    ?.displayName()
                    .shouldNotBeNull()
            }

            it("should build item with lore") {
                val builder = ItemBuilder.standalone()
                builder.material(Material.BOOK)
                builder.lore("<gray>Line 1", "<gray>Line 2")

                val item = builder.build()

                item.shouldNotBeNull()
                val lore = item.item.itemMeta?.lore()
                lore.shouldNotBeNull()
                lore.size shouldBe 2
            }

            it("should build item with lore from list") {
                val builder = ItemBuilder.standalone()
                builder.material(Material.BOOK)
                builder.lore(listOf("<gray>Line 1", "<gray>Line 2", "<gray>Line 3"))

                val item = builder.build()

                item.shouldNotBeNull()
                val lore = item.item.itemMeta?.lore()
                lore.shouldNotBeNull()
                lore.size shouldBe 3
            }

            it("should overlay display from config via fromConfig") {
                val mockConfig =
                    mockk<Config> {
                        every { exists("item") } returns true
                        every { map("item", emptyMap<String, Any>()) } returns
                            mapOf("display" to "<red>Config Display")
                    }
                val builder = ItemBuilder.standalone(mockConfig)
                builder.material(Material.STONE)
                builder.display("<gray>Default")
                builder.fromConfig(mockConfig, "item")

                val item = builder.build()
                item.shouldNotBeNull()
            }

            it("should overlay lore from config via fromConfig") {
                val mockConfig =
                    mockk<Config> {
                        every { exists("item") } returns true
                        every { map("item", emptyMap<String, Any>()) } returns
                            mapOf("lore" to listOf("<gray>A", "<gray>B"))
                    }
                val builder = ItemBuilder.standalone(mockConfig)
                builder.material(Material.STONE)
                builder.lore("<gray>Default")
                builder.fromConfig(mockConfig, "item")

                val item = builder.build()
                item.shouldNotBeNull()
                val lore = item.item.itemMeta?.lore()
                lore.shouldNotBeNull()
                lore.size shouldBe 2
            }

            it("should track click handler setting") {
                var handlerCalled = false
                val builder = ItemBuilder.standalone()
                builder.material(Material.LEVER)
                builder.onClick { handlerCalled = true }

                val item = builder.build()
                item.shouldNotBeNull()
                // Handler is set but not invoked yet
            }

            it("should support skull creation") {
                val uuid = java.util.UUID.randomUUID()
                val builder = ItemBuilder.standalone()
                builder.skull(uuid)
                builder.display("<yellow>Player Head")

                val item = builder.build()
                item.shouldNotBeNull()
                item.item.type shouldBe Material.PLAYER_HEAD
            }

            it("should support tags for placeholders") {
                val builder = ItemBuilder.standalone()
                builder.material(Material.PAPER)
                builder.tag("name", "TestValue")
                builder.display("<name>")

                val item = builder.build()
                item.shouldNotBeNull()
            }

            it("should support multiple tags") {
                val builder = ItemBuilder.standalone()
                builder.material(Material.PAPER)
                builder.tags(mapOf("a" to "1", "b" to "2", "c" to "3"))
                builder.display("<a> <b> <c>")

                val item = builder.build()
                item.shouldNotBeNull()
            }

            it("should support tags infix block") {
                val builder = ItemBuilder.standalone()
                builder.material(Material.PAPER)
                builder.tags {
                    "a" to "1"
                    "b" to "2"
                }
                builder.display("<a> <b>")

                val item = builder.build()
                item.shouldNotBeNull()
            }

            it("should support setting amount") {
                val builder = ItemBuilder.standalone()
                builder.material(Material.DIAMOND)
                builder.amount(64)

                val item = builder.build()
                item.shouldNotBeNull()
                item.item.amount shouldBe 64
            }

            it("should support allowClick") {
                val builder = ItemBuilder.standalone()
                builder.material(Material.STONE)
                builder.allowClick()

                val item = builder.build()
                item.shouldNotBeNull()
            }
        }

        describe("guiItem quick builder") {

            it("should create simple gui item") {
                val item =
                    guiItem(
                        material = Material.STONE,
                        displayName = "<gray>Stone Block",
                    )

                item.shouldNotBeNull()
                item.item.type shouldBe Material.STONE
            }

            it("should create gui item with lore") {
                val item =
                    guiItem(
                        material = Material.DIAMOND,
                        displayName = "<aqua>Diamond",
                        loreLines = listOf("<gray>Shiny", "<gray>Valuable"),
                    )

                item.shouldNotBeNull()
                item.item.type shouldBe Material.DIAMOND
            }

            it("should create gui item with model data") {
                val item =
                    guiItem(
                        material = Material.PAPER,
                        displayName = "<white>Custom",
                        modelDataValue = 9999,
                    )

                item.shouldNotBeNull()
                item.item.itemMeta?.customModelData shouldBe 9999
            }

            it("should preserve custom model data when built from existing stack") {
                val base =
                    org.bukkit.inventory.ItemStack(Material.PAPER).apply {
                        editMeta { it.setCustomModelData(4242) }
                    }
                val builder = ItemBuilder.standalone()
                builder.stack(base)
                builder.display("<yellow>Pool")

                builder
                    .build()
                    .item.itemMeta
                    ?.customModelData shouldBe 4242
            }

            it("should create gui item with click handler") {
                var clicked = false
                val item =
                    guiItem(
                        material = Material.LEVER,
                        displayName = "<yellow>Toggle",
                        onClick = { clicked = true },
                    )

                item.shouldNotBeNull()
            }
        }

        describe("showTo extension") {

            it("should handle null player") {
                val mockGui = mockk<com.github.stefvanschie.inventoryframework.gui.type.ChestGui>(relaxed = true)
                mockGui.showTo(null)
                // No exception means pass
            }
        }
    })
