package ru.arc.staffspells

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.command.CommandSender
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import ru.arc.ARC
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.util.CooldownManager
import java.util.UUID
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object StaffSpellsModule : PluginModule {
    override val name = "StaffSpells"
    override val priority = 85
    private var controller: StaffSpellController? = null

    override fun init() {
        val config = StaffSpellConfig(ConfigManager.of(ARC.instance.dataPath, "modules/staff-spells.yml"))
        val settings = config.settings // Validate the replacement before closing the active generation.
        shutdown()
        controller = StaffSpellController(config, settings, StaffSpellDamage(),
            StaffSpellDisplayEffects(PaperPacketDisplays(ARC.instance, "staff-spells"))).also {
            Bukkit.getPluginManager().registerEvents(it, ARC.instance)
            it.start()
        }
    }

    override fun reload() = init()
    override fun shutdown() {
        controller?.let { HandlerList.unregisterAll(it); it.close() }
        controller = null
    }

    internal fun give(player: Player, spells: List<StaffSpell>) = controller?.give(player, spells) ?: false
    internal fun ready() = controller != null
}

/** Prototype items own their input. No FMM item, listener order or existing staff is rewritten. */
internal class StaffSpellController(
    private val config: StaffSpellConfig,
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val effects: StaffSpellDisplayEffects,
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val visuals = StaffSpellVisuals(tasks)
    private val marks = mutableMapOf<UUID, PendingStaffMark>()
    private val embers = mutableListOf<PendingStaffEmber>()
    private val waves = mutableListOf<PendingStaffWave>()
    private val cooldownId = "staff-spells"

    fun start() {
        tasks.runTimer(4, 4) {
            Bukkit.getOnlinePlayers().forEach { player ->
                val spell = StaffSpell.from(player.inventory.itemInMainHand)
                if (ready(player) && spell in setOf(StaffSpell.CHAIN, StaffSpell.MARK)) {
                    listOfNotNull(target(player)).forEach { selected ->
                        player.spawnParticle(Particle.END_ROD, center(selected).add(0.0, selected.height * 0.55, 0.0),
                            2, 0.13, 0.05, 0.13, 0.0)
                    }
                }
            }
        }
        tasks.runTimer(2, 2) {
            tickMarks()
            tickWaves()
        }
        tasks.runTimer(1, 1) { tickEmbers() }
    }

    fun give(player: Player, spells: List<StaffSpell>): Boolean {
        if (player.inventory.storageContents.count { it == null || it.type.isAir } < spells.size) {
            player.sendMessage(config.text("full-inventory"))
            return false
        }
        val items = try {
            spells.map(config::item).toTypedArray()
        } catch (_: StaffSpellSkinUnavailableException) {
            player.sendMessage(config.text("skin-unavailable"))
            return false
        }
        player.inventory.addItem(*items)
        player.sendMessage(config.text("given"))
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND ||
            event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK) ||
            StaffSpell.from(event.item) == null || event.useItemInHand() == Event.Result.DENY) return
        // Right-click air may be pre-cancelled by vanilla because rods have no default use.
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.useInteractedBlock() == Event.Result.DENY) return
        event.setUseItemInHand(Event.Result.DENY)
        event.setUseInteractedBlock(Event.Result.DENY)
        cast(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityInteract(event: PlayerInteractEntityEvent) = interactEntity(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityInteractAt(event: PlayerInteractAtEntityEvent) = interactEntity(event)

    private fun interactEntity(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND || StaffSpell.from(event.player.inventory.itemInMainHand) == null) return
        event.isCancelled = true
        cast(event.player)
    }

    internal fun cast(player: Player) {
        val spell = StaffSpell.from(player.inventory.itemInMainHand) ?: return
        if (!ready(player)) return
        if (CooldownManager.isOnCooldown(player.uniqueId, cooldownId)) {
            player.sendActionBar(config.text("cooldown"))
            return
        }
        val cast = damage.capture(player) ?: run {
            player.sendActionBar(config.text("unavailable"))
            return
        }
        val tuning = settings.tuning.getValue(spell)
        CooldownManager.addCooldown(player.uniqueId, cooldownId, tuning.cooldownTicks)
        when (spell) {
            StaffSpell.CHAIN -> chain(player, target(player), cast, tuning)
            StaffSpell.MARK -> {
                val selected = target(player)
                val point = selected?.let(::center) ?: aimPoint(player)
                effects.remove(marks.remove(player.uniqueId)?.visualId)
                val visualId = effects.play(player.uniqueId, spell, point, radius = 1.3, durationTicks = settings.markTicks)
                marks[player.uniqueId] = PendingStaffMark(selected, point, player.world.uid, cast, tuning, settings.markTicks, visualId)
                visuals.markLaunch(player.eyeLocation, point)
            }
            StaffSpell.FROST -> frost(player, cast, tuning)
            StaffSpell.LANCE -> {
                val eye = player.eyeLocation
                val end = aimPoint(player)
                lineTargets(player, eye, end, settings.lanceWidth).take(settings.lanceTargets).forEach {
                    damage.hit(player, it, cast, tuning.power, tuning.vanillaDamage)
                }
                visuals.lance(eye, end)
                effects.play(player.uniqueId, spell, eye, end, radius = 0.75, durationTicks = 20, impact = true)
            }
            StaffSpell.EMBER -> {
                // The direction is captured once. Flight never steers towards a nearby mob.
                if (embers.count { it.cast.casterId == player.uniqueId } >= 8) {
                    val removed = embers.removeAt(embers.indexOfFirst { it.cast.casterId == player.uniqueId })
                    effects.remove(removed.visualId)
                }
                val eye = player.eyeLocation
                val direction = eye.direction.normalize()
                val muzzle = eye.clone().add(direction.clone().multiply(0.8))
                val visualId = effects.play(player.uniqueId, spell, muzzle,
                    muzzle.clone().add(direction), radius = 0.85,
                    durationTicks = kotlin.math.ceil(settings.range / settings.emberSpeed).toInt() + 4)
                embers += PendingStaffEmber(eye, direction, cast, tuning, settings.range, visualId, muzzle)
                visuals.emberTrail(muzzle, muzzle)
            }
            StaffSpell.NOVA -> {
                val origin = player.location.clone()
                val candidates = nearby(player, origin, settings.novaRadius)
                    .sortedBy { horizontalDistance(origin, center(it)) }
                    .take(MAX_WAVE_CANDIDATES)
                visuals.nova(origin, settings.novaRadius)
                val visualId = effects.play(player.uniqueId, spell, origin, radius = settings.novaRadius,
                    durationTicks = NOVA_DURATION_TICKS, impact = true)
                waves += PendingStaffWave(player.uniqueId, player.world.uid, spell, origin, Vector(),
                    settings.novaRadius, candidates, cast, tuning, visualId, WAVE_START_DELAY_TICKS,
                    NOVA_TRAVEL_TICKS, NOVA_DURATION_TICKS, NOVA_INITIAL_FRONT_RADIUS)
            }
        }
        tasks.runLater(tuning.cooldownTicks) {
            if (ready(player) && StaffSpell.from(player.inventory.itemInMainHand) != null)
                player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.3f, 1.8f)
        }
    }

    private fun chain(player: Player, first: LivingEntity?, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        if (first == null) {
            val end = aimPoint(player)
            chainVisual(player, player.eyeLocation, end, hop = 0, impact = false)
            return
        }
        val visited = mutableSetOf<UUID>()
        var current: LivingEntity? = first
        var origin = player.eyeLocation
        var scale = 1.0
        var hop = 0
        repeat(settings.chainTargets) {
            val victim = current ?: return
            val endpoint = center(victim)
            visited += victim.uniqueId
            chainVisual(player, origin, endpoint, hop++, impact = true)
            if (!damage.hit(player, victim, cast, tuning.power * scale, tuning.vanillaDamage * scale)) return
            origin = endpoint
            current = nearby(player, endpoint, settings.chainRadius)
                .filter { it.uniqueId !in visited }
                .sortedBy { center(it).distanceSquared(endpoint) }
                .firstOrNull { visible(endpoint, center(it)) && visible(player.eyeLocation, center(it)) }
            scale *= settings.chainDecay
        }
    }

    private fun chainVisual(player: Player, from: Location, to: Location, hop: Int, impact: Boolean) {
        val casterId = player.uniqueId
        val worldId = player.world.uid
        val start = from.clone()
        val end = to.clone()
        val show = {
            val caster = Bukkit.getPlayer(casterId)
            if (caster != null && ready(caster) && caster.world.uid == worldId) {
                visuals.lightning(start, end)
                effects.play(casterId, StaffSpell.CHAIN, start, end, radius = 0.65,
                    durationTicks = CHAIN_VISUAL_DURATION_TICKS, impact = impact)
            }
        }
        val delay = hop * CHAIN_VISUAL_STEP_TICKS
        if (delay == 0) show() else tasks.runLater(delay.toLong()) { show() }
    }

    private fun frost(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        val origin = player.location.clone()
        val yaw = Math.toRadians(origin.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw)).normalize()
        val end = origin.clone().add(forward.clone().multiply(settings.frostRange))
        val spread = tan(Math.toRadians(settings.frostDegrees))
        val candidates = nearby(player, origin, settings.frostRange)
            .filter { inFrostCone(origin, forward, center(it), settings.frostRange, spread) }
            .sortedBy { horizontalDistance(origin, center(it)) }
            .take(MAX_WAVE_CANDIDATES)
        val radius = spread * settings.frostRange
        visuals.frost(origin, end, settings.frostDegrees, WAVE_START_DELAY_TICKS, FROST_TRAVEL_TICKS)
        val visualId = effects.play(player.uniqueId, StaffSpell.FROST, origin, end,
            radius = radius, durationTicks = FROST_DURATION_TICKS)
        waves += PendingStaffWave(player.uniqueId, player.world.uid, StaffSpell.FROST, origin, forward,
            settings.frostRange, candidates, cast, tuning, visualId, WAVE_START_DELAY_TICKS,
            FROST_TRAVEL_TICKS, FROST_DURATION_TICKS, slowTicks = settings.frostSlowTicks, frostSpread = spread)
    }

    private fun tickWaves() {
        val hits = mutableListOf<PendingStaffWaveHit>()
        val finished = mutableListOf<PendingStaffWave>()
        waves.toList().forEach waveLoop@{ wave ->
            if (!waves.contains(wave)) return@waveLoop
            val player = Bukkit.getPlayer(wave.casterId)
            if (player == null || !ready(player) || player.world.uid != wave.worldId) {
                waves.remove(wave)
                effects.remove(wave.visualId)
                return@waveLoop
            }

            val previousAge = wave.elapsedTicks
            val age = previousAge + WAVE_STEP_TICKS
            wave.elapsedTicks = age
            val previousFront = wave.frontAt(previousAge)
            val front = wave.frontAt(age)
            if (age >= wave.startDelayTicks && age <= wave.startDelayTicks + wave.travelTicks &&
                wave.targetAttempts < settings.maxAreaTargets) {
                wave.candidates.asSequence()
                    .filter { it.uniqueId !in wave.attemptedTargets }
                    .map { it to center(it) }
                    .filter { (_, at) -> intersectsFront(wave, at, previousFront, front) }
                    .sortedBy { (_, at) -> waveDistance(wave, at) }
                    .forEach candidateLoop@{ (target, at) ->
                        if (!wave.attemptedTargets.add(target.uniqueId)) return@candidateLoop
                        if (wave.targetAttempts >= settings.maxAreaTargets) return@candidateLoop
                        if (!damage.eligible(player, target) || !visible(wave.origin, at) ||
                            !visible(player.eyeLocation, at)) return@candidateLoop
                        // Reserve the normal area-target slot before the hit event can re-enter cleanup.
                        wave.targetAttempts++
                        hits += PendingStaffWaveHit(wave, target, previousFront, front)
                    }
            }
            if (age >= wave.durationTicks || wave.targetAttempts >= settings.maxAreaTargets ||
                age >= wave.startDelayTicks + wave.travelTicks) finished += wave
        }

        // Damage callbacks may quit, teleport or kill a caster and remove its wave.
        hits.forEach hitLoop@{ staged ->
            val wave = staged.wave
            if (!waves.contains(wave)) return@hitLoop
            val player = Bukkit.getPlayer(wave.casterId) ?: return@hitLoop
            val target = staged.target
            val at = center(target)
            if (!ready(player) || player.world.uid != wave.worldId ||
                !damage.eligible(player, target) || !intersectsFront(wave, at, staged.previousFront, staged.front) ||
                !visible(wave.origin, at) || !visible(player.eyeLocation, at)) return@hitLoop
            val damaged = damage.hit(player, target, wave.cast, wave.tuning.power, wave.tuning.vanillaDamage)
            if (damaged && wave.slowTicks != null)
                target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, wave.slowTicks, 1, false, true))
        }
        finished.forEach { waves.remove(it) }
    }

    private fun horizontalDistance(from: Location, to: Location): Double {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun forwardDistance(origin: Location, forward: Vector, at: Location): Double {
        val dx = at.x - origin.x
        val dz = at.z - origin.z
        return dx * forward.x + dz * forward.z
    }

    private fun inFrostCone(origin: Location, forward: Vector, at: Location, range: Double, spread: Double): Boolean {
        val along = forwardDistance(origin, forward, at)
        if (along < 0.0 || along > range) return false
        val lateral = abs((at.x - origin.x) * forward.z - (at.z - origin.z) * forward.x)
        return lateral <= along * spread + WAVE_BODY_TOLERANCE
    }

    private fun intersectsFront(wave: PendingStaffWave, at: Location, previousFront: Double, front: Double): Boolean {
        val distance = horizontalDistance(wave.origin, at)
        if (wave.spell == StaffSpell.FROST &&
            !inFrostCone(wave.origin, wave.forward, at, wave.range, wave.frostSpread)) return false
        return distance + WAVE_BODY_TOLERANCE >= previousFront &&
            distance - WAVE_BODY_TOLERANCE <= front
    }

    private fun waveDistance(wave: PendingStaffWave, at: Location) = horizontalDistance(wave.origin, at)

    private fun tickMarks() {
        val due = mutableListOf<Pair<Player, PendingStaffMark>>()
        val iterator = marks.iterator()
        while (iterator.hasNext()) {
            val (playerId, mark) = iterator.next()
            val player = Bukkit.getPlayer(playerId)
            val origin = mark.target?.let(::center) ?: mark.point
            if (player == null || !ready(player) || player.world.uid != mark.worldId ||
                (mark.target != null && !damage.eligible(player, mark.target)) ||
                player.eyeLocation.distanceSquared(origin) > (settings.range + 1) * (settings.range + 1)) {
                iterator.remove()
                effects.remove(mark.visualId)
                continue
            }
            effects.move(mark.visualId, origin)
            mark.remainingTicks -= 2
            if (mark.remainingTicks <= 0) {
                iterator.remove()
                due += player to mark
            } else {
                visuals.markCharge(origin, 1.0 - mark.remainingTicks.toDouble() / settings.markTicks)
            }
        }
        due.forEach { (player, mark) ->
            val origin = mark.target?.let(::center) ?: mark.point
            areaDamage(player, origin, settings.markRadius, mark.cast, mark.tuning, mark.target)
            visuals.markBurst(origin, settings.markRadius)
            effects.impact(mark.visualId, origin, settings.markRadius, MARK_IMPACT_DURATION_TICKS)
        }
    }

    private fun tickEmbers() {
        val impacts = mutableListOf<Triple<Player, PendingStaffEmber, Pair<Location, LivingEntity?>>>()
        val iterator = embers.iterator()
        while (iterator.hasNext()) {
            val ember = iterator.next()
            val player = Bukkit.getPlayer(ember.cast.casterId)
            if (player == null || !ready(player) || player.world != ember.position.world) {
                iterator.remove()
                effects.remove(ember.visualId)
                continue
            }
            val from = ember.position
            val distance = min(ember.remainingDistance, settings.emberSpeed)
            val blockEnd = rayEnd(from, ember.direction, distance)
            val victim = lineTargets(player, from, blockEnd, 0.25).firstOrNull()
            val collision = victim?.boundingBox?.expand(0.25)
                ?.rayTrace(from.toVector(), ember.direction, from.distance(blockEnd))?.hitPosition
            val end = collision?.toLocation(from.world) ?: blockEnd
            visuals.emberTrail(ember.visualPosition, end)
            ember.remainingDistance -= distance
            if (victim != null || from.distanceSquared(blockEnd) < distance * distance - 0.0001 || ember.remainingDistance <= 0.001) {
                iterator.remove()
                impacts += Triple(player, ember, end to victim)
            } else {
                ember.position = end
                ember.visualPosition = end
                effects.move(ember.visualId, end)
            }
        }
        // Damage events can themselves trigger quit/teleport cleanup; no active list iterator here.
        impacts.forEach { (player, ember, impact) ->
            areaDamage(player, impact.first, settings.emberRadius, ember.cast, ember.tuning, impact.second)
            visuals.emberBurst(impact.first, settings.emberRadius)
            effects.impact(ember.visualId, impact.first, settings.emberRadius, EMBER_IMPACT_DURATION_TICKS)
        }
    }

    private fun areaDamage(player: Player, origin: Location, radius: Double, cast: StaffSpellCast,
        tuning: StaffSpellTuning, primary: LivingEntity? = null) {
        if (!visible(player.eyeLocation, origin)) return
        (listOfNotNull(primary) + nearby(player, origin, radius).sortedBy { center(it).distanceSquared(origin) })
            .distinctBy { it.uniqueId }
            .filter { visible(origin, center(it)) && visible(player.eyeLocation, center(it)) }
            .take(settings.maxAreaTargets)
            .forEach { damage.hit(player, it, cast, tuning.power, tuning.vanillaDamage) }
    }

    /** Exact swept collision along the cast line, never soft acquisition. */
    private fun lineTargets(player: Player, from: Location, to: Location, width: Double): List<LivingEntity> {
        val direction = to.toVector().subtract(from.toVector())
        val length = direction.length()
        if (length < 0.001) return emptyList()
        direction.multiply(1.0 / length)
        val midpoint = from.clone().add(direction.clone().multiply(length / 2))
        return from.world.getNearbyLivingEntities(midpoint, length / 2 + width + 2)
            .filter { damage.eligible(player, it) }
            .mapNotNull { entity ->
                entity.boundingBox.expand(width).rayTrace(from.toVector(), direction, length)
                    ?.let { entity to it.hitPosition.distanceSquared(from.toVector()) }
            }.sortedBy { it.second }.map { it.first }.filter { visible(from, center(it)) }
    }

    private fun aimPoint(player: Player) = rayEnd(player.eyeLocation, player.eyeLocation.direction, settings.range)

    private fun rayEnd(from: Location, direction: Vector, range: Double): Location {
        val hit = from.world.rayTraceBlocks(from, direction, range, FluidCollisionMode.NEVER, true)
        // Keep bursts just outside the wall, so line-of-sight starts in air.
        val distance = hit?.hitPosition?.distance(from.toVector())?.let { (it - 0.04).coerceAtLeast(0.0) } ?: range
        return from.clone().add(direction.clone().multiply(distance))
    }

    private fun target(player: Player) = aimed(player, settings.range, settings.aimDegrees).firstOrNull()

    private fun aimed(player: Player, range: Double, angle: Double): List<LivingEntity> {
        val eye = player.eyeLocation
        val look = eye.direction
        return nearby(player, eye, range).mapNotNull { target ->
            val offset = center(target).subtract(eye).toVector()
            // A direct hit on a tall/near mob's body must not fail because its centre is off-axis.
            val direct = target.boundingBox.expand(0.25).rayTrace(eye.toVector(), look, range) != null
            (if (direct) 1.0 else staffAimScore(offset.x, offset.y, offset.z, look.x, look.y, look.z, range, angle))
                ?.let { Triple(target, it, offset.lengthSquared()) }
        }.sortedWith(compareByDescending<Triple<LivingEntity, Double, Double>> { it.second }.thenBy { it.third })
            .take(32) // Bound expensive line-of-sight checks in dense mob farms.
            .filter { visible(eye, center(it.first)) }.map { it.first }
    }

    private fun nearby(player: Player, origin: Location, radius: Double) = origin.world
        .getNearbyLivingEntities(origin, radius, radius, radius)
        .filter { center(it).distanceSquared(origin) <= radius * radius && damage.eligible(player, it) }

    private fun ready(player: Player) = player.isOnline && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR && player.isValid

    private fun cancel(playerId: UUID) {
        marks.remove(playerId)
        embers.removeAll { it.cast.casterId == playerId }
        waves.removeAll { it.casterId == playerId }
        effects.cancel(playerId)
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) = cancel(event.player.uniqueId)
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) = cancel(event.player.uniqueId)
    @EventHandler fun onDeath(event: PlayerDeathEvent) = cancel(event.entity.uniqueId)

    override fun close() {
        tasks.close()
        marks.clear()
        embers.clear()
        waves.clear()
        effects.close()
    }

    private companion object {
        const val WAVE_STEP_TICKS = 2
        const val WAVE_START_DELAY_TICKS = 2
        const val WAVE_BODY_TOLERANCE = 0.6
        const val MAX_WAVE_CANDIDATES = 32
        const val FROST_TRAVEL_TICKS = 12
        const val FROST_DURATION_TICKS = 24
        const val NOVA_INITIAL_FRONT_RADIUS = 1.8
        const val NOVA_TRAVEL_TICKS = 16
        const val NOVA_DURATION_TICKS = 30
        const val CHAIN_VISUAL_STEP_TICKS = 2
        const val CHAIN_VISUAL_DURATION_TICKS = 18
        const val MARK_IMPACT_DURATION_TICKS = 20
        const val EMBER_IMPACT_DURATION_TICKS = 20
    }

}

