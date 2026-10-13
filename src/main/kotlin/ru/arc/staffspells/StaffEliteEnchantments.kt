package ru.arc.staffspells

import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.freeminecraftmodels.magmacore.enchantments.EnchantmentDefinitions
import com.magmaguy.freeminecraftmodels.magmacore.enchantments.EnchantmentItems
import com.magmaguy.freeminecraftmodels.magmacore.enchantments.EnchantmentQueries
import com.magmaguy.freeminecraftmodels.magmacore.scripting.ScriptHook
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityCombustByEntityEvent
import org.bukkit.inventory.ItemStack
import kotlin.math.ceil

/** Native FMM numeric queries are captured once; provider reloads cannot change an in-flight cast. */
internal data class StaffEliteEnchantments(
    val damageMultiplier: Double = 1.0,
    val radiusMultiplier: Double = 1.0,
    val ignitionTicks: Int = 0,
) {
    fun ignite(player: Player, target: LivingEntity) {
        if (ignitionTicks <= 0 || target.isDead || !target.isValid) return
        val event = EntityCombustByEntityEvent(player, target, ceil(ignitionTicks / 20.0).toInt())
        Bukkit.getPluginManager().callEvent(event)
        if (!event.isCancelled) target.fireTicks = maxOf(target.fireTicks, ceil(event.duration * 20.0).toInt())
    }

    companion object {
        val NONE = StaffEliteEnchantments()

        fun capture(player: Player, item: ItemStack, skill: SkillType): StaffEliteEnchantments {
            val wand = skill == SkillType.WANDS
            val attack = if (wand) "WAND_MISSILE" else "STAFF_FIREBALL"
            val hooks = if (wand) listOf("on_missile_count", "on_missile_damage_factor")
                else listOf("on_blast_radius_factor", "on_ignition_ticks")
            val queries = EnchantmentItems.inspectCustom(item.itemMeta).toSortedMap().flatMap { (id, level) ->
                val resolved = requireNotNull(EnchantmentDefinitions.resolve(id)) { "Missing enchantment provider: $id" }
                require(resolved.available()) { "Unavailable enchantment: $id" }
                val kinds = resolved.definition().attackKinds()
                if ((kinds.isNotEmpty() && attack !in kinds) ||
                    EnchantmentQueries.CAPABILITY !in resolved.provider().capabilities()) emptyList()
                else hooks.map { hook -> EnchantmentQueries.Query(resolved.provider(), id, ScriptHook(hook),
                    level, mapOf<String, Any>("attack_kind" to attack), player.uniqueId, null) }
            }
            if (queries.isEmpty()) return NONE
            val result = EnchantmentQueries.evaluate(queries, EnchantmentQueries.Limits(128, 50_000_000, 250_000))
            require(result.status() == EnchantmentQueries.Status.OK) { "Staff enchantment query failed: ${result.status()}" }
            return fromContributions(result.contributions().map { it.hook() to it.value() })
        }

        /** Multicast combines its native missile damage budget into the new spell's complete volley. */
        internal fun fromContributions(values: List<Pair<String, Double?>>): StaffEliteEnchantments {
            var missiles: Int? = null
            var damage = 1.0
            var radius = 1.0
            var ignition = 0
            for ((hook, value) in values) {
                if (value == null) continue
                require(value.isFinite() && value >= 0) { "Invalid staff enchantment value" }
                when (hook) {
                    "on_missile_count" -> {
                        require(missiles == null && value >= 1 && value <= Int.MAX_VALUE && value % 1.0 == 0.0)
                        missiles = value.toInt()
                    }
                    "on_missile_damage_factor" -> { require(value > 0); damage *= value }
                    "on_blast_radius_factor" -> { require(value > 0); radius *= value }
                    "on_ignition_ticks" -> {
                        require(value <= Int.MAX_VALUE && value % 1.0 == 0.0)
                        ignition = maxOf(ignition, value.toInt())
                    }
                }
            }
            damage *= missiles ?: 1
            require(damage.isFinite() && radius.isFinite())
            return StaffEliteEnchantments(damage, radius, ignition)
        }
    }
}
