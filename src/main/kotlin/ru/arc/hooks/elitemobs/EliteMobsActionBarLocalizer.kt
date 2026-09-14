package ru.arc.hooks.elitemobs

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule
import com.magmaguy.elitemobs.advancedcombat.classes.ClassResourceType
import com.magmaguy.elitemobs.skills.SkillType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import ru.arc.util.Logging

/**
 * Translates EliteMobs' resource-pack-free alpha combat action bar without patching EliteMobs.
 * Packets have no plugin owner, so the transformer accepts only the exact HUD, class-control and
 * ability-feedback shapes EliteMobs emits.
 */
internal class EliteMobsActionBarLocalizer(
    dynamicTranslations: Map<String, String> = emptyMap(),
) {
    private val plain = PlainTextComponentSerializer.plainText()
    private val translations = (dynamicTranslations + FIXED_TRANSLATIONS)
        .filter { (source, target) -> source.isNotBlank() && source != target }
        .entries
        .sortedByDescending { it.key.length }
        .map { it.key to it.value }
    private val dynamicSources = dynamicTranslations.keys.filter(String::isNotBlank)

    fun localize(component: Component): Component {
        val source = plain.serialize(component)
        // The resource-pack HUD precomputes half-pixel offsets from the original text metrics.
        // Replacing its text after packet assembly would shift the whole panel, so leave it intact.
        if (source.any(::isPrivateUse)) return component
        if (!looksLikeEliteMobsCombatUi(source)) return component
        val translated = translate(source)
        if (translated == source) return component

        val leaves = localizeLeaves(component)
        if (plain.serialize(leaves) == translated) return leaves

        // Ability names are gradient-rendered one character per child. Rebuild only the recognized
        // resource-pack-free feedback line so the complete name is translated as one component.
        return Component.text(translated).style(component.style())
    }

    internal fun translate(source: String): String {
        var translated = source
        for ((english, russian) in translations) translated = translated.replace(english, russian)
        return SECONDS.replace(translated) { "${it.groupValues[1]} с" }
    }

    private fun looksLikeEliteMobsCombatUi(text: String): Boolean {
        if (text.startsWith("HP ")) return true
        if (FIXED_ANCHORS.any(text::contains)) return true
        if (text.contains("/F]") && text.contains("/LMB]") && text.contains("/RMB]")) return true
        if (dynamicSources.any(text::contains) && ABILITY_RECEIPT.containsMatchIn(text)) return true
        return false
    }

    private fun localizeLeaves(component: Component): Component {
        val translated = if (component is TextComponent) component.content(translate(component.content())) else component
        if (translated.children().isEmpty()) return translated
        return translated.children(translated.children().map(::localizeLeaves))
    }

    private companion object {
        val SECONDS = Regex("(\\d+(?:[.,]\\d+)?)s\\b")
        val ABILITY_RECEIPT = Regex("!.*-\\d+(?:[.,]\\d+)?\\s+(?:Fury|Mana|Resolve|Focus|Grace|Stamina)\\b")
        val FIXED_ANCHORS = listOf(
            "F,F: Mobility",
            "Entering combat!",
            "Out of combat",
            "No active class",
            "No class",
            "Not enough ",
            "Class skills are unavailable",
            "Class controls are not active",
            "Select an unlocked class",
            " needs a valid target.",
            "Aim at a recent Elite corpse.",
            " is blocked by terrain.",
            " could not find safe footing.",
            " is unavailable due to an internal error.",
            " cannot be used right now.",
            "Off-class weapon",
        )
        val FIXED_TRANSLATIONS = mapOf(
            "F,F: Mobility | F+LMB: Signature | F+RMB: Utility" to
                "F, F: перемещение | F + ЛКМ: основная | F + ПКМ: вспомогательная",
            "Out of combat - slow healing resumed" to "Бой окончен — медленное лечение возобновилось",
            "Class skills are unavailable during transport." to "Способности класса недоступны во время перемещения.",
            "Class controls are not active here." to "Управление классом здесь не действует.",
            "Select an unlocked class with /em class first." to "Сначала выберите открытый класс: Shift + F → Классы.",
            "No active class | /em class" to "Класс не выбран | Shift + F → Классы",
            "No class » pick one with /em class" to "Класс не выбран » Shift + F → Классы",
            "Entering combat!" to "Вы вступили в бой!",
            "Not enough " to "Недостаточно ресурса: ",
            " required" to " нужно",
            " needs a valid target." to ": нужна подходящая цель.",
            "Aim at a recent Elite corpse." to "Наведитесь на недавний труп элитного моба.",
            " is blocked by terrain." to ": путь перекрыт.",
            " could not find safe footing." to ": не найдено безопасное место.",
            " is unavailable due to an internal error. Please report this." to ": внутренняя ошибка. Сообщите администрации.",
            " cannot be used right now." to ": сейчас применить нельзя.",
            "Off-class weapon" to "Неподходящее оружие",
            " more damage with " to " больше урона с классом ",
            " deal " to " наносят ",
            " and " to " и ",
            " damage reduction" to " снижения урона",
            " next heal echoes " to " следующее лечение повторяется на ",
            "next heal echoes " to "следующее лечение повторяется на ",
            " spell damage" to " урона заклинаний",
            " damage taken" to " получаемого урона",
            " marked damage" to " урона по метке",
            " enemy damage" to " урона врага",
            "enemy damage " to "урон врага ",
            "x expanding hit" to "× расширяющегося удара",
            " arrows at " to " стрел по ",
            " hits at " to " ударов по ",
            "x damage" to "× урона",
            " as HP falls" to " при низком здоровье",
            "up to +" to "до +",
            "lifesteal" to "вампиризм",
            "cleanse" to "очищение",
            "knockback " to "отбрасывание ",
            "pull " to "притягивание ",
            "launch " to "подбрасывание ",
            " blocks" to " блоков",
            "Speed II" to "Скорость II",
            "Slowness III" to "Замедление III",
            "burn" to "горение",
            "root" to "обездвиживание",
            "fear" to "страх",
            "disrupt" to "прерывание",
            "taunt" to "провокация",
            "reveal" to "обнаружение",
            "summon" to "призыв",
            "activate" to "активация",
            " over " to " за ",
            " for " to " на ",
            " shield" to " щита",
            " damage" to " урона",
            " speed" to " скорости",
            " HP" to " здоровья",
            "/LMB]" to "/ЛКМ]",
            "/RMB]" to "/ПКМ]",
            "Fury" to "Ярость",
            "Resolve" to "Решимость",
            "Focus" to "Концентрация",
            "Grace" to "Благодать",
            "Mana" to "Мана",
            "Stamina" to "Выносливость",
            "HP" to "Здоровье",
        )
    }
}

