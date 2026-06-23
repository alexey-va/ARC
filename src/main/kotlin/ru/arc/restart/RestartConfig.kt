package ru.arc.restart

import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.configs.EmptyConfig
import java.nio.file.Path
import java.time.Duration

open class RestartConfig(
    private val config: Config,
) {
    open val enabled: Boolean
        get() = config.bool("enabled", true)

    open val defaultDelay: Duration
        get() = config.duration("default-delay", Duration.ofMinutes(3))

    open val knownServers: List<String>
        get() =
            config.stringList("known-servers").ifEmpty {
                listOf("spawn", "survival")
            }

    open val permissionRestart: String
        get() = config.string("permission.restart", "arc.restart")

    open val permissionAllServers: String
        get() = config.string("permission.all-servers", "arc.restart.all")

    open val permissionCancel: String
        get() = config.string("permission.cancel", "arc.restart.cancel")

    open val messageScheduled: String
        get() = config.string("messages.scheduled", "<green>Запланирована перезагрузка через <white><delay>")

    open val messageCancelled: String
        get() = config.string("messages.cancelled", "<yellow>Перезагрузка отменена")

    open val messageAlreadyPending: String
        get() =
            config.string(
                "messages.already-pending",
                "<red>Уже запланирована перезагрузка. Отмена: <white>/arc restart cancel",
            )

    open val messageNothingPending: String
        get() = config.string("messages.nothing-pending", "<gray>Нет запланированной перезагрузки")

    open val messageBroadcastScheduled: String
        get() = config.string("messages.broadcast-scheduled", "<red>[Сервер] <gray>Перезагрузка через <white><delay>")

    open val messageBroadcastCancelled: String
        get() = config.string("messages.broadcast-cancelled", "<green>[Сервер] <gray>Перезагрузка отменена")

    open val messageKick: String
        get() = config.string("messages.kick", "<red>Сервер перезагружается. Зайдите через минуту.")

    open val kickBeforeRestart: Boolean
        get() = config.bool("kick-before-restart", true)

    open val kickDelayTicks: Long
        get() = config.durationTicks("kick-delay", 20)

    open val titleWarning: String
        get() = config.string("title.warning", "<red>⚠ Перезагрузка")

    open val titleSubtitle: String
        get() = config.string("title.subtitle", "<gray>через <white><time>")

    open val titleCancelledTitle: String
        get() = config.string("title.cancelled-title", "<green>Отменено")

    open val titleCancelledSubtitle: String
        get() = config.string("title.cancelled-subtitle", "<gray>Перезагрузка отменена")

    open val titleFadeIn: Int
        get() = config.integer("title.fade-in", 10)

    open val titleStay: Int
        get() = config.integer("title.stay", 70)

    open val titleFadeOut: Int
        get() = config.integer("title.fade-out", 20)

    open val titleFinalCountdownSeconds: Int
        get() = config.integer("title.final-countdown-seconds", 60)

    open val titleFinalFadeIn: Int
        get() = config.integer("title.final-fade-in", 0)

    open val titleFinalStay: Int
        get() = config.integer("title.final-stay", 40)

    open val titleFinalFadeOut: Int
        get() = config.integer("title.final-fade-out", 0)

    open val normalTitleTiming: TitleTiming
        get() = TitleTiming(titleFadeIn, titleStay, titleFadeOut)

    open val finalMinuteTitleTiming: TitleTiming
        get() = TitleTiming(titleFinalFadeIn, titleFinalStay, titleFinalFadeOut)

    open fun titleTimingFor(secondsRemaining: Int): TitleTiming =
        RestartTitleTiming.forSecondsRemaining(
            secondsRemaining = secondsRemaining,
            finalCountdownSeconds = titleFinalCountdownSeconds,
            normal = normalTitleTiming,
            finalMinute = finalMinuteTitleTiming,
        )

    open val warningMinuteMarks: Boolean
        get() = config.bool("warnings.minute-marks", true)

    open val warningAtSeconds: List<Int>
        get() =
            config.stringList("warnings.at-seconds").mapNotNull { it.toIntOrNull() }.ifEmpty {
                listOf(30, 10, 5, 4, 3, 2, 1)
            }

    open val warningChatBroadcast: Boolean
        get() = config.bool("warnings.chat-broadcast", true)

    companion object {
        fun load(dataPath: Path): RestartConfig = RestartConfig(ConfigManager.of(dataPath, "modules/restart.yml"))
    }
}

class TestRestartConfig(
    override val enabled: Boolean = true,
    override val defaultDelay: Duration = Duration.ofMinutes(3),
    override val knownServers: List<String> = listOf("spawn", "survival"),
    override val permissionRestart: String = "arc.restart",
    override val permissionAllServers: String = "arc.restart.all",
    override val permissionCancel: String = "arc.restart.cancel",
    override val messageScheduled: String = "<green>scheduled <delay>",
    override val messageCancelled: String = "<yellow>cancelled",
    override val messageAlreadyPending: String = "<red>pending",
    override val messageNothingPending: String = "<gray>none",
    override val messageBroadcastScheduled: String = "<red>broadcast <delay>",
    override val messageBroadcastCancelled: String = "<green>broadcast cancelled",
    override val messageKick: String = "<red>kick",
    override val kickBeforeRestart: Boolean = true,
    override val kickDelayTicks: Long = 20,
    override val titleWarning: String = "<red>warning",
    override val titleSubtitle: String = "<gray><time>",
    override val titleCancelledTitle: String = "<green>cancel",
    override val titleCancelledSubtitle: String = "<gray>cancelled",
    override val titleFadeIn: Int = 10,
    override val titleStay: Int = 70,
    override val titleFadeOut: Int = 20,
    override val titleFinalCountdownSeconds: Int = 60,
    override val titleFinalFadeIn: Int = 0,
    override val titleFinalStay: Int = 40,
    override val titleFinalFadeOut: Int = 0,
    override val warningMinuteMarks: Boolean = true,
    override val warningAtSeconds: List<Int> = listOf(30, 10, 5, 4, 3, 2, 1),
    override val warningChatBroadcast: Boolean = true,
) : RestartConfig(EmptyConfig)
