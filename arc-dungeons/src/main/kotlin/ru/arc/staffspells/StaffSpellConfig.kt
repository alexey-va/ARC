package ru.arc.staffspells

import dev.lone.itemsadder.api.CustomStack
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Consumable
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import java.util.Locale
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.config.Config
import ru.arc.eliteloot.applyEliteSkin
import ru.arc.logging.ArcLogging
import kotlin.math.cos

internal enum class StaffSpell(val id: String) {
    CHAIN("chain"), MARK("mark"), FROST("frost"), LANCE("lance"), EMBER("ember"), NOVA("nova"),
    ICICLE("icicle"), FAULT("fault"), LUNAR("lunar"), GALE("gale"), TIDE("tide"),
    REQUIEM("requiem"), PRISM("prism"), NECROMANCER("necromancer"),
    INFERNO("inferno"), GRAVITY("gravity"), OSSUARY("ossuary");

    companion object {
        // Keep retired IDs recognizable so old prototypes cannot bypass staff input guards.
        val available = entries.filterNot { it == INFERNO }
        val itemKey = NamespacedKey("arc", "staff_spell_prototype")
        val duelOnlyKey = NamespacedKey("arc", "staff_spell_duel_only")
        fun from(item: ItemStack?): StaffSpell? {
            val meta = item?.itemMeta ?: return null
            val prototype = meta.persistentDataContainer.get(itemKey, PersistentDataType.STRING)
                ?.let { id -> entries.find { it.id == id } }
            return prototype ?: StaffEliteItems.resolve(item)
        }

        fun isDuelOnly(item: ItemStack?) = item?.itemMeta?.persistentDataContainer
            ?.get(duelOnlyKey, PersistentDataType.BYTE) == 1.toByte()
    }
}

internal enum class StaffCastInput(val secondary: Boolean, val quick: Boolean) {
    PRIMARY(false, false), SECONDARY(true, false), QUICK(false, true), QUICK_SECONDARY(true, true);
    companion object {
        fun of(secondary: Boolean, quick: Boolean) = entries.first { it.secondary == secondary && it.quick == quick }
    }
}

private const val STAFF_NATIVE_USE_SECONDS = 3_600f
private const val STAFF_PROTOTYPE_LORE_LINES = 10
private val STAFF_INPUT_COLOR = TextColor.color(0x73D9FF)
private val STAFF_STATUS_COLOR = TextColor.color(0xBDCADA)

/** Long native use opens the vanilla stop-using packet; consumption itself is always suppressed. */
internal fun ensureStaffChargeUseComponent(item: ItemStack, spell: StaffSpell): Boolean {
    if (spell !in STAFF_CHARGED_PRIMARY_SPELLS) return false
    val current = item.getData(DataComponentTypes.CONSUMABLE)
    if (current != null && current.consumeSeconds() >= STAFF_NATIVE_USE_SECONDS &&
        current.animation() == ItemUseAnimation.NONE && !current.hasConsumeParticles() &&
        current.consumeEffects().isEmpty()) return false
    item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
        .consumeSeconds(STAFF_NATIVE_USE_SECONDS)
        .animation(ItemUseAnimation.NONE)
        .hasConsumeParticles(false)
        .effects(emptyList())
        .build())
    return true
}

internal data class StaffSpellTuning(val power: Double, val vanillaDamage: Double, val cooldownTicks: Long)

internal data class StaffAimAssistSettings(
    val enabled: Boolean = true,
    val halfAngleDegrees: Double = 8.0,
    val maxMiss: Double = 2.0,
    val strength: Double = 0.70,
    val excludedSpells: Set<String> = emptySet(),
)

