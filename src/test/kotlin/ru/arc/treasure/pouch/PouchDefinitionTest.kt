package ru.arc.treasure.pouch

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ru.arc.KotestTestBase

class PouchDefinitionTest :
    KotestTestBase({
        describe("PouchDefinitionParser") {
            it("parses multiple pools, ranges, percentages and presentation") {
                val definition =
                    PouchDefinitionParser.parse(
                        "SF-Master",
                        mapOf(
                            "description" to "Test",
                            "item" to mapOf("material" to "NETHER_STAR", "display" to "<gold>Master"),
                            "rewards" to
                                listOf(
                                    mapOf("pool" to "sf_components", "rolls" to "3-5"),
                                    mapOf("pool" to "sf_jackpot", "rolls" to 1, "chance" to "5%"),
                                ),
                            "open" to mapOf("sound" to "entity.player.levelup", "pitch" to 1.2),
                        ),
                    )

                definition.id shouldBe "sf_master"
                definition.rewards shouldHaveSize 2
                definition.rewards.first().rolls shouldBe PouchRolls(3, 5)
                definition.rewards.last().chance.shouldBeExactly(0.05)
                definition.open.sound shouldBe "entity.player.levelup"
                definition.itemSpec(3).get("amount").asInt shouldBe 3
                definition.itemSpec().getAsJsonObject("customData").get("arc:pouch_id").asString shouldBe "sf_master"
            }

            it("requires a guaranteed reward source") {
                val error = shouldThrow<IllegalArgumentException> {
                    PouchDefinitionParser.parse(
                        "risky",
                        mapOf(
                            "item" to mapOf("material" to "CHEST"),
                            "rewards" to listOf(mapOf("pool" to "rare", "chance" to "10%")),
                        ),
                    )
                }

                error.message shouldContain "guaranteed"
            }

            it("rejects unknown fields and excessive total rolls") {
                shouldThrow<IllegalArgumentException> {
                    PouchDefinitionParser.parse(
                        "broken",
                        mapOf(
                            "item" to mapOf("material" to "CHEST"),
                            "rewards" to listOf(mapOf("pool" to "a", "rolls" to 1, "typo" to true)),
                        ),
                    )
                }.message shouldContain "unknown fields"

                shouldThrow<IllegalArgumentException> {
                    PouchDefinitionParser.parse(
                        "too_big",
                        mapOf(
                            "item" to mapOf("material" to "CHEST"),
                            "rewards" to listOf(
                                mapOf("pool" to "a", "rolls" to 40),
                                mapOf("pool" to "b", "rolls" to 40),
                            ),
                        ),
                    )
                }.message shouldContain "64 total rolls"
            }

            it("protects the reserved pouch identity tag") {
                shouldThrow<IllegalArgumentException> {
                    PouchDefinitionParser.parse(
                        "forged",
                        mapOf(
                            "item" to mapOf(
                                "material" to "CHEST",
                                "customData" to mapOf("arc:pouch_id" to "royal"),
                            ),
                            "rewards" to listOf(mapOf("pool" to "common")),
                        ),
                    )
                }.message shouldContain "reserved"
            }
        }

        describe("bundled pouch catalog") {
            it("loads the complete catalog and builds a pouch ItemSpec") {
                Pouches.all().size shouldBe 11
                val stack = Pouches.createStack("Slimefun-Starter", 3).getOrThrow()

                stack.amount shouldBe 3
                stack.type.name shouldBe "IRON_INGOT"
            }
        }
    })
