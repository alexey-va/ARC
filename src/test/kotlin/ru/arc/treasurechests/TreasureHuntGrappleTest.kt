package ru.arc.treasurechests

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.TestConfig

class TreasureHuntGrappleTest :
    DescribeSpec({
        describe("grapple settings") {
            it("defaults to an enabled spawn-hook profile within the safe limits") {
                val settings = TreasureHuntGrappleSettings.load(TestConfig().section("grapple"))

                settings.enabled shouldBe true
                settings.maxDistance shouldBe 34.0
                settings.cooldownTicks shouldBe 12L
                settings.cableSegments shouldBe 8
            }

            it("keeps an explicit disable and replaces unsafe operator values with defaults") {
                val config = TestConfig(
                    mapOf(
                        "grapple.enabled" to false,
                        "grapple.max-distance" to 37.0,
                        "grapple.cooldown-ticks" to 2L,
                        "grapple.pull-speed" to 4.0,
                        "grapple.cable-segments" to 13,
                    ),
                )
                val settings = TreasureHuntGrappleSettings.load(config.section("grapple"))

                settings.enabled shouldBe false
                settings.maxDistance shouldBe 34.0
                settings.cooldownTicks shouldBe 12L
                settings.pullSpeed shouldBe 1.0
                settings.cableSegments shouldBe 8
            }

            it("accepts the documented upper boundaries") {
                val config = TestConfig(
                    mapOf(
                        "grapple.max-distance" to 36.0,
                        "grapple.cooldown-ticks" to 40L,
                        "grapple.pull-speed" to 1.25,
                        "grapple.cable-segments" to 12,
                    ),
                )
                val settings = TreasureHuntGrappleSettings.load(config.section("grapple"))

                settings.maxDistance shouldBe 36.0
                settings.cooldownTicks shouldBe 40L
                settings.pullSpeed shouldBe 1.25
                settings.cableSegments shouldBe 12
            }

            it("loads a permanent mine cuboid and rejects incomplete or reversed areas") {
                val config = TestConfig(mapOf(
                    "grapple.areas.mine.world" to "rc_atelier_compact_mine",
                    "grapple.areas.mine.min-x" to 0, "grapple.areas.mine.min-y" to 64,
                    "grapple.areas.mine.min-z" to 0, "grapple.areas.mine.max-x" to 87,
                    "grapple.areas.mine.max-y" to 139, "grapple.areas.mine.max-z" to 87,
                    "grapple.areas.mine.permission" to "arcfarms.mine",
                    "grapple.areas.missing.world" to "other",
                    "grapple.areas.reversed.world" to "other",
                    "grapple.areas.reversed.min-x" to 10, "grapple.areas.reversed.max-x" to 0,
                    "grapple.areas.reversed.min-y" to 0, "grapple.areas.reversed.max-y" to 10,
                    "grapple.areas.reversed.min-z" to 0, "grapple.areas.reversed.max-z" to 10,
                ))

                TreasureHuntGrappleSettings.load(config.section("grapple")).areas shouldBe listOf(
                    TreasureHuntGrappleArea("rc_atelier_compact_mine", 0, 64, 0, 87, 139, 87, "arcfarms.mine"),
                )
            }
        }
    })
