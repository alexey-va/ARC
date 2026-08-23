package ru.arc.worldcontent

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.UUID

class FurnitureCleanupSafetyTest :
    DescribeSpec({
        describe("cleanup plan") {
            it("deduplicates one ItemsAdder root discovered through multiple entities") {
                val root = UUID.fromString("00000000-0000-0000-0000-000000000216")
                val plan =
                    FurnitureCleanupPlan.create(
                        center = CleanupCenter("spawn", 10, 65, 10),
                        radius = 8,
                        candidates =
                            listOf(
                                CleanupTarget.Furniture(root, FurnitureFamily.SIMPLE, "iasurvival:bench"),
                                CleanupTarget.Furniture(root, FurnitureFamily.SIMPLE, "iasurvival:bench"),
                                CleanupTarget.Barrier(BlockPosition("spawn", 11, 65, 10)),
                            ),
                    )

                plan.targets.shouldContainExactly(
                    CleanupTarget.Furniture(root, FurnitureFamily.SIMPLE, "iasurvival:bench"),
                    CleanupTarget.Barrier(BlockPosition("spawn", 11, 65, 10)),
                )
                plan.furnitureCount shouldBe 1
                plan.barrierCount shouldBe 1
            }

            it("does not turn an unmarked display into a cleanup target") {
                ItemsAdderMarkerPolicy.classify(
                    EntityProbe(
                        uuid = UUID.fromString("00000000-0000-0000-0000-000000000999"),
                        type = ProbeEntityType.ITEM_DISPLAY,
                        simpleId = null,
                        complexId = null,
                    ),
                ) shouldBe null
            }
        }

        describe("two step confirmation") {
            var now = Instant.parse("2026-08-18T12:00:00Z")
            val registry =
                CleanupConfirmationRegistry(
                    clock = { now },
                    tokenFactory = { "AB12CD" },
                )
            val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val center = CleanupCenter("spawn", 10, 65, 10)

            it("accepts only the exact fresh plan once") {
                val confirmation = registry.issue(owner, center, 8, "digest-v1")
                confirmation.token shouldBe "AB12CD"

                registry.consume(owner, center, 8, "digest-v1", "AB12CD")
                    .shouldBeInstanceOf<CleanupConfirmationResult.Accepted>()
                registry.consume(owner, center, 8, "digest-v1", "AB12CD")
                    .shouldBeInstanceOf<CleanupConfirmationResult.Rejected>()
                    .reason shouldBe "confirmation_not_found"
            }

            it("refuses a changed scan and an expired token") {
                registry.issue(owner, center, 8, "digest-v1")
                registry.consume(owner, center, 8, "digest-v2", "AB12CD")
                    .shouldBeInstanceOf<CleanupConfirmationResult.Rejected>()
                    .reason shouldBe "world_state_changed"

                registry.issue(owner, center, 8, "digest-v1")
                now = now.plusSeconds(31)
                registry.consume(owner, center, 8, "digest-v1", "AB12CD")
                    .shouldBeInstanceOf<CleanupConfirmationResult.Rejected>()
                    .reason shouldBe "confirmation_expired"
            }
        }

        describe("command input") {
            it("accepts preview and explicit confirmation forms") {
                FurnitureCleanupInput.parse(arrayOf("cleanup", "8")) shouldBe
                    FurnitureCleanupInput.Preview(8)
                FurnitureCleanupInput.parse(arrayOf("cleanup", "8", "confirm", "AB12CD")) shouldBe
                    FurnitureCleanupInput.Confirm(8, "AB12CD")
            }

            it("rejects wide or ambiguous cleanup requests") {
                runCatching { FurnitureCleanupInput.parse(arrayOf("cleanup", "25")) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                runCatching { FurnitureCleanupInput.parse(arrayOf("cleanup", "8", "AB12CD")) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
            }
        }
    })
