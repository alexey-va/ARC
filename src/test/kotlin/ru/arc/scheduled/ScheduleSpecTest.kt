package ru.arc.scheduled

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleSpecTest :
    FreeSpec({

        val zone = ZoneId.of("Europe/Moscow")
        val grace = Duration.ofSeconds(60)

        "DailySchedule" - {
            "should match configured time on allowed day" {
                val schedule =
                    DailySchedule(
                        times = listOf(LocalTime.of(8, 0)),
                        daysOfWeek = setOf(DayOfWeek.MONDAY),
                    )
                val mondayMorning = ZonedDateTime.of(2026, 6, 22, 8, 0, 10, 0, zone)

                schedule.matchSlot(mondayMorning, grace).shouldNotBeNull()
            }

            "should not match on wrong weekday" {
                val schedule =
                    DailySchedule(
                        times = listOf(LocalTime.of(8, 0)),
                        daysOfWeek = setOf(DayOfWeek.MONDAY),
                    )
                val sundayMorning = ZonedDateTime.of(2026, 6, 21, 8, 0, 0, 0, zone)

                schedule.matchSlot(sundayMorning, grace).shouldBeNull()
            }
        }

        "IntervalSchedule" - {
            "should produce stable slot key within grace window" {
                val schedule = IntervalSchedule(every = Duration.ofMinutes(30))
                val now = ZonedDateTime.of(2026, 6, 21, 10, 0, 10, 0, zone)

                val key1 = schedule.matchSlot(now, grace)
                val key2 = schedule.matchSlot(now.plusSeconds(30), grace)

                key1.shouldNotBeNull()
                key1 shouldBe key2
            }
        }

        "CronSchedule" - {
            "should parse hourly cron and match top of hour" {
                val cron = ScheduleSpecParser.parseCron("0 * * * *")
                val top = ZonedDateTime.of(2026, 6, 21, 14, 0, 15, 0, zone)

                cron.matchSlot(top, grace).shouldNotBeNull()
            }

            "should not match when minute is wrong" {
                val cron = ScheduleSpecParser.parseCron("0 * * * *")
                val mid = ZonedDateTime.of(2026, 6, 21, 14, 30, 0, 0, zone)

                cron.matchSlot(mid, grace).shouldBeNull()
            }

            "should support step syntax" {
                val field = CronField.parse("*/15", 0, 59)

                field.matches(0) shouldBe true
                field.matches(15) shouldBe true
                field.matches(10) shouldBe false
            }
        }

        "ScheduleSpecParser" - {
            "should parse daily times from config section values" {
                val cron = ScheduleSpecParser.parseCron("30 9 * * 1-5")

                cron.describe() shouldBe "Cron: 30 9 * * 1-5"
            }
        }
    })
