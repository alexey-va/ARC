package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.particle.PaperViewerParticles
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** A bounded extra attack for native EliteMobs dungeon actors; native AI owns every target. */
internal class StaffMobCasters(
    private val config: StaffSpellConfig,
    private val settings: StaffMobCasterSettings,
    private val effects: StaffSpellDisplayEffects,
    particles: PaperViewerParticles,
) : Listener, AutoCloseable {
    private class Caster(val actor: StaffDungeonMob, val spell: StaffSpell,
        val equipmentVisual: StaffMobEquipmentVisuals, var readyAt: Long) {
        val mob get() = actor.body
        var cast: Cast? = null
    }

    private class Cast(val targetId: UUID, val aim: Location, var position: Location,
        var direction: Vector, var visualId: UUID, val preparationTicks: Int) {
        var age = 0
        var flying = false
        var travelled = 0.0
        var length = 0.0
        var trail = position.clone()
    }

    private val tasks = LifecycleTaskScope()
    // The parent owns the particle transport; this owner contributes only cancellable visual phases.
    private val visuals = StaffSpellVisuals(tasks, particles)
    private val casters = linkedMapOf<UUID, Caster>()
    private var dungeonBridge: AutoCloseable? = null
    private var clock = 0L
    private var started = false
    private var closed = false

    fun start() {
        check(!started && !closed) { "Mob staff controller already started or closed" }
        started = true
        if (!settings.enabled) return
        tasks.runTimer(1, 1) { tick() }
        val plugin = Bukkit.getPluginManager().getPlugin("EliteMobs")?.takeIf { it.isEnabled } ?: return
        dungeonBridge = staffDungeonBoundary<AutoCloseable?>(null) {
            Class.forName("com.magmaguy.elitemobs.api.EliteMobSpawnEvent", false, plugin.javaClass.classLoader)
            StaffDungeonMobBridge(::enrollFromDungeon).also { bridge ->
                try { bridge.start() } catch (failure: RuntimeException) { bridge.close(); throw failure }
                catch (failure: LinkageError) { bridge.close(); throw failure }
            }
        }
    }

    internal fun enrollFromDungeon(actor: StaffDungeonMob) {
        val mob = actor.body
        if (closed || !settings.enabled || settings.spawnChancePercent <= 0.0 ||
            casters.size >= settings.maxCasters.coerceAtMost(32) || mob.uniqueId in casters ||
            !inWorld(mob) || !staffMobOwnerAllowed(actor) ||
            ThreadLocalRandom.current().nextDouble(100.0) >= settings.spawnChancePercent) return
        staffDungeonBoundary(Unit) {
            val spell = SPELLS.random()
            val equipment = StaffMobEquipmentVisuals(mob, config.mobItem(spell), effects)
            val caster = Caster(actor, spell, equipment, clock + 20)
            if (updateEquipment(caster)) casters[mob.uniqueId] = caster
            else staffDungeonBoundary(Unit) { equipment.close() }
        }
    }

    private fun updateEquipment(caster: Caster): Boolean = staffDungeonBoundary(false) {
        if (!caster.equipmentVisual.isValid) return@staffDungeonBoundary false
        caster.equipmentVisual.update(caster.mob, caster.actor.currentTarget())
        true
    }

    private fun tick() {
        if (closed) return
        clock++
        casters.values.toList().forEach { caster ->
            val mob = caster.mob
            if (!staffMobOwnerAllowed(caster.actor) || !inWorld(mob) ||
                (clock % 2 == 0L && !updateEquipment(caster))) {
                forget(mob.uniqueId)
                return@forEach
            }
            val cast = caster.cast
            if (cast == null) {
                if (clock < caster.readyAt || casters.values.count { it.cast != null } >= MAX_ACTIVE_CASTS) return@forEach
                val target = staffDungeonBoundary<Player?>(null) { caster.actor.currentTarget() } ?: return@forEach
                if (staffMobTargetAllowed(caster.actor, target, settings.range)) begin(caster, target)
            } else {
                val target = Bukkit.getPlayer(cast.targetId)
                if (target == null || !staffMobTargetAllowed(caster.actor, target, settings.range) ||
                    !effects.isActive(cast.visualId) || !effects.hasAudience(cast.visualId, cast.targetId)) {
                    cancelCast(caster)
                    return@forEach
                }
                cast.age++
                if (cast.flying) flight(caster, cast, target) else prepare(caster, cast, target)
            }
        }
    }

    private fun begin(caster: Caster, target: Player) {
        val mob = caster.mob
        val aim = if (caster.spell == StaffSpell.MARK) groundAt(target) else staffVisibleTargetPoint(mob.eyeLocation, target)
        if (aim == null) return
        val direction = aim.toVector().subtract(mob.eyeLocation.toVector())
        if (direction.lengthSquared() < .01) return
        direction.normalize()
        val muzzle = muzzle(mob, direction)
        if (!visible(mob.eyeLocation, muzzle) || !visible(muzzle, aim)) return
        val preparation = if (caster.spell == StaffSpell.MARK) settings.markTelegraphTicks else settings.telegraphTicks
        val id = when (caster.spell) {
            StaffSpell.EMBER, StaffSpell.ICICLE -> {
                val eye = staffMobChargeEye(muzzle, direction, caster.spell)
                effects.play(mob.uniqueId, caster.spell, eye, eye.clone().add(direction),
                    durationTicks = preparation + 2, charging = true, controllerDriven = true,
                    charge = .01, chargeSlot = if (caster.spell == StaffSpell.ICICLE) 0 else null,
                    opportunistic = true)
            }
            StaffSpell.LANCE -> effects.custom(mob.uniqueId, caster.spell, muzzle, preparation + 2,
                ownerAttachedMotion = false, opportunistic = true) { age ->
                staffMobLancePreparation(age, preparation + 2, direction)
            }
            else -> effects.play(mob.uniqueId, StaffSpell.MARK, aim, radius = MARK_RADIUS,
                durationTicks = preparation + 2, controllerDriven = true, opportunistic = true)
        }
        caster.readyAt = clock + 20 // Back off when the shared renderer has no spare capacity.
        if (id == null || !effects.isActive(id)) return
        if (!effects.hasAudience(id, target.uniqueId)) { effects.remove(id); return }
        caster.cast = Cast(target.uniqueId, aim.clone(), muzzle, direction, id, preparation)
        visuals.withOwner(mob.uniqueId) {
            visuals.cue(muzzle, Sound.ENTITY_EVOKER_PREPARE_ATTACK, .5f, 1.3f)
            if (caster.spell == StaffSpell.MARK) {
                visuals.markLaunch(muzzle, aim)
                visuals.particles(aim, staffMobMarkBoundary(0, preparation, MARK_RADIUS), clearance = .1)
            }
        }
    }

    private fun prepare(caster: Caster, cast: Cast, target: Player) {
        val mob = caster.mob
        val progress = (cast.age.toDouble() / cast.preparationTicks).coerceIn(0.0, 1.0)
        val direction = cast.aim.toVector().subtract(mob.eyeLocation.toVector())
        if (direction.lengthSquared() < .01) { cancelCast(caster); return }
        direction.normalize()
        val muzzle = muzzle(mob, direction)
        if (!visible(mob.eyeLocation, muzzle) || !visible(muzzle, cast.aim)) { cancelCast(caster); return }
        if (cast.age % StaffSpellDisplayEffects.FRAME_TICKS == 0 || cast.age >= cast.preparationTicks) {
            when (caster.spell) {
                StaffSpell.EMBER, StaffSpell.ICICLE -> {
                    effects.charge(cast.visualId, staffMobChargeEye(muzzle, direction, caster.spell),
                        if (caster.spell == StaffSpell.ICICLE) progress / 11.0 else progress * .55)
                    effects.synchronize(cast.visualId, cast.age)
                }
                StaffSpell.LANCE -> effects.updateCustom(cast.visualId, muzzle,
                    staffMobLancePreparation(cast.age.coerceAtMost(cast.preparationTicks - 1), cast.preparationTicks + 6, direction))
                else -> {
                    effects.synchronize(cast.visualId, cast.age)
                    visuals.withOwner(mob.uniqueId) {
                        visuals.markCharge(cast.aim, progress)
                        if (cast.age % 4 == 0) visuals.particles(cast.aim,
                            staffMobMarkBoundary(cast.age, cast.preparationTicks, MARK_RADIUS), clearance = .1)
                    }
                }
            }
        }
        if (cast.age < cast.preparationTicks || !effects.isActive(cast.visualId)) return
        if (caster.spell == StaffSpell.MARK) {
            val feet = target.location
            val dx = feet.x - cast.aim.x
            val dz = feet.z - cast.aim.z
            val hit = dx * dx + dz * dz <= MARK_RADIUS * MARK_RADIUS &&
                kotlin.math.abs(feet.y - cast.aim.y) <= 2.0 && visible(cast.aim, target)
            impact(caster, cast, target, cast.aim, hit)
            return
        }
        cast.position = if (caster.spell == StaffSpell.LANCE) muzzle else effects.chargeOrigin(cast.visualId) ?: muzzle
        cast.direction = cast.aim.toVector().subtract(cast.position.toVector()).normalize()
        cast.length = settings.range
        cast.trail = cast.position.clone()
        if (caster.spell == StaffSpell.LANCE) {
            val ray = staffBlockHit(cast.position, cast.direction, cast.length, ignoreVegetation = true)
            if (!ray.pathLoaded) { cancelCast(caster); return }
            cast.length = ray.hit?.hitPosition?.distance(cast.position.toVector())
                ?.let { (it - .04).coerceAtLeast(0.0) } ?: cast.length
            if (cast.length < .1) { cancelCast(caster); return }
            effects.remove(cast.visualId)
            val end = cast.position.clone().add(cast.direction.clone().multiply(cast.length))
            cast.visualId = effects.play(mob.uniqueId, StaffSpell.LANCE, cast.position, end,
                durationTicks = staffLanceDuration(cast.length), impact = true, opportunistic = true)
                ?: run { cancelCast(caster); return }
            visuals.withOwner(mob.uniqueId) { visuals.lance(cast.position, end) }
        } else {
            effects.releaseCharge(cast.visualId, 80, controllerDriven = true)
            effects.moveProjectile(cast.visualId, cast.position, cast.direction)
            effects.synchronize(cast.visualId, 0)
        }
        cast.age = 0
        cast.flying = true
        visuals.withOwner(mob.uniqueId) { visuals.release(cast.position, caster.spell, charged = true) }
    }

    private fun flight(caster: Caster, cast: Cast, target: Player) {
        // Match player LANCE's native display interpolation rather than damaging ahead of its front.
        val visualAge = cast.age - StaffSpellDisplayEffects.FRAME_TICKS
        if (caster.spell == StaffSpell.LANCE && visualAge < 4) return
        val distance = minOf(cast.length - cast.travelled, when (caster.spell) {
            StaffSpell.LANCE -> (staffLanceFront(visualAge, cast.length) - cast.travelled).coerceAtLeast(0.0)
            StaffSpell.ICICLE -> minOf(1.2, staffIcicleStep(cast.age))
            else -> staffEmberStep(cast.age, .5)
        })
        if (distance <= .0001) return
        val from = cast.position
        val ray = staffBlockHit(from, cast.direction, distance, ignoreVegetation = true)
        if (!ray.pathLoaded) { cancelCast(caster); return }
        val reach = ray.hit?.hitPosition?.distance(from.toVector())?.let { (it - .04).coerceAtLeast(0.0) } ?: distance
        val collision = if (reach > .0001) target.boundingBox.expand(.24)
            .rayTrace(from.toVector(), cast.direction, reach)?.hitPosition else null
        val end = collision?.toLocation(from.world) ?: from.clone().add(cast.direction.clone().multiply(reach))
        cast.travelled += from.distance(end)
        cast.position = end
        if (collision != null || ray.hit != null || cast.travelled >= cast.length - .001) {
            impact(caster, cast, target, end, collision != null && visible(from, target), blocked = ray.hit != null)
            return
        }
        if (cast.age % StaffSpellDisplayEffects.FRAME_TICKS == 0) {
            if (caster.spell != StaffSpell.LANCE) effects.moveProjectile(cast.visualId, end, cast.direction)
            effects.synchronize(cast.visualId, cast.age)
            visuals.withOwner(caster.mob.uniqueId) {
                when (caster.spell) {
                    StaffSpell.EMBER -> visuals.emberTrail(cast.trail, from)
                    StaffSpell.ICICLE -> visuals.icicleTrail(cast.trail, from)
                    else -> Unit
                }
            }
            cast.trail = from.clone()
        }
    }

    private fun impact(caster: Caster, cast: Cast, target: Player, at: Location, hit: Boolean, blocked: Boolean = false) {
        if (!effects.isActive(cast.visualId) || !effects.hasAudience(cast.visualId, target.uniqueId)) {
            cancelCast(caster)
            return
        }
        caster.cast = null
        caster.readyAt = clock + settings.recoveryTicks
        if (hit && staffMobTargetAllowed(caster.actor, target, settings.range)) staffMobHit(caster.actor, target, settings.difficulty)
        // A native damage listener may remove the actor or reload the entire module.
        if (closed || casters[caster.mob.uniqueId] !== caster) return
        if (caster.spell == StaffSpell.LANCE) {
            if (blocked) effects.remove(cast.visualId)
        } else effects.impact(cast.visualId, at, if (caster.spell == StaffSpell.MARK) MARK_RADIUS else 1.1, 14)
        visuals.withOwner(caster.mob.uniqueId) {
            when (caster.spell) {
                StaffSpell.EMBER -> visuals.emberBurst(at, 1.1)
                StaffSpell.ICICLE -> visuals.icicleBurst(at)
                StaffSpell.MARK -> visuals.markBurst(at, MARK_RADIUS)
                else -> visuals.cue(at, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, .45f, 1.6f)
            }
        }
    }

    private fun cancelCast(caster: Caster) {
        caster.cast = null
        caster.readyAt = clock + settings.recoveryTicks
        effects.cancel(caster.mob.uniqueId)
        visuals.cancel(caster.mob.uniqueId)
    }

    private fun forget(id: UUID) {
        val caster = casters.remove(id) ?: return
        cancelCast(caster)
        staffDungeonBoundary(Unit) { caster.equipmentVisual.close() }
    }

    private fun inWorld(mob: Mob) = settings.allowedWorlds.isEmpty() || mob.world.name in settings.allowedWorlds
    private fun muzzle(mob: Mob, direction: Vector) = mob.eyeLocation.add(0.0, -.12, 0.0).add(direction.clone().multiply(.75))

    private fun groundAt(target: Player): Location? {
        val from = target.location.add(0.0, .8, 0.0)
        val ray = staffBlockHit(from, Vector(0.0, -1.0, 0.0), 3.0)
        return if (ray.pathLoaded) ray.hit?.hitPosition?.toLocation(from.world)?.add(0.0, .06, 0.0) else null
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        forget(event.entity.uniqueId)
        event.drops.removeIf(::isStaff)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onItemSpawn(event: ItemSpawnEvent) {
        if (isStaff(event.entity.itemStack)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTransform(event: EntityTransformEvent) {
        forget(event.entity.uniqueId)
        event.transformedEntities.filterIsInstance<Mob>().forEach(::stripOrphan)
    }

    @EventHandler fun onUnload(event: EntitiesUnloadEvent) = event.entities.forEach { forget(it.uniqueId) }
    @EventHandler fun onLoad(event: EntitiesLoadEvent) = event.entities.filterIsInstance<Mob>().forEach(::stripOrphan)

    private fun stripOrphan(mob: Mob) {
        if (mob.uniqueId !in casters && mob.equipment?.itemInMainHand?.let(::isStaff) == true)
            mob.equipment?.setItemInMainHand(ItemStack(Material.AIR))
    }

    override fun close() {
        if (closed) return
        closed = true
        dungeonBridge?.close()
        dungeonBridge = null
        tasks.close()
        casters.keys.toList().forEach(::forget)
    }

    internal companion object {
        val staffKey = NamespacedKey("arc", "mob_staff")
        private val SPELLS = listOf(StaffSpell.EMBER, StaffSpell.ICICLE, StaffSpell.LANCE, StaffSpell.MARK)
        private const val MAX_ACTIVE_CASTS = 8
        private const val MARK_RADIUS = 1.6
        private fun isStaff(item: ItemStack) = item.hasItemMeta() &&
            item.itemMeta.persistentDataContainer.get(staffKey, PersistentDataType.STRING) in SPELLS.map { it.id }
    }
}
