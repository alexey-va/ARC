package ru.arc.graves

import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Wither
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.PlayerDeathEvent
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.InputStream
import java.util.UUID

internal enum class GraveDeathKind {
    FALL, LAVA, FIRE, DROWNING, EXPLOSION, PROJECTILE, MOB, PVP, VOID, SUFFOCATION,
    STARVATION, FREEZING, MAGIC, LIGHTNING, THORNS, FALLING_BLOCK, ELYTRA, WITHER, DRAGON, GENERIC;

    companion object {
        fun of(event: PlayerDeathEvent): GraveDeathKind {
            val damage = event.entity.lastDamageCause ?: return GENERIC
            val damager = (damage as? EntityDamageByEntityEvent)?.damager
            val attacker = event.damageSource.causingEntity ?: if (damager is Projectile) damager.shooter else damager
            // Specific environmental causes remain honest even when another player caused them.
            return when (damage.cause) {
                DamageCause.FALL -> FALL
                DamageCause.LAVA -> LAVA
                DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.HOT_FLOOR, DamageCause.CAMPFIRE -> FIRE
                DamageCause.DROWNING -> DROWNING
                DamageCause.BLOCK_EXPLOSION, DamageCause.ENTITY_EXPLOSION -> EXPLOSION
                DamageCause.VOID -> VOID
                DamageCause.SUFFOCATION, DamageCause.CRAMMING -> SUFFOCATION
                DamageCause.STARVATION -> STARVATION
                DamageCause.FREEZE -> FREEZING
                DamageCause.LIGHTNING -> LIGHTNING
                DamageCause.THORNS -> THORNS
                DamageCause.FALLING_BLOCK -> FALLING_BLOCK
                DamageCause.FLY_INTO_WALL -> ELYTRA
                DamageCause.WITHER -> WITHER
                else -> when {
                    attacker is Player -> PVP
                    attacker is EnderDragon || damage.cause == DamageCause.DRAGON_BREATH -> DRAGON
                    attacker is Wither -> WITHER
                    damage.cause == DamageCause.PROJECTILE -> PROJECTILE
                    damage.cause in setOf(DamageCause.MAGIC, DamageCause.POISON) -> MAGIC
                    damage.cause in setOf(DamageCause.ENTITY_ATTACK, DamageCause.ENTITY_SWEEP_ATTACK, DamageCause.SONIC_BOOM) -> MOB
                    else -> GENERIC
                }
            }
        }
    }
}

/** Bundled, validated once at runtime construction; death selection does no I/O or parsing. */
internal class GraveEpitaphs private constructor(private val pools: Map<GraveDeathKind, List<String>>) {
    fun choose(id: UUID, cause: GraveDeathKind): String {
        val pool = pools.getValue(cause)
        val seed = UUID.nameUUIDFromBytes("arc:epitaph:$cause:$id".toByteArray(Charsets.UTF_8))
        return pool[((seed.mostSignificantBits ushr 1) % pool.size).toInt()]
    }

    companion object {
        fun load(resource: (String) -> InputStream?): GraveEpitaphs {
            val raw = requireNotNull(resource("graves/epitaphs.yml")) { "Grave epitaph catalog is missing" }.use {
                Load(LoadSettings.builder().setCodePointLimit(64_000).build()).loadFromInputStream(it) as Map<*, *>
            }
            val pools = GraveDeathKind.entries.associateWith { cause ->
                val lines = (raw[cause.name] as? List<*>)?.map { it as String }.orEmpty()
                require(lines.isNotEmpty() && lines.size <= 128 && lines.all { it.isNotBlank() && GravePreferences.validText(it) }) {
                    "Invalid grave epitaph pool: $cause"
                }
                lines.distinct()
            }
            return GraveEpitaphs(pools)
        }
    }
}
