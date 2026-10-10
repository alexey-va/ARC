package ru.arc.staffspells

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.bossbar.BossBar
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
    private var duelAccess: StaffSpellDuelAccess? = null

    internal fun installDuelAccess(access: StaffSpellDuelAccess?) {
        duelAccess = access
    }

    internal fun activeController() = controller

    override fun init() {
        val config = StaffSpellConfig(ConfigManager.of(ARC.instance.dataPath, "modules/staff-spells.yml"))
        val settings = config.settings // Validate the replacement before closing the active generation.
        shutdown()
        controller = StaffSpellController(config, settings, StaffSpellDamage(duelAccess),
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
    private val bolts = mutableListOf<PendingStaffBolt>()
    private val delayedCasts = mutableMapOf<UUID, UUID>()
    private val cooldownLengths = mutableMapOf<UUID, Long>()
    private val cooldownBars = mutableMapOf<UUID, BossBar>()
    private val cooldownId = "staff-spells"

    fun start() {
        tasks.runTimer(4, 4) {
            Bukkit.getOnlinePlayers().forEach { player ->
                val spell = StaffSpell.from(player.inventory.itemInMainHand)
                if (ready(player) && spell != null) {
                    showCooldown(player, spell)
                } else hideCooldown(player.uniqueId)
                if (ready(player) && !StaffSpell.isDuelOnly(player.inventory.itemInMainHand) &&
                    spell in setOf(StaffSpell.CHAIN, StaffSpell.MARK)) {
                    listOfNotNull(target(player)).forEach { selected ->
                        val cue = center(selected).add(0.0, selected.height * 0.55, 0.0)
                        if (cue.distanceSquared(player.eyeLocation) >= 3.2 * 3.2)
                            player.spawnParticle(Particle.END_ROD, cue, 2, 0.13, 0.05, 0.13, 0.0)
                    }
                }
            }
        }
        tasks.runTimer(2, 2) {
            tickMarks()
            tickWaves()
        }
        tasks.runTimer(1, 1) { tickEmbers(); tickBolts() }
    }

    fun give(player: Player, spells: List<StaffSpell>): Boolean {
        if (player.inventory.storageContents.count { it == null || it.type.isAir } < spells.size) {
            player.sendMessage(config.text("full-inventory"))
            return false
        }
        val items = try {
            spells.map { config.item(it) }.toTypedArray()
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
        val item = player.inventory.itemInMainHand
        val spell = StaffSpell.from(item) ?: return
        if (!ready(player)) return
        if (CooldownManager.isOnCooldown(player.uniqueId, cooldownId)) {
            showCooldown(player, spell)
            return
        }
        val cast = damage.capture(player, duelOnly = StaffSpell.isDuelOnly(item)) ?: run {
            player.sendActionBar(config.text("unavailable"))
            return
        }
        val tuning = settings.tuning.getValue(spell)
        val secondary = player.isSneaking
        val cooldownTicks = when {
            !secondary -> tuning.cooldownTicks
            spell in setOf(StaffSpell.MARK, StaffSpell.EMBER) -> tuning.cooldownTicks * 2
            else -> tuning.cooldownTicks * 3 / 2
        }
        cooldownLengths[player.uniqueId] = cooldownTicks
        CooldownManager.addCooldown(player.uniqueId, cooldownId, cooldownTicks)
        showCooldown(player, spell)
        if (spell == StaffSpell.CHAIN) visuals.withOwner(cast.casterId) { visuals.lightningLaunch(player.eyeLocation) }
        if (secondary) castSecondary(player, spell, cast, tuning) else when (spell) {
            StaffSpell.CHAIN -> chain(player, target(player, cast), cast, tuning)
            StaffSpell.MARK -> {
                val selected = target(player, cast)
                val point = selected?.let(::center) ?: aimPoint(player)
                effects.remove(marks.remove(player.uniqueId)?.visualId)
                val visualId = effects.play(player.uniqueId, spell, point, radius = 1.3, durationTicks = settings.markTicks)
                marks[player.uniqueId] = PendingStaffMark(selected, point, player.world.uid, cast, tuning, settings.markTicks, visualId)
                visuals.withOwner(cast.casterId) { visuals.markLaunch(visualStart(player.eyeLocation, point), point) }
            }
            StaffSpell.FROST -> frost(player, cast, tuning)
            StaffSpell.ICICLE -> frostIcicles(player, cast, tuning)
            StaffSpell.FAULT -> fault(player, cast, tuning, secondary = false)
            StaffSpell.LANCE -> {
                val eye = player.eyeLocation
                val end = aimPoint(player)
                lineTargets(player, eye, end, settings.lanceWidth, cast).take(settings.lanceTargets).forEach {
                    damage.hit(player, it, cast, tuning.power, tuning.vanillaDamage)
                }
                visuals.withOwner(cast.casterId) { visuals.lance(eye, end) }
                effects.play(player.uniqueId, spell, eye, end, radius = 0.75,
                    durationTicks = staffLanceDuration(eye.distance(end)), impact = true)
            }
            StaffSpell.EMBER -> {
                // The direction is captured once. Flight never steers towards a nearby mob.
                if (embers.count { it.cast.casterId == player.uniqueId } >= 8) {
                    val removed = embers.removeAt(embers.indexOfFirst { it.cast.casterId == player.uniqueId })
                    effects.remove(removed.visualId)
                }
                val eye = player.eyeLocation
                val direction = eye.direction.normalize()
                val muzzle = eye.clone() // Camera exclusion belongs to the shared renderer; collision starts at the eye.
                val visualId = effects.play(player.uniqueId, spell, muzzle,
                    muzzle.clone().add(direction), radius = 0.85,
                    durationTicks = kotlin.math.ceil(settings.range / settings.emberSpeed).toInt() + 4)
                embers += PendingStaffEmber(eye, direction, cast, tuning, settings.range, visualId, muzzle)
                visuals.withOwner(cast.casterId) { visuals.emberTrail(muzzle, muzzle) }
            }
            StaffSpell.NOVA -> {
                val origin = player.location.clone()
                val candidates = nearby(player, origin, settings.novaRadius, cast)
                    .sortedBy { horizontalDistance(origin, center(it)) }
                    .take(MAX_WAVE_CANDIDATES)
                visuals.withOwner(cast.casterId) { visuals.nova(origin, settings.novaRadius) }
                val visualId = effects.play(player.uniqueId, spell, origin, radius = settings.novaRadius,
                    durationTicks = NOVA_DURATION_TICKS, impact = true)
                waves += PendingStaffWave(player.uniqueId, player.world.uid, spell, origin, Vector(),
                    settings.novaRadius, candidates, cast, tuning, visualId, WAVE_START_DELAY_TICKS,
                    NOVA_TRAVEL_TICKS, NOVA_DURATION_TICKS, NOVA_INITIAL_FRONT_RADIUS)
            }
        }
        tasks.runLater(cooldownTicks) {
            if (ready(player) && StaffSpell.from(player.inventory.itemInMainHand) != null && damage.isCurrent(player, cast))
                player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.3f, 1.8f)
        }
    }

    internal fun duelItem(spell: StaffSpell) = config.duelItem(spell)

    internal fun duelFingerprint(pluginVersion: String) = settings.fingerprint(pluginVersion)

    internal fun resetPlayer(playerId: UUID) {
        cancel(playerId)
        CooldownManager.clearCooldown(playerId, cooldownId)
    }

    private fun showCooldown(player: Player, spell: StaffSpell) {
        val remaining = CooldownManager.cooldown(player.uniqueId, cooldownId)
        val total = cooldownLengths[player.uniqueId] ?: settings.tuning.getValue(spell).cooldownTicks
        val bar = cooldownBars.getOrPut(player.uniqueId) {
            BossBar.bossBar(config.cooldown(spell, player.isSneaking, remaining, total),
                staffCooldownProgress(remaining, total), BossBar.Color.BLUE, BossBar.Overlay.PROGRESS)
                .also(player::showBossBar)
        }
        bar.name(config.cooldown(spell, player.isSneaking, remaining, total))
        bar.progress(staffCooldownProgress(remaining, total))
        bar.color(if (remaining > 0) BossBar.Color.BLUE else BossBar.Color.GREEN)
    }

    private fun hideCooldown(playerId: UUID) {
        cooldownBars.remove(playerId)?.let { Bukkit.getPlayer(playerId)?.hideBossBar(it) }
    }

    private fun visualStart(from: Location, to: Location): Location {
        val delta = to.toVector().subtract(from.toVector())
        val length = delta.length()
        return if (length < 0.001) to.clone()
        else from.clone().add(delta.multiply(min(1.4, length) / length))
    }

    private fun castSecondary(player: Player, spell: StaffSpell, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        when (spell) {
            StaffSpell.CHAIN -> {
                val targets = aimed(player, settings.range, 55.0, cast).take(3)
                val visited = mutableSetOf<UUID>()
                repeat(3) { index ->
                    val direction = player.eyeLocation.direction.rotateAroundY(Math.toRadians((index - 1) * 48.0))
                    launchBolt(player, player.eyeLocation, direction, targets.getOrNull(index), cast,
                        tuning, 0.75, 1, visited, settings.range * 1.6)
                }
            }
            StaffSpell.MARK -> {
                val at = target(player, cast)?.let(::center) ?: aimPoint(player)
                effects.remove(marks.remove(player.uniqueId)?.visualId)
                val visualId = effects.play(player.uniqueId, spell, at, radius = 4.5,
                    durationTicks = 40, secondary = true)
                marks[player.uniqueId] = PendingStaffMark(null, at, player.world.uid,
                    cast, tuning, 40, visualId, gravity = true)
                visuals.withOwner(cast.casterId) { visuals.markLaunch(visualStart(player.eyeLocation, at), at) }
            }
            StaffSpell.ICICLE -> hail(player, cast, tuning)
            StaffSpell.FAULT -> fault(player, cast, tuning, secondary = true)
            StaffSpell.FROST, StaffSpell.NOVA -> {
                val origin = player.location.clone()
                val forward = Vector(-sin(Math.toRadians(origin.yaw.toDouble())), 0.0,
                    cos(Math.toRadians(origin.yaw.toDouble())))
                val directional = spell == StaffSpell.NOVA
                val range = if (directional) 12.0 else settings.novaRadius
                val spread = if (directional) 5.5 / range else 0.0
                val candidates = nearby(player, origin, range, cast).filter {
                    !directional || inFrostCone(origin, forward, center(it), range, spread)
                }.sortedBy { horizontalDistance(origin, center(it)) }.take(MAX_WAVE_CANDIDATES)
                val end = origin.clone().add(forward.clone().multiply(range))
                val duration = if (directional) NOVA_DURATION_TICKS else 30
                val visualId = effects.play(player.uniqueId, spell, origin, end,
                    radius = if (directional) 5.5 else range, durationTicks = duration, impact = true, secondary = true)
                visuals.withOwner(cast.casterId) {
                    visuals.wave(origin, end, if (directional) 5.5 else range, frost = !directional, directional = directional)
                }
                waves += PendingStaffWave(player.uniqueId, player.world.uid, spell, origin, forward,
                    range, candidates, cast, tuning, visualId, WAVE_START_DELAY_TICKS, NOVA_TRAVEL_TICKS,
                    duration, NOVA_INITIAL_FRONT_RADIUS,
                    slowTicks = if (directional) null else settings.frostSlowTicks,
                    frostSpread = spread, directional = directional)
            }
            StaffSpell.LANCE -> {
                val eye = player.eyeLocation
                val visited = mutableSetOf<UUID>()
                for (angle in listOf(-16.0, 0.0, 16.0)) {
                    val end = rayEnd(eye, eye.direction.rotateAroundY(Math.toRadians(angle)), settings.range)
                    lineTargets(player, eye, end, settings.lanceWidth, cast).forEach { victim ->
                        if (visited.size < settings.lanceTargets && visited.add(victim.uniqueId))
                            damage.hit(player, victim, cast, tuning.power * 0.65, tuning.vanillaDamage * 0.65)
                    }
                    visuals.withOwner(cast.casterId) { visuals.lance(eye, end) }
                    effects.play(player.uniqueId, spell, eye, end, radius = 0.9,
                        durationTicks = staffLanceDuration(eye.distance(end)),
                        impact = true, secondary = true)
                }
            }
            StaffSpell.EMBER -> meteorShower(player, cast, tuning)
        }
    }

    private fun meteorShower(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        val selected = target(player, cast)?.let(::center) ?: aimPoint(player)
        val center = groundBelow(selected.clone().add(0.0, 0.2, 0.0), 16.0) ?: selected.clone()
        val worldId = player.world.uid
        val hitTargets = mutableSetOf<UUID>()
        delayedCasts[cast.casterId] = cast.attackId
        repeat(3) { index ->
            tasks.runLater((index * 6 + 1).toLong()) {
                val caster = Bukkit.getPlayer(cast.casterId)
                if (delayedCasts[cast.casterId] == cast.attackId && caster != null && ready(caster) && caster.world.uid == worldId &&
                    damage.isCurrent(caster, cast) && visible(caster.eyeLocation, selected)) {
                    val offset = center.clone().add((index - 1) * 2.0, 4.0, if (index == 1) 1.6 else -0.8)
                    val impact = groundBelow(offset, 12.0) ?: offset.clone().subtract(0.0, 4.0, 0.0)
                    // Trace upward from the destination: indoor casts start below the ceiling, never beyond it.
                    val top = rayEnd(impact.clone().add(0.0, 0.15, 0.0), Vector(0.0, 1.0, 0.0), 12.0 + index)
                    val travel = top.y - impact.y
                    if (travel > 0.4 && top.world.isChunkLoaded(top.blockX shr 4, top.blockZ shr 4)) {
                        val direction = Vector(0.0, -1.0, 0.0)
                        val id = effects.play(cast.casterId, StaffSpell.EMBER, top, top.clone().add(direction),
                            radius = 1.2, durationTicks = 24, secondary = true)
                        while (embers.count { it.cast.casterId == cast.casterId } >= 8)
                            embers.first { it.cast.casterId == cast.casterId }.let { effects.remove(it.visualId); embers.remove(it) }
                        embers += PendingStaffEmber(top, direction, cast,
                            tuning.copy(power = tuning.power * 0.65, vanillaDamage = tuning.vanillaDamage * 0.65),
                            travel, id, top, speed = 1.6, radius = 3.5, hitTargets = hitTargets)
                        visuals.withOwner(cast.casterId) { visuals.emberTrail(top, top, meteor = true) }
                    }
                }
                if (index == 2 && delayedCasts[cast.casterId] == cast.attackId) delayedCasts.remove(cast.casterId)
            }
        }
    }

    private fun frostIcicles(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        val eye = player.eyeLocation
        val yaw = Math.toRadians(player.location.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw)).normalize()
        val right = Vector(forward.z, 0.0, -forward.x).normalize()
        // Leave the shoulders horizontally before seeking: a downwards look must not shoot through the eye.
        val direction = forward.clone()
        val targets = aimed(player, settings.range, settings.aimDegrees, cast).take(3)
        val aimPoint = aimPoint(player)
        val visited = mutableSetOf<UUID>()

        repeat(3) { index ->
            val offset = staffIcicleAnchorOffset(index)
            val anchor = eye.clone()
                .add(right.clone().multiply(offset.x.toDouble()))
                .add(0.0, offset.y.toDouble(), 0.0)
                .add(forward.clone().multiply(offset.z.toDouble()))
            if (!visible(eye, anchor)) return@repeat

            reserveBolt(player.uniqueId)
            val target = targets.getOrNull(index) ?: targets.firstOrNull()
            val visualId = effects.play(player.uniqueId, StaffSpell.ICICLE, anchor,
                anchor.clone().add(direction), radius = 0.9, durationTicks = 80)
            bolts += PendingStaffBolt(anchor, direction.clone(), target, cast, tuning, 1.0, 1,
                visited, settings.range, visualId, spell = StaffSpell.ICICLE,
                fixedAimPoint = if (target == null) aimPoint.clone() else null,
                launchDelayTicks = staffIcicleLaunchDelay(index), holdForwardTicks = 4)
        }
    }

    private fun hail(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        val selected = target(player, cast)?.let(::center) ?: aimPoint(player)
        val center = groundBelow(selected.clone().add(0.0, 0.2, 0.0), 16.0) ?: selected
        val visited = mutableSetOf<UUID>()
        repeat(3) { index ->
            val impact = center.clone().add((index - 1) * 1.8, 0.0, if (index == 1) 1.2 else -0.6)
            if (!visible(player.eyeLocation, impact)) return@repeat
            val top = rayEnd(impact.clone().add(0.0, 0.15, 0.0), Vector(0.0, 1.0, 0.0), 10.0)
            val distance = top.y - impact.y
            if (distance < 0.5 || !top.world.isChunkLoaded(top.blockX shr 4, top.blockZ shr 4)) return@repeat
            reserveBolt(player.uniqueId)
            val direction = Vector(0.0, -1.0, 0.0)
            val id = effects.play(player.uniqueId, StaffSpell.ICICLE, top, top.clone().add(direction),
                radius = 0.9, durationTicks = 60, secondary = true)
            bolts += PendingStaffBolt(top, direction, null, cast, tuning, 1.0, 1,
                visited, distance, id, spell = StaffSpell.ICICLE,
                launchDelayTicks = staffIcicleLaunchDelay(index), burstRadius = 2.4)
            visuals.withOwner(cast.casterId) { visuals.icicleBurst(impact) }
        }
    }

    private fun fault(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning, secondary: Boolean) {
        val worldId = player.world.uid
        val points = if (secondary) {
            val selected = target(player, cast)?.let(::center) ?: aimPoint(player)
            listOfNotNull(groundBelow(selected.clone().add(0.0, 0.2, 0.0), 16.0))
        } else {
            val yaw = Math.toRadians(player.location.yaw.toDouble())
            val forward = Vector(-sin(yaw), 0.0, cos(yaw))
            val range = min(18.0, settings.range)
            val unobstructed = rayEnd(player.eyeLocation, forward, range).distance(player.eyeLocation)
            buildList {
                for (index in 1..5) {
                    val distance = range * index / 5.0
                    if (distance > unobstructed) break
                    val probe = player.location.clone().add(forward.clone().multiply(distance)).add(0.0, 2.0, 0.0)
                    if (!probe.world.isChunkLoaded(probe.blockX shr 4, probe.blockZ shr 4)) break
                    val at = groundBelow(probe, 6.0) ?: break
                    if (!visible(player.eyeLocation, at)) break
                    add(at)
                }
            }
        }
        if (points.isEmpty()) {
            player.sendActionBar(config.text("no-ground"))
            return
        }
        val radius = if (secondary) 4.5 else 2.0
        val hitTargets = mutableSetOf<UUID>()
        delayedCasts[cast.casterId] = cast.attackId
        points.forEachIndexed { index, at ->
            if (secondary) visuals.withOwner(cast.casterId) { visuals.faultCharge(at, radius) }
            tasks.runLater(if (secondary) 8L else 2L + index * 6L) {
                val caster = Bukkit.getPlayer(cast.casterId)
                if (delayedCasts[cast.casterId] == cast.attackId && caster != null && ready(caster) &&
                    caster.world.uid == worldId && at.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4) &&
                    damage.isCurrent(caster, cast) && visible(caster.eyeLocation, at)) {
                    effects.play(caster.uniqueId, StaffSpell.FAULT, at, radius = radius,
                        durationTicks = if (secondary) 24 else 22, impact = true, secondary = secondary)
                    visuals.withOwner(cast.casterId) { visuals.faultBurst(at, radius) }
                    val hit = areaDamage(caster, at, radius, cast, tuning, hitTargets = hitTargets)
                    if (secondary && damage.isCurrent(caster, cast)) hit.filter { damage.eligible(caster, it, cast) }
                        .forEach { it.velocity = it.velocity.apply { y = maxOf(y, 0.55) } }
                }
                if (index == points.lastIndex && delayedCasts[cast.casterId] == cast.attackId)
                    delayedCasts.remove(cast.casterId)
            }
        }
    }

    private fun chain(player: Player, first: LivingEntity?, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        launchBolt(player, player.eyeLocation, player.eyeLocation.direction.clone(),
            first, cast, tuning, 1.0, settings.chainTargets, mutableSetOf(), settings.range * 1.6)
    }

    private fun reserveBolt(casterId: UUID) {
        while (bolts.count { it.cast.casterId == casterId } >= MAX_BOLTS_PER_CASTER)
            bolts.first { it.cast.casterId == casterId }.let { effects.remove(it.visualId); bolts.remove(it) }
    }

    private fun launchBolt(player: Player, from: Location, direction: Vector, target: LivingEntity?,
        cast: StaffSpellCast, tuning: StaffSpellTuning, scale: Double, jumps: Int,
        visited: MutableSet<UUID>, distance: Double) {
        reserveBolt(player.uniqueId)
        val id = effects.play(player.uniqueId, StaffSpell.CHAIN, from, from.clone().add(direction.clone().multiply(2.5)),
            radius = 0.8, durationTicks = 80)
        bolts += PendingStaffBolt(from.clone(), direction.clone().normalize(), target, cast, tuning,
            scale, jumps, visited, distance, id)
    }

    private fun tickBolts() {
        bolts.toList().forEach { bolt ->
            if (bolt !in bolts) return@forEach
            val player = Bukkit.getPlayer(bolt.cast.casterId)
            if (player == null || !ready(player) || player.world != bolt.position.world ||
                !damage.isCurrent(player, bolt.cast) ||
                bolt.target?.let { !damage.eligible(player, it, bolt.cast) } == true) {
                bolts.remove(bolt); effects.remove(bolt.visualId); return@forEach
            }
            if (bolt.launchDelayTicks > 0) {
                bolt.launchDelayTicks--
                if (bolt.launchDelayTicks > 0) return@forEach
            }
            val from = bolt.position
            bolt.age++
            val targetAt = bolt.target?.let(::center) ?: bolt.fixedAimPoint
            if (targetAt != null && bolt.age > bolt.holdForwardTicks) {
                val desired = targetAt.toVector().subtract(from.toVector())
                steerStaffBolt(bolt.direction, desired, bolt.age)
            }
            val step = min(if (bolt.spell == StaffSpell.ICICLE) staffIcicleStep(bolt.age)
                else staffLightningStep(bolt.age), bolt.remainingDistance)
            val blockEnd = rayEnd(from, bolt.direction, step)
            val frostIcicle = bolt.spell == StaffSpell.ICICLE
            val victim = lineTargets(player, from, blockEnd, 0.38, bolt.cast).firstOrNull {
                frostIcicle || it.uniqueId !in bolt.visited
            }
            val hitPoint = victim?.boundingBox?.expand(0.38)
                ?.rayTrace(from.toVector(), bolt.direction, from.distance(blockEnd))?.hitPosition?.toLocation(from.world)
            val end = hitPoint ?: blockEnd
            if (frostIcicle) {
                visuals.withOwner(bolt.cast.casterId) { visuals.icicleTrail(from, end) }
                effects.moveProjectile(bolt.visualId, end, bolt.direction)
            } else {
                visuals.withOwner(bolt.cast.casterId) { visuals.lightningTrail(from, end) }
                bolt.trail += end.clone()
                effects.moveTrail(bolt.visualId, bolt.trail)
            }
            bolt.remainingDistance -= step
            if (victim != null || from.distanceSquared(blockEnd) < step * step - 0.0001 ||
                bolt.remainingDistance <= 0.001 || bolt.age >= 60) {
                bolts.remove(bolt)
                if (frostIcicle) {
                    effects.impact(bolt.visualId, end, 0.9, FROST_ICICLE_SHATTER_TICKS)
                    visuals.withOwner(bolt.cast.casterId) { visuals.icicleBurst(end) }
                    if (bolt.burstRadius > 0.0) {
                        areaDamage(player, end, bolt.burstRadius, bolt.cast, bolt.tuning,
                            primary = victim, hitTargets = bolt.visited).forEach {
                            if (damage.isCurrent(player, bolt.cast) && damage.eligible(player, it, bolt.cast))
                                it.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS,
                                    settings.frostSlowTicks, 1, false, true))
                        }
                    }
                } else effects.finishTrail(bolt.visualId)
                if (victim != null && visible(player.eyeLocation, center(victim))) {
                    if (frostIcicle) {
                        if (bolt.burstRadius == 0.0 && bolt.visited.add(victim.uniqueId) &&
                            damage.hit(player, victim, bolt.cast, bolt.tuning.power * bolt.scale,
                                bolt.tuning.vanillaDamage * bolt.scale)) {
                            if (damage.isCurrent(player, bolt.cast) && damage.eligible(player, victim, bolt.cast))
                                victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS,
                                    settings.frostSlowTicks, 1, false, true))
                        }
                    } else {
                        bolt.visited += victim.uniqueId
                        if (damage.isCurrent(player, bolt.cast))
                            visuals.withOwner(bolt.cast.casterId) {
                                visuals.lightning(end, end.clone().add(0.0, 0.6, 0.0))
                            }
                    }
                    if (!frostIcicle && damage.hit(player, victim, bolt.cast, bolt.tuning.power * bolt.scale,
                            bolt.tuning.vanillaDamage * bolt.scale) && damage.isCurrent(player, bolt.cast) &&
                        bolt.jumps > 1 && ready(player)) {
                        val next = nearby(player, end, settings.chainRadius, bolt.cast).filter { it.uniqueId !in bolt.visited }
                            .sortedBy { center(it).distanceSquared(end) }
                            .firstOrNull { visible(end, center(it)) && visible(player.eyeLocation, center(it)) }
                        if (next != null) launchBolt(player, end,
                            center(next).toVector().subtract(end.toVector()).normalize().rotateAroundY(-0.4),
                            next, bolt.cast, bolt.tuning, bolt.scale * settings.chainDecay, bolt.jumps - 1,
                            bolt.visited, settings.chainRadius * 2.0)
                    }
                }
            } else bolt.position = end
        }
    }

    private fun frost(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        val origin = player.location.clone()
        val yaw = Math.toRadians(origin.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw)).normalize()
        val end = origin.clone().add(forward.clone().multiply(settings.frostRange))
        val spread = tan(Math.toRadians(settings.frostDegrees))
        val candidates = nearby(player, origin, settings.frostRange, cast)
            .filter { inFrostCone(origin, forward, center(it), settings.frostRange, spread) }
            .sortedBy { horizontalDistance(origin, center(it)) }
            .take(MAX_WAVE_CANDIDATES)
        val radius = spread * settings.frostRange
        visuals.withOwner(cast.casterId) {
            visuals.frost(origin, end, settings.frostDegrees, WAVE_START_DELAY_TICKS, FROST_TRAVEL_TICKS)
        }
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
            if (player == null || !ready(player) || player.world.uid != wave.worldId ||
                !damage.isCurrent(player, wave.cast)) {
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
                        if (!damage.eligible(player, target, wave.cast) || !visible(wave.origin, at) ||
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
            if (!ready(player) || player.world.uid != wave.worldId || !damage.isCurrent(player, wave.cast) ||
                !damage.eligible(player, target, wave.cast) || !intersectsFront(wave, at, staged.previousFront, staged.front) ||
                !visible(wave.origin, at) || !visible(player.eyeLocation, at)) return@hitLoop
            val damaged = damage.hit(player, target, wave.cast, wave.tuning.power, wave.tuning.vanillaDamage)
            if (damaged && wave.slowTicks != null && damage.isCurrent(player, wave.cast) &&
                damage.eligible(player, target, wave.cast))
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
        val distance = waveDistance(wave, at)
        if (wave.directional &&
            !inFrostCone(wave.origin, wave.forward, at, wave.range, wave.frostSpread)) return false
        return distance + WAVE_BODY_TOLERANCE >= previousFront &&
            distance - WAVE_BODY_TOLERANCE <= front
    }

    private fun waveDistance(wave: PendingStaffWave, at: Location) =
        if (wave.spell == StaffSpell.NOVA && wave.directional) forwardDistance(wave.origin, wave.forward, at)
        else horizontalDistance(wave.origin, at)

    private fun tickMarks() {
        val pulses = mutableListOf<Pair<Player, PendingStaffMark>>()
        val due = mutableListOf<Pair<Player, PendingStaffMark>>()
        val iterator = marks.iterator()
        while (iterator.hasNext()) {
            val (playerId, mark) = iterator.next()
            val player = Bukkit.getPlayer(playerId)
            val origin = mark.target?.let(::center) ?: mark.point
            if (player == null || !ready(player) || player.world.uid != mark.worldId ||
                !damage.isCurrent(player, mark.cast) ||
                (mark.target != null && !damage.eligible(player, mark.target, mark.cast)) ||
                player.eyeLocation.distanceSquared(origin) > (settings.range + 1) * (settings.range + 1)) {
                iterator.remove()
                effects.remove(mark.visualId)
                continue
            }
            effects.move(mark.visualId, origin)
            mark.remainingTicks -= 2
            if (mark.gravity && mark.remainingTicks > 0 && mark.remainingTicks % 4 == 0)
                visuals.withOwner(mark.cast.casterId) { visuals.gravity(origin, 4.5, 1.0 - mark.remainingTicks / 40.0) }
            if (mark.gravity && mark.remainingTicks > 0 && mark.remainingTicks % 8 == 0) pulses += player to mark
            if (mark.remainingTicks <= 0) {
                iterator.remove()
                due += player to mark
            } else {
                if (!mark.gravity) visuals.withOwner(mark.cast.casterId) {
                    visuals.markCharge(origin, 1.0 - mark.remainingTicks.toDouble() / settings.markTicks)
                }
            }
        }
        pulses.forEach { (player, mark) ->
            val origin = mark.point
            val candidates = nearby(player, origin, 4.5, mark.cast).sortedBy { center(it).distanceSquared(origin) }
                .take(MAX_WAVE_CANDIDATES)
            var attempts = 0
            for (victim in candidates) {
                if (marks[player.uniqueId] !== mark || !ready(player) || player.world.uid != mark.worldId ||
                    !damage.isCurrent(player, mark.cast)) break
                if (attempts >= settings.maxAreaTargets) break
                if (!damage.eligible(player, victim, mark.cast) || !visible(origin, center(victim)) ||
                    !visible(player.eyeLocation, center(victim))) continue
                attempts++
                if (damage.hit(player, victim, mark.cast, mark.tuning.power * 0.12, mark.tuning.vanillaDamage * 0.12) &&
                    marks[player.uniqueId] === mark && damage.isCurrent(player, mark.cast) &&
                    damage.eligible(player, victim, mark.cast)) {
                    val pull = origin.toVector().subtract(center(victim).toVector())
                    if (pull.lengthSquared() > 0.04) victim.velocity = pull.normalize().multiply(0.48).apply { y = y.coerceIn(-0.15, 0.18) }
                }
            }
        }
        due.forEach { (player, mark) ->
            if (!damage.isCurrent(player, mark.cast)) return@forEach
            val origin = mark.target?.let(::center) ?: mark.point
            val tuning = if (mark.gravity) mark.tuning.copy(power = mark.tuning.power * 0.65,
                vanillaDamage = mark.tuning.vanillaDamage * 0.65) else mark.tuning
            val radius = if (mark.gravity) 4.5 else settings.markRadius
            areaDamage(player, origin, radius, mark.cast, tuning, mark.target)
            if (!damage.isCurrent(player, mark.cast)) return@forEach
            visuals.withOwner(mark.cast.casterId) { visuals.markBurst(origin, radius, blackhole = mark.gravity) }
            effects.impact(mark.visualId, origin, radius, MARK_IMPACT_DURATION_TICKS)
        }
    }

    private fun tickEmbers() {
        val impacts = mutableListOf<Triple<Player, PendingStaffEmber, Pair<Location, LivingEntity?>>>()
        val iterator = embers.iterator()
        while (iterator.hasNext()) {
            val ember = iterator.next()
            val player = Bukkit.getPlayer(ember.cast.casterId)
            if (player == null || !ready(player) || player.world != ember.position.world ||
                !damage.isCurrent(player, ember.cast)) {
                iterator.remove()
                effects.remove(ember.visualId)
                continue
            }
            val from = ember.position
            ember.age++
            val distance = min(ember.remainingDistance,
                staffEmberStep(ember.age, ember.speed ?: settings.emberSpeed, meteor = ember.radius != null))
            val blockEnd = rayEnd(from, ember.direction, distance)
            if (!damage.isCurrent(player, ember.cast)) {
                iterator.remove(); effects.remove(ember.visualId); continue
            }
            val victim = lineTargets(player, from, blockEnd, 0.25, ember.cast).firstOrNull()
            val collision = victim?.boundingBox?.expand(0.25)
                ?.rayTrace(from.toVector(), ember.direction, from.distance(blockEnd))?.hitPosition
            val end = collision?.toLocation(from.world) ?: blockEnd
            visuals.withOwner(ember.cast.casterId) {
                visuals.emberTrail(ember.visualPosition, end, meteor = ember.radius != null)
            }
            ember.remainingDistance -= distance
            if (victim != null || from.distanceSquared(blockEnd) < distance * distance - 0.0001 || ember.remainingDistance <= 0.001) {
                iterator.remove()
                impacts += Triple(player, ember, end to victim)
            } else {
                ember.position = end
                ember.visualPosition = end
                if ((ember.age - 1) % StaffSpellDisplayEffects.FRAME_TICKS == 0) {
                    // Native Display interpolation consumes the next two ticks. Lead its target
                    // by that interval; logical collision and damage still run every tick.
                    val ahead = min(ember.remainingDistance, (1..StaffSpellDisplayEffects.FRAME_TICKS).sumOf {
                        staffEmberStep(ember.age + it, ember.speed ?: settings.emberSpeed, meteor = ember.radius != null)
                    })
                    val visualEnd = rayEnd(end, ember.direction, ahead)
                    val visualVictim = lineTargets(player, end, visualEnd, 0.25, ember.cast).firstOrNull()
                    val visualHit = visualVictim?.boundingBox?.expand(0.25)
                        ?.rayTrace(end.toVector(), ember.direction, end.distance(visualEnd))?.hitPosition
                    effects.move(ember.visualId, visualHit?.toLocation(end.world) ?: visualEnd)
                }
            }
        }
        // Damage events can themselves trigger quit/teleport cleanup; no active list iterator here.
        impacts.forEach { (player, ember, impact) ->
            if (!damage.isCurrent(player, ember.cast)) {
                effects.remove(ember.visualId)
                return@forEach
            }
            val radius = ember.radius ?: settings.emberRadius
            areaDamage(player, impact.first, radius, ember.cast, ember.tuning, impact.second, ember.hitTargets)
            if (!damage.isCurrent(player, ember.cast)) {
                effects.remove(ember.visualId)
                return@forEach
            }
            visuals.withOwner(ember.cast.casterId) { visuals.emberBurst(impact.first, radius, meteor = ember.radius != null) }
            effects.impact(ember.visualId, impact.first, radius, EMBER_IMPACT_DURATION_TICKS)
        }
    }

    private fun areaDamage(player: Player, origin: Location, radius: Double, cast: StaffSpellCast,
        tuning: StaffSpellTuning, primary: LivingEntity? = null, hitTargets: MutableSet<UUID>? = null): List<LivingEntity> {
        if (!visible(player.eyeLocation, origin)) return emptyList()
        return (listOfNotNull(primary) + nearby(player, origin, radius, cast).sortedBy { center(it).distanceSquared(origin) })
            .distinctBy { it.uniqueId }
            .filter { visible(origin, center(it)) && visible(player.eyeLocation, center(it)) }
            .filter { hitTargets == null || it.uniqueId !in hitTargets }
            .take((settings.maxAreaTargets - (hitTargets?.size ?: 0)).coerceAtLeast(0))
            .filter {
                hitTargets?.add(it.uniqueId)
                damage.hit(player, it, cast, tuning.power, tuning.vanillaDamage)
            }
    }

    /** Exact swept collision along the cast line, never soft acquisition. */
    private fun lineTargets(
        player: Player,
        from: Location,
        to: Location,
        width: Double,
        cast: StaffSpellCast,
    ): List<LivingEntity> {
        val direction = to.toVector().subtract(from.toVector())
        val length = direction.length()
        if (length < 0.001) return emptyList()
        direction.multiply(1.0 / length)
        val midpoint = from.clone().add(direction.clone().multiply(length / 2))
        return from.world.getNearbyLivingEntities(midpoint, length / 2 + width + 2)
            .filter { damage.eligible(player, it, cast) }
            .mapNotNull { entity ->
                entity.boundingBox.expand(width).rayTrace(from.toVector(), direction, length)
                    ?.let { entity to it.hitPosition.distanceSquared(from.toVector()) }
            }.sortedBy { it.second }.map { it.first }.filter { visible(from, center(it)) }
    }

    private fun aimPoint(player: Player) = rayEnd(player.eyeLocation, player.eyeLocation.direction, settings.range)

    private fun groundBelow(from: Location, range: Double): Location? {
        val hit = from.world.rayTraceBlocks(from, Vector(0.0, -1.0, 0.0), range, FluidCollisionMode.NEVER, true)
            ?: return null
        return hit.hitPosition.toLocation(from.world).add(0.0, 0.04, 0.0)
    }

    private fun rayEnd(from: Location, direction: Vector, range: Double): Location {
        val hit = from.world.rayTraceBlocks(from, direction, range, FluidCollisionMode.NEVER, true)
        // Keep bursts just outside the wall, so line-of-sight starts in air.
        val distance = hit?.hitPosition?.distance(from.toVector())?.let { (it - 0.04).coerceAtLeast(0.0) } ?: range
        return from.clone().add(direction.clone().multiply(distance))
    }

    private fun target(player: Player, cast: StaffSpellCast? = null) =
        aimed(player, settings.range, settings.aimDegrees, cast).firstOrNull()

    private fun aimed(player: Player, range: Double, angle: Double, cast: StaffSpellCast? = null): List<LivingEntity> {
        val eye = player.eyeLocation
        val look = eye.direction
        return nearby(player, eye, range, cast).mapNotNull { target ->
            val offset = center(target).subtract(eye).toVector()
            // A direct hit on a tall/near mob's body must not fail because its centre is off-axis.
            val direct = target.boundingBox.expand(0.25).rayTrace(eye.toVector(), look, range) != null
            (if (direct) 1.0 else staffAimScore(offset.x, offset.y, offset.z, look.x, look.y, look.z, range, angle))
                ?.let { Triple(target, it, offset.lengthSquared()) }
        }.sortedWith(compareByDescending<Triple<LivingEntity, Double, Double>> { it.second }.thenBy { it.third })
            .take(32) // Bound expensive line-of-sight checks in dense mob farms.
            .filter { visible(eye, center(it.first)) }.map { it.first }
    }

    private fun nearby(player: Player, origin: Location, radius: Double, cast: StaffSpellCast? = null) = origin.world
        .getNearbyLivingEntities(origin, radius, radius, radius)
        .filter { center(it).distanceSquared(origin) <= radius * radius && damage.eligible(player, it, cast) }

    private fun ready(player: Player) = player.isOnline && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR && player.isValid

    private fun cancel(playerId: UUID) {
        marks.remove(playerId)
        embers.removeAll { it.cast.casterId == playerId }
        waves.removeAll { it.casterId == playerId }
        bolts.removeAll { it.cast.casterId == playerId }
        delayedCasts.remove(playerId)
        cooldownLengths.remove(playerId)
        hideCooldown(playerId)
        visuals.cancel(playerId)
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
        bolts.clear()
        delayedCasts.clear()
        cooldownLengths.clear()
        cooldownBars.keys.toList().forEach(::hideCooldown)
        effects.close()
    }

    private companion object {
        const val WAVE_STEP_TICKS = 2
        const val WAVE_START_DELAY_TICKS = 2
        const val WAVE_BODY_TOLERANCE = 0.6
        const val MAX_WAVE_CANDIDATES = 32
        const val MAX_BOLTS_PER_CASTER = 8
        const val FROST_ICICLE_SHATTER_TICKS = 10
        const val FROST_TRAVEL_TICKS = 12
        const val FROST_DURATION_TICKS = 24
        const val NOVA_INITIAL_FRONT_RADIUS = 1.8
        const val NOVA_TRAVEL_TICKS = 16
        const val NOVA_DURATION_TICKS = 20
        const val MARK_IMPACT_DURATION_TICKS = 20
        const val EMBER_IMPACT_DURATION_TICKS = 20
    }

}

