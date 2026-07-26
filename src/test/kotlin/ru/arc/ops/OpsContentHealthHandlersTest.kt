package ru.arc.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class OpsContentHealthHandlersTest :
    FreeSpec({
        "content health summary" - {
            "reports a complete healthy audit while listing intentionally disabled content" {
                val result =
                    OpsContentHealthHandlers.collect(
                        TestOpsHttpConfig(),
                        readers(
                            kits =
                                mapOf(
                                    "count" to 2,
                                    "kits" to
                                        listOf(
                                            mapOf("name" to "starter", "enabled" to true),
                                            mapOf("name" to "retired", "enabled" to false),
                                        ),
                                ),
                            schedules =
                                mapOf(
                                    "count" to 1,
                                    "entries" to listOf(mapOf("id" to "weekend", "enabled" to false)),
                                ),
                        ),
                    )

                result["complete"] shouldBe true
                result["healthy"] shouldBe true
                result["issueCount"] shouldBe 0
                @Suppress("UNCHECKED_CAST")
                val components = result["components"] as Map<String, Map<String, Any?>>
                components["cmiKits"]?.get("disabled") shouldBe listOf("retired")
                components["scheduledCommands"]?.get("disabled") shouldBe listOf("weekend")
            }

            "reports unhealthy location and treasure pools with bounded diagnostics" {
                val result =
                    OpsContentHealthHandlers.collect(
                        TestOpsHttpConfig(),
                        readers(
                            locations =
                                mapOf(
                                    "count" to 1,
                                    "pools" to
                                        listOf(
                                            mapOf(
                                                "id" to "spawn",
                                                "healthyForCurrentServer" to false,
                                                "localCount" to 1,
                                                "localUsable" to 0,
                                            ),
                                        ),
                                ),
                            treasures =
                                mapOf(
                                    "count" to 1,
                                    "pools" to
                                        listOf(
                                            mapOf(
                                                "id" to "event",
                                                "healthy" to false,
                                                "totalWeight" to 0,
                                                "cyclic" to false,
                                                "missingSubPools" to listOf("rare"),
                                                "unusableSubPools" to emptyList<String>(),
                                            ),
                                        ),
                                ),
                        ),
                    )

                result["complete"] shouldBe true
                result["healthy"] shouldBe false
                result["issueCount"] shouldBe 2
                @Suppress("UNCHECKED_CAST")
                val unhealthy = result["unhealthyComponents"] as List<String>
                unhealthy.shouldContainExactly("locationPools", "treasurePools")
            }

            "isolates failed and disabled component reads" {
                val config = TestOpsHttpConfig(treasurePoolsReadEnabled = false)
                val failingReaders =
                    readers().copy(
                        cmiKits = { error("CMI is not ready") },
                    )

                val result = OpsContentHealthHandlers.collect(config, failingReaders)

                result["complete"] shouldBe false
                result["healthy"] shouldBe false
                result["issueCount"] shouldBe 2
                @Suppress("UNCHECKED_CAST")
                val unavailable = result["unavailableComponents"] as List<String>
                unavailable.shouldContainExactly("cmiKits", "treasurePools")
                @Suppress("UNCHECKED_CAST")
                val components = result["components"] as Map<String, Map<String, Any?>>
                components["cmiKits"]?.get("reason") shouldBe "read-failed"
                components["treasurePools"]?.get("reason") shouldBe "read-disabled"
            }
        }
    })

private fun readers(
    kits: Map<String, Any?> =
        mapOf(
            "count" to 1,
            "kits" to listOf(mapOf("name" to "starter", "enabled" to true)),
        ),
    schedules: Map<String, Any?> =
        mapOf(
            "count" to 1,
            "entries" to listOf(mapOf("id" to "weekend", "enabled" to true)),
        ),
    locations: Map<String, Any?> =
        mapOf(
            "count" to 1,
            "pools" to listOf(mapOf("id" to "spawn", "healthyForCurrentServer" to true)),
        ),
    treasures: Map<String, Any?> =
        mapOf(
            "count" to 1,
            "pools" to listOf(mapOf("id" to "generic", "healthy" to true)),
        ),
): ContentCatalogReaders =
    ContentCatalogReaders(
        itemPresets = { mapOf("count" to 2, "presets" to emptyList<Any>()) },
        cmiKits = { kits },
        scheduledCommands = { schedules },
        locationPools = { locations },
        treasurePools = { treasures },
    )
