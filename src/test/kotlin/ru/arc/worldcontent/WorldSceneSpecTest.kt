package ru.arc.worldcontent

import com.google.gson.JsonParser
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class WorldSceneSpecTest :
    DescribeSpec({
        describe("managed scene schema") {
            it("parses blocks and ItemsAdder furniture with stable logical ids") {
                val spec =
                    WorldSceneSpecParser.parse(
                        "spawn_market",
                        JsonParser.parseString(
                            """
                            {
                              "id":"spawn_market",
                              "objects":[
                                {
                                  "id":"forge_floor",
                                  "kind":"minecraft_block",
                                  "world":"spawn",
                                  "x":10,"y":65,"z":10,
                                  "blockData":"minecraft:polished_andesite"
                                },
                                {
                                  "id":"forge_bench",
                                  "kind":"itemsadder_furniture",
                                  "world":"spawn",
                                  "x":11,"y":65,"z":10,
                                  "namespacedId":"iasurvival:forge_bench",
                                  "placement":"block"
                                }
                              ]
                            }
                            """.trimIndent(),
                        ).asJsonObject,
                    )

                spec.id shouldBe "spawn_market"
                spec.objects.map { it.id }.shouldContainExactly("forge_floor", "forge_bench")
                spec.objects[1].kind shouldBe SceneObjectKind.ITEMSADDER_FURNITURE
                spec.objects[1].placement shouldBe FurniturePlacement.BLOCK
            }

            it("rejects duplicate ids and unsafe block furniture rotation") {
                val duplicate =
                    JsonParser.parseString(
                        """
                        {"objects":[
                          {"id":"same","kind":"minecraft_block","world":"world","x":1,"y":2,"z":3,"blockData":"stone"},
                          {"id":"same","kind":"minecraft_block","world":"world","x":2,"y":2,"z":3,"blockData":"stone"}
                        ]}
                        """.trimIndent(),
                    ).asJsonObject
                runCatching { WorldSceneSpecParser.parse("spawn", duplicate) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "duplicate object id"

                val rotatedSolid =
                    JsonParser.parseString(
                        """
                        {"objects":[
                          {
                            "id":"bench","kind":"itemsadder_furniture","world":"world",
                            "x":1,"y":2,"z":3,"namespacedId":"ia:bench","placement":"block","yaw":90
                          }
                        ]}
                        """.trimIndent(),
                    ).asJsonObject
                runCatching { WorldSceneSpecParser.parse("spawn", rotatedSolid) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "yaw=0"
            }
        }

        describe("review digest") {
            it("changes when either desired content or live preimage changes") {
                val spec =
                    WorldSceneSpec(
                        "spawn",
                        listOf(
                            SceneObjectSpec.block("floor", "world", 1, 2, 3, "minecraft:stone"),
                        ),
                    )
                val first = WorldSceneReview.digest(spec, revision = 2, liveFingerprint = "stone")
                (WorldSceneReview.digest(spec, revision = 2, liveFingerprint = "dirt") == first) shouldBe false
                (WorldSceneReview.digest(spec.copy(objects = listOf(SceneObjectSpec.block("floor", "world", 1, 2, 3, "minecraft:dirt"))), 2, "stone") == first) shouldBe false
            }
        }
    })
