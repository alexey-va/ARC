package ru.arc.onboarding

import net.kyori.adventure.text.Component
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.util.Locale

internal class OnboardingConfig private constructor(
    private val source: Config,
    val publicEnabled: Boolean,
    val npcId: Int,
    val worlds: Set<String>,
) {
    val claimGuideEnabled: Boolean get() = source.bool("claim-guide.enabled", true)
    val claimGuideLandWallsEnabled: Boolean get() = source.bool("claim-guide.land-walls-enabled", true)
    val claimGuideGridMode: ClaimGuideGridMode
        get() = ClaimGuideGridMode.valueOf(
            source.string("claim-guide.grid-mode", "CURRENT_CHUNK").trim().uppercase(Locale.ROOT).replace('-', '_'),
        )

    fun claimText(key: String): Component = source.component("claim-guide.text.$key", CLAIM_TEXT.getValue(key))

    fun allowsWorld(worldName: String): Boolean = worldName.trim().lowercase(Locale.ROOT) in worlds

    fun questText(key: String): Component =
        source.component("quest.text.$key", OnboardingQuestDialog.DEFAULT_TEXT.getValue(key))

    fun validate() {
        require(npcId >= 0) { "onboarding.quest.npc-id must be non-negative" }
        require(worlds.isNotEmpty()) { "onboarding.worlds must not be empty" }
        if (claimGuideEnabled) {
            claimGuideGridMode
            CLAIM_TEXT.keys.forEach(::claimText)
        }
        OnboardingQuestDialog.DEFAULT_TEXT.keys.forEach(::questText)
    }

    companion object {
        internal val CLAIM_TEXT = linkedMapOf(
            "title" to "<#92bed8>Поставь блок на землю",
            "subtitle" to "<white>Снять защиту здесь: <#ff9f0f>/unclaim",
            "aim" to "<white>Посмотри на землю рядом — покажу участок",
            "free" to "<#b3d3df>Ничейная земля<newline><white>Поставь блок — создай приват",
            "expand" to "<#92bed8>Расширить «{land}»<newline><white>Поставь блок на ничейной земле",
            "action-expand" to "<white>Расширить «{land}» — поставь блок на ничейной земле",
            "own" to "<#80e89b>Этот чанк уже защищен<newline><white>Для расширения поставь блок за границей",
            "occupied" to "<#ff8178>Здесь уже занято<newline><white>Выбери другое место",
            "world-unavailable" to "<#ff8178>В этом мире приват недоступен",
            "success-title" to "<#80e89b>Участок защищён",
            "success-subtitle" to "<white>Здесь твои постройки под защитой",
            "success" to "<#92bed8>Готово! Этот участок защищён",
            "action-free" to "<white>Поставь блок — защити эту землю",
            "action-own" to "<white>Расширить — поставь блок за голубой границей",
            "action-occupied" to "<#ff8178>Место занято • <white>Найди свободный участок",
            "menu" to "<gray>Shift + F - меню",
            "boundary-enter" to "<#92bed8>Участок «{land}» <gray>• <white>Земля под защитой",
            "boundary-leave" to "<#ff9f0f>Ты вышел из «{land}» <gray>• <white>Здесь нет привата",
            "grid-legend" to "<gray>Тонкие линии — чанки 16×16 <dark_gray>• <#92bed8>Толстые — приваты",
            "remove" to "<white>Встань внутри своего участка → <#ff9f0f>/unclaim <white>— снять защиту здесь",
        )

        fun load(source: Config = Config(ARC.instance.dataPath, ConfigManager.moduleYamlRelative(ARC.instance.dataPath, "onboarding.yml"))): OnboardingConfig {
            val publicEnabled = source.bool("quest.public-enabled", false)
            val worlds =
                source
                    .stringList("worlds", listOf("survival", "mining", "vanilla"))
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter(String::isNotEmpty)
                    .toSet()
            return OnboardingConfig(
                source = source,
                publicEnabled = publicEnabled,
                npcId = source.int("quest.npc-id", 447),
                worlds = worlds,
            )
        }

    }
}
