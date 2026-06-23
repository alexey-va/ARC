package ru.arc.scheduled

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ScheduledCommandInputValidatorTest :
    FreeSpec({

        val existing = setOf("morning", "evening")

        "ScheduledCommandInputValidator" - {
            "should treat exit as cancel" {
                ScheduledCommandInputValidator.isCancel("exit") shouldBe true
                ScheduledCommandInputValidator.isCancel("EXIT") shouldBe true
            }

            "should reject invalid time" {
                val result =
                    ScheduledCommandInputValidator.validate(
                        inputId = 1,
                        input = "not-a-time",
                        scheduleType = ScheduleEditorType.DAILY,
                        existingIds = existing,
                        currentId = "morning",
                    )
                result.shouldBeInstanceOf<ValidationResult.Error>()
            }

            "should accept valid times" {
                val result =
                    ScheduledCommandInputValidator.validate(
                        inputId = 1,
                        input = "09:00,21:00",
                        scheduleType = ScheduleEditorType.DAILY,
                        existingIds = existing,
                        currentId = "morning",
                    )
                result shouldBe ValidationResult.Ok
            }

            "should reject invalid interval" {
                val result =
                    ScheduledCommandInputValidator.validate(
                        inputId = 1,
                        input = "abc",
                        scheduleType = ScheduleEditorType.INTERVAL,
                        existingIds = existing,
                        currentId = "morning",
                    )
                result.shouldBeInstanceOf<ValidationResult.Error>()
            }

            "should accept valid interval" {
                val result =
                    ScheduledCommandInputValidator.validate(
                        inputId = 1,
                        input = "30m",
                        scheduleType = ScheduleEditorType.INTERVAL,
                        existingIds = existing,
                        currentId = "morning",
                    )
                result shouldBe ValidationResult.Ok
            }

            "should validate rename id" {
                ScheduledCommandInputValidator.validateId("night_job", existing, "morning") shouldBe ValidationResult.Ok
                ScheduledCommandInputValidator.validateId("morning", existing, "morning") shouldBe ValidationResult.Ok
                ScheduledCommandInputValidator
                    .validateId("evening", existing, "morning")
                    .shouldBeInstanceOf<ValidationResult.Error>()
                ScheduledCommandInputValidator
                    .validateId("Bad ID", existing, "morning")
                    .shouldBeInstanceOf<ValidationResult.Error>()
            }

            "should validate weekly days" {
                ScheduledCommandInputValidator.validateWeeklyDays("MONDAY,FRIDAY") shouldBe ValidationResult.Ok
                ScheduledCommandInputValidator
                    .validateWeeklyDays("NOTADAY")
                    .shouldBeInstanceOf<ValidationResult.Error>()
            }

            "should validate cron" {
                ScheduledCommandInputValidator.validateScheduleValue("0 8 * * *", ScheduleEditorType.CRON) shouldBe
                    ValidationResult.Ok
                ScheduledCommandInputValidator
                    .validateScheduleValue("bad", ScheduleEditorType.CRON)
                    .shouldBeInstanceOf<ValidationResult.Error>()
            }
        }
    })
