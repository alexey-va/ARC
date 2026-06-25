package ru.arc.xserver.announcements

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.mockbukkit.mockbukkit.MockBukkit
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.util.Logging
import java.io.File

class AnnounceManagerReloadTest : FreeSpec({

    beforeSpec { Logging.quietMode = true }
    afterSpec { Logging.quietMode = false }

    "AnnounceManager" - {
        "should replace rotation timer when delay-seconds changes on reload" {
            if (MockBukkit.isMocked()) {
                MockBukkit.unmock()
            }
            ConfigManager.clear()

            val server = MockBukkit.mock()
            ARC.serverName = "test-server"
            val plugin = MockBukkit.load(ARC::class.java)
            ARC.plugin = plugin
            val dataPath = plugin.dataFolder.toPath()

            val redisFile = ConfigManager.moduleYamlPath(dataPath, "redis.yml").toFile()
            redisFile.parentFile.mkdirs()
            redisFile.writeText(
                """
                enabled: false
                server-name: test-server
                main-server: true
                """.trimIndent(),
            )

            fun writeAnnounce(delaySeconds: Int) {
                val announceFile = ConfigManager.moduleYamlPath(dataPath, "announce.yml").toFile()
                announceFile.parentFile.mkdirs()
                announceFile.writeText(
                    """
                    config:
                      delay-seconds: $delaySeconds
                    messages:
                      '1':
                        message: '<gray>test'
                        servers: all
                        type: chat
                        serialization-type: mini_message
                        weight: 1
                    """.trimIndent(),
                )
            }

            writeAnnounce(100)
            ConfigManager.reloadAll()

            val scheduler = TestTaskScheduler()
            try {
                Tasks.withScheduler(scheduler) {
                    AnnounceManager.reload()
                    scheduler.timerCount() shouldBe 2

                    writeAnnounce(5)
                    ConfigManager.reloadAll()
                    AnnounceManager.reload()
                    scheduler.timerCount() shouldBe 2
                }
            } finally {
                AnnounceManager.cancel()
                ConfigManager.clear()
                MockBukkit.unmock()
            }
        }

        "should not load rotation messages when main-server is false" {
            if (MockBukkit.isMocked()) {
                MockBukkit.unmock()
            }
            ConfigManager.clear()

            val server = MockBukkit.mock()
            ARC.serverName = "parkour"
            val plugin = MockBukkit.load(ARC::class.java)
            ARC.plugin = plugin
            val dataPath = plugin.dataFolder.toPath()

            val redisFile = ConfigManager.moduleYamlPath(dataPath, "redis.yml").toFile()
            redisFile.parentFile.mkdirs()
            redisFile.writeText(
                """
                enabled: false
                server-name: parkour
                main-server: false
                """.trimIndent(),
            )

            val announceFile = ConfigManager.moduleYamlPath(dataPath, "announce.yml").toFile()
            announceFile.parentFile.mkdirs()
            announceFile.writeText(
                """
                config:
                  delay-seconds: 60
                messages:
                  '1':
                    message: '<gray>should-not-rotate'
                    servers: all
                    type: chat
                    serialization-type: mini_message
                    weight: 1
                """.trimIndent(),
            )
            ConfigManager.reloadAll()

            val scheduler = TestTaskScheduler()
            try {
                Tasks.withScheduler(scheduler) {
                    AnnounceManager.reload()
                    scheduler.timerCount() shouldBe 1
                }
            } finally {
                AnnounceManager.cancel()
                ConfigManager.clear()
                MockBukkit.unmock()
            }
        }
    }
})
