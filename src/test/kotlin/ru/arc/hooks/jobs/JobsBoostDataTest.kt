package ru.arc.hooks.jobs

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class JobsBoostDataTest :
    DescribeSpec({

        describe("JobsBoostData") {

            describe("expiration") {
                it("should be expired when expires is in the past") {
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            expires = System.currentTimeMillis() - 1000,
                        )

                    boost.isExpired() shouldBe true
                }

                it("should not be expired when expires is in the future") {
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            expires = System.currentTimeMillis() + 60000,
                        )

                    boost.isExpired() shouldBe false
                }

                it("should calculate remaining time correctly") {
                    val futureTime = System.currentTimeMillis() + 30000
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            expires = futureTime,
                        )

                    val remaining = boost.expiresInMillis()
                    remaining shouldBe (futureTime - System.currentTimeMillis()) // approximately
                }
            }

            describe("appliesTo type") {
                it("should apply to matching type") {
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.MONEY,
                        )

                    boost.appliesTo(ru.arc.jobs.BoostType.MONEY) shouldBe true
                }

                it("should not apply to different type") {
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.MONEY,
                        )

                    boost.appliesTo(ru.arc.jobs.BoostType.EXP) shouldBe false
                }

                it("should apply to any type when type is ALL") {
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.ALL,
                        )

                    boost.appliesTo(ru.arc.jobs.BoostType.MONEY) shouldBe true
                    boost.appliesTo(ru.arc.jobs.BoostType.EXP) shouldBe true
                    boost.appliesTo(ru.arc.jobs.BoostType.POINTS) shouldBe true
                }
            }

            describe("appliesToJob") {
                it("should apply to matching job name") {
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Miner"),
                        )

                    boost.appliesToJob("Miner") shouldBe true
                }

                it("should apply case-insensitively") {
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("miner"),
                        )

                    boost.appliesToJob("Miner") shouldBe true
                    boost.appliesToJob("MINER") shouldBe true
                }

                it("should not apply to different job") {
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Farmer"),
                        )

                    boost.appliesToJob("Miner") shouldBe false
                }

                it("should apply to all jobs when jobTarget is All") {
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            jobTarget = ru.arc.jobs.JobTarget.All,
                        )

                    boost.appliesToJob("Miner") shouldBe true
                    boost.appliesToJob("Farmer") shouldBe true
                    boost.appliesToJob("anything") shouldBe true
                }
            }

            describe("JobTarget") {
                it("should parse 'all' as All") {
                    ru.arc.jobs.JobTarget
                        .parse("all") shouldBe ru.arc.jobs.JobTarget.All
                    ru.arc.jobs.JobTarget
                        .parse("ALL") shouldBe ru.arc.jobs.JobTarget.All
                    ru.arc.jobs.JobTarget
                        .parse("All") shouldBe ru.arc.jobs.JobTarget.All
                }

                it("should parse null/blank as All") {
                    ru.arc.jobs.JobTarget
                        .parse(null) shouldBe ru.arc.jobs.JobTarget.All
                    ru.arc.jobs.JobTarget
                        .parse("") shouldBe ru.arc.jobs.JobTarget.All
                    ru.arc.jobs.JobTarget
                        .parse("  ") shouldBe ru.arc.jobs.JobTarget.All
                }

                it("should parse specific job names") {
                    val target =
                        ru.arc.jobs.JobTarget
                            .parse("Miner")
                    target shouldBe
                        ru.arc.jobs.JobTarget
                            .Specific("Miner")
                }

                it("should provide correct display names") {
                    ru.arc.jobs.JobTarget.All
                        .displayName() shouldBe "all"
                    ru.arc.jobs.JobTarget
                        .Specific("Miner")
                        .displayName() shouldBe "Miner"
                }
            }

            describe("data class properties") {
                it("should be immutable") {
                    val boost =
                        _root_ide_package_.ru.arc.jobs.JobsBoostData(
                            boost = 0.5,
                            type = ru.arc.jobs.BoostType.MONEY,
                            jobTarget =
                                ru.arc.jobs.JobTarget
                                    .Specific("Miner"),
                            expires = 12345L,
                            boostUuid = UUID.randomUUID(),
                            id = "test-id",
                        )

                    // All properties are val, so they can only be read
                    boost.boost shouldBe 0.5
                    boost.type shouldBe ru.arc.jobs.BoostType.MONEY
                    boost.jobTarget shouldBe
                        ru.arc.jobs.JobTarget
                            .Specific("Miner")
                    boost.expires shouldBe 12345L
                    boost.id shouldBe "test-id"
                }

                it("should have sensible defaults") {
                    val boost =
                        _root_ide_package_.ru.arc.jobs
                            .JobsBoostData()

                    boost.boost shouldBe 0.0
                    boost.type shouldBe ru.arc.jobs.BoostType.ALL
                    boost.jobTarget shouldBe ru.arc.jobs.JobTarget.All
                    boost.expires shouldBe 0L
                    boost.id shouldBe ""
                }
            }
        }
    })
