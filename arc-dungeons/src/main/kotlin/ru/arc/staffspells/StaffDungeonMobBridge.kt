package ru.arc.staffspells

import com.magmaguy.elitemobs.api.EliteMobSpawnEvent
import com.magmaguy.elitemobs.combatsystem.CombatDamageContext
import com.magmaguy.elitemobs.dungeons.EMPackage
import com.magmaguy.elitemobs.dungeons.WorldDungeonPackage
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.mobconstructor.EliteEntity
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.mobconstructor.custombosses.InstancedBossEntity
import com.magmaguy.elitemobs.mobconstructor.custombosses.RegionalBossEntity
import org.bukkit.Bukkit
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import ru.arc.core.LifecycleTaskScope
import ru.arc.dungeons.ArcDungeonsPlugin
import java.util.UUID
import java.util.Collections
import java.util.WeakHashMap

/** Loaded only after the optional EliteMobs API has been resolved. */
internal class StaffDungeonMobBridge(private val accept: (StaffDungeonMob) -> Unit) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val pending = mutableSetOf<UUID>()
    private val considered = Collections.newSetFromMap(WeakHashMap<EliteEntity, Boolean>())
    private var closed = false

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, ArcDungeonsPlugin.instance)
        // Existing open-dungeon actors may predate ARC listeners; never scan Bukkit worlds.
        tasks.runLater(2) {
            if (!closed) staffDungeonBoundary(Unit) { adoptExisting() }
        }
    }

    private fun adoptExisting() {
        // The native registry is mutable: retain IDs, never its iterator, between ticks.
        val ids = EntityTracker.getEliteMobEntities().keys.toList()
        var next = 0
        var candidates = 0
        fun batch() {
            if (closed) return
            staffDungeonBoundary(Unit) {
                val current = EntityTracker.getEliteMobEntities()
                val end = minOf(next + 32, ids.size)
                while (!closed && next < end && candidates < 512) {
                    val elite = current[ids[next++]] ?: continue
                    val actor = resolveStaffDungeonMob(elite) ?: continue
                    candidates++
                    acceptOnce(elite, actor)
                }
                if (!closed && next < ids.size && candidates < 512) tasks.runLater(1) { batch() }
            }
        }
        batch()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpawn(event: EliteMobSpawnEvent) = queue(event.eliteMobEntity) { !event.isCancelled }

    private fun queue(elite: EliteEntity, outcome: () -> Boolean = { true }) {
        if (closed || pending.size >= 512) return
        val body = elite.livingEntity as? Mob ?: return
        val id = body.uniqueId
        if (!pending.add(id)) return
        tasks.runLater(2) {
            pending.remove(id)
            if (closed || !outcome()) return@runLater
            staffDungeonBoundary(Unit) {
                if (elite.livingEntity !== body || EntityTracker.getEliteMobEntity(body) !== elite) return@staffDungeonBoundary
                resolveStaffDungeonMob(elite)?.let { acceptOnce(elite, it) }
            }
        }
    }

    private fun acceptOnce(elite: EliteEntity, actor: StaffDungeonMob) {
        // A spawn event and startup adoption must share one chance roll per native actor.
        if (considered.add(elite)) accept(actor)
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        tasks.close()
        pending.clear()
        considered.clear()
    }
}

internal fun resolveStaffDungeonMob(elite: EliteEntity): StaffDungeonMob? {
    val boss = elite as? CustomBossEntity ?: return null
    val body = boss.livingEntity as? Mob ?: return null
    if (boss.isReinforcementOrMount || boss.customBossesConfigFields.isReinforcement) return null
    val currentScope: () -> Boolean
    val permits: (Player) -> Boolean
    if (boss is InstancedBossEntity) {
        val instance = boss.dungeonInstance ?: return null
        currentScope = {
            !instance.isCancelled && instance.world == body.world &&
                instance in DungeonInstance.getDungeonInstances() && instance.state.name in setOf("WAITING", "ONGOING")
        }
        permits = { player -> instance.state.name == "ONGOING" && player in instance.players && player !in boss.lockoutPlayers }
    } else {
        if (boss !is RegionalBossEntity) return null
        val content = boss.emPackage as? WorldDungeonPackage ?: return null
        currentScope = {
            content.isInstalled && content.contentPackagesConfigFields.isEnabled &&
                content.contentPackagesConfigFields.contentType.name == "OPEN_DUNGEON" &&
                EMPackage.getEmPackages().values.any { it === content } &&
                content.customBossEntityList.any { it === boss } &&
                content.world == body.world
        }
        permits = { true }
    }
    val actor = object : StaffDungeonMob {
        override val body = body
        override fun isCurrent() = Bukkit.getPluginManager().isPluginEnabled("EliteMobs") &&
            boss.livingEntity === body && EntityTracker.getEliteMobEntity(body) === boss &&
            !boss.isDying && currentScope()

        override fun currentTarget(): Player? = if (boss.isAIActive && !boss.isTriggeredAntiExploit &&
            !boss.isInAntiExploitCooldown && !boss.isInCombatGracePeriod) body.target as? Player else null

        override fun permitsTarget(player: Player) = isCurrent() && permits(player)

        override fun damage(player: Player, multiplier: Double): Boolean {
            if (!permitsTarget(player) || currentTarget()?.uniqueId != player.uniqueId) return false
            val before = player.health + player.absorptionAmount
            CombatDamageContext.runEliteToPlayerMultiplier(multiplier) {
                player.damage(1.0, DamageSource.builder(DamageType.MAGIC)
                    .withDirectEntity(body).withCausingEntity(body).build())
            }
            return before - player.health - player.absorptionAmount > 1.0e-6
        }
    }
    return actor.takeIf { it.isCurrent() }
}