private data class PendingStaffMark(val target: LivingEntity?, val point: Location, val worldId: UUID,
    val cast: StaffSpellCast, val tuning: StaffSpellTuning, var remainingTicks: Int, val visualId: UUID?)

private data class PendingStaffEmber(var position: Location, val direction: Vector,
    val cast: StaffSpellCast, val tuning: StaffSpellTuning, var remainingDistance: Double, val visualId: UUID?,
    var visualPosition: Location)

private class PendingStaffWave(
    val casterId: UUID,
    val worldId: UUID,
    val spell: StaffSpell,
    val origin: Location,
    val forward: Vector,
    val range: Double,
    val candidates: List<LivingEntity>,
    val cast: StaffSpellCast,
    val tuning: StaffSpellTuning,
    val visualId: UUID?,
    val startDelayTicks: Int,
    val travelTicks: Int,
    val durationTicks: Int,
    val initialFront: Double = 0.0,
    val slowTicks: Int? = null,
    val frostSpread: Double = 0.0,
) {
    val attemptedTargets = mutableSetOf<UUID>()
    var elapsedTicks = 0
    var targetAttempts = 0

    fun frontAt(ageTicks: Int): Double {
        if (ageTicks < startDelayTicks) return 0.0
        val expansion = staffWaveFront(ageTicks, range, travelTicks)
        return if (spell == StaffSpell.NOVA) initialFront + staffWaveFront(ageTicks,
            (range - initialFront).coerceAtLeast(0.0), travelTicks) else expansion
    }
}

