package ru.arc.onboarding

import net.kyori.adventure.text.Component
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.util.Locale

internal class OnboardingConfig private constructor(
    private val source: Config,
    val enabled: Boolean,
    val worlds: Set<String>,
    val firstDelayTicks: Long,
    val resumeDelayTicks: Long,
    val betweenMessagesTicks: Long,
) {
    fun allowsWorld(worldName: String): Boolean = worldName.trim().lowercase(Locale.ROOT) in worlds

    fun hintEnabled(hint: OnboardingHint): Boolean = source.bool("steps.${hint.id}.enabled", true)

    fun message(hint: OnboardingHint): Component =
        source.component("steps.${hint.id}.message", DEFAULT_MESSAGES.getValue(hint))

    fun validate() {
        OnboardingHint.entries.filter(::hintEnabled).forEach { hint ->
            require(source.string("steps.${hint.id}.message", DEFAULT_MESSAGES.getValue(hint)).isNotBlank()) {
                "onboarding step ${hint.id} has a blank message"
            }
            message(hint)
        }
    }

    companion object {
        fun load(source: Config = ConfigManager.ofModule(ARC.instance.dataPath, "onboarding.yml")): OnboardingConfig {
            val enabled = source.bool("enabled", false)
            val worlds =
                source
                    .stringList("worlds", listOf("survival", "mining", "vanilla"))
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter(String::isNotEmpty)
                    .toSet()
            require(!enabled || worlds.isNotEmpty()) { "onboarding.worlds must not be empty when onboarding is enabled" }
            return OnboardingConfig(
                source = source,
                enabled = enabled,
                worlds = worlds,
                firstDelayTicks = source.long("delivery.first-delay-ticks", 30L).coerceIn(1L, 1_200L),
                resumeDelayTicks = source.long("delivery.resume-delay-ticks", 60L).coerceIn(1L, 1_200L),
                betweenMessagesTicks = source.long("delivery.between-messages-ticks", 100L).coerceIn(20L, 2_400L),
            )
        }

        private val DEFAULT_MESSAGES =
            mapOf(
                OnboardingHint.FIRST_RTP to
                    "<green>Место найдено. <gray>Сохрани возврат через <white>/sethome<gray> и защити участок claim-блоками из <white>/kit start<gray>.",
                OnboardingHint.HOME_CREATED to
                    "<green>Точка возврата сохранена. <gray>Теперь защити участок claim-блоком из <white>/kit start<gray>.",
                OnboardingHint.LAND_CLAIMED to
                    "<green>Участок защищён. <gray>Теперь поставь <white>/sethome<gray>, чтобы вернуться сюда после смерти или выхода.",
                OnboardingHint.FOOTHOLD_MISMATCH to
                    "<yellow>Дом и приват пока не совпадают. <gray>Поставь <white>/sethome <gray>внутри защищённого участка — тогда это будет безопасная база.",
                OnboardingHint.FOOTHOLD_COMPLETE to
                    "<green>База закреплена. <gray>В <white>/kit start <gray>есть книга «Первый дом»: возьми её в руку и нажми ПКМ по земле.",
                OnboardingHint.BUILD_BOOK_MISSING_HOME to
                    "<yellow>Макет дома открыт. <gray>Перед стройкой поставь здесь <white>/sethome<gray>, чтобы не потерять базу.",
                OnboardingHint.BUILD_BOOK_MISSING_LAND to
                    "<yellow>Макет дома открыт. <gray>Перед стройкой защити участок claim-блоком из <white>/kit start<gray>.",
                OnboardingHint.BUILD_BOOK_MISSING_BOTH to
                    "<yellow>Макет дома открыт, но место ещё не закреплено. <gray>Поставь <white>/sethome <gray>и защити участок claim-блоком.",
                OnboardingHint.BUILD_BOOK_OUTSIDE_FOOTHOLD to
                    "<yellow>Макет открыт вне безопасной базы. <gray>Строй там, где <white>/sethome <gray>находится внутри Lands-привата, либо сначала закрепи это место.",
                OnboardingHint.AUTOBUILD_COMPLETE to
                    "<green>Первый дом готов. <gray>Дальше добывай и строй; излишки продавай в <white>/shop <gray>или выставляй на <white>/ah<gray>.",
            )
    }
}
