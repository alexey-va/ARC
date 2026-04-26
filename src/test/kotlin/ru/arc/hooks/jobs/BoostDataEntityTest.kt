package ru.arc.hooks.jobs

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID

class BoostDataEntityTest :
    DescribeSpec({

        fun futureTime(offsetMs: Long = 60000) = System.currentTimeMillis() + offsetMs

        fun pastTime(offsetMs: Long = 1000) = System.currentTimeMillis() - offsetMs

        describe("BoostDataEntity") {

            describe("id") {
                it("should return player UUID as string") {
                    val uuid = UUID.randomUUID()
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = uuid)

                    entity.id() shouldBe uuid.toString()
                }
            }

            describe("addBoost") {
                it("should add a new boost") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            id = "test-boost",
                            expires = futureTime(),
                        )

                    val result = entity.addBoost(boost)

                    result shouldBe true
                    entity.boostCount() shouldBe 1
                    entity.findById("test-boost") shouldNotBe null
                }

                it("should reject duplicate boost ID") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    val boost1 =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            id = "test-boost",
                            expires = futureTime(),
                        )
                    val boost2 =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 1.0,
                            id = "test-boost",
                            expires = futureTime(),
                        )

                    entity.addBoost(boost1) shouldBe true
                    entity.addBoost(boost2) shouldBe false
                    entity.boostCount() shouldBe 1
                }

                it("should set isDirty when boost is added") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.isDirty = false

                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            id = "test",
                            expires = futureTime(),
                        ),
                    )

                    entity.isDirty shouldBe true
                }
            }

            describe("removeBoost") {
                it("should remove boost by ID") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            id = "test",
                            expires = futureTime(),
                        ),
                    )
                    entity.isDirty = false

                    val result = entity.removeBoost("test")

                    result shouldBe true
                    entity.boostCount() shouldBe 0
                    entity.isDirty shouldBe true
                }

                it("should return false when boost not found") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())

                    val result = entity.removeBoost("nonexistent")

                    result shouldBe false
                }
            }

            describe("removeExpired") {
                it("should remove expired boosts") {
                    // Use constructor to add boosts directly without triggering removeExpired
                    val expiredBoost =
                        _root_ide_package_.ru.arc.jobs
                            .JobsBoostData(boost = 0.5, id = "expired", expires = pastTime())
                    val activeBoost =
                        _root_ide_package_.ru.arc.jobs
                            .JobsBoostData(boost = 0.5, id = "active", expires = futureTime())

                    val entity =
                        _root_ide_package_.ru.arc.jobs.BoostDataEntity(
                            player = UUID.randomUUID(),
                            boosts = setOf(expiredBoost, activeBoost),
                        )
                    entity.isDirty = false

                    val result = entity.removeExpired()

                    result shouldBe true
                    entity.boostCount() shouldBe 1
                    entity.findById("expired") shouldBe null
                    entity.findById("active") shouldNotBe null
                    entity.isDirty shouldBe true
                }

                it("should return false when no boosts expired") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            id = "active",
                            expires = futureTime(),
                        ),
                    )
                    entity.isDirty = false

                    val result = entity.removeExpired()

                    result shouldBe false
                    entity.isDirty shouldBe false
                }

                it("should be called automatically during addBoost") {
                    val expiredBoost =
                        _root_ide_package_.ru.arc.jobs
                            .JobsBoostData(boost = 0.5, id = "expired", expires = pastTime())
                    val entity =
                        _root_ide_package_.ru.arc.jobs.BoostDataEntity(
                            player = UUID.randomUUID(),
                            boosts = setOf(expiredBoost),
                        )

                    // Adding a new boost should trigger removeExpired
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.3,
                            id = "new",
                            expires = futureTime(),
                        ),
                    )

                    entity.findById("expired") shouldBe null
                    entity.findById("new") shouldNotBe null
                    entity.boostCount() shouldBe 1
                }
            }

            describe("getBoost") {

                it("should return 1.0 when no boosts exist") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())

                    entity.getBoost("Miner", ru.arc.jobs.BoostType.MONEY) shouldBe 1.0
                }

                it("should apply single boost correctly") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.MONEY,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Miner"),
                            id = "test",
                            expires = futureTime(),
                        ),
                    )

                    entity.getBoost("Miner", ru.arc.jobs.BoostType.MONEY) shouldBe 1.5
                }

                it("should sum multiple boosts") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.MONEY,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Miner"),
                            id = "test1",
                            expires = futureTime(),
                        ),
                    )
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.3,
                            type = ru.arc.jobs.BoostType.MONEY,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Miner"),
                            id = "test2",
                            expires = futureTime(),
                        ),
                    )

                    entity.getBoost("Miner", ru.arc.jobs.BoostType.MONEY) shouldBe 1.8
                }

                it("should not apply boost for different job") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.MONEY,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Farmer"),
                            id = "test",
                            expires = futureTime(),
                        ),
                    )

                    entity.getBoost("Miner", ru.arc.jobs.BoostType.MONEY) shouldBe 1.0
                }

                it("should not apply boost for different type") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.EXP,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Miner"),
                            id = "test",
                            expires = futureTime(),
                        ),
                    )

                    entity.getBoost("Miner", ru.arc.jobs.BoostType.MONEY) shouldBe 1.0
                }

                it("should apply ALL type to any query type") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.ALL,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Miner"),
                            id = "test",
                            expires = futureTime(),
                        ),
                    )

                    entity.getBoost("Miner", ru.arc.jobs.BoostType.MONEY) shouldBe 1.5
                    entity.getBoost("Miner", ru.arc.jobs.BoostType.EXP) shouldBe 1.5
                    entity.getBoost("Miner", ru.arc.jobs.BoostType.POINTS) shouldBe 1.5
                }

                it("should apply JobTarget.All to any job") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.MONEY,
                            jobTarget = ru.arc.jobs.JobTarget.All,
                            id = "test",
                            expires = futureTime(),
                        ),
                    )

                    entity.getBoost("Miner", ru.arc.jobs.BoostType.MONEY) shouldBe 1.5
                    entity.getBoost("Farmer", ru.arc.jobs.BoostType.MONEY) shouldBe 1.5
                }

                it("should ignore expired boosts") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.MONEY,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Miner"),
                            id = "expired",
                            expires = pastTime(),
                        ),
                    )

                    entity.getBoost("Miner", ru.arc.jobs.BoostType.MONEY) shouldBe 1.0
                }

                it("should cache boost calculations") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.MONEY,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Miner"),
                            id = "test",
                            expires = futureTime(),
                        ),
                    )

                    // First call populates cache
                    val first = entity.getBoost("Miner", ru.arc.jobs.BoostType.MONEY)
                    // Second call should use cache (same result)
                    val second = entity.getBoost("Miner", ru.arc.jobs.BoostType.MONEY)

                    first shouldBe second
                    first shouldBe 1.5
                }

                it("should match job names case-insensitively") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.MONEY,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("miner"),
                            // lowercase
                            id = "test",
                            expires = futureTime(),
                        ),
                    )

                    entity.getBoost("Miner", ru.arc.jobs.BoostType.MONEY) shouldBe 1.5 // PascalCase
                    entity.getBoost("MINER", ru.arc.jobs.BoostType.MONEY) shouldBe 1.5 // UPPERCASE
                }
            }

            describe("boostsForJob") {

                it("should return empty list when no boosts") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())

                    entity.boostsForJob("Miner").shouldBeEmpty()
                }

                it("should return boosts for specific job") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Miner"),
                            id = "1",
                            expires = futureTime(),
                        ),
                    )
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.3,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Farmer"),
                            id = "2",
                            expires = futureTime(),
                        ),
                    )

                    val boosts = entity.boostsForJob("Miner")

                    boosts shouldHaveSize 1
                    boosts[0].id shouldBe "1"
                }

                it("should include global boosts (JobTarget.All)") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            jobTarget = ru.arc.jobs.JobTarget.All,
                            id = "global",
                            expires = futureTime(),
                        ),
                    )
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.3,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Miner"),
                            id = "specific",
                            expires = futureTime(),
                        ),
                    )

                    val boosts = entity.boostsForJob("Miner")

                    boosts shouldHaveSize 2
                }
            }

            describe("clearBoosts") {
                it("should remove all boosts") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            id = "1",
                            expires = futureTime(),
                        ),
                    )
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.3,
                            id = "2",
                            expires = futureTime(),
                        ),
                    )
                    entity.isDirty = false

                    entity.clearBoosts()

                    entity.boostCount() shouldBe 0
                    entity.isDirty shouldBe true
                }
            }

            describe("merge") {
                it("should replace all boosts with other entity's boosts") {
                    val entity1 =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity1.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            id = "old",
                            expires = futureTime(),
                        ),
                    )

                    val entity2 =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity2.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 1.0,
                            id = "new1",
                            expires = futureTime(),
                        ),
                    )
                    entity2.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.8,
                            id = "new2",
                            expires = futureTime(),
                        ),
                    )

                    entity1.merge(entity2)

                    entity1.boostCount() shouldBe 2
                    entity1.findById("old") shouldBe null
                    entity1.findById("new1") shouldNotBe null
                    entity1.findById("new2") shouldNotBe null
                }
            }

            describe("hasBoostWithId") {
                it("should return true when boost exists") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    entity.addBoost(
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            id = "test",
                            expires = futureTime(),
                        ),
                    )

                    entity.hasBoostWithId("test") shouldBe true
                }

                it("should return false when boost doesn't exist") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())

                    entity.hasBoostWithId("nonexistent") shouldBe false
                }
            }

            describe("thread safety") {
                it("should handle concurrent addBoost calls") {
                    val entity =
                        _root_ide_package_.ru.arc.jobs
                            .BoostDataEntity(player = UUID.randomUUID())
                    val threads =
                        (1..100).map { i ->
                            Thread {
                                entity.addBoost(
                                    _root_ide_package_.ru.arc.jobs.JobsBoostData(
                                        boost = 0.1,
                                        id = "boost-$i",
                                        expires = futureTime(),
                                    ),
                                )
                            }
                        }

                    threads.forEach { it.start() }
                    threads.forEach { it.join() }

                    entity.boostCount() shouldBe 100
                }
            }
        }
    })