private data class PendingStaffWaveHit(
    val wave: PendingStaffWave,
    val target: LivingEntity,
    val previousFront: Double,
    val front: Double,
)

internal fun center(entity: LivingEntity) = entity.location.add(0.0, entity.height * 0.5, 0.0)

internal fun visible(from: Location, to: Location): Boolean {
    if (from.world != to.world) return false
    val offset = to.toVector().subtract(from.toVector())
    val distance = offset.length()
    if (distance < 0.01) return true
    return from.world.rayTraceBlocks(from, offset.multiply(1.0 / distance), distance,
        FluidCollisionMode.NEVER, true) == null
}

object StaffTestSubCommand : SubCommand {
    override val configKey = "stafftest"
    override val defaultPermission = "arc.test"
    override val defaultDescription = "Выдать посохи для пробы новых атак"
    override val defaultUsage = "/arc stafftest [all|chain|mark|frost|lance|ember|nova] [игрок]"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size > 2) { sendUsage(sender); return true }
        val selection = args.firstOrNull()?.lowercase() ?: "all"
        val spells = if (selection == "all") StaffSpell.entries else StaffSpell.entries.filter { it.id == selection }
        if (spells.isEmpty()) { sendUsage(sender); return true }
        val player = args.getOrNull(1)?.let { getOnlinePlayer(sender, it) } ?: if (args.size < 2) requirePlayer(sender) else null
        if (player == null) return true
        if (!StaffSpellsModule.ready()) {
            sender.sendMessage(ConfigManager.of(ARC.instance.dataPath, "modules/staff-spells.yml").component("messages.unavailable", TagResolver.empty()))
            return true
        }
        StaffSpellsModule.give(player, spells)
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>) = when (args.size) {
        1 -> (listOf("all") + StaffSpell.entries.map { it.id }).tabComplete(args[0])
        2 -> Bukkit.getOnlinePlayers().map { it.name }.tabComplete(args[1])
        else -> emptyList()
    }
}
