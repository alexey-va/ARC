package ru.arc.staffspells

import dev.lone.itemsadder.api.CustomStack
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.Component
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
    CHAIN("chain"), MARK("mark"), FROST("frost"), LANCE("lance"), EMBER("ember"), NOVA("nova");

    companion object {
        val itemKey = NamespacedKey("arc", "staff_spell_prototype")
        fun from(item: ItemStack?) = item?.itemMeta?.persistentDataContainer
            ?.get(itemKey, PersistentDataType.STRING)?.let { id -> entries.find { it.id == id } }
    }
}

internal data class StaffSpellTuning(val power: Double, val vanillaDamage: Double, val cooldownTicks: Long)

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
    val novaRadius: Double = 6.0,
)

/** Values are read again on ARC reload; the controller owns one validated settings generation. */
internal open class StaffSpellConfig(private val config: Config) {
    open val settings get() = StaffSpellSettings(
        number("targeting.range", 24.0, 2.0..32.0),
        number("targeting.half-angle-degrees", 12.0, 1.0..30.0),
        number("chain.radius", 6.0, 1.0..10.0),
        config.integer("chain.targets", 4).coerceIn(1, 8),
        number("chain.decay", 0.7, 0.1..1.0),
        config.integer("mark.delay-ticks", 18).coerceIn(4, 80),
        number("mark.radius", 3.5, 1.0..8.0),
        number("frost.range", 7.0, 2.0..12.0),
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
            }
            StaffSpellTuning(number("${spell.id}.power", power, 0.1..5.0),
                number("${spell.id}.vanilla-damage", damage, 0.1..40.0),
                config.integer("${spell.id}.cooldown-ticks", ticks).coerceIn(4, 200).toLong())
        },
        number("lance.width", 0.22, 0.0..0.6),
        config.integer("lance.targets", 3).coerceIn(1, 8),
        number("ember.speed", 1.2, 0.5..3.0),
        number("ember.radius", 2.8, 1.0..6.0),
        number("nova.radius", 6.0, 2.0..10.0),
    )

    private fun number(key: String, fallback: Double, bounds: ClosedFloatingPointRange<Double>) =
        config.real(key, fallback).takeIf(Double::isFinite)?.coerceIn(bounds) ?: fallback

    fun text(key: String) = config.component("messages.$key", TagResolver.empty()).decoration(TextDecoration.ITALIC, false)

    fun item(spell: StaffSpell) = ItemStack(Material.BLAZE_ROD).apply {
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
        editMeta { meta ->
            meta.displayName(config.component("${spell.id}.name", TagResolver.empty()).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(Component.empty(),
                config.component("${spell.id}.description", TagResolver.empty()).decoration(TextDecoration.ITALIC, false),
                config.component("${spell.id}.aim", TagResolver.empty()).decoration(TextDecoration.ITALIC, false),
                text("prototype"), Component.empty(), text("use")))
            meta.persistentDataContainer.set(StaffSpell.itemKey, PersistentDataType.STRING, spell.id)
            meta.setMaxStackSize(1)
        }
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
