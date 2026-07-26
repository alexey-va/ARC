package ru.arc.ops

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.config.ConfigManager
import ru.arc.scheduled.ScheduleEditorType
import ru.arc.scheduled.ScheduledCommandsConfig
import ru.arc.scheduled.ServerEditorMode
import java.nio.file.Files

class OpsScheduledCommandHandlersTest :
    FreeSpec({
        "scheduled command ItemSpec-style JSON" - {
            "should parse a weekly command for both servers" {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "enabled": true,
                          "command": "cmi broadcast <yellow>Weekend event",
                          "servers": ["spawn", "survival"],
                          "schedule": {
                            "type": "weekly",
                            "days": ["FRIDAY", "SATURDAY"],
                            "times": ["18:00", "21:00"]
                          }
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val draft = OpsScheduledCommandHandlers.parseDraft("Weekend_Event", body)

                draft.id shouldBe "weekend_event"
                draft.serverMode shouldBe ServerEditorMode.BOTH
                draft.scheduleType shouldBe ScheduleEditorType.WEEKLY
                draft.scheduleValue shouldBe "18:00,21:00"
                draft.weeklyDays shouldBe "FRIDAY,SATURDAY"
            }

            "should reject unsupported server names" {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "command": "say test",
                          "servers": ["parkour"],
                          "schedule": {"type": "interval", "every": "30m"}
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val error =
                    runCatching {
                        OpsScheduledCommandHandlers.parseDraft("test", body)
                    }.exceptionOrNull()

                error.shouldBeInstanceOf<IllegalArgumentException>()
                error.message shouldContain "servers supports only"
            }

            "should reject all mixed with another server" {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "command": "say test",
                          "servers": ["all", "spawn"],
                          "schedule": {"type": "interval", "every": "30m"}
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val error =
                    runCatching {
                        OpsScheduledCommandHandlers.parseDraft("test", body)
                    }.exceptionOrNull()

                error.shouldBeInstanceOf<IllegalArgumentException>()
                error.message shouldContain "cannot be combined"
            }

            "should reject unknown and misleading fields" {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "id": "another_id",
                          "command": "say test",
                          "schedule": {"type": "interval", "every": "30m"}
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val error =
                    runCatching {
                        OpsScheduledCommandHandlers.parseDraft("test", body)
                    }.exceptionOrNull()

                error.shouldBeInstanceOf<IllegalArgumentException>()
                error.message shouldContain "body id must match"
            }

            "should reject a wrong boolean type" {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "enabled": "yes",
                          "command": "say test",
                          "schedule": {"type": "interval", "every": "30m"}
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val error =
                    runCatching {
                        OpsScheduledCommandHandlers.parseDraft("test", body)
                    }.exceptionOrNull()

                error.shouldBeInstanceOf<IllegalArgumentException>()
                error.message shouldContain "enabled must be a boolean"
            }

            "should reject multiline console commands" {
                val body =
                    JsonParser.parseString(
                        """
                        {
                          "command": "say one\nsay two",
                          "schedule": {"type": "cron", "expression": "0 * * * *"}
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val error =
                    runCatching {
                        OpsScheduledCommandHandlers.parseDraft("test", body)
                    }.exceptionOrNull()

                error.shouldBeInstanceOf<IllegalArgumentException>()
                error.message shouldContain "one line"
            }
        }

        "ScheduledCommandsConfig deletion" - {
            "should persist deletion through the native Config model" {
                val root = Files.createTempDirectory("scheduled-command-delete")
                val modules = Files.createDirectories(root.resolve("modules"))
                val yaml = modules.resolve("scheduled-commands.yml")
                Files.writeString(
                    yaml,
                    """
                    # keep this comment
                    enabled: true
                    commands:
                      remove_me:
                        enabled: false
                        command: "say old"
                        servers: all
                        schedule:
                          type: interval
                          every: 1h
                    """.trimIndent(),
                )
                ConfigManager.clear()
                val config = ScheduledCommandsConfig.load(root)

                config.deleteEntry("REMOVE_ME") shouldBe true
                config.reloadConfig()
                config.entry("remove_me") shouldBe null
                config.deleteEntry("remove_me") shouldBe false
                Files.readString(yaml) shouldContain "keep this comment"
            }

            "should remove stale schedule fields when changing type" {
                val root = Files.createTempDirectory("scheduled-command-switch")
                val modules = Files.createDirectories(root.resolve("modules"))
                val yaml = modules.resolve("scheduled-commands.yml")
                Files.writeString(
                    yaml,
                    """
                    enabled: true
                    commands:
                      rotate:
                        enabled: true
                        command: "say old"
                        servers: [spawn]
                        schedule:
                          type: interval
                          every: 1h
                          run-on-start: true
                    """.trimIndent(),
                )
                ConfigManager.clear()
                val config = ScheduledCommandsConfig.load(root)
                val draft =
                    OpsScheduledCommandHandlers.parseDraft(
                        "rotate",
                        JsonParser.parseString(
                            """
                            {
                              "command": "say new",
                              "servers": ["spawn"],
                              "schedule": {"type": "daily", "times": ["09:00"]}
                            }
                            """.trimIndent(),
                        ).asJsonObject,
                    )

                config.saveEntry(draft)
                config.reloadConfig()

                val persisted = Files.readString(yaml)
                persisted shouldContain "type: daily"
                persisted shouldContain "09:00"
                persisted.contains("every:") shouldBe false
                persisted.contains("run-on-start:") shouldBe false
            }
        }
    })
