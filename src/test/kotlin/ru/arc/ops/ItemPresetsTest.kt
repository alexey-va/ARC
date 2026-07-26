package ru.arc.ops

import com.google.gson.JsonParser
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.KotestTestBase
import ru.arc.config.ConfigManager
import java.nio.file.Files

class ItemPresetsTest :
    KotestTestBase({
        afterTest {
            runCatching {
                if (ItemPresets.definition("content_test_bundle") != null) {
                    ItemPresets.delete("content_test_bundle")
                }
            }
            runCatching {
                if (ItemPresets.definition("content_test_item") != null) {
                    ItemPresets.delete("content_test_item")
                }
            }
        }

        describe("ItemPresets") {

            it("should resolve sf lootbox preset amount") {
                val specs = ItemPresets.resolveSpecs("sf_lootbox", 2).getOrThrow()

                specs shouldHaveSize 1
                specs.first().get("amount").asInt shouldBe 2
                specs.first().get("material").asString shouldBe "IRON_INGOT"
            }

            it("should resolve lootbox bundle preset") {
                val specs = ItemPresets.resolveSpecs("lootbox_bundle", 1).getOrThrow()

                specs shouldHaveSize 5
            }

            it("should scale sf count in large bundle") {
                val specs = ItemPresets.resolveSpecs("lootbox_bundle_large", 4).getOrThrow()

                specs shouldHaveSize 5
                specs.count { it.get("material")?.asString == "IRON_INGOT" && it.get("amount")?.asInt == 4 } shouldBe 1
            }

            it("should fail for unknown preset") {
                val result = ItemPresets.resolveSpecs("unknown_preset", 1)

                result.isFailure shouldBe true
                result.exceptionOrNull()?.message shouldContain "Unknown preset"
            }

            it("should normalize preset names") {
                ItemPresets.normalize("SF-Lootbox") shouldBe "sf_lootbox"
            }

            it("should reject unsafe preset names") {
                runCatching {
                    ItemPresets.normalize("../lootbox")
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "Item preset ID"
            }
        }

        describe("structured content API") {
            it("parses canonical preset and bundle schemas") {
                val preset =
                    OpsItemPresetHandlers.parseDefinition(
                        "Content-Test-Item",
                        JsonParser.parseString(
                            """
                            {
                              "type":"preset",
                              "description":"Content reward",
                              "item":{
                                "material":"DIAMOND",
                                "display":"<aqua>Content reward",
                                "lore":["<gray>Managed by ARC"],
                                "customData":{"arc:content_reward":true}
                              }
                            }
                            """.trimIndent(),
                        ).asJsonObject,
                    )

                preset.shouldBeInstanceOf<ItemDefinition>()
                preset.id shouldBe "content_test_item"

                val bundle =
                    OpsItemPresetHandlers.parseDefinition(
                        "Content-Test-Bundle",
                        JsonParser.parseString(
                            """
                            {
                              "type":"bundle",
                              "items":[
                                {"preset":"sf_lootbox","amount":"scaled"},
                                {"preset":"money_bag","amount":1}
                              ]
                            }
                            """.trimIndent(),
                        ).asJsonObject,
                    )
                bundle.shouldBeInstanceOf<BundleDefinition>()
                bundle.items.first().amount shouldBe null
            }

            it("rejects unknown fields and removed aliases") {
                val legacyItem =
                    JsonParser.parseString(
                        """
                        {
                          "type":"preset",
                          "item":{"ia":"iageneric:bag_of_coins"}
                        }
                        """.trimIndent(),
                    ).asJsonObject
                runCatching {
                    OpsItemPresetHandlers.parseDefinition("content_test_item", legacyItem)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "unknown fields"

                val legacyScaledAmount =
                    JsonParser.parseString(
                        """
                        {
                          "type":"bundle",
                          "items":[{"preset":"sf_lootbox","amount":"amount"}]
                        }
                        """.trimIndent(),
                    ).asJsonObject
                runCatching {
                    OpsItemPresetHandlers.parseDefinition("content_test_bundle", legacyScaledAmount)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "'scaled'"
            }

            it("previews, atomically persists, reloads, and safely deletes definitions") {
                val presetBody =
                    JsonParser.parseString(
                        """
                        {
                          "type":"preset",
                          "description":"Content reward",
                          "item":{
                            "material":"DIAMOND",
                            "amount":2,
                            "display":"<aqua>Content reward",
                            "customData":{"arc:content_reward":true}
                          }
                        }
                        """.trimIndent(),
                    ).asJsonObject
                val preview = OpsItemPresetHandlers.preview("content_test_item", presetBody)
                preview["persisted"] shouldBe false
                ItemPresets.definition("content_test_item") shouldBe null

                OpsItemPresetHandlers.upsert("content_test_item", presetBody)["saved"] shouldBe true
                ItemPresets.definition("content_test_item").shouldBeInstanceOf<ItemDefinition>()

                val configPath = ConfigManager.moduleYamlPath(dataPath, "item-presets.yml")
                val persisted = Files.readString(configPath)
                persisted shouldContain "item-presets.yml"
                persisted shouldContain "content_test_item:"
                Files
                    .list(configPath.parent)
                    .use { stream -> stream.filter { it.fileName.toString().endsWith(".tmp") }.toList() }
                    .shouldBeEmpty()

                ConfigManager.ofModule(dataPath, "item-presets.yml").reload()
                val reloaded = ItemPresets.definition("content_test_item")
                reloaded.shouldBeInstanceOf<ItemDefinition>()
                reloaded.item.get("material").asString shouldBe "DIAMOND"

                val bundleBody =
                    JsonParser.parseString(
                        """
                        {
                          "type":"bundle",
                          "description":"Scaled content bundle",
                          "items":[{"preset":"content_test_item","amount":"scaled"}]
                        }
                        """.trimIndent(),
                    ).asJsonObject
                OpsItemPresetHandlers.upsert("content_test_bundle", bundleBody)["saved"] shouldBe true
                ItemPresets.resolveSpecs("content_test_bundle", 3).getOrThrow().single().get("amount").asInt shouldBe 3

                runCatching {
                    OpsItemPresetHandlers.delete("content_test_item")
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "referenced by bundles"

                OpsItemPresetHandlers.delete("content_test_bundle")["deleted"] shouldBe true
                OpsItemPresetHandlers.delete("content_test_item")["deleted"] shouldBe true
                ItemPresets.definition("content_test_bundle") shouldBe null
                ItemPresets.definition("content_test_item") shouldBe null
            }

            it("gives a native preset without MCP-side YAML resolution") {
                val player = server.addPlayer("ContentTester")
                val result =
                    OpsItemPresetHandlers.give(
                        player.name,
                        JsonParser.parseString(
                            """{"preset":"golden_apple","amount":2,"dropOverflow":false}""",
                        ).asJsonObject,
                    )

                result["preset"] shouldBe "golden_apple"
                result["requestedItems"] shouldBe 2
                result["insertedItems"] shouldBe 2
                player.inventory.contents.filterNotNull().sumOf { it.amount } shouldBe 2
            }
        }

    })
