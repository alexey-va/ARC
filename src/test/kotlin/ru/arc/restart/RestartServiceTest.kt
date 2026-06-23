package ru.arc.restart

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import ru.arc.core.TestTaskScheduler
import java.time.Duration

class RestartServiceTest :
    FreeSpec({
        "RestartService" - {
            "should schedule shutdown after delay" {
                val scheduler = TestTaskScheduler()
                var shutdownCalled = false
                val titles = mutableListOf<Pair<String, String>>()

                val service =
                    RestartManager.initForTests(
                        testConfig = TestRestartConfig(defaultDelay = Duration.ofMinutes(3), kickDelayTicks = 0),
                        taskScheduler = scheduler,
                        showTitle = { title, subtitle, _ -> titles += title to subtitle },
                        broadcastChat = {},
                        shutdown = { shutdownCalled = true },
                    )

                service.schedule(Duration.ofSeconds(10), "admin").shouldBeTrue()
                service.isPending().shouldBeTrue()

                scheduler.tick(10 * 20)
                shutdownCalled.shouldBeTrue()
                service.isPending().shouldBeFalse()
            }

            "should reject second schedule while pending" {
                val scheduler = TestTaskScheduler()
                val service =
                    RestartManager.initForTests(
                        testConfig = TestRestartConfig(),
                        taskScheduler = scheduler,
                        showTitle = { _, _, _ -> },
                        broadcastChat = {},
                        shutdown = {},
                    )

                service.schedule(Duration.ofSeconds(30), "admin").shouldBeTrue()
                service.schedule(Duration.ofSeconds(30), "admin").shouldBeFalse()
            }

            "should broadcast chat only once on schedule" {
                val scheduler = TestTaskScheduler()
                val chats = mutableListOf<String>()

                val service =
                    RestartManager.initForTests(
                        testConfig = TestRestartConfig(warningAtSeconds = listOf(30, 10, 5, 4, 3, 2, 1)),
                        taskScheduler = scheduler,
                        showTitle = { _, _, _ -> },
                        broadcastChat = { chats += it },
                        shutdown = {},
                    )

                service.schedule(Duration.ofSeconds(30), "admin").shouldBeTrue()
                chats.size shouldBe 1
            }

            "should cancel pending restart" {
                val scheduler = TestTaskScheduler()
                var shutdownCalled = false
                val service =
                    RestartManager.initForTests(
                        testConfig = TestRestartConfig(),
                        taskScheduler = scheduler,
                        showTitle = { _, _, _ -> },
                        broadcastChat = {},
                        shutdown = { shutdownCalled = true },
                    )

                service.schedule(Duration.ofSeconds(30), "admin").shouldBeTrue()
                service.cancel("admin").shouldBeTrue()
                service.isPending().shouldBeFalse()

                scheduler.tick(30 * 20)
                shutdownCalled.shouldBeFalse()
            }
            "should kick players before shutdown" {
                val scheduler = TestTaskScheduler()
                var kickCalled = false
                var shutdownCalled = false

                val service =
                    RestartManager.initForTests(
                        testConfig = TestRestartConfig(kickDelayTicks = 20),
                        taskScheduler = scheduler,
                        showTitle = { _, _, _ -> },
                        broadcastChat = {},
                        kickPlayers = { kickCalled = true },
                        shutdown = { shutdownCalled = true },
                    )

                service.schedule(Duration.ofSeconds(5), "admin").shouldBeTrue()
                scheduler.tick(5 * 20)
                kickCalled.shouldBeTrue()
                shutdownCalled.shouldBeFalse()
                scheduler.tick(20)
                shutdownCalled.shouldBeTrue()
            }
        }
    })
