package ru.arc.util

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import ru.arc.KotestTestBase

class ItemStackDslTest :
    KotestTestBase({

        describe("itemStack DSL") {

            it("should create basic item with material") {
                val item = itemStack(Material.DIAMOND) {}

                item shouldNotBe null
                item.type shouldBe Material.DIAMOND
                item.amount shouldBe 1
            }

            it("should create item with amount") {
                val item = itemStack(Material.DIAMOND, 16) {}

                item.type shouldBe Material.DIAMOND
                item.amount shouldBe 16
            }

            it("should set display name from string") {
                val item =
                    itemStack(Material.DIAMOND) {
                        display("<gold>Test Name")
                    }

                val meta = item.itemMeta
                meta shouldNotBe null
                meta!!.hasDisplayName() shouldBe true
            }

            it("should set lore from vararg") {
                val item =
                    itemStack(Material.DIAMOND) {
                        lore("<gray>Line 1", "<red>Line 2")
                    }

                val meta = item.itemMeta
                meta shouldNotBe null
                meta!!.hasLore() shouldBe true
                meta.lore().shouldNotBeNull()
                meta.lore()!!.size shouldBe 2
            }

            it("should set lore from list") {
                val item =
                    itemStack(Material.DIAMOND) {
                        lore(listOf("<gray>Line 1", "<red>Line 2", "<blue>Line 3"))
                    }

                val meta = item.itemMeta
                meta?.lore().shouldNotBeNull()
                meta.lore()!!.size shouldBe 3
            }

            it("should set model data") {
                val item =
                    itemStack(Material.DIAMOND) {
                        modelData(12345)
                    }

                val meta = item.itemMeta
                meta shouldNotBe null
                @Suppress("DEPRECATION")
                meta!!.hasCustomModelData() shouldBe true
            }

            it("should add enchantment") {
                val item =
                    itemStack(Material.DIAMOND_SWORD) {
                        enchant(Enchantment.SHARPNESS, 5)
                    }

                val meta = item.itemMeta
                meta shouldNotBe null
                meta!!.hasEnchants() shouldBe true
                meta.enchants.containsKey(Enchantment.SHARPNESS) shouldBe true
                meta.enchants[Enchantment.SHARPNESS] shouldBe 5
            }

            it("should add enchantment with unsafe level") {
                val item =
                    itemStack(Material.DIAMOND_SWORD) {
                        enchantUnsafe(Enchantment.SHARPNESS, 100)
                    }

                val meta = item.itemMeta
                meta!!.enchants[Enchantment.SHARPNESS] shouldBe 100
            }

            it("should add item flags") {
                val item =
                    itemStack(Material.DIAMOND_SWORD) {
                        flags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES)
                    }

                val meta = item.itemMeta
                meta shouldNotBe null
                meta!!.itemFlags shouldContain ItemFlag.HIDE_ENCHANTS
                meta.itemFlags shouldContain ItemFlag.HIDE_ATTRIBUTES
            }

            it("should hide all flags") {
                val item =
                    itemStack(Material.DIAMOND_SWORD) {
                        hideAll()
                    }

                val meta = item.itemMeta
                meta shouldNotBe null
                meta!!.itemFlags shouldContain ItemFlag.HIDE_ATTRIBUTES
                meta.itemFlags shouldContain ItemFlag.HIDE_ENCHANTS
            }

            it("should make item glowing") {
                val item =
                    itemStack(Material.DIAMOND) {
                        glowing()
                    }

                val meta = item.itemMeta
                meta shouldNotBe null
                meta!!.hasEnchants() shouldBe true
                meta.itemFlags shouldContain ItemFlag.HIDE_ENCHANTS
            }

            it("should set unbreakable") {
                val item =
                    itemStack(Material.DIAMOND_PICKAXE) {
                        unbreakable(true)
                    }

                val meta = item.itemMeta
                meta shouldNotBe null
                meta!!.isUnbreakable shouldBe true
            }
        }

        describe("lore DSL builder") {

            it("should build lore with + operator") {
                val item =
                    itemStack(Material.DIAMOND) {
                        lore {
                            +"<gray>Line 1"
                            +"<red>Line 2"
                            +"<blue>Line 3"
                        }
                    }

                val meta = item.itemMeta
                meta?.lore().shouldNotBeNull()
                meta.lore()!!.size shouldBe 3
            }

            it("should add conditional lines") {
                val condition = true

                val item =
                    itemStack(Material.DIAMOND) {
                        lore {
                            +"<gray>Always"
                            lineIf(condition, "<green>Conditional")
                            lineIf(!condition, "<red>Not shown")
                        }
                    }

                val meta = item.itemMeta
                meta?.lore().shouldNotBeNull()
                meta.lore()!!.size shouldBe 2
            }

            it("should add empty lines") {
                val item =
                    itemStack(Material.DIAMOND) {
                        lore {
                            +"<gray>Line 1"
                            empty()
                            +"<gray>Line 2"
                        }
                    }

                val meta = item.itemMeta
                meta?.lore().shouldNotBeNull()
                meta.lore()!!.size shouldBe 3
            }
        }

        describe("tags DSL builder") {

            it("should apply tags to display") {
                val playerName = "TestPlayer"
                val level = 42

                val item =
                    itemStack(Material.PAPER) {
                        display("<gold>Player: <player>")
                        tags {
                            "player" to playerName
                            "level" to level.toString()
                        }
                    }

                val meta = item.itemMeta
                meta shouldNotBe null
                meta!!.hasDisplayName() shouldBe true
            }

            it("should apply single tag") {
                val item =
                    itemStack(Material.PAPER) {
                        display("<gold>Value: <value>")
                        tag("value", "100")
                    }

                val meta = item.itemMeta
                meta!!.hasDisplayName() shouldBe true
            }
        }

        describe("helper functions") {

            it("should create quick item with display") {
                val item = quickItem(Material.DIAMOND, "<gold>Quick Item")

                item.type shouldBe Material.DIAMOND
                item.itemMeta?.hasDisplayName() shouldBe true
                item.itemMeta?.itemFlags?.contains(ItemFlag.HIDE_ATTRIBUTES) shouldBe true
            }

            it("should create quick item with display and lore") {
                val item = quickItem(Material.DIAMOND, "<gold>Quick Item", "<gray>Lore 1", "<gray>Lore 2")

                item.type shouldBe Material.DIAMOND
                item.itemMeta?.hasDisplayName() shouldBe true
                item.itemMeta?.lore()?.size shouldBe 2
                item.itemMeta?.itemFlags?.contains(ItemFlag.HIDE_ATTRIBUTES) shouldBe true
            }
        }

        describe("skull items") {

            it("should create skull item from UUID") {
                val uuid = java.util.UUID.randomUUID()

                val item =
                    skullItem(uuid) {
                        display("<gold>Player Head")
                    }

                item.type shouldBe Material.PLAYER_HEAD
                item.itemMeta?.hasDisplayName() shouldBe true
            }
        }

        describe("item modification") {

            it("should modify existing item") {
                val original =
                    itemStack(Material.DIAMOND) {
                        display("<gray>Original")
                        modelData(100)
                    }

                val modified =
                    original.modify {
                        display("<gold>Modified")
                    }

                modified.type shouldBe Material.DIAMOND
                modified.itemMeta?.hasDisplayName() shouldBe true
            }
        }
    })
