package ru.arc.ops

import com.google.gson.JsonParser
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.KotestTestBase
import ru.arc.common.locationpools.LocationPoolManager
import java.nio.file.Files

class OpsLocationPoolHandlersTest :
    KotestTestBase({
        beforeSpec {
            server.addSimpleWorld("test-world")
        }

        afterTest {
            LocationPoolManager.getPool("event_spawn")?.let {
                LocationPoolManager.delete("event_spawn")
            }
        }

        describe("location pool schema") {
            it("parses a stable weighted coordinate list") {
                val definition =
                    OpsLocationPoolHandlers.parseDefinition(
                        "Event_Spawn",
                        JsonParser.parseString(
                            """
                            {
                              "locations": [
                                {
                                  "server":"test-server",
                                  "world":"test-world",
                                  "x":10.5,
                                  "y":64.0,
                                  "z":-20.5,
                                  "yaw":90,
                                  "pitch":0,
                                  "weight":2.5
                                }
                              ]
                            }
                            """.trimIndent(),
                        ).asJsonObject,
                    )

                definition.id shouldBe "event_spawn"
                definition.locations.single().location.world shouldBe "test-world"
                definition.locations.single().weight shouldBe 2.5
            }

            it("rejects empty pools and duplicate coordinates") {
                val empty =
                    JsonParser.parseString("""{"locations":[]}""").asJsonObject
                runCatching {
                    OpsLocationPoolHandlers.parseDefinition("event_spawn", empty)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "delete the pool"

                val duplicates =
                    JsonParser.parseString(
                        """
                        {
                          "locations": [
                            {"server":"test-server","world":"test-world","x":1,"y":2,"z":3},
                            {"server":"test-server","world":"test-world","x":1,"y":2,"z":3,"weight":2}
                          ]
                        }
                        """.trimIndent(),
                    ).asJsonObject
                runCatching {
                    OpsLocationPoolHandlers.parseDefinition("event_spawn", duplicates)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "duplicates"
            }

            it("rejects unknown fields and unsafe ids") {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "locations": [
                            {"server":"test-server","world":"test-world","x":1,"y":2,"z":3,"blob":"legacy"}
                          ]
                        }
                        """.trimIndent(),
                    ).asJsonObject

                runCatching {
                    OpsLocationPoolHandlers.parseDefinition("event_spawn", body)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "unknown fields"

                runCatching {
                    OpsLocationPoolHandlers.parseDefinition("../event", body)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "Location pool ID"
            }
        }

        describe("native persistence") {
            it("previews a locally usable pool and rejects a remote-only pool") {
                val localBody =
                    JsonParser.parseString(
                        """
                        {
                          "locations": [
                            {"server":"test-server","world":"test-world","x":1,"y":64,"z":3}
                          ]
                        }
                        """.trimIndent(),
                    ).asJsonObject
                val preview = OpsLocationPoolHandlers.preview("event_spawn", localBody)
                @Suppress("UNCHECKED_CAST")
                val pool = preview["pool"] as Map<String, Any?>
                pool["localUsable"] shouldBe 1

                val remoteBody =
                    JsonParser.parseString(
                        """
                        {
                          "locations": [
                            {"server":"survival","world":"world","x":1,"y":64,"z":3}
                          ]
                        }
                        """.trimIndent(),
                    ).asJsonObject
                runCatching {
                    OpsLocationPoolHandlers.preview("event_spawn", remoteBody)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "no locations for current server"
            }

            it("atomically saves through native config and deletes the durable file") {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "locations": [
                            {
                              "server":"test-server",
                              "world":"test-world",
                              "x":10.5,
                              "y":64,
                              "z":20.5,
                              "weight":3
                            }
                          ]
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val result = OpsLocationPoolHandlers.upsert("event_spawn", body)
                result["saved"] shouldBe true
                val path = dataPath.resolve("location_pools/event_spawn.json")
                Files.exists(path) shouldBe true
                LocationPoolManager.getPool("event_spawn")?.isDirty shouldBe false
                Files
                    .list(path.parent)
                    .use { stream -> stream.filter { it.fileName.toString().endsWith(".tmp") }.toList() }
                    .shouldBeEmpty()

                plugin.locationPoolConfig!!.loadConfig()
                val reloaded = LocationPoolManager.getPool("event_spawn")!!
                reloaded.size shouldBe 1
                reloaded.getWeightedLocations().single().weight shouldBe 3.0

                OpsLocationPoolHandlers.delete("event_spawn")["deleted"] shouldBe true
                Files.exists(path) shouldBe false
            }
        }
    })
