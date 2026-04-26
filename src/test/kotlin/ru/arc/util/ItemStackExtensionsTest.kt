package ru.arc.util

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase

@Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
class ItemStackExtensionsTest :
    KotestTestBase({

        describe("Custom Model Data extensions") {

            it("hasCustomModelDataSafe should return false for item without model data") {
                val stack = ItemStack(Material.DIAMOND_SWORD)

                stack.hasCustomModelDataSafe().shouldBeFalse()
            }

            @Suppress("DEPRECATION")
            it("hasCustomModelDataSafe should return true for item with model data") {
                val stack = ItemStack(Material.DIAMOND_SWORD)
                stack.editMeta { it.setCustomModelData(1000) }

                stack.hasCustomModelDataSafe().shouldBeTrue()
            }

            it("customModelDataOrNull should return null for item without model data") {
                val stack = ItemStack(Material.DIAMOND_SWORD)

                stack.customModelDataOrNull.shouldBeNull()
            }

            @Suppress("DEPRECATION")
            it("customModelDataOrNull should return value for item with model data") {
                val stack = ItemStack(Material.DIAMOND_SWORD)
                stack.editMeta { it.setCustomModelData(1234) }

                stack.customModelDataOrNull shouldBe 1234
            }

            it("customModelDataOrZero should return 0 for item without model data") {
                val stack = ItemStack(Material.DIAMOND_SWORD)

                stack.customModelDataOrZero shouldBe 0
            }

            @Suppress("DEPRECATION")
            it("customModelDataOrZero should return value for item with model data") {
                val stack = ItemStack(Material.DIAMOND_SWORD)
                stack.editMeta { it.setCustomModelData(5678) }

                stack.customModelDataOrZero shouldBe 5678
            }

            @Suppress("DEPRECATION")
            it("withCustomModelData should set model data") {
                val stack =
                    ItemStack(Material.DIAMOND_SWORD)
                        .withCustomModelData(999)

                stack.customModelDataOrNull shouldBe 999
            }

            @Suppress("DEPRECATION")
            it("withCustomModelData with null should remove model data") {
                val stack = ItemStack(Material.DIAMOND_SWORD)
                stack.editMeta { it.setCustomModelData(100) }

                stack.withCustomModelData(null)

                stack.hasCustomModelDataSafe().shouldBeFalse()
            }
        }

        describe("Display name extensions") {

            it("withDisplayName should set display name from MiniMessage") {
                val stack =
                    ItemStack(Material.DIAMOND_SWORD)
                        .withDisplayName("<gold>Epic Sword")

                stack.displayNamePlain shouldBe "Epic Sword"
            }
        }

        describe("Lore extensions") {

            it("withLore should set lore from vararg") {
                val stack =
                    ItemStack(Material.DIAMOND_SWORD)
                        .withLore("<gray>Line 1", "<red>Line 2")

                val lore = stack.itemMeta?.lore()
                lore.shouldNotBeNull()
                lore!!.size shouldBe 2
            }

            it("withLore should set lore from list") {
                val stack =
                    ItemStack(Material.DIAMOND_SWORD)
                        .withLore(listOf("<gray>A", "<gray>B", "<gray>C"))

                val lore = stack.itemMeta?.lore()
                lore.shouldNotBeNull()
                lore!!.size shouldBe 3
            }

            it("appendLore should add to existing lore") {
                val stack =
                    ItemStack(Material.DIAMOND_SWORD)
                        .withLore("<gray>Original")
                        .appendLore("<red>Appended")

                val lore = stack.itemMeta?.lore()
                lore.shouldNotBeNull()
                lore!!.size shouldBe 2
            }
        }

        describe("Amount extensions") {

            it("withAmount should set stack amount") {
                val stack =
                    ItemStack(Material.DIAMOND)
                        .withAmount(32)

                stack.amount shouldBe 32
            }
        }

        describe("Null/Air checks") {

            it("isNullOrAir should return true for null") {
                val stack: ItemStack? = null

                stack.isNullOrAir().shouldBeTrue()
            }

            it("isNullOrAir should return true for air") {
                val stack = ItemStack(Material.AIR)

                stack.isNullOrAir().shouldBeTrue()
            }

            it("isNullOrAir should return false for valid item") {
                val stack = ItemStack(Material.DIAMOND)

                stack.isNullOrAir().shouldBeFalse()
            }

            it("isNotNullOrAir should return true for valid item") {
                val stack = ItemStack(Material.DIAMOND)

                stack.isNotNullOrAir().shouldBeTrue()
            }
        }

        @Suppress("DEPRECATION")
        describe("simpleItemStack factory") {

            it("should create ItemStack with DSL") {
                val stack =
                    simpleItemStack(Material.DIAMOND_SWORD) {
                        withDisplayName("<gold>Test Sword")
                        withAmount(1)
                    }

                stack.type shouldBe Material.DIAMOND_SWORD
                stack.displayNamePlain shouldBe "Test Sword"
            }

            it("should create ItemStack with custom amount") {
                val stack = simpleItemStack(Material.DIAMOND, 64)

                stack.type shouldBe Material.DIAMOND
                stack.amount shouldBe 64
            }
        }

        describe("copy extension") {

            it("should create a copy with modifications") {
                val original = ItemStack(Material.DIAMOND_SWORD)
                val copy =
                    original.copy {
                        withDisplayName("<red>Modified")
                    }

                original.displayNamePlain.shouldBeNull()
                copy.displayNamePlain shouldBe "Modified"
            }
        }
    })
