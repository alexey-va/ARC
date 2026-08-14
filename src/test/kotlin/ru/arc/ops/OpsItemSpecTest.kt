package ru.arc.ops

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import ru.arc.KotestTestBase
import ru.arc.util.customModelDataOrNull

class OpsItemSpecTest :
    KotestTestBase({

        describe("OpsItemSpec") {

            it("should build item from material and customModelData") {
                val json =
                    JsonObject().apply {
                        addProperty("material", "STICK")
                        addProperty("amount", 3)
                        addProperty("customModelData", 11138)
                        addProperty("display", "<gold>Coins")
                    }

                val stack = OpsItemSpec.build(json)

                stack.type shouldBe Material.STICK
                stack.amount shouldBe 3
                stack.customModelDataOrNull shouldBe 11138
            }

            it("should resolve itemsadder shorthand") {
                val json =
                    JsonObject().apply {
                        addProperty("itemsadder", "arc:background")
                    }

                val stack = OpsItemSpec.build(json)

                stack.type shouldBe Material.GRAY_STAINED_GLASS_PANE
                stack.customModelDataOrNull shouldBe 11000
            }

            it("should resolve canonical ARC menu icons") {
                val category = OpsItemSpec.build(JsonObject().apply { addProperty("itemsadder", "arc:change_category") })
                val sort = OpsItemSpec.build(JsonObject().apply { addProperty("itemsadder", "arc:sort") })

                category.type shouldBe Material.COMPASS
                category.customModelDataOrNull shouldBe 11019
                sort.type shouldBe Material.HOPPER
                sort.customModelDataOrNull shouldBe 11022
            }

            it("should resolve iageneric bag_of_coins through canonical itemsadder field") {
                val json =
                    JsonObject().apply {
                        addProperty("itemsadder", "iageneric:bag_of_coins")
                    }

                val stack = OpsItemSpec.build(json)

                stack.type shouldBe Material.STICK
                stack.customModelDataOrNull shouldBe 11138
            }

            it("should apply enchants and flags") {
                val json =
                    JsonParser.parseString(
                        """
                        {
                          "material": "DIAMOND_SWORD",
                          "enchants": {"sharpness": 5},
                          "itemFlags": ["HIDE_ENCHANTS"],
                          "unbreakable": true
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val stack = OpsItemSpec.build(json)
                val meta = stack.itemMeta!!

                meta.enchants[Enchantment.SHARPNESS] shouldBe 5
                meta.itemFlags.contains(ItemFlag.HIDE_ENCHANTS) shouldBe true
                meta.isUnbreakable shouldBe true
            }

            it("should resolve a namespaced enchantment key") {
                val json =
                    JsonParser.parseString(
                        """
                        {
                          "material": "DIAMOND_SWORD",
                          "enchants": {"minecraft:sharpness": 3}
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val stack = OpsItemSpec.build(json)

                stack.itemMeta.enchants[Enchantment.SHARPNESS] shouldBe 3
            }

            it("should format customData as SNBT for treasure tokens") {
                val customData =
                    JsonObject().apply {
                        addProperty("slimefun_token", 1)
                        addProperty("arc:treasure_key", "sf")
                    }

                OpsItemSpec.customDataToSnbt(customData) shouldBe
                    """{slimefun_token:1,"arc:treasure_key":"sf"}"""
            }

            it("should roundtrip serialize built item") {
                val json =
                    JsonObject().apply {
                        addProperty("material", "CHEST")
                        addProperty("display", "<yellow>Storage")
                    }

                val stack = OpsItemSpec.build(json)
                val map = OpsItemSpec.toMap(stack)

                map["material"] shouldBe "CHEST"
                map["amount"] shouldBe 1
                (map.containsKey("display")) shouldBe true
            }

            it("should mark air as empty") {
                val map = OpsItemSpec.toMap(null)
                map["empty"] shouldBe true
            }
        }

        describe("OpsItemHandlers") {

            it("should give item to online player inventory") {
                val player = server.addPlayer("itemops")
                OpsHttpConfig.loadForTest(TestOpsHttpConfig(itemsGiveEnabled = true))

                val body =
                    JsonParser.parseString(
                        """
                        {
                          "itemsadder": "iageneric:bag_of_coins",
                          "amount": 1
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val result = OpsItemHandlers.giveItem(player.name, body, maxStack = 64)

                result["mode"] shouldBe "inventory"
                result["overflow"] shouldBe 0
                player.inventory.contents.any { it?.customModelDataOrNull == 11138 } shouldBe true
            }

            it("should read player inventory slots") {
                val player = server.addPlayer("invread")
                player.inventory.setItem(0, OpsItemSpec.build(JsonObject().apply { addProperty("material", "DIAMOND") }))

                val result = OpsItemHandlers.playerInventory(player.name)

                (result.containsKey("slots")) shouldBe true
                @Suppress("UNCHECKED_CAST")
                val slots = result["slots"] as Map<String, Any?>
                (slots.containsKey("0")) shouldBe true
                @Suppress("UNCHECKED_CAST")
                val slot0 = slots["0"] as Map<String, Any?>
                slot0["material"] shouldBe "DIAMOND"
            }

            it("should preview item without giving") {
                val body =
                    JsonObject().apply {
                        addProperty("itemsadder", "arc:left_gray")
                    }

                val result = OpsItemHandlers.previewItem(body)

                result["preview"] shouldBe true
                @Suppress("UNCHECKED_CAST")
                val item = result["item"] as Map<String, Any?>
                item["material"] shouldBe "BLUE_STAINED_GLASS_PANE"
                item["customModelData"] shouldBe 11013
            }
        }

        describe("OpsCmiKitHandlers") {

            it("should parse ItemSpec kit definition without binary blobs") {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "display": "&eМеню",
                          "delay": 0,
                          "enabled": true,
                          "icon": {"material": "CLOCK", "display": "<yellow>Меню"},
                          "items": {
                            "0": {"material": "TORCH", "amount": 16}
                          },
                          "extraItems": {
                            "offhand": {"material": "SHIELD"}
                          },
                          "commands": ["asConsole! dm open main_menu [playerName]"]
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val definition = OpsCmiKitHandlers.parseDefinition("menu", body)

                definition.name shouldBe "menu"
                definition.commandName shouldBe "menu"
                definition.delay shouldBe 0L
                definition.icon.type shouldBe Material.CLOCK
                definition.items[0]?.type shouldBe Material.TORCH
                definition.items[0]?.amount shouldBe 16
                definition.extraItems.values.single().type shouldBe Material.SHIELD
                definition.commands shouldBe listOf("asConsole! dm open main_menu [playerName]")
            }

            it("should reject preview-only empty kits") {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "display": "Empty",
                          "icon": {"material": "CHEST"}
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val error =
                    runCatching {
                        OpsCmiKitHandlers.parseDefinition("empty", body)
                    }.exceptionOrNull()

                error?.message shouldBe "kit needs items, extraItems, and/or commands"
            }

            it("should reject binary data instead of accepting legacy CMI blobs") {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "display": "Legacy",
                          "icon": {"blob": "H4sIA..."},
                          "commands": ["say test"]
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val error =
                    runCatching {
                        OpsCmiKitHandlers.parseDefinition("legacy", body)
                    }.exceptionOrNull()

                error?.message shouldBe "material or itemsadder required"
            }

            it("should require a live CMI plugin for runtime preview") {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "display": "Menu",
                          "icon": {"material": "CLOCK"},
                          "commands": ["say test"]
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val error =
                    runCatching {
                        OpsCmiKitHandlers.preview("menu", body)
                    }.exceptionOrNull()

                error?.message shouldBe "CMI plugin is not enabled"
            }
        }
    })
