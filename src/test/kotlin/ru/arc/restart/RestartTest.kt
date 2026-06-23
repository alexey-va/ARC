package ru.arc.restart

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.Duration

class RestartFlagParserTest :
    FreeSpec({
        "RestartFlagParser" - {
            "should parse space-separated flags" {
                val flags = RestartFlagParser.parse(arrayOf("-servers", "all", "-delay", "3m"), Duration.ofMinutes(5))
                flags.serverTarget shouldBe RestartServerTarget.All
                flags.delay shouldBe Duration.ofMinutes(3)
                flags.cancel shouldBe false
            }

            "should parse colon flags" {
                val flags = RestartFlagParser.parse(arrayOf("-servers:spawn,survival", "-delay:90"), Duration.ofMinutes(5))
                flags.serverTarget shouldBe RestartServerTarget.Named(setOf("spawn", "survival"))
                flags.delay shouldBe Duration.ofSeconds(90)
            }

            "should default to current server and config delay" {
                val flags = RestartFlagParser.parse(emptyArray(), Duration.ofMinutes(3))
                flags.serverTarget shouldBe RestartServerTarget.Current
                flags.delay shouldBe Duration.ofMinutes(3)
            }

            "should parse cancel with servers" {
                val flags = RestartFlagParser.parse(arrayOf("cancel", "-servers", "all"), Duration.ofMinutes(3))
                flags.cancel shouldBe true
                flags.serverTarget shouldBe RestartServerTarget.All
            }

            "requiresCrossServerPublish should be false for current only" {
                RestartFlagParser.requiresCrossServerPublish(RestartServerTarget.Current, "spawn") shouldBe false
            }

            "requiresCrossServerPublish should be true for all" {
                RestartFlagParser.requiresCrossServerPublish(RestartServerTarget.All, "spawn") shouldBe true
            }

            "requiresCrossServerPublish should be true for other server" {
                RestartFlagParser.requiresCrossServerPublish(
                    RestartServerTarget.Named(setOf("survival")),
                    "spawn",
                ) shouldBe true
            }
        }
    })

class RestartWarningPlannerTest :
    FreeSpec({
        "RestartWarningPlanner" - {
            "should include minute marks and configured seconds for 3 minutes" {
                val plan =
                    RestartWarningPlanner.plan(
                        delay = Duration.ofMinutes(3),
                        configuredSeconds = listOf(30, 10, 5, 4, 3, 2, 1),
                        minuteMarks = true,
                        finalCountdownSeconds = 60,
                    )
                plan.titlePoints shouldContain 120
                plan.titlePoints shouldContain 60
                plan.titlePoints shouldContain 30
                plan.titlePoints shouldContain 10
                plan.titlePoints shouldContain 1
                plan.titlePoints.contains(180) shouldBe false
                plan.chatPoints shouldContain 180
            }

            "should include every second in final countdown for short delay" {
                val plan =
                    RestartWarningPlanner.plan(
                        delay = Duration.ofSeconds(30),
                        configuredSeconds = listOf(30, 10, 5, 4, 3, 2, 1),
                        minuteMarks = true,
                        finalCountdownSeconds = 60,
                    )
                plan.titlePoints shouldContain 29
                plan.titlePoints shouldContain 15
                plan.titlePoints.contains(30) shouldBe false
            }
        }
    })

class RestartDurationFormatTest :
    FreeSpec({
        "RestartDurationFormat" - {
            "should format minutes and seconds" {
                RestartDurationFormat.format(Duration.ofMinutes(3)) shouldBe "3 мин"
                RestartDurationFormat.formatSeconds(45) shouldBe "45 сек"
            }
        }
    })

class RestartServerNamesTest :
    FreeSpec({
        "RestartServerNames" - {
            "should include configured known servers" {
                val names = RestartServerNames.suggestions(TestRestartConfig(knownServers = listOf("spawn", "survival")))
                names shouldContain "spawn"
                names shouldContain "survival"
                names shouldContain "all"
            }

            "should complete partial server name" {
                RestartServerNames.tabComplete(TestRestartConfig(), "sur") shouldContain "survival"
            }

            "should complete after comma prefix" {
                RestartServerNames.tabComplete(TestRestartConfig(), "spawn,sur") shouldContain "spawn,survival"
            }
        }
    })
