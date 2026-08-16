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
                    "<#92bed8>База <#666666>• <#2bba43>Место найдено.<#e6fff3> Поставьте <#92bed8>/sethome<#e6fff3>.",
                OnboardingHint.HOME_CREATED to
                    "<#92bed8>База <#666666>• <#2bba43>Дом сохранён.<#e6fff3> Получите блоки привата: <#92bed8>/kit start<#e6fff3>.",
                OnboardingHint.LAND_CLAIMED to
                    "<#92bed8>База <#666666>• <#2bba43>Участок защищён.<#e6fff3> Поставьте внутри <#92bed8>/sethome<#e6fff3>.",
                OnboardingHint.FOOTHOLD_MISMATCH to
                    "<#92bed8>База <#666666>• <#ff9f0f>Дом вне привата.<#e6fff3> Поставьте <#92bed8>/sethome <#e6fff3>внутри участка.",
                OnboardingHint.FOOTHOLD_COMPLETE to
                    "<#92bed8>База <#666666>• <#2bba43>Место закреплено.<#e6fff3> Книга «Первый дом» ждёт в <#92bed8>/kit start<#e6fff3>.",
                OnboardingHint.BUILD_BOOK_MISSING_HOME to
                    "<#92bed8>База <#666666>• <#ff9f0f>Нет точки дома.<#e6fff3> Поставьте здесь <#92bed8>/sethome<#e6fff3>.",
                OnboardingHint.BUILD_BOOK_MISSING_LAND to
                    "<#92bed8>База <#666666>• <#ff9f0f>Участок не защищён.<#e6fff3> Поставьте блок привата.",
                OnboardingHint.BUILD_BOOK_MISSING_BOTH to
                    "<#92bed8>База <#666666>• <#ff9f0f>Место не закреплено.<#e6fff3> Нужны <#92bed8>/sethome <#e6fff3>и блок привата.",
                OnboardingHint.BUILD_BOOK_OUTSIDE_FOOTHOLD to
                    "<#92bed8>База <#666666>• <#ff9f0f>Макет вне базы.<#e6fff3> Нужны <#92bed8>/sethome <#e6fff3>и приват.",
                OnboardingHint.AUTOBUILD_COMPLETE to
                    "<#92bed8>База <#666666>• <#2bba43>Дом построен.<#e6fff3> Продать излишки: <#92bed8>/shop <#e6fff3>или <#92bed8>/ah<#e6fff3>.",
            )
    }
}
