package ru.arc.ops

import com.google.gson.JsonParser
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.KotestTestBase
import ru.arc.treasure.core.AeKind
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import java.nio.file.Files

class OpsTreasurePoolHandlersTest :
    KotestTestBase({
        val ids = listOf("event_rewards", "rare_rewards", "parent_rewards")

        afterTest {
            ids.forEach { id ->
                runCatching {
                    if (Treasures.getPool(id) != null) {
                        Treasures.deleteAndSave(id)
                    }
                }
            }
        }

        describe("treasure pool schema") {
            it("parses every native reward type without Bukkit serialization") {
                val pool =
                    OpsTreasurePoolHandlers.parsePool(
                        "Event_Rewards",
                        JsonParser.parseString(
                            """
                            {
                              "messages":[{"text":"<gold>Reward","destination":"action-bar"}],
                              "treasures":[
                                {"id":"item_1","type":"item","weight":3,
                                 "item":{"material":"DIAMOND","display":"<aqua>Prize"},"amount":{"min":1,"max":2}},
                                {"id":"money_1","type":"money","amount":{"min":100,"max":250}},
                                {"id":"command_1","type":"command","commands":["say %player% won"]},
                                {"id":"sub_1","type":"sub-pool","poolId":"rare_rewards"},
                                {"id":"enchant_1","type":"enchant","amount":2,"exclude":["mending"]},
                                {"id":"potion_1","type":"potion","amount":1},
                                {"id":"ae_1","type":"ae","kind":"item","name":"orb","amount":1,
                                 "args":[{"type":"random-slot"},{"type":"integer","min":10,"max":13}]},
                                {"id":"sf_1","type":"slimefun","itemId":"CARBON","amount":{"min":1,"max":3}}
                              ]
                            }
                            """.trimIndent(),
                        ).asJsonObject,
                    )

                pool.id shouldBe "event_rewards"
                pool.treasures.size shouldBe 8
                (pool.treasures[0] as Treasure.Item).max shouldBe 2
                (pool.treasures[1] as Treasure.Money).max shouldBe 250.0
                (pool.treasures[6] as Treasure.Ae).kind shouldBe AeKind.ITEM
                (pool.treasures[7] as Treasure.Slimefun).itemId shouldBe "CARBON"
            }

            it("rejects legacy aliases, unknown fields, and duplicate reward ids") {
                runCatching {
                    OpsTreasurePoolHandlers.parsePool(
                        "event_rewards",
                        JsonParser.parseString("""{"treasures":[]}""").asJsonObject,
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "delete the pool"

                val legacy =
                    JsonParser.parseString(
                        """
                        {
                          "treasures":[
                            {"type":"slimefun","item-id":"CARBON","amount":1}
                          ]
                        }
                        """.trimIndent(),
                    ).asJsonObject
                runCatching {
                    OpsTreasurePoolHandlers.parsePool("event_rewards", legacy)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "unknown fields"

                val duplicate =
                    JsonParser.parseString(
                        """
                        {
                          "treasures":[
                            {"id":"same","type":"money","amount":10},
                            {"id":"same","type":"money","amount":20}
                          ]
                        }
                        """.trimIndent(),
                    ).asJsonObject
                runCatching {
                    OpsTreasurePoolHandlers.parsePool("event_rewards", duplicate)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "duplicated"
            }

            it("rejects unsafe ids and malformed command rewards") {
                val command =
                    JsonParser.parseString(
                        """{"treasures":[{"type":"command","commands":["/op %player%"]}]}""",
                    ).asJsonObject
                runCatching {
                    OpsTreasurePoolHandlers.parsePool("../event", command)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "Treasure pool ID"

                runCatching {
                    OpsTreasurePoolHandlers.parsePool("event_rewards", command)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "must not start"
            }
        }

        describe("native validation and persistence") {
            it("previews probabilities without persisting") {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "treasures":[
                            {"id":"common","type":"money","weight":3,"amount":100},
                            {"id":"rare","type":"money","weight":1,"amount":1000}
                          ]
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val result = OpsTreasurePoolHandlers.preview("event_rewards", body)
                result["persisted"] shouldBe false
                Treasures.getPool("event_rewards") shouldBe null
                @Suppress("UNCHECKED_CAST")
                val pool = result["pool"] as Map<String, Any?>
                pool["selectableWeight"] shouldBe 4
                @Suppress("UNCHECKED_CAST")
                val rewards = pool["treasures"] as List<Map<String, Any?>>
                rewards[0]["probability"] shouldBe 0.75
                rewards[1]["probability"] shouldBe 0.25
            }

            it("atomically saves and reloads a pool with ItemSpec input") {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "treasures":[
                            {"id":"diamond","type":"item","weight":2,
                             "item":{"material":"DIAMOND","amount":1},"amount":3}
                          ]
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val result = OpsTreasurePoolHandlers.upsert("event_rewards", body)
                result["saved"] shouldBe true
                val path = dataPath.resolve("treasures/event_rewards.yml")
                Files.exists(path) shouldBe true
                Files.readString(path) shouldContain "stack:"
                Files.readString(path).contains("!!binary") shouldBe false
                Files
                    .list(path.parent)
                    .use { stream -> stream.filter { it.fileName.toString().endsWith(".tmp") }.toList() }
                    .shouldBeEmpty()

                Treasures.reload()
                val reloaded = Treasures.getPool("event_rewards")!!
                reloaded.size shouldBe 1
                (reloaded.treasures.single() as Treasure.Item).max shouldBe 3

                val preserveBody =
                    JsonParser.parseString(
                        """
                        {
                          "treasures":[
                            {"id":"diamond","type":"item","weight":5,
                             "preserveItem":true,
                             "itemPreview":{"material":"BARRIER"},
                             "amount":2}
                          ]
                        }
                        """.trimIndent(),
                    ).asJsonObject
                OpsTreasurePoolHandlers.upsert("event_rewards", preserveBody)
                val preserved = Treasures.getPool("event_rewards")!!.treasures.single() as Treasure.Item
                preserved.stack.type.name shouldBe "DIAMOND"
                preserved.weight shouldBe 5
                preserved.min shouldBe 2

                @Suppress("UNCHECKED_CAST")
                val listedPool =
                    (OpsTreasurePoolHandlers.list("event_rewards")["pools"] as List<Map<String, Any?>>).single()
                @Suppress("UNCHECKED_CAST")
                val listedReward = (listedPool["treasures"] as List<Map<String, Any?>>).single()
                listedReward["preserveItem"] shouldBe true
                listedReward.containsKey("itemPreview") shouldBe true
                listedReward.containsKey("item") shouldBe false
            }

            it("rejects missing references, cycles, and deletion of referenced pools") {
                Treasures.replaceAndSave(
                    TreasurePool(
                        id = "rare_rewards",
                        treasures = listOf(Treasure.Money(10.0, 10.0, id = "money")),
                    ),
                )
                val parentBody =
                    JsonParser.parseString(
                        """
                        {
                          "treasures":[
                            {"id":"nested","type":"sub-pool","poolId":"rare_rewards"}
                          ]
                        }
                        """.trimIndent(),
                    ).asJsonObject
                OpsTreasurePoolHandlers.upsert("parent_rewards", parentBody)

                runCatching {
                    OpsTreasurePoolHandlers.delete("rare_rewards")
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "referenced by"

                val missing =
                    JsonParser.parseString(
                        """{"treasures":[{"type":"sub-pool","poolId":"missing_pool"}]}""",
                    ).asJsonObject
                runCatching {
                    OpsTreasurePoolHandlers.preview("event_rewards", missing)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "missing sub-pool"

                val cycle =
                    JsonParser.parseString(
                        """{"treasures":[{"type":"sub-pool","poolId":"parent_rewards"}]}""",
                    ).asJsonObject
                runCatching {
                    OpsTreasurePoolHandlers.preview("rare_rewards", cycle)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "cycle"
            }
        }
    })
