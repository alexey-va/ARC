package ru.arc.scheduled

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ru.arc.core.TestTaskScheduler
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduledCommandsServiceTest :
    FreeSpec({

        val zone = ZoneId.of("Europe/Moscow")

        "ScheduledCommandsService" - {
            "should dispatch command when daily schedule matches" {
                val dispatched = mutableListOf<String>()
                val settings =
                    MutableScheduledCommandsSettings(timezone = zone).apply {
                        put(
                            ScheduledCommandEntry(
                                id = "morning",
                                enabled = true,
                                command = "say hello",
                                servers = null,
                                schedule =
                                    DailySchedule(
                                        times = listOf(LocalTime.of(9, 0)),
                                        daysOfWeek = null,
                                    ),
                            ),
                        )
                    }
                val scheduler = TestTaskScheduler()
                val service =
                    ScheduledCommandsManager.initForTests(
                        settings = settings,
                        dispatcher = CommandDispatcher { dispatched += it },
                        serverName = "spawn",
                        clock =
                            object : ScheduleClock {
                                override fun now(zone: ZoneId) = ZonedDateTime.of(2026, 6, 21, 9, 0, 5, 0, zone)
                            },
                        taskScheduler = scheduler,
                    )
                service.clearState()

                service.tick(ZonedDateTime.of(2026, 6, 21, 9, 0, 5, 0, zone))

                dispatched shouldContainExactly listOf("say hello")
                service.lastFireKey("morning").shouldNotBeNull()
            }

            "should not dispatch twice for same fire key" {
                val dispatched = mutableListOf<String>()
                val settings =
                    MutableScheduledCommandsSettings(timezone = zone).apply {
                        put(
                            ScheduledCommandEntry(
                                id = "morning",
                                enabled = true,
                                command = "say once",
                                servers = null,
                                schedule =
                                    DailySchedule(
                                        times = listOf(LocalTime.of(9, 0)),
                                        daysOfWeek = null,
                                    ),
                            ),
                        )
                    }
                val service =
                    ScheduledCommandsManager.initForTests(
                        settings = settings,
                        dispatcher = CommandDispatcher { dispatched += it },
                        serverName = "spawn",
                        clock =
                            object : ScheduleClock {
                                override fun now(zone: ZoneId) = ZonedDateTime.of(2026, 6, 21, 9, 0, 5, 0, zone)
                            },
                        taskScheduler = TestTaskScheduler(),
                    )
                service.clearState()
                val now = ZonedDateTime.of(2026, 6, 21, 9, 0, 5, 0, zone)

                service.tick(now)
                service.tick(now)

                dispatched shouldContainExactly listOf("say once")
            }

            "should respect server filter" {
                val dispatched = mutableListOf<String>()
                val settings =
                    MutableScheduledCommandsSettings(timezone = zone).apply {
                        put(
                            ScheduledCommandEntry(
                                id = "spawn-only",
                                enabled = true,
                                command = "say spawn",
                                servers = setOf("spawn"),
                                schedule = IntervalSchedule(Duration.ofMinutes(5), runOnStart = true),
                            ),
                        )
                    }
                val service =
                    ScheduledCommandsManager.initForTests(
                        settings = settings,
                        dispatcher = CommandDispatcher { dispatched += it },
                        serverName = "survival",
                        clock =
                            object : ScheduleClock {
                                override fun now(zone: ZoneId) = ZonedDateTime.now(zone)
                            },
                        taskScheduler = TestTaskScheduler(),
                    )
                service.clearState()
                val now = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, zone)

                service.tick(now)

                dispatched shouldContainExactly emptyList()
            }

            "should toggle enabled state via settings" {
                val settings =
                    MutableScheduledCommandsSettings(timezone = zone).apply {
                        put(
                            ScheduledCommandEntry(
                                id = "toggle-me",
                                enabled = true,
                                command = "say x",
                                servers = null,
                                schedule = IntervalSchedule(Duration.ofMinutes(1), runOnStart = true),
                            ),
                        )
                    }
                val service =
                    ScheduledCommandsManager.initForTests(
                        settings = settings,
                        dispatcher = CommandDispatcher { },
                        serverName = "spawn",
                        clock =
                            object : ScheduleClock {
                                override fun now(zone: ZoneId) = ZonedDateTime.now(zone)
                            },
                        taskScheduler = TestTaskScheduler(),
                    )

                service.toggleEnabled("toggle-me") shouldBe false
                settings.entry("toggle-me")?.enabled shouldBe false
            }
        }
    })
