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
            Bukkit.getPluginManager().registerEvents(it.input, ARC.instance)
            it.start()
        }
    }

    override fun reload() = init()
    override fun shutdown() {
        controller?.let { HandlerList.unregisterAll(it); HandlerList.unregisterAll(it.input); it.close() }
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
    summonActorFactory: ((org.bukkit.World, Location, Boolean) -> org.bukkit.entity.AbstractSkeleton)? = null,
) : Listener, AutoCloseable, StaffSpellInputActions {
    private val tasks = LifecycleTaskScope()
    private val visuals = StaffSpellVisuals(tasks)
    private val targeting = StaffSpellTargeting(settings, damage)
    private val arsenal = StaffSpellArsenal(settings, damage, effects, visuals, targeting)
    private val gestures = StaffSpellGestures(settings, damage, visuals, targeting)
    private val summons = StaffSpellSummons(settings, damage, visuals, targeting, summonActorFactory)
    internal val input = StaffSpellInput(this)
    private val chargeScenes = mutableMapOf<UUID, UUID?>()
    private val lances = mutableListOf<PendingStaffLance>()
    private val cooldownInputs = mutableMapOf<UUID, StaffCastInput>()
    private val marks = mutableMapOf<UUID, PendingStaffMark>()
    private val embers = mutableListOf<PendingStaffEmber>()
    private val waves = mutableListOf<PendingStaffWave>()
    private val bolts = mutableListOf<PendingStaffBolt>()
    private val delayedCasts = mutableMapOf<UUID, UUID>()
    private val cooldownLengths = mutableMapOf<UUID, Long>()
    private val cooldownBars = mutableMapOf<UUID, BossBar>()
    private val cooldownId = "staff-spells"

    fun start() {
        input.start()
        arsenal.start()
        gestures.start()
        summons.start()
        tasks.runTimer(4, 4) {
            cooldownLengths.keys.filter { CooldownManager.cooldown(it, cooldownId) <= 0 }.forEach {
                cooldownLengths.remove(it)
                cooldownInputs.remove(it)
            }
            Bukkit.getOnlinePlayers().forEach { player ->
                val spell = StaffSpell.from(player.inventory.itemInMainHand)
                if (ready(player) && spell != null) {
                    if (player.uniqueId !in chargeScenes) showCooldown(player, spell)
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
        tasks.runTimer(1, 1) { tickEmbers(); tickBolts(); tickLances() }
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

    // Event ownership lives in StaffSpellInput; these wrappers preserve direct test entry points.
    internal fun onInteract(event: PlayerInteractEvent) = input.onInteract(event)

    override fun canCharge(player: Player, spell: StaffSpell): Boolean {
        if (!ready(player)) return false
        if (CooldownManager.isOnCooldown(player.uniqueId, cooldownId)) {
            showCooldown(player, spell)
            visuals.cooldownFeedback(player, spell)
            return false
        }
        return true
    }

    override fun charge(player: Player, spell: StaffSpell, progress: Double) {
        val id = chargeScenes.getOrPut(player.uniqueId) {
            effects.play(player.uniqueId, spell, player.eyeLocation, durationTicks = 220, charging = true)
        }
        effects.charge(id, player.eyeLocation, progress)
        visuals.withOwner(player.uniqueId) { visuals.chargeFeedback(player, spell, progress) }
        val bar = cooldownBars.getOrPut(player.uniqueId) {
            BossBar.bossBar(config.charge(spell, progress, player.isSneaking), progress.toFloat(), BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS)
                .also(player::showBossBar)
        }
        bar.name(config.charge(spell, progress, player.isSneaking))
        bar.progress(progress.toFloat().coerceIn(0f, 1f))
        bar.color(if (progress >= 1.0) BossBar.Color.YELLOW else BossBar.Color.PURPLE)
    }

    override fun cancelCharge(player: Player) {
        visuals.cancelChargeFeedback(player.uniqueId)
        effects.remove(chargeScenes.remove(player.uniqueId))
        StaffSpell.from(player.inventory.itemInMainHand)?.let { showCooldown(player, it) }
    }

    override fun cast(player: Player, input: StaffCastInput, charge: Double) = castSpell(player, input, charge)

    internal fun cast(player: Player) = castSpell(player, StaffCastInput.of(player.isSneaking, false), 0.0)

    private fun castSpell(player: Player, input: StaffCastInput, charge: Double) {
        val item = player.inventory.itemInMainHand
        val spell = StaffSpell.from(item) ?: return
        if (!ready(player)) return
        if (CooldownManager.isOnCooldown(player.uniqueId, cooldownId)) {
            showCooldown(player, spell)
            visuals.cooldownFeedback(player, spell)
            return
        }
        val level = if (input.quick) 0.0 else charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val cast = damage.capture(player, duelOnly = StaffSpell.isDuelOnly(item))?.copy(visualCharge = level) ?: run {
            player.sendActionBar(config.text("unavailable"))
            return
        }
        val base = settings.tuning.getValue(spell)
        val multiplier = (1.0 + level * 0.75) * if (input.quick) 0.55 else 1.0
        val tuning = base.copy(power = base.power * multiplier, vanillaDamage = base.vanillaDamage * multiplier)
        val secondary = input.secondary
        val cooldownTicks = when {
            input.quick -> maxOf(8, tuning.cooldownTicks / 2)
            !secondary -> tuning.cooldownTicks
            spell in setOf(StaffSpell.MARK, StaffSpell.EMBER) -> tuning.cooldownTicks * 2
            else -> tuning.cooldownTicks * 3 / 2
        }
        cooldownInputs[player.uniqueId] = input
        cooldownLengths[player.uniqueId] = cooldownTicks
        CooldownManager.addCooldown(player.uniqueId, cooldownId, cooldownTicks)
        showCooldown(player, spell)
        if (!input.quick && spell !in ARSENAL_SPELLS) visuals.withOwner(cast.casterId) { visuals.release(player.eyeLocation, spell, level >= 0.75) }
        if (!input.quick && spell == StaffSpell.CHAIN) visuals.withOwner(cast.casterId) { visuals.lightningLaunch(player.eyeLocation) }
        if (input.quick) gestures.cast(player, spell, cast, tuning, input)
        else if (spell == StaffSpell.NECROMANCER) summons.cast(player, cast, tuning, input, level)
        else if (spell in ARSENAL_SPELLS) arsenal.cast(player, spell, cast, tuning, input, level)
        else if (secondary) castSecondary(player, spell, cast, tuning, level) else when (spell) {
            StaffSpell.CHAIN -> chain(player, target(player, cast), cast, tuning)
            StaffSpell.MARK -> {
                val selected = target(player, cast)
                val point = selected?.let(::center) ?: aimPoint(player)
                effects.remove(marks.remove(player.uniqueId)?.visualId)
                val visualId = effects.play(player.uniqueId, spell, point, radius = 1.3, durationTicks = settings.markTicks, charge = cast.visualCharge)
                marks[player.uniqueId] = PendingStaffMark(selected, point, player.world.uid, cast, tuning, settings.markTicks, visualId)
                visuals.withOwner(cast.casterId) { visuals.markLaunch(visualStart(player.eyeLocation, point), point) }
            }
            StaffSpell.FROST -> frost(player, cast, tuning)
            StaffSpell.ICICLE -> frostIcicles(player, cast, tuning, 7 + (level * 4).toInt())
            StaffSpell.FAULT -> fault(player, cast, tuning, secondary = false)
            StaffSpell.LANCE -> lance(player, cast, tuning, secondary = false)
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
                    durationTicks = kotlin.math.ceil(settings.range / settings.emberSpeed).toInt() + 4, controllerDriven = true, charge = cast.visualCharge)
                embers += PendingStaffEmber(eye, direction, cast, tuning, settings.range, visualId, muzzle)
                visuals.withOwner(cast.casterId) { visuals.emberTrail(muzzle, muzzle) }
            }
            else -> Unit // Additional staff families have their own bounded attack lifecycle.

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
        cooldownInputs.remove(playerId)
        cooldownLengths.remove(playerId)
    }

    private fun showCooldown(player: Player, spell: StaffSpell) {
        val remaining = CooldownManager.cooldown(player.uniqueId, cooldownId)
        val total = cooldownLengths[player.uniqueId] ?: settings.tuning.getValue(spell).cooldownTicks
        val bar = cooldownBars.getOrPut(player.uniqueId) {
            BossBar.bossBar(config.cooldown(spell, player.isSneaking, remaining, total,
                if (remaining > 0) cooldownInputs[player.uniqueId] ?: StaffCastInput.PRIMARY else StaffCastInput.of(player.isSneaking, false)),
                staffCooldownProgress(remaining, total), BossBar.Color.BLUE, BossBar.Overlay.PROGRESS)
                .also(player::showBossBar)
        }
        bar.name(config.cooldown(spell, player.isSneaking, remaining, total,
                if (remaining > 0) cooldownInputs[player.uniqueId] ?: StaffCastInput.PRIMARY else StaffCastInput.of(player.isSneaking, false)))
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

    private fun castSecondary(player: Player, spell: StaffSpell, cast: StaffSpellCast, tuning: StaffSpellTuning, level: Double) {
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
                    durationTicks = 40, secondary = true, charge = cast.visualCharge)
                marks[player.uniqueId] = PendingStaffMark(null, at, player.world.uid,
                    cast, tuning, 40, visualId, gravity = true)
                visuals.withOwner(cast.casterId) { visuals.markLaunch(visualStart(player.eyeLocation, at), at) }
            }
            StaffSpell.ICICLE -> hail(player, cast, tuning, 5 + (level * 4).toInt())
            StaffSpell.FAULT -> fault(player, cast, tuning, secondary = true)
            StaffSpell.FROST -> frostRing(player, cast, tuning, settings.novaRadius)
            StaffSpell.LANCE -> lance(player, cast, tuning, secondary = true)
            StaffSpell.EMBER -> meteorShower(player, cast, tuning)
            else -> Unit
        }
    }

    /** Damage follows the same front as the client display, including its two-tick interpolation. */
    private fun lance(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning, secondary: Boolean,
        range: Double = settings.range, width: Double = settings.lanceWidth) {
        val eye = player.eyeLocation
        val visited = mutableSetOf<UUID>()
        (if (secondary) listOf(-16.0, 0.0, 16.0) else listOf(0.0)).forEach { angle ->
            val end = rayEnd(eye, eye.direction.rotateAroundY(Math.toRadians(angle)), range)
            launchLance(player, eye, end, cast, if (secondary) tuning.copy(power = tuning.power * 0.65,
                vanillaDamage = tuning.vanillaDamage * 0.65) else tuning, width, visited, secondary)
        }
    }

    private fun launchLance(player: Player, from: Location, end: Location, cast: StaffSpellCast,
        tuning: StaffSpellTuning, width: Double, visited: MutableSet<UUID>, secondary: Boolean = false) {
        while (lances.count { it.cast.casterId == cast.casterId } >= 6) {
            val removed = lances.first { it.cast.casterId == cast.casterId }
            effects.remove(removed.visualId); lances.remove(removed)
        }
        val id = effects.play(player.uniqueId, StaffSpell.LANCE, from, end,
            radius = 0.85, durationTicks = staffLanceDuration(from.distance(end)), impact = true, secondary = secondary, charge = cast.visualCharge)
        visuals.withOwner(cast.casterId) { visuals.lance(from, end) }
        lances += PendingStaffLance(from.clone(), end.clone(), cast, tuning, width, visited, id)
    }

    private fun tickLances() {
        lances.toList().forEach { lance ->
            if (lance !in lances) return@forEach
            val player = Bukkit.getPlayer(lance.cast.casterId)
            if (player == null || !ready(player) || player.world != lance.from.world || !damage.isCurrent(player, lance.cast)) {
                lances.remove(lance); effects.remove(lance.visualId); return@forEach
            }
            lance.age++
            val length = lance.from.distance(lance.end)
            if (length < 0.001) { lances.remove(lance); return@forEach }
            val visualAge = lance.age - StaffSpellDisplayEffects.FRAME_TICKS
            if (visualAge < 4) return@forEach
            val direction = lance.end.toVector().subtract(lance.from.toVector()).normalize()
            val front = staffLanceFront(visualAge, length)
            val from = lance.from.clone().add(direction.clone().multiply(lance.previousFront))
            val to = rayEnd(lance.from, direction, front)
            if (to.distance(lance.from) >= lance.previousFront) {
                lineTargets(player, from, to, lance.width, lance.cast).forEach { victim ->
                    if (lance !in lances || !ready(player) || player.world != lance.from.world || !damage.isCurrent(player, lance.cast)) return@forEach
                    if (lance.visited.size < settings.lanceTargets && lance.visited.add(victim.uniqueId) &&
                        visible(player.eyeLocation, center(victim)))
                        damage.hit(player, victim, lance.cast, lance.tuning.power, lance.tuning.vanillaDamage)
                }
            }
            lance.previousFront = front
            if (front >= length - 0.001 || lance.visited.size >= settings.lanceTargets) lances.remove(lance)
        }
    }

    private fun delayedPulse(player: Player, spell: StaffSpell, at: Location, radius: Double, delay: Long,
        cast: StaffSpellCast, tuning: StaffSpellTuning, secondary: Boolean) {
        delayedCasts[cast.casterId] = cast.attackId
        val worldId = player.world.uid
        when (spell) {
            StaffSpell.MARK -> visuals.withOwner(cast.casterId) { visuals.markLaunch(player.eyeLocation, at) }
            StaffSpell.FAULT -> visuals.withOwner(cast.casterId) { visuals.faultCharge(at, radius) }
            StaffSpell.EMBER -> visuals.withOwner(cast.casterId) { visuals.emberTrail(at, at) }
            else -> Unit
        }
        tasks.runLater(delay) {
            if (delayedCasts[cast.casterId] != cast.attackId || !ready(player) || player.world.uid != worldId ||
                !damage.isCurrent(player, cast) || !at.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4) || !visible(player.eyeLocation, at)) return@runLater
            delayedCasts.remove(cast.casterId)
            val hit = areaDamage(player, at, radius, cast, tuning)
            if (!damage.isCurrent(player, cast)) return@runLater
            when (spell) {
                StaffSpell.MARK -> {
                    visuals.withOwner(cast.casterId) { visuals.markBurst(at, radius, secondary) }
                    if (secondary) hit.filter { damage.eligible(player, it, cast) }.forEach { victim ->
                        val pull = at.toVector().subtract(center(victim).toVector())
                        if (pull.lengthSquared() > 0.1) victim.velocity = pull.normalize().multiply(0.55)
                    }
                }
                StaffSpell.EMBER -> visuals.withOwner(cast.casterId) { visuals.emberBurst(at, radius) }
                StaffSpell.FROST -> {
                    visuals.withOwner(cast.casterId) { visuals.wave(at, at, radius, frost = true, directional = false) }
                    hit.filter { damage.eligible(player, it, cast) }.forEach { it.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, settings.frostSlowTicks, 1)) }
                }
                StaffSpell.FAULT -> visuals.withOwner(cast.casterId) { visuals.faultBurst(at, radius) }
                else -> Unit
            }
            effects.play(player.uniqueId, spell, at, radius = radius, durationTicks = 20, impact = true, secondary = secondary, charge = cast.visualCharge)
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
                            radius = 1.2, durationTicks = 24, secondary = true, controllerDriven = true, charge = cast.visualCharge)
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

    private fun frostIcicles(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning, count: Int = 7) {
        val eye = player.eyeLocation
        val yaw = Math.toRadians(player.location.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw)).normalize()
        val right = Vector(forward.z, 0.0, -forward.x).normalize()
        // Leave the shoulders horizontally before seeking: a downwards look must not shoot through the eye.
        val direction = forward.clone()
        val targets = aimed(player, settings.range, settings.aimDegrees, cast).take(count)
        val aimPoint = aimPoint(player)
        val visited = mutableSetOf<UUID>()

        repeat(count) { index ->
            val offset = staffIcicleAnchorOffset(index, count)
            val anchor = eye.clone()
                .add(right.clone().multiply(offset.x.toDouble()))
                .add(0.0, offset.y.toDouble(), 0.0)
                .add(forward.clone().multiply(offset.z.toDouble()))
            if (!visible(eye, anchor)) return@repeat

            reserveBolt(player.uniqueId)
            val target = targets.getOrNull(index) ?: targets.firstOrNull()
            val visualId = effects.play(player.uniqueId, StaffSpell.ICICLE, anchor,
                anchor.clone().add(direction), radius = 0.9, durationTicks = 80, charge = cast.visualCharge)
            bolts += PendingStaffBolt(anchor, direction.clone(), target, cast, tuning, 1.0, 1,
                visited, settings.range, visualId, spell = StaffSpell.ICICLE,
                fixedAimPoint = if (target == null) aimPoint.clone() else null,
                launchDelayTicks = staffIcicleLaunchDelay(index), holdForwardTicks = 4)
        }
    }

    private fun hail(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning, count: Int = 5) {
        val selected = target(player, cast)?.let(::center) ?: aimPoint(player)
        val center = groundBelow(selected.clone().add(0.0, 0.2, 0.0), 16.0) ?: selected
        val visited = mutableSetOf<UUID>()
        repeat(count) { index ->
            val angle = index * Math.PI * 2 / count
            val impact = center.clone().add(cos(angle) * 2.2, 0.0, sin(angle) * 2.2)
            if (!visible(player.eyeLocation, impact)) return@repeat
            val top = rayEnd(impact.clone().add(0.0, 0.15, 0.0), Vector(0.0, 1.0, 0.0), 10.0)
            val distance = top.y - impact.y
            if (distance < 0.5 || !top.world.isChunkLoaded(top.blockX shr 4, top.blockZ shr 4)) return@repeat
            reserveBolt(player.uniqueId)
            val direction = Vector(0.0, -1.0, 0.0)
            val id = effects.play(player.uniqueId, StaffSpell.ICICLE, top, top.clone().add(direction),
                radius = 0.9, durationTicks = 60, secondary = true, charge = cast.visualCharge)
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
                        durationTicks = if (secondary) 24 else 22, impact = true, secondary = secondary, charge = cast.visualCharge)
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
            radius = 0.8, durationTicks = 80, charge = cast.visualCharge)
        bolts += PendingStaffBolt(from.clone(), direction.clone().normalize(), target, cast, tuning,
            scale, jumps, visited, distance, id)
    }

    private fun tickBolts() {
        bolts.toList().forEach { bolt ->
            if (bolt !in bolts) return@forEach
            val player = Bukkit.getPlayer(bolt.cast.casterId)
            if (player == null || !ready(player) || player.world != bolt.position.world ||
                !damage.isCurrent(player, bolt.cast) ||
                (bolt.spell != StaffSpell.ICICLE && bolt.target?.let { !damage.eligible(player, it, bolt.cast) } == true)) {
                bolts.remove(bolt); effects.remove(bolt.visualId); return@forEach
            }
            if (bolt.launchDelayTicks > 0) {
                bolt.launchDelayTicks--
                if (bolt.launchDelayTicks > 0) return@forEach
            }
            val from = bolt.position
            bolt.age++
            val targetAt = bolt.target?.takeIf { damage.eligible(player, it, bolt.cast) }?.let(::center) ?: bolt.fixedAimPoint
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

    private fun frostRing(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning, range: Double) {
        val origin = player.location.clone()
        val candidates = nearby(player, origin, range, cast).sortedBy { horizontalDistance(origin, center(it)) }
            .take(MAX_WAVE_CANDIDATES)
        val id = effects.play(player.uniqueId, StaffSpell.FROST, origin, radius = range,
            durationTicks = 30, impact = true, secondary = true, charge = cast.visualCharge)
        visuals.withOwner(cast.casterId) { visuals.wave(origin, origin, range, frost = true, directional = false) }
        waves += PendingStaffWave(player.uniqueId, player.world.uid, StaffSpell.FROST, origin, Vector(),
            range, candidates, cast, tuning, id, WAVE_START_DELAY_TICKS, 16, 30, 1.8,
            slowTicks = settings.frostSlowTicks, directional = false)
    }

    private fun frost(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning,
        range: Double = settings.frostRange, degrees: Double = settings.frostDegrees) {
        val origin = player.location.clone()
        val yaw = Math.toRadians(origin.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw)).normalize()
        val end = origin.clone().add(forward.clone().multiply(range))
        val spread = tan(Math.toRadians(degrees))
        val candidates = nearby(player, origin, range, cast)
            .filter { inFrostCone(origin, forward, center(it), range, spread) }
            .sortedBy { horizontalDistance(origin, center(it)) }
            .take(MAX_WAVE_CANDIDATES)
        val radius = spread * range
        visuals.withOwner(cast.casterId) { visuals.frost(origin, end, degrees, WAVE_START_DELAY_TICKS, FROST_TRAVEL_TICKS) }
        val visualId = effects.play(player.uniqueId, StaffSpell.FROST, origin, end,
            radius = radius, durationTicks = FROST_DURATION_TICKS, charge = cast.visualCharge)
        waves += PendingStaffWave(player.uniqueId, player.world.uid, StaffSpell.FROST, origin, forward,
            range, candidates, cast, tuning, visualId, WAVE_START_DELAY_TICKS,
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
            // The client reaches each two-tick display keyframe after interpolation.
            // Keep its native-particle wake behind that keyframe, never at the future collision point.
            if (ember.age % 2 == 0) visuals.withOwner(ember.cast.casterId) {
                visuals.emberTrail(ember.visualPosition, from, meteor = ember.radius != null)
                ember.visualPosition = end.clone()
            }
            ember.remainingDistance -= distance
            if (victim != null || from.distanceSquared(blockEnd) < distance * distance - 0.0001 || ember.remainingDistance <= 0.001) {
                iterator.remove()
                impacts += Triple(player, ember, end to victim)
            } else {
                ember.position = end
                if (ember.age % StaffSpellDisplayEffects.FRAME_TICKS == 0) {
                    effects.move(ember.visualId, end)
                    effects.synchronize(ember.visualId, ember.age)
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

    private fun lineTargets(player: Player, from: Location, to: Location, width: Double, cast: StaffSpellCast) = targeting.lineTargets(player, from, to, width, cast)
    private fun aimPoint(player: Player) = targeting.aimPoint(player)
    private fun groundBelow(from: Location, range: Double) = targeting.groundBelow(from, range)
    private fun rayEnd(from: Location, direction: Vector, range: Double) = targeting.rayEnd(from, direction, range)
    private fun target(player: Player, cast: StaffSpellCast? = null) = aimed(player, settings.range, settings.aimDegrees, cast).firstOrNull()
    private fun aimed(player: Player, range: Double, angle: Double, cast: StaffSpellCast? = null) = targeting.aimed(player, range, angle, cast)
    private fun nearby(player: Player, origin: Location, radius: Double, cast: StaffSpellCast? = null) = targeting.nearby(player, origin, radius, cast)

    private fun ready(player: Player) = player.isOnline && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR && player.isValid

    private fun cancel(playerId: UUID) {
        input.cancel(playerId)
        arsenal.cancel(playerId)
        gestures.cancel(playerId)
        summons.cancel(playerId)
        effects.remove(chargeScenes.remove(playerId))
        lances.removeAll { it.cast.casterId == playerId }
        marks.remove(playerId)
        embers.removeAll { it.cast.casterId == playerId }
        waves.removeAll { it.casterId == playerId }
        bolts.removeAll { it.cast.casterId == playerId }
        delayedCasts.remove(playerId)
        hideCooldown(playerId)
        visuals.cancel(playerId)
        effects.cancel(playerId)
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) = cancel(event.player.uniqueId)
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) = cancel(event.player.uniqueId)
    @EventHandler fun onDeath(event: PlayerDeathEvent) = cancel(event.entity.uniqueId)

    override fun close() {
        input.close()
        visuals.close()
        arsenal.close()
        gestures.close()
        summons.close()
        chargeScenes.values.forEach(effects::remove)
        chargeScenes.clear()
        lances.clear()
        cooldownInputs.clear()
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
        val ARSENAL_SPELLS = setOf(StaffSpell.NOVA, StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM, StaffSpell.PRISM)
        const val WAVE_STEP_TICKS = 2
        const val WAVE_START_DELAY_TICKS = 2
        const val WAVE_BODY_TOLERANCE = 0.6
        const val MAX_WAVE_CANDIDATES = 32
        const val MAX_BOLTS_PER_CASTER = 12
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

private data class PendingStaffLance(val from: Location, val end: Location, val cast: StaffSpellCast,
    val tuning: StaffSpellTuning, val width: Double, val visited: MutableSet<UUID>, val visualId: UUID?,
    var age: Int = 0, var previousFront: Double = 0.0)

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
    override val defaultUsage = "/arc stafftest [all|${StaffSpell.entries.joinToString("|") { it.id }}] [игрок]"

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
