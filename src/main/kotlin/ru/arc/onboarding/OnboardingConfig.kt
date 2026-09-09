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
    val claimGuideEnabled: Boolean get() = source.bool("claim-guide.enabled", true)

    fun claimText(key: String): Component = source.component("claim-guide.text.$key", CLAIM_TEXT.getValue(key))

    fun allowsWorld(worldName: String): Boolean = worldName.trim().lowercase(Locale.ROOT) in worlds

    fun hintEnabled(hint: OnboardingHint): Boolean = source.bool("steps.${hint.id}.enabled", true)

    fun message(hint: OnboardingHint): Component =
        source.component("steps.${hint.id}.message", DEFAULT_MESSAGES.getValue(hint))

    fun validate() {
        if (claimGuideEnabled) CLAIM_TEXT.keys.forEach(::claimText)
        OnboardingHint.entries.filter(::hintEnabled).forEach { hint ->
            require(source.string("steps.${hint.id}.message", DEFAULT_MESSAGES.getValue(hint)).isNotBlank()) {
                "onboarding step ${hint.id} has a blank message"
            }
            message(hint)
        }
    }

    companion object {
        internal val CLAIM_TEXT = linkedMapOf(
            "title" to "<#92bed8>Поставь блок на землю",
            "subtitle" to "<white>Снять защиту здесь: <#ff9f0f>/unclaim",
            "aim" to "<white>Посмотри на землю рядом — покажу участок",
            "free" to "<#b3d3df>Ничейная земля<newline><white>Поставь блок — создай приват",
            "expand" to "<#92bed8>Расширить «{land}»<newline><white>Поставь блок на ничейной земле",
            "action-expand" to "<white>Расширить «{land}» — поставь блок на ничейной земле",
            "own" to "<#92bed8>Твой участок уже защищён<newline><white>Для расширения поставь блок за границей",
            "occupied" to "<#ff8178>Здесь уже занято<newline><white>Выбери другое место",
            "success-title" to "<#80e89b>Участок защищён",
            "success-subtitle" to "<white>Здесь твои постройки под защитой",
            "success" to "<#92bed8>Готово! Этот участок защищён",
            "action-free" to "<white>Поставь блок — защити эту землю",
            "action-own" to "<white>Расширить — поставь блок за голубой границей",
            "action-occupied" to "<#ff8178>Место занято • <white>Найди свободный участок",
            "menu" to "<gray>Shift → наведи → ЛКМ: меню",
            "add-friend" to "<#92bed8>Добавить друга<newline><white>«{land}»<newline><gray>Shift → наведи → ЛКМ",
            "boundary-enter" to "<#92bed8>Участок «{land}» <gray>• <white>Земля под защитой",
            "boundary-leave" to "<#ff9f0f>Ты вышел из «{land}» <gray>• <white>Здесь нет привата",
            "grid-legend" to "<gray>Тонкие линии — чанки 16×16 <dark_gray>• <#92bed8>Толстые — приваты",
            "remove" to "<white>Встань внутри своего участка → <#ff9f0f>/unclaim <white>— снять защиту здесь",
        )

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
                    "<#92bed8>Начало <#666666>• <#2bba43>Место найдено.<#e6fff3> Сохраните точку возвращения: <#92bed8>/sethome<#e6fff3>.",
                OnboardingHint.HOME_CREATED to
                    "<#92bed8>Начало <#666666>• <#2bba43>Дом сохранён.<#e6fff3> Возврат: <#92bed8>/home<#e6fff3>. Блоки привата: <#92bed8>/kit start<#e6fff3>.",
                OnboardingHint.LAND_CLAIMED to
                    "<#92bed8>Начало <#666666>• <#2bba43>Участок защищён.<#e6fff3> Поставьте внутри <#92bed8>/sethome<#e6fff3>.",
                OnboardingHint.FOOTHOLD_MISMATCH to
                    "<#92bed8>Начало <#666666>• <#ff9f0f>Дом вне привата.<#e6fff3> Поставьте <#92bed8>/sethome <#e6fff3>внутри участка.",
                OnboardingHint.FOOTHOLD_COMPLETE to
                    "<#92bed8>Начало <#666666>• <#2bba43>Место закреплено.<#e6fff3> Книга «Первый дом» ждёт в <#92bed8>/kit start<#e6fff3>.",
                OnboardingHint.BUILD_BOOK_MISSING_HOME to
                    "<#92bed8>Начало <#666666>• <#ff9f0f>Нет точки дома.<#e6fff3> Поставьте здесь <#92bed8>/sethome<#e6fff3>.",
                OnboardingHint.BUILD_BOOK_MISSING_LAND to
                    "<#92bed8>Начало <#666666>• <#ff9f0f>Участок не защищён.<#e6fff3> Поставьте блок привата.",
                OnboardingHint.BUILD_BOOK_MISSING_BOTH to
                    "<#92bed8>Начало <#666666>• <#ff9f0f>Место не закреплено.<#e6fff3> Нужны <#92bed8>/sethome <#e6fff3>и блок привата.",
                OnboardingHint.BUILD_BOOK_OUTSIDE_FOOTHOLD to
                    "<#92bed8>Начало <#666666>• <#ff9f0f>Макет вне базы.<#e6fff3> Нужны <#92bed8>/sethome <#e6fff3>и приват.",
                OnboardingHint.AUTOBUILD_COMPLETE to
                    "<#92bed8>Начало <#666666>• <#2bba43>Дом построен.<#e6fff3> Продать излишки: <#92bed8>/shop <#e6fff3>или <#92bed8>/ah<#e6fff3>.",
            )
    }
}
