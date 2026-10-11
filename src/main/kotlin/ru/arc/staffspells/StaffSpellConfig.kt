package ru.arc.staffspells

import dev.lone.itemsadder.api.CustomStack
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import java.util.Locale
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.config.Config
import ru.arc.eliteloot.applyEliteSkin
import ru.arc.util.Logging.warn
import kotlin.math.cos

internal enum class StaffSpell(val id: String) {
    CHAIN("chain"), MARK("mark"), FROST("frost"), LANCE("lance"), EMBER("ember"), NOVA("nova"),
    ICICLE("icicle"), FAULT("fault"), LUNAR("lunar"), GALE("gale"), TIDE("tide"),
    REQUIEM("requiem"), PRISM("prism"), NECROMANCER("necromancer"),
    INFERNO("inferno"), GRAVITY("gravity"), OSSUARY("ossuary");

    companion object {
        val itemKey = NamespacedKey("arc", "staff_spell_prototype")
        val duelOnlyKey = NamespacedKey("arc", "staff_spell_duel_only")
        fun from(item: ItemStack?) = item?.itemMeta?.persistentDataContainer
            ?.get(itemKey, PersistentDataType.STRING)?.let { id -> entries.find { it.id == id } }

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

internal data class StaffSpellTuning(val power: Double, val vanillaDamage: Double, val cooldownTicks: Long)

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
) {
    fun tuningFor(spell: StaffSpell, duel: Boolean): StaffSpellTuning =
        (if (duel) duelTuning[spell] else null) ?: tuning.getValue(spell)

    fun fingerprint(pluginVersion: String): String {
        val canonical = buildString {
            append("staff-duel-damage-v1\narc=").append(pluginVersion).append('\n')
            append("range=").append(range.hex()).append('\n')
            append("aimDegrees=").append(aimDegrees.hex()).append('\n')
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

/** Values are read again on ARC reload; the controller owns one validated settings generation. */
internal open class StaffSpellConfig(private val config: Config) {
    open val settings get() = StaffSpellSettings(
        number("targeting.range", 48.0, 2.0..64.0),
        number("targeting.half-angle-degrees", 20.0, 1.0..30.0),
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
    )

    private fun number(key: String, fallback: Double, bounds: ClosedFloatingPointRange<Double>) =
        config.real(key, fallback).takeIf(Double::isFinite)?.coerceIn(bounds) ?: fallback

    fun text(key: String) = config.component("messages.$key", TagResolver.empty()).decoration(TextDecoration.ITALIC, false)

    fun cooldown(spell: StaffSpell, secondary: Boolean, remaining: Long, total: Long, input: StaffCastInput = StaffCastInput.of(secondary, false)): Component {
        val resolvers = TagResolver.resolver(
            Placeholder.component("ability", config.component("${spell.id}.${when (input) { StaffCastInput.PRIMARY -> "name"; StaffCastInput.SECONDARY -> "secondary-name"; StaffCastInput.QUICK -> "quick-name"; StaffCastInput.QUICK_SECONDARY -> "quick-secondary-name" }}", TagResolver.empty())),
            Placeholder.unparsed("seconds", String.format(Locale.ROOT, "%.1f", remaining.coerceAtLeast(0) / 20.0)),
            Placeholder.unparsed("input", when (input) { StaffCastInput.PRIMARY -> "ПКМ"; StaffCastInput.SECONDARY -> "Shift + ПКМ"; StaffCastInput.QUICK -> "ЛКМ"; StaffCastInput.QUICK_SECONDARY -> "Shift + ЛКМ" }),
        )
        return config.component("messages.${if (remaining > 0) "cooldown-bar" else "ready-bar"}", resolvers)
    }

    fun charge(spell: StaffSpell, progress: Double, secondary: Boolean = false): Component = config.component("messages.charge-bar", TagResolver.resolver(
        Placeholder.component("ability", config.component("${spell.id}.${if (secondary) "secondary-name" else "name"}", TagResolver.empty())),
        Placeholder.unparsed("percent", (progress.coerceIn(0.0, 1.0) * 100).toInt().toString()),
    ))

    fun defense(spell: StaffSpell, active: Boolean, seconds: Double): Component = config.component(
        "messages.${if (active) "defense-active" else "defense-cooldown"}", TagResolver.resolver(
            Placeholder.component("ability", config.component("${spell.id}.quick-name", TagResolver.empty())),
            Placeholder.unparsed("seconds", String.format(Locale.ROOT, "%.1f", seconds)),
        ))

    fun shield(spell: StaffSpell, remaining: Double, capacity: Double): Component = config.component(
        "messages.shield-active", TagResolver.resolver(
            Placeholder.component("ability", config.component("${spell.id}.quick-secondary-name", TagResolver.empty())),
            Placeholder.unparsed("remaining", String.format(Locale.ROOT, "%.1f", remaining)),
            Placeholder.unparsed("capacity", String.format(Locale.ROOT, "%.0f", capacity)),
        ))

    fun item(spell: StaffSpell) = buildItem(spell, duelOnly = false)

    /** Duel items deliberately use a stable native base, independent of optional ItemsAdder startup timing. */
    fun duelItem(spell: StaffSpell) = buildItem(spell, duelOnly = true)

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

    private fun buildItem(spell: StaffSpell, duelOnly: Boolean) = ItemStack(Material.BLAZE_ROD).apply {
        if (!duelOnly) {
            val skinId = config.string("${spell.id}.skin", "").trim()
            if (skinId.isNotEmpty()) {
                val skin = try {
                    check(Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) { "ItemsAdder is not enabled" }
                    CustomStack.getInstance(skinId)?.itemStack?.clone()
                        ?: error("ItemsAdder item is not registered")
                } catch (failure: Exception) {
                    if (unavailableStaffSkins.add(skinId)) {
                        warn("StaffSpells: configured skin {} for spell {} cannot be loaded", skinId, spell.id, failure)
                    }
                    throw StaffSpellSkinUnavailableException(skinId, failure)
                }
                applyEliteSkin(this, skin)
            }
        } else {
            val appearance = duelAppearance(spell)
            appearance.itemModel?.let { model ->
                setData(DataComponentTypes.ITEM_MODEL, model)
                setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                    CustomModelData.customModelData().addFloat(appearance.customModelData.toFloat()).build())
            }
        }
        editMeta { meta ->
            meta.displayName(config.component("${spell.id}.name", TagResolver.empty()).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(Component.empty(),
                config.component("${spell.id}.description", TagResolver.empty()).decoration(TextDecoration.ITALIC, false),
                config.component("${spell.id}.aim", TagResolver.empty()).decoration(TextDecoration.ITALIC, false),
                Component.empty(), config.component("${spell.id}.secondary", TagResolver.empty()).decoration(TextDecoration.ITALIC, false),
                config.component("${spell.id}.quick", TagResolver.empty()).decoration(TextDecoration.ITALIC, false),
                config.component("${spell.id}.quick-secondary", TagResolver.empty()).decoration(TextDecoration.ITALIC, false),
                text(if (duelOnly) "duel-only" else "prototype"), Component.empty(),
                text(if (spell in setOf(StaffSpell.INFERNO, StaffSpell.GRAVITY)) "channel-use" else "use")))
            meta.persistentDataContainer.set(StaffSpell.itemKey, PersistentDataType.STRING, spell.id)
            if (duelOnly) meta.persistentDataContainer.set(StaffSpell.duelOnlyKey, PersistentDataType.BYTE, 1)
            meta.setMaxStackSize(1)
        }
        configureStaffUse(this)
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