/** Arena tuning is independent of EliteMobs/PvE. The duel kit applies its own damage multiplier. */
internal val staffDuelDefaults = mapOf(
    StaffSpell.CHAIN to StaffSpellTuning(1.0, 6.5, 26),
    StaffSpell.MARK to StaffSpellTuning(1.0, 10.0, 44),
    StaffSpell.LANCE to StaffSpellTuning(1.0, 9.0, 32),
    StaffSpell.EMBER to StaffSpellTuning(1.0, 10.0, 40),
    StaffSpell.ICICLE to StaffSpellTuning(1.0, 8.0, 40),
    StaffSpell.FAULT to StaffSpellTuning(1.0, 10.0, 44),
    StaffSpell.NECROMANCER to StaffSpellTuning(1.0, 10.0, 160),
)

internal class StaffSpellSkinUnavailableException(skinId: String, cause: Throwable) :
    IllegalStateException("Configured ItemsAdder staff skin '$skinId' is unavailable", cause)

private val unavailableStaffSkins = mutableSetOf<String>()

internal data class StaffSpellSettings(
    val range: Double,
    val aimDegrees: Double,
    val chainRadius: Double,
    val chainTargets: Int,
    val chainDecay: Double,
    val markTicks: Int,
    val markRadius: Double,
    val frostRange: Double,
    val frostDegrees: Double,
    val frostSlowTicks: Int,
    val maxAreaTargets: Int,
    val tuning: Map<StaffSpell, StaffSpellTuning>,
    val lanceWidth: Double = 0.22,
    val lanceTargets: Int = 3,
    val emberSpeed: Double = 1.2,
    val emberRadius: Double = 2.8,
    val novaRadius: Double = 8.0,
    val duelTuning: Map<StaffSpell, StaffSpellTuning> = staffDuelDefaults,
    val aimAssist: StaffAimAssistSettings = StaffAimAssistSettings(),
    val hotkeyCooldowns: Map<StaffSpell, Pair<Long, Long>> = emptyMap(),
) {
    fun tuningFor(spell: StaffSpell, duel: Boolean): StaffSpellTuning =
        (if (duel) duelTuning[spell] else null) ?: tuning.getValue(spell)

    fun fingerprint(pluginVersion: String): String {
        val canonical = buildString {
            append("staff-duel-damage-v1\narc=").append(pluginVersion).append('\n')
            append("range=").append(range.hex()).append('\n')
            append("aimDegrees=").append(aimDegrees.hex()).append('\n')
            append("aimAssist=").append(aimAssist.enabled).append(',')
                .append(aimAssist.halfAngleDegrees.hex()).append(',').append(aimAssist.maxMiss.hex())
                .append(',').append(aimAssist.strength.hex()).append(',')
                .append(aimAssist.excludedSpells.sorted().joinToString(",")).append('\n')
            append("chainRadius=").append(chainRadius.hex()).append('\n')
            append("chainTargets=").append(chainTargets).append('\n')
            append("chainDecay=").append(chainDecay.hex()).append('\n')
            append("markTicks=").append(markTicks).append('\n')
            append("markRadius=").append(markRadius.hex()).append('\n')
            append("frostRange=").append(frostRange.hex()).append('\n')
            append("frostDegrees=").append(frostDegrees.hex()).append('\n')
            append("frostSlowTicks=").append(frostSlowTicks).append('\n')
            append("maxAreaTargets=").append(maxAreaTargets).append('\n')
            append("lanceWidth=").append(lanceWidth.hex()).append('\n')
            append("lanceTargets=").append(lanceTargets).append('\n')
            append("emberSpeed=").append(emberSpeed.hex()).append('\n')
            append("emberRadius=").append(emberRadius.hex()).append('\n')
            append("novaRadius=").append(novaRadius.hex()).append('\n')
            StaffSpell.entries.sortedBy { it.id }.forEach { spell ->
                val spellTuning = tuning.getValue(spell)
                append(spell.id).append(".power=").append(spellTuning.power.hex()).append('\n')
                append(spell.id).append(".vanillaDamage=").append(spellTuning.vanillaDamage.hex()).append('\n')
                append(spell.id).append(".cooldownTicks=").append(spellTuning.cooldownTicks).append('\n')
                val hotkeys = hotkeyCooldowns[spell]
                append(spell.id).append(".hotkeys=").append(hotkeys).append('\n')
                val arena = tuningFor(spell, duel = true)
                append(spell.id).append(".duelDamage=").append(arena.vanillaDamage.hex()).append('\n')
                append(spell.id).append(".duelCooldownTicks=").append(arena.cooldownTicks).append('\n')
            }
        }
        return sha256(canonical)
    }

    private fun Double.hex() = java.lang.Double.toHexString(this)

    companion object {
        fun fingerprint(pluginVersion: String, settings: StaffSpellSettings?) = settings?.fingerprint(pluginVersion)
            ?: sha256("staff-duel-damage-v1\narc=$pluginVersion\nstaff-spells=unavailable\n")
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

/** Player HUD placement values are read again on ARC reload. */
internal data class StaffSpellHudPositionSettings(
    val forwardBlocks: Double = 2.5,
    val verticalBlocks: Double = 0.85,
    val horizontalBlocks: Double = 0.0,
    val scale: Double = 0.66,
)

internal open class StaffSpellConfig(private val config: Config) {
    /** Camera-plane placement is read from this config generation and validated before use. */
    val hudPosition get() = StaffSpellHudPositionSettings(
        forwardBlocks = number("hud.position.forward-blocks", 2.5, 0.8..4.0),
        verticalBlocks = number("hud.position.vertical-blocks", 0.85, 0.1..2.0),
        horizontalBlocks = number("hud.position.horizontal-blocks", 0.0, -1.5..1.5),
        scale = number("hud.position.scale", 0.66, 0.35..1.0),
    )

    open val settings get() = StaffSpellSettings(
        number("targeting.range", 56.0, 2.0..64.0),
        number("targeting.half-angle-degrees", 8.0, 1.0..30.0),
        number("chain.radius", 6.0, 1.0..10.0),
        config.integer("chain.targets", 4).coerceIn(1, 8),
        number("chain.decay", 0.7, 0.1..1.0),
        config.integer("mark.delay-ticks", 18).coerceIn(4, 80),
        number("mark.radius", 3.5, 1.0..8.0),
        number("frost.range", 28.0, 2.0..48.0),
        number("frost.half-angle-degrees", 50.0, 10.0..80.0),
        config.integer("frost.slow-ticks", 40).coerceIn(1, 100),
        config.integer("max-area-targets", 8).coerceIn(1, 16),
        StaffSpell.entries.associateWith { spell ->
            val (power, damage, ticks) = when (spell) {
                StaffSpell.CHAIN -> Triple(1.0, 8.0, 24)
                StaffSpell.MARK -> Triple(1.6, 12.0, 40)
                StaffSpell.FROST -> Triple(0.8, 6.0, 32)
                StaffSpell.LANCE -> Triple(1.3, 10.0, 28)
                StaffSpell.EMBER -> Triple(1.4, 10.0, 36)
                StaffSpell.NOVA -> Triple(0.9, 7.0, 40)
                StaffSpell.ICICLE -> Triple(0.9, 7.0, 40)
                StaffSpell.FAULT -> Triple(1.2, 9.0, 44)
                StaffSpell.LUNAR -> Triple(1.0, 8.0, 32)
                StaffSpell.GALE -> Triple(0.8, 6.0, 40)
                StaffSpell.TIDE -> Triple(1.0, 8.0, 36)
                StaffSpell.REQUIEM -> Triple(1.1, 8.0, 40)
                StaffSpell.PRISM -> Triple(1.1, 9.0, 36)
                StaffSpell.NECROMANCER -> Triple(0.85, 6.0, 100)
                StaffSpell.INFERNO -> Triple(1.2, 10.0, 60)
                StaffSpell.GRAVITY -> Triple(1.2, 10.0, 44)
                StaffSpell.OSSUARY -> Triple(1.0, 8.0, 40)
            }
            StaffSpellTuning(number("${spell.id}.power", power, 0.1..5.0),
                number("${spell.id}.vanilla-damage", damage, 0.1..40.0),
                config.integer("${spell.id}.cooldown-ticks", ticks).coerceIn(4, 200).toLong())
        },
        number("lance.width", 0.22, 0.0..0.6),
        config.integer("lance.targets", 3).coerceIn(1, 8),
        number("ember.speed", 1.2, 0.5..3.0),
        number("ember.radius", 2.8, 1.0..6.0),
        number("nova.radius", 8.0, 2.0..10.0),
        staffDuelDefaults.mapValues { (spell, default) -> default.copy(
            vanillaDamage = number("duel.${spell.id}.damage", default.vanillaDamage, 0.1..40.0),
            cooldownTicks = config.integer("duel.${spell.id}.cooldown-ticks", default.cooldownTicks.toInt())
                .coerceIn(4, 400).toLong(),
        ) },
        hotkeyCooldowns = StaffSpell.available.associateWith {
            hotkeyCooldown(it, StaffHotkey.Q) to hotkeyCooldown(it, StaffHotkey.F)
        },
        aimAssist = StaffAimAssistSettings(
            enabled = config.bool("targeting.aim-assist.enabled", true),
            halfAngleDegrees = number("targeting.aim-assist.half-angle-degrees", 8.0, 0.0..12.0),
            maxMiss = number("targeting.aim-assist.max-miss-blocks", 2.0, 0.0..4.0),
            strength = number("targeting.aim-assist.strength", 0.70, 0.0..0.8),
            excludedSpells = config.stringList("targeting.aim-assist.excluded-spells").toSet(),
        ),
    )

    private fun number(key: String, fallback: Double, bounds: ClosedFloatingPointRange<Double>) =
        config.real(key, fallback).takeIf(Double::isFinite)?.coerceIn(bounds) ?: fallback

    val sceneryWorlds: Set<String> get() = config.stringList("scenery.worlds").toSet()

    fun text(key: String) = config.component("messages.$key", TagResolver.empty()).decoration(TextDecoration.ITALIC, false)

    fun cooldown(spell: StaffSpell, secondary: Boolean, remaining: Long, total: Long, input: StaffCastInput = StaffCastInput.of(secondary, false)): Component {
        val resolvers = TagResolver.resolver(
            Placeholder.component("ability", abilityName(spell, when (input) { StaffCastInput.PRIMARY -> "name"; StaffCastInput.SECONDARY -> "secondary-name"; StaffCastInput.QUICK -> "quick-name"; StaffCastInput.QUICK_SECONDARY -> "quick-secondary-name" })),
            Placeholder.unparsed("seconds", String.format(Locale.ROOT, "%.1f", remaining.coerceAtLeast(0) / 20.0)),
            Placeholder.component("input", inputComponent(spell, input)),
        )
        return config.component("messages.${if (remaining > 0) "cooldown-bar" else "ready-bar"}", resolvers)
    }

    fun charge(spell: StaffSpell, progress: Double, secondary: Boolean = false): Component = config.component("messages.charge-bar", TagResolver.resolver(
        Placeholder.component("ability", abilityName(spell, if (secondary) "secondary-name" else "name")),
        Placeholder.unparsed("percent", (progress.coerceIn(0.0, 1.0) * 100).toInt().toString()),
        Placeholder.component("input", inputComponent(spell, StaffCastInput.of(secondary, quick = false))),
    ))

    fun staffHudReady(spell: StaffSpell, secondary: Boolean): Component {
        val message = when (staffAttackInputMode(spell, secondary)) {
            StaffAttackInputMode.INSTANT -> "ready-instant"
            StaffAttackInputMode.HOLD_RELEASE -> "ready-charge"
        }
        return staffHud(message, spell, secondary)
    }

    fun staffHudCharging(spell: StaffSpell, secondary: Boolean, progress: Double, bar: String): Component {
        val percent = (progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0) * 100.0
        return staffHud("charging", spell, secondary,
            Placeholder.unparsed("percent", percent.toInt().toString()),
            Placeholder.unparsed("bar", bar))
    }

    fun staffHudCooldown(spell: StaffSpell, secondary: Boolean, remainingTicks: Long, bar: String): Component =
        staffHud("cooldown", spell, secondary,
            Placeholder.unparsed("seconds", String.format(Locale.ROOT, "%.1f", remainingTicks.coerceAtLeast(0) / 20.0)),
            Placeholder.unparsed("bar", bar))

    fun staffHudChannel(spell: StaffSpell, secondary: Boolean): Component = staffHud("channel", spell, secondary)

    fun staffHudArmy(count: Int, limit: Int): Component = config.component(
        "messages.staff-hud.army", "<dark_gray>Войско <#BDD8BB><count>/<limit>",
    ) {
        tag("count", count.coerceIn(0, limit))
        tag("limit", limit)
    }

    fun hotkeyCooldown(spell: StaffSpell, key: StaffHotkey): Long {
        val defaults = staffHotkeyDescriptions[spell] ?: return 160
        val ticks = if (key == StaffHotkey.Q) defaults.qCooldown else defaults.fCooldown
        return config.integer("${spell.id}.hotkeys.${key.name.lowercase(Locale.ROOT)}.cooldown-ticks", ticks)
            .coerceIn(20, 1200).toLong()
    }

    private fun hotkeyName(spell: StaffSpell, key: StaffHotkey): String {
        val defaults = staffHotkeyDescriptions[spell] ?: return key.name
        return config.string("${spell.id}.hotkeys.${key.name.lowercase(Locale.ROOT)}.name",
            if (key == StaffHotkey.Q) defaults.qName else defaults.fName)
    }

    internal fun hotkeyLore(spell: StaffSpell, key: StaffHotkey): Component =
        Component.text("${key.name} · ${hotkeyName(spell, key)}", net.kyori.adventure.text.format.NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false)

    fun hotkeyUnavailable(spell: StaffSpell, key: StaffHotkey): Component {
        val defaults = staffHotkeyDescriptions[spell] ?: return Component.empty()
        val description = config.string("${spell.id}.hotkeys.${key.name.lowercase(Locale.ROOT)}.description",
            if (key == StaffHotkey.Q) defaults.qDescription else defaults.fDescription)
        return Component.text("${key.name} · ${hotkeyName(spell, key)}: $description",
            net.kyori.adventure.text.format.NamedTextColor.GRAY)
    }

    fun hotkeyHud(state: StaffHotkeyHudState): Component {
        fun status(ticks: Long) = if (ticks <= 0) config.string("messages.hotkey-ready", "готово")
            else String.format(Locale.ROOT, "%.1fс", ticks / 20.0)
        return Component.text("Q", STAFF_INPUT_COLOR)
            .append(Component.text(" ${state.continuation ?: status(state.qTicks)}   ", STAFF_STATUS_COLOR))
            .append(Component.text("F", STAFF_INPUT_COLOR))
            .append(Component.text(" ${status(state.fTicks)}", STAFF_STATUS_COLOR))
    }

    fun staffHudBossbar(state: StaffSpellHudState): Component {
        val message = when (state.phase) {
            StaffSpellHudPhase.READY -> when (staffAttackInputMode(state.spell, state.secondary)) {
                StaffAttackInputMode.INSTANT -> "bossbar-ready-instant"
                StaffAttackInputMode.HOLD_RELEASE -> "bossbar-ready-charge"
            }
            StaffSpellHudPhase.CHARGING -> "bossbar-charging"
            StaffSpellHudPhase.CHANNEL -> "bossbar-channel"
            StaffSpellHudPhase.COOLDOWN -> "bossbar-cooldown"
        }
        val extra = mutableListOf<TagResolver>()
        if (state.phase == StaffSpellHudPhase.CHARGING) {
            extra += Placeholder.unparsed("percent", (state.progress.coerceIn(0.0, 1.0) * 100).toInt().toString())
            extra += Placeholder.unparsed("bar", staffSpellHudBar(state.progress))
        }
        if (state.phase == StaffSpellHudPhase.COOLDOWN) {
            extra += Placeholder.unparsed("seconds", String.format(Locale.ROOT,
                "%.1f", state.remainingTicks.coerceAtLeast(0) / 20.0))
            extra += Placeholder.unparsed("bar", staffSpellHudBar(state.progress))
        }
        return staffHud(message, state.spell, state.secondary, *extra.toTypedArray())
    }

    private fun staffHud(message: String, spell: StaffSpell, secondary: Boolean,
        vararg extra: TagResolver): Component {
        val ability = if (secondary) "secondary-name" else "name"
        return config.component("messages.staff-hud.$message", TagResolver.resolver(
            Placeholder.component("ability", abilityName(spell, ability)),
            Placeholder.component("input", inputComponent(spell, StaffCastInput.of(secondary, quick = false))),
            *extra,
        )).decoration(TextDecoration.ITALIC, false)
    }

    internal fun inputLabel(spell: StaffSpell, input: StaffCastInput): String {
        val button = if (input.quick) staffDefenseInputButton(spell) else staffAttackInputButton(spell)
        val key = "messages.controls.${if (input.secondary) "shift-" else ""}${button.configKey}"
        val fallback = when (button) {
            StaffSpellInputButton.LEFT -> if (input.secondary) "Shift + ЛКМ" else "ЛКМ"
            StaffSpellInputButton.RIGHT -> if (input.secondary) "Shift + ПКМ" else "ПКМ"
        }
        return config.string(key, fallback).trim().ifEmpty { fallback }
    }

    private fun inputComponent(spell: StaffSpell, input: StaffCastInput): Component =
        Component.text(inputLabel(spell, input), STAFF_INPUT_COLOR)

    /** Unstyled secondary/defense names inherit the staff's configured element color. */
    private fun abilityName(spell: StaffSpell, key: String): Component {
        fun firstColor(component: Component): TextColor? = component.color()
            ?: component.children().firstNotNullOfOrNull(::firstColor)
        val primary = config.component("${spell.id}.name", TagResolver.empty())
        val color = firstColor(primary) ?: STAFF_INPUT_COLOR
        return (if (key == "name") primary else config.component("${spell.id}.$key", TagResolver.empty()))
            .colorIfAbsent(color)
    }

    private fun controlPlaceholders(spell: StaffSpell) = TagResolver.resolver(
        Placeholder.unparsed("attack", inputLabel(spell, StaffCastInput.PRIMARY)),
        Placeholder.unparsed("attack-secondary", inputLabel(spell, StaffCastInput.SECONDARY)),
        Placeholder.unparsed("defense", inputLabel(spell, StaffCastInput.QUICK)),
        Placeholder.unparsed("defense-secondary", inputLabel(spell, StaffCastInput.QUICK_SECONDARY)),
    )

    private fun useInstructions(message: String, spell: StaffSpell) =
        config.component("messages.$message", controlPlaceholders(spell)).decoration(TextDecoration.ITALIC, false)

    fun defense(spell: StaffSpell, active: Boolean, seconds: Double, allAround: Boolean = false): Component {
        val message = when {
            active -> "defense-active"
            allAround -> "shield-cooldown"
            else -> "defense-cooldown"
        }
        val ability = if (allAround) "quick-secondary-name" else "quick-name"
        return config.component("messages.$message", TagResolver.resolver(
            Placeholder.component("ability", abilityName(spell, ability)),
            Placeholder.unparsed("seconds", String.format(Locale.ROOT, "%.1f", seconds)),
            Placeholder.component("input", inputComponent(spell,
                StaffCastInput.of(secondary = allAround, quick = true))),
        ))
    }

    fun shield(spell: StaffSpell, remaining: Double, capacity: Double): Component = config.component(
        "messages.shield-active", TagResolver.resolver(
            Placeholder.component("ability", abilityName(spell, "quick-secondary-name")),
            Placeholder.unparsed("remaining", String.format(Locale.ROOT, "%.1f", remaining)),
            Placeholder.unparsed("capacity", String.format(Locale.ROOT, "%.0f", capacity)),
        ))

    fun item(spell: StaffSpell) = buildItem(spell, duelOnly = false)

    /** Duel items deliberately use a stable native base, independent of optional ItemsAdder startup timing. */
    fun duelItem(spell: StaffSpell) = buildItem(spell, duelOnly = true)

    /** Refresh only ARC's known prototype lore block, preserving item model, enchantments, and extra lore. */
    internal fun refreshPrototypeLore(item: ItemStack, spell: StaffSpell): Boolean {
        if (item.type.isAir) return false
        val meta = item.itemMeta ?: return false
        if (meta.persistentDataContainer.get(StaffSpell.itemKey, PersistentDataType.STRING) != spell.id) return false
        val currentLore = meta.lore().orEmpty()
        val refreshedLore = prototypeLore(spell, StaffSpell.isDuelOnly(item)) + currentLore.drop(STAFF_PROTOTYPE_LORE_LINES)
        if (currentLore == refreshedLore) return false
        meta.lore(refreshedLore)
        item.itemMeta = meta
        return true
    }

    /** Elite items keep their material and native data; only ARC's configured appearance is copied. */
    internal fun applyEliteStaffAppearance(item: ItemStack, spell: StaffSpell) {
        val skin = try {
            configuredSkin(spell)
        } catch (_: StaffSpellSkinUnavailableException) {
            null
        }
        if (skin != null) applyEliteSkin(item, skin)
        item.editMeta { meta -> meta.displayName(staffName(spell)) }
        ensureStaffChargeUseComponent(item, spell)
    }

    /** Ability rows for migrated EliteMobs weapons deliberately omit prototype and duel-only labels. */
    internal fun eliteStaffLore(spell: StaffSpell): List<Component> = listOf(
        Component.empty(),
        spellComponent(spell, "description"),
        spellComponent(spell, "aim"),
        Component.empty(),
        spellComponent(spell, "secondary"),
        spellComponent(spell, "quick"),
        spellComponent(spell, "quick-secondary"),
        hotkeyLore(spell, StaffHotkey.Q),
        hotkeyLore(spell, StaffHotkey.F),
        useInstructions(useMessage(spell), spell),
    )

    private fun staffName(spell: StaffSpell) = spellComponent(spell, "name")

    private fun prototypeLore(spell: StaffSpell, duelOnly: Boolean): List<Component> = listOf(
        Component.empty(),
        spellComponent(spell, "description"),
        spellComponent(spell, "aim"),
        hotkeyLore(spell, StaffHotkey.Q),
        spellComponent(spell, "secondary"),
        spellComponent(spell, "quick"),
        spellComponent(spell, "quick-secondary"),
        text(if (duelOnly) "duel-only" else "prototype"),
        hotkeyLore(spell, StaffHotkey.F),
        useInstructions(useMessage(spell), spell),
    )

    private fun spellComponent(spell: StaffSpell, suffix: String) =
        config.component("${spell.id}.$suffix", controlPlaceholders(spell)).decoration(TextDecoration.ITALIC, false)

    private fun useMessage(spell: StaffSpell) = when (spell) {
        StaffSpell.INFERNO, StaffSpell.GRAVITY -> "channel-use"
        StaffSpell.EMBER, StaffSpell.ICICLE -> "charge-use"
        else -> "use"
    }

    private data class DuelAppearance(val itemModel: Key?, val customModelData: Int)

    private fun duelAppearance(spell: StaffSpell): DuelAppearance {
        val path = "${spell.id}.duel-appearance"
        val itemModel = config.stringOrNull("$path.item-model")?.trim().orEmpty()
        val rawCustomModelData = config.stringOrNull("$path.custom-model-data")
        val customModelData = when {
            rawCustomModelData == null -> 0
            else -> rawCustomModelData.trim().toIntOrNull()
                ?: throw IllegalArgumentException("$path.custom-model-data must be an integer")
        }
        require(customModelData >= 0) { "$path.custom-model-data must be non-negative" }
        require((itemModel.isEmpty() && customModelData == 0) || (itemModel.isNotEmpty() && customModelData > 0)) {
            "$path.item-model and $path.custom-model-data must be configured together"
        }

        val key = if (itemModel.isEmpty()) null else {
            val parsed = NamespacedKey.fromString(itemModel)
            require(parsed != null && parsed.toString() == itemModel) {
                "$path.item-model must be a fully namespaced key"
            }
            Key.key(parsed.namespace, parsed.key)
        }
        return DuelAppearance(key, customModelData)
    }

    private fun configuredSkin(spell: StaffSpell): ItemStack? {
        val skinId = config.string("${spell.id}.skin", "").trim()
        if (skinId.isEmpty()) return null
        return try {
            check(Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) { "ItemsAdder is not enabled" }
            CustomStack.getInstance(skinId)?.itemStack?.clone()
                ?: error("ItemsAdder item is not registered")
        } catch (failure: Exception) {
            if (unavailableStaffSkins.add(skinId)) {
                ArcLogging.warn("StaffSpells: configured skin {} for spell {} cannot be loaded", skinId, spell.id, failure)
            }
            throw StaffSpellSkinUnavailableException(skinId, failure)
        }
    }

    private fun buildItem(spell: StaffSpell, duelOnly: Boolean) = ItemStack(Material.BLAZE_ROD).apply {
        if (!duelOnly) {
            configuredSkin(spell)?.let { applyEliteSkin(this, it) }
        } else {
            val appearance = duelAppearance(spell)
            appearance.itemModel?.let { model ->
                setData(DataComponentTypes.ITEM_MODEL, model)
                setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                    CustomModelData.customModelData().addFloat(appearance.customModelData.toFloat()).build())
            }
        }
        editMeta { meta ->
            meta.displayName(staffName(spell))
            meta.lore(prototypeLore(spell, duelOnly))
            meta.persistentDataContainer.set(StaffSpell.itemKey, PersistentDataType.STRING, spell.id)
            if (duelOnly) meta.persistentDataContainer.set(StaffSpell.duelOnlyKey, PersistentDataType.BYTE, 1)
            meta.setMaxStackSize(1)
        }
        ensureStaffChargeUseComponent(this, spell)
    }
}

/** Cosine ranks the reticle first; distance only breaks ties. No projectile lead is needed. */
internal fun staffAimScore(dx: Double, dy: Double, dz: Double,
    lookX: Double, lookY: Double, lookZ: Double, range: Double, halfAngle: Double): Double? {
    val distanceSquared = dx * dx + dy * dy + dz * dz
    if (!distanceSquared.isFinite() || distanceSquared > range * range) return null
    if (distanceSquared < 0.0001) return 1.0
    val dot = (dx * lookX + dy * lookY + dz * lookZ) / kotlin.math.sqrt(distanceSquared)
    return dot.takeIf { it.isFinite() && it >= cos(Math.toRadians(halfAngle)) }
}

internal fun staffCooldownProgress(remaining: Long, total: Long): Float =
    (1.0 - remaining.coerceAtLeast(0).toDouble() / total.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()