private data class PendingStaffMark(val target: LivingEntity?, val point: Location, val worldId: UUID,
    val cast: StaffSpellCast, val tuning: StaffSpellTuning, var remainingTicks: Int, val visualId: UUID?, val gravity: Boolean = false)

private data class PendingStaffEmber(var position: Location, val direction: Vector,
    val cast: StaffSpellCast, val tuning: StaffSpellTuning, var remainingDistance: Double, val visualId: UUID?,
    var visualPosition: Location, val speed: Double? = null, val radius: Double? = null,
    val hitTargets: MutableSet<UUID>? = null, var age: Int = 0)

private data class PendingStaffBolt(var position: Location, val direction: Vector, val target: LivingEntity?,
    val cast: StaffSpellCast, val tuning: StaffSpellTuning, val scale: Double, val jumps: Int,
    val visited: MutableSet<UUID>, var remainingDistance: Double, val visualId: UUID?, var age: Int = 0,
    val trail: MutableList<Location> = mutableListOf(position.clone()), val spell: StaffSpell = StaffSpell.CHAIN,
    val fixedAimPoint: Location? = null, var launchDelayTicks: Int = 0, val holdForwardTicks: Int = 0, val burstRadius: Double = 0.0)

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
    val directional: Boolean = spell == StaffSpell.FROST,
) {
    val attemptedTargets = mutableSetOf<UUID>()
    var elapsedTicks = 0
    var targetAttempts = 0

    fun frontAt(ageTicks: Int): Double {
        if (ageTicks < startDelayTicks) return 0.0
        val expansion = staffWaveFront(ageTicks, range, travelTicks)
        return if (initialFront > 0.0) initialFront + staffWaveFront(ageTicks,
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

/** A slow ignition grows into a fast discharge; collision sweeps the full variable-length step. */
internal fun staffEmberStep(age: Int, speed: Double, meteor: Boolean = false): Double {
    val t = ((age - 1).coerceAtLeast(0) / if (meteor) 8.0 else 16.0).coerceIn(0.0, 1.0)
    val eased = t * t * (3.0 - 2.0 * t)
    return speed * if (meteor) 0.75 + eased else 0.28 + 1.62 * eased
}

/** Shared by live pursuit and the offline flight fixture; changes only the supplied heading. */
internal fun steerStaffBolt(direction: Vector, offset: Vector, age: Int) {
    if (offset.lengthSquared() <= 0.000001) return
    val turn = if (offset.lengthSquared() < 36.0) 0.72 else if (age < 4) 0.10 else 0.42
    direction.multiply(1.0 - turn).add(offset.clone().normalize().multiply(turn)).normalize()
}

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
    override val defaultUsage = "/arc stafftest [all|chain|mark|frost|lance|ember|nova|icicle|fault] [игрок]"

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

/** A short probing leader followed by a fast discharge, repeated along the homing route. */
internal fun staffLightningStep(age: Int): Double = when ((age - 1).coerceAtLeast(0) % 4) {
    0 -> 1.5
    1 -> 5.5
    2 -> 0.75
    else -> 5.5
}