internal class EliteMobsActionBarPackets private constructor(
    private val localizer: EliteMobsActionBarLocalizer,
) : AutoCloseable {
    private val listener = object : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        override fun onPacketSend(event: PacketSendEvent) {
            when (event.packetType) {
                PacketType.Play.Server.ACTION_BAR -> WrapperPlayServerActionBar(event).let {
                    it.actionBarText = localizer.localize(it.actionBarText)
                }
                PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> WrapperPlayServerSystemChatMessage(event).let {
                    if (it.isOverlay) it.message = localizer.localize(it.message)
                }
            }
        }
    }

    init {
        PacketEvents.getAPI().eventManager.registerListener(listener)
    }

    override fun close() {
        PacketEvents.getAPI().eventManager.unregisterListener(listener)
    }

    companion object {
        fun create(): EliteMobsActionBarPackets? {
            if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) return null
            return runCatching {
                EliteMobsActionBarPackets(EliteMobsActionBarLocalizer(combatCatalogTranslations()))
            }.onFailure {
                Logging.error("EliteMobs action-bar localization is unavailable", it)
            }.getOrNull()
        }
    }
}

private fun combatCatalogTranslations(): Map<String, String> {
    if (!AdvancedCombatModule.isInitialized()) return emptyMap()
    val catalog = AdvancedCombatModule.get().catalog()
    val localization = DungeonClassLocalization()
    val candidates = linkedMapOf<String, MutableSet<String>>()
    fun add(source: String, target: String) {
        if (source.isNotBlank() && source != target) candidates.getOrPut(source, ::linkedSetOf).add(target)
    }

    for (form in catalog.forms()) {
        add(form.displayName(), localization.formName(form.id(), form.displayName()))
        val lineage = catalog.lineageOf(form.id())
        val rootId = catalog.rootOf(form.id()).id()
        add(lineage.mobility().displayName(), localization.ability(
            rootId, "mobility", lineage.mobility().displayName(), lineage.mobility().description(),
        ).name)
        add(form.signature().displayName(), localization.ability(
            form.id(), "signature", form.signature().displayName(), form.signature().description(),
        ).name)
        add(form.utility().displayName(), localization.ability(
            form.id(), "utility", form.utility().displayName(), form.utility().description(),
        ).name)
    }
    ClassResourceType.entries.forEach { add(it.displayName(), dungeonResourceName(it)) }
    SkillType.entries.forEach { add(it.displayName, dungeonSkillName(it)) }
    return candidates.mapNotNull { (source, targets) ->
        targets.singleOrNull()?.let { source to it }
    }.toMap()
}

private fun isPrivateUse(character: Char): Boolean = character in '\uE000'..'\uF8FF'
