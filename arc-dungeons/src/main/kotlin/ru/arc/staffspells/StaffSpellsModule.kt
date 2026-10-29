package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.plugin.ServicePriority
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.dungeons.ArcDungeonsPlugin
import ru.arc.dungeons.api.ArcStaffCooldowns
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.particle.PaperViewerParticles
import ru.arc.staffspells.api.QuestGuidanceHudService
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
    private var guidanceHud: QuestGuidanceHudService? = null
    private var duelAccess: StaffSpellDuelAccess? = null
    private var masteryCooldown: ((Player, StaffSpellCast, Long) -> Long)? = null

    internal fun installDuelAccess(access: StaffSpellDuelAccess?) {
        duelAccess = access
    }

    internal fun installMasteryCooldown(adjust: ((Player, StaffSpellCast, Long) -> Long)?) {
        masteryCooldown = adjust
    }

    internal fun masteryCooldownTicks(player: Player, cast: StaffSpellCast, ticks: Long): Long =
        masteryCooldown?.invoke(player, cast, ticks) ?: ticks

    internal fun activeController() = controller
    internal fun itemConfig(): StaffSpellConfig? = controller?.config
    internal fun mobTargetProtected(player: Player) = duelAccess?.let {
        !it.inputAllowed(player) || it.context(player) != null
    } ?: false

    override fun init() {
        val plugin = ArcDungeonsPlugin.instance
        val cooldowns = requireNotNull(Bukkit.getServicesManager().load(ArcStaffCooldowns::class.java)) {
            "ARC staff cooldown service is not registered"
        }
        val config = StaffSpellConfig(ConfigManager.of(plugin.legacyDataPath, "modules/staff-spells.yml"))
        val settings = config.settings // Validate the replacement before closing the active generation.
        val mobSettings = config.mobCasters
        shutdown()
        val scenery = config.sceneryWorlds.takeIf { it.isNotEmpty() }?.let { worlds ->
            StaffSceneryEffects(PaperPacketDisplays(plugin, "staff-scenery"), worlds).also {
                Bukkit.getPluginManager().registerEvents(it, plugin)
            }
        }
        val displays = PaperPacketDisplays(plugin, "staff-spells")
        val hud = StaffSpellHud(displays, config, ownDisplays = false)
        controller = StaffSpellController(config, settings, StaffSpellDamage(duelAccess),
            StaffSpellDisplayEffects(displays), cooldowns, PaperViewerParticles(plugin, "staff-spells"),
            scenery = scenery, hud = hud,
            mobSettings = mobSettings).also {
            Bukkit.getPluginManager().registerEvents(it, plugin)
            Bukkit.getPluginManager().registerEvents(it.input, plugin)
            Bukkit.getPluginManager().registerEvents(it.defense, plugin)
            Bukkit.getPluginManager().registerEvents(it.summons, plugin)
            Bukkit.getPluginManager().registerEvents(it.conjurations, plugin)
            Bukkit.getPluginManager().registerEvents(it.mobCasters, plugin)
            it.start()
        }
        guidanceHud = object : QuestGuidanceHudService {
            override fun show(player: Player, source: QuestGuidanceHudService.Source, text: net.kyori.adventure.text.Component): Boolean {
                check(Bukkit.isPrimaryThread()) { "Quest guidance must be shown on the Paper thread" }
                return hud.showGuidance(player, source, text)
            }

            override fun clear(playerId: UUID, source: QuestGuidanceHudService.Source) {
                check(Bukkit.isPrimaryThread()) { "Quest guidance must be cleared on the Paper thread" }
                hud.clearGuidance(playerId, source)
            }
        }.also { Bukkit.getServicesManager().register(QuestGuidanceHudService::class.java, it, plugin, ServicePriority.Normal) }
    }

    override fun reload() = init()
    override fun shutdown() {
        guidanceHud?.let { Bukkit.getServicesManager().unregister(QuestGuidanceHudService::class.java, it) }
        guidanceHud = null
        controller?.let { HandlerList.unregisterAll(it); HandlerList.unregisterAll(it.input); HandlerList.unregisterAll(it.defense); HandlerList.unregisterAll(it.summons); HandlerList.unregisterAll(it.conjurations); HandlerList.unregisterAll(it.mobCasters); it.close() }
        controller = null
    }

    internal fun give(player: Player, spells: List<StaffSpell>) = controller?.give(player, spells) ?: false
    internal fun ready() = controller != null
}

/** Shared spell runtime for prototype, duel and migrated EliteMobs weapons. */
internal class StaffSpellController(
    internal val config: StaffSpellConfig,
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val effects: StaffSpellDisplayEffects,
    private val cooldowns: ArcStaffCooldowns,
    private val particles: PaperViewerParticles,
    summonActorFactory: ((org.bukkit.World, Location, Boolean) -> org.bukkit.entity.AbstractSkeleton)? = null,
    private val scenery: StaffSceneryEffects? = null,
    private val hud: StaffSpellHud? = null,
    private val hotkeys: StaffHotkeyCooldowns = StaffHotkeyCooldowns(),
    mobSettings: StaffMobCasterSettings = config.mobCasters,
) : Listener, AutoCloseable, StaffSpellInputActions {
    private val tasks = LifecycleTaskScope()
    private val visuals = StaffSpellVisuals(tasks, particles, sceneryImpact = { owner, at, radius ->
        scenery?.impact(owner, at, radius)
    }, sceneryDepression = { owner, at, radius ->
        scenery?.depress(owner, at, radius)
    })
    private val targeting = StaffSpellTargeting(settings, damage)
    internal val mobCasters = StaffMobCasters(config, mobSettings, effects, particles)
    internal val conjurations = StaffSpellConjurations(settings, damage, effects, visuals, targeting)
    private val arsenal = StaffSpellArsenal(settings, damage, effects, visuals, targeting,
        rootTarget = conjurations::rootTarget, rootActive = conjurations::rootActive,
        clearRoots = conjurations::clearRoots)
    private val melee = StaffSpellMelee(settings, damage, effects, visuals, targeting,
        onFrostSlow = conjurations::rememberFrostSlow)
    internal val defense = StaffSpellDefense(config, damage, visuals, effects)
    private val channels = StaffSpellChannels(settings, damage, effects, visuals, targeting)
    private data class ChannelInput(val spell: StaffSpell, val cast: StaffSpellCast)
    private val channelInputs = mutableMapOf<UUID, ChannelInput>()
    internal val summons = StaffSpellSummons(settings, damage, visuals, targeting, summonActorFactory)
    private val techniques = StaffSpellTechniques(settings, damage, effects, visuals, targeting, summons,
        rootTarget = conjurations::rootTarget, rootActive = conjurations::rootActive,
        clearRoots = conjurations::clearRoots)
    private val mobility = StaffSpellMobility(settings, damage, effects, visuals, targeting)
    init {
        damage.summonCast = summons::castFor
        hud?.armyCount = { player -> summons.countFor(player.uniqueId) }
        hud?.hotkeyState = { player, spell -> StaffHotkeyHudState(
            hotkeys.remaining(player.uniqueId, StaffAbility.Q),
            hotkeys.remaining(player.uniqueId, StaffAbility.F),
            techniques.continuationLabel(player.uniqueId, spell) ?: conjurations.continuationLabel(player.uniqueId, spell),
        ) }
    }
    internal val input = StaffSpellInput(this)
    private val chargeScenes = mutableMapOf<UUID, List<UUID?>>()
    private val lances = mutableListOf<PendingStaffLance>()
    private val marks = mutableMapOf<UUID, PendingStaffMark>()
    private val embers = mutableListOf<PendingStaffEmber>()
    private val waves = mutableListOf<PendingStaffWave>()
    private val bolts = mutableListOf<PendingStaffBolt>()
    private val delayedCasts = mutableMapOf<UUID, UUID>()
    private val gcdOwners = mutableSetOf<UUID>()

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPulseFallDamage(event: EntityDamageEvent) {
        channels.suppressPulseFallDamage(event)
    }

    fun start() {
        mobCasters.start()
        input.start()
        hud?.start()
        arsenal.start()
        defense.start()
        channels.start()
        summons.start()
        conjurations.start()
        techniques.start()
        mobility.start()
        tasks.runTimer(4, 4) {
            hotkeys.prune()
            gcdOwners.removeIf { cooldowns.remainingTicks(it) <= 0 }
            Bukkit.getOnlinePlayers().forEach { player ->
                val spell = StaffSpell.from(player.inventory.itemInMainHand)
                if (ready(player) && spell != null) {
                    if (player.uniqueId !in chargeScenes && player.uniqueId !in channelInputs) showCooldown(player, spell)
                } else hideHud(player.uniqueId)
                if (ready(player) && !StaffSpell.isDuelOnly(player.inventory.itemInMainHand) &&
                    spell in setOf(StaffSpell.CHAIN, StaffSpell.MARK)) {
                    listOfNotNull(target(player)).forEach { selected ->
                        val cue = center(selected).add(0.0, selected.height * 0.55, 0.0)
                        if (cue.distanceSquared(player.eyeLocation) >= 3.2 * 3.2)
                            particles.emit(player, cue, Particle.END_ROD, 2, 0.13, 0.05, 0.13, 0.0, null, false)
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
        if (spells.any { it !in StaffSpell.available }) {
            player.sendActionBar(config.text("retired"))
            return false
        }
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

    override fun refreshPrototypeLore(item: org.bukkit.inventory.ItemStack, spell: StaffSpell): Boolean =
        config.refreshPrototypeLore(item, spell)

    override fun canCharge(player: Player, spell: StaffSpell, secondary: Boolean): Boolean {
        if (spell !in StaffSpell.available) {
            player.sendActionBar(config.text("retired"))
            return false
        }
        if (!ready(player)) return false
        if (attackRemaining(player.uniqueId, secondary) > 0) {
            showCooldown(player, spell)
            visuals.cooldownFeedback(player, spell)
            return false
        }
        return true
    }

    override fun canHotkey(player: Player, spell: StaffSpell, key: StaffHotkey): Boolean {
        if (!ready(player) || spell !in StaffSpell.available || cooldowns.remainingTicks(player.uniqueId) > 0) return false
        if (key == StaffHotkey.Q && canRecast(player.uniqueId, spell)) return true
        if (hotkeys.remaining(player.uniqueId, StaffAbility.hotkey(key)) > 0) {
            visuals.cooldownFeedback(player, spell)
            showCooldown(player, spell)
            return false
        }
        return true
    }

    override fun hotkey(player: Player, spell: StaffSpell, key: StaffHotkey) {
        if (!ready(player) || spell !in StaffSpell.available || cooldowns.remainingTicks(player.uniqueId) > 0 ||
            StaffSpell.from(player.inventory.itemInMainHand) != spell) return
        val recast = key == StaffHotkey.Q && canRecast(player.uniqueId, spell)
        if (!recast && hotkeys.remaining(player.uniqueId, StaffAbility.hotkey(key)) > 0) return
        val cast = damage.capture(player, duelOnly = StaffSpell.isDuelOnly(player.inventory.itemInMainHand)) ?: return
        val pvpDenied = key == StaffHotkey.Q && targeting.reportDeniedAim(player, cast)
        val tuning = settings.tuningFor(spell, duel = cast.duelAuthorization != null)
        val accepted = if (key == StaffHotkey.F) mobility.cast(player, spell, cast, tuning)
            else if (spell in CONJURATION_SPELLS) conjurations.cast(player, spell, cast, tuning)
            else techniques.cast(player, spell, cast, tuning)
        if (accepted) {
            if (!recast) hotkeys.start(player.uniqueId, StaffAbility.hotkey(key), config.hotkeyCooldown(spell, key))
            beginGcd(player.uniqueId)
        } else if (!pvpDenied) {
            visuals.cooldownFeedback(player, spell)
            player.sendMessage(config.hotkeyUnavailable(spell, key))
        }
        showCooldown(player, spell)
    }

    private fun canRecast(playerId: UUID, spell: StaffSpell) = techniques.canRecast(playerId, spell) || conjurations.canRecast(playerId, spell)

    override fun defend(player: Player, spell: StaffSpell) {
        if (spell !in StaffSpell.available) {
            player.sendActionBar(config.text("retired"))
            return
        }
        if (!ready(player) || cooldowns.remainingTicks(player.uniqueId) > 0) return
        val cast = damage.capture(player, duelOnly = StaffSpell.isDuelOnly(player.inventory.itemInMainHand)) ?: return
        if (defense.activate(player, spell, cast, player.isSneaking)) beginGcd(player.uniqueId)
    }

    override fun beginChannel(player: Player, spell: StaffSpell): Boolean {
        if (spell !in StaffSpell.available) {
            player.sendActionBar(config.text("retired"))
            return false
        }
        if (!canCharge(player, spell, secondary = false)) return false
        val cast = damage.capture(player, duelOnly = StaffSpell.isDuelOnly(player.inventory.itemInMainHand)) ?: return false
        if (spell != StaffSpell.GRAVITY) targeting.reportDeniedAim(player, cast)
        if (!channels.begin(player, spell, cast, settings.tuningFor(spell, duel = cast.duelAuthorization != null), player.isSneaking)) {
            player.sendActionBar(config.text("no-grab"))
            return false
        }
        channelInputs[player.uniqueId] = ChannelInput(spell, cast)
        hud?.channel(player, spell, player.isSneaking)
        return true
    }

    override fun updateChannel(player: Player, ticksHeld: Int): Boolean {
        if (channelInputs[player.uniqueId] == null) return false
        return channels.update(player, ticksHeld)
    }

    override fun releaseChannel(player: Player) = finishChannel(player, release = true)
    override fun cancelChannel(player: Player) = finishChannel(player, release = false)
    private fun finishChannel(player: Player, release: Boolean) {
        val (spell, cast) = channelInputs.remove(player.uniqueId) ?: return
        if (release) targeting.reportDeniedAim(player, cast)
        if (release) channels.release(player) else channels.cancel(player.uniqueId)
        beginCooldown(player, spell, secondary = false, settings.tuningFor(spell, duel = cast.duelAuthorization != null).cooldownTicks, cast, startGcd = release)
    }

    private fun beginCooldown(player: Player, spell: StaffSpell, secondary: Boolean, ticks: Long, cast: StaffSpellCast, startGcd: Boolean = true): Long {
        val adjusted = StaffSpellsModule.masteryCooldownTicks(player, cast, ticks)
        hotkeys.start(player.uniqueId, StaffAbility.attack(secondary), adjusted)
        if (startGcd) beginGcd(player.uniqueId)
        if (ready(player)) showCooldown(player, spell)
        return adjusted
    }

    override fun charge(player: Player, spell: StaffSpell, progress: Double) {
        val ids = chargeScenes.getOrPut(player.uniqueId) {
            List(if (spell == StaffSpell.ICICLE) 11 else 1) { index ->
                val at = if (spell == StaffSpell.ICICLE) icicleChargeAnchor(player, index) else player.eyeLocation
                effects.play(player.uniqueId, spell, at, durationTicks = 220, charging = true,
                    chargeSlot = index.takeIf { spell == StaffSpell.ICICLE })
            }
        }
        ids.forEachIndexed { index, id ->
            effects.charge(id, if (spell == StaffSpell.ICICLE) icicleChargeAnchor(player, index) else player.eyeLocation, progress)
        }
        visuals.withOwner(player.uniqueId) { visuals.chargeFeedback(player, spell, progress) }
        if (spell == StaffSpell.EMBER && player.isSneaking) {
            meteorPoints(player).forEach { at -> visuals.withOwner(player.uniqueId) {
                visuals.particles(at, staffMeteorWarningParticles(progress, locked = false))
            } }
        }
        hud?.charging(player, spell, player.isSneaking, progress)
    }

    override fun cancelCharge(player: Player) {
        visuals.cancelChargeFeedback(player.uniqueId)
        chargeScenes.remove(player.uniqueId)?.forEach(effects::remove)
        StaffSpell.from(player.inventory.itemInMainHand)?.let { showCooldown(player, it) }
    }

    override fun cast(player: Player, input: StaffCastInput, charge: Double) = castSpell(player, input, charge)

    internal fun cast(player: Player) = castSpell(player, StaffCastInput.of(player.isSneaking, false), 0.0)

    private fun castSpell(player: Player, input: StaffCastInput, charge: Double) {
        val item = player.inventory.itemInMainHand
        val spell = StaffSpell.from(item) ?: return
        if (spell !in StaffSpell.available) {
            player.sendActionBar(config.text("retired"))
            return
        }
        if (!ready(player) || cooldowns.remainingTicks(player.uniqueId) > 0) return
        if (input.quick) { defend(player, spell); return }
        if (spell == StaffSpell.GRAVITY && !input.secondary) return
        if (attackRemaining(player.uniqueId, input.secondary) > 0) {
            showCooldown(player, spell)
            visuals.cooldownFeedback(player, spell)
            return
        }
        val level = if (input.quick) 0.0 else charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        if (spell == StaffSpell.ICICLE && !input.secondary && staffIcicleCount(level) == 0) return
        val cast = damage.capture(player, duelOnly = StaffSpell.isDuelOnly(item))?.copy(visualCharge = level) ?: run {
            player.sendActionBar(config.text("unavailable"))
            return
        }
        targeting.reportDeniedAim(player, cast)
        val base = settings.tuningFor(spell, duel = cast.duelAuthorization != null)
        val multiplier = (1.0 + level * 0.75) * if (input.quick) 0.55 else 1.0
        val tuning = base.copy(power = base.power * multiplier, vanillaDamage = base.vanillaDamage * multiplier)
        val secondary = input.secondary
        val cooldownTicks = when {
            input.quick -> maxOf(8, tuning.cooldownTicks / 2)
            !secondary -> tuning.cooldownTicks
            spell == StaffSpell.CHAIN -> maxOf(80L, tuning.cooldownTicks * 2)
            spell == StaffSpell.REQUIEM -> maxOf(100L, tuning.cooldownTicks * 2)
            spell in setOf(StaffSpell.MARK, StaffSpell.EMBER) -> tuning.cooldownTicks * 2
            else -> tuning.cooldownTicks * 3 / 2
        }
        if (spell == StaffSpell.NECROMANCER) {
            when (summons.castResult(player, cast, tuning, input, level)) {
                StaffSummonCastResult.CAST -> Unit
                StaffSummonCastResult.NO_GROUND -> {
                    player.sendActionBar(config.text("no-summon-ground"))
                    return
                }
                StaffSummonCastResult.CAPACITY -> {
                    player.sendActionBar(config.text("no-summon-cap"))
                    return
                }
            }
        }
        if (spell == StaffSpell.GRAVITY && !channels.gravityPulse(player, cast, tuning)) return
        val closeAttack = !secondary && staffMeleeReach(spell) != null
        if (!closeAttack && spell in ARSENAL_SPELLS) {
            val admission = arsenal.cast(player, spell, cast, tuning, input, level)
            if (admission != StaffArsenalCastResult.ACCEPTED) {
                player.sendActionBar(config.text(when (admission) {
                    StaffArsenalCastResult.NO_GROUND -> "no-ground"
                    StaffArsenalCastResult.BLOCKED -> "blocked-path"
                    else -> "unavailable"
                }))
                return
            }
        }
        val adjustedCooldownTicks = beginCooldown(player, spell, secondary, cooldownTicks, cast)
        if (!closeAttack && staffPreparationParts(spell, secondary, 0).isNotEmpty())
            effects.play(player.uniqueId, spell, player.eyeLocation, durationTicks = 10,
                secondary = secondary, preparation = true)
        if (!closeAttack && !input.quick && spell !in ARSENAL_SPELLS) visuals.withOwner(cast.casterId) { visuals.release(player.eyeLocation, spell, level >= 0.75) }
        if (!input.quick && spell == StaffSpell.CHAIN) visuals.withOwner(cast.casterId) { visuals.lightningLaunch(player.eyeLocation) }
        if (closeAttack) melee.cast(player, cast, spell, tuning)
        else if (spell == StaffSpell.GRAVITY) Unit // Already admitted before consuming cooldown.
        else if (spell == StaffSpell.NECROMANCER) Unit // Already admitted before consuming cooldown.
        else if (spell in ARSENAL_SPELLS) Unit // Admitted before consuming cooldown.
        else if (secondary) castSecondary(player, spell, cast, tuning, level) else when (spell) {
            StaffSpell.CHAIN -> chain(player, cast, tuning)
            StaffSpell.MARK -> {
                val selected = target(player, cast)
                val point = selected?.let(::center) ?: aimPoint(player)
                cancelMark(player.uniqueId)
                val visualId = effects.play(player.uniqueId, spell, point, radius = 1.3, durationTicks = settings.markTicks, charge = cast.visualCharge)
                marks[player.uniqueId] = PendingStaffMark(selected, point, player.world.uid, cast, tuning, settings.markTicks, visualId)
                visuals.withOwner(cast.casterId) { visuals.markLaunch(visualStart(player.eyeLocation, point), point) }
            }
            StaffSpell.FROST -> frost(player, cast, tuning)
            StaffSpell.ICICLE -> frostIcicles(player, cast, tuning, staffIcicleCount(level),
                chargeScenes.remove(player.uniqueId).orEmpty())
            StaffSpell.FAULT -> fault(player, cast, tuning, secondary = false)
            StaffSpell.LANCE -> lance(player, cast, tuning, secondary = false)
            StaffSpell.EMBER -> {
                if (embers.count { it.cast.casterId == player.uniqueId } >= 8) {
                    val removed = embers.removeAt(embers.indexOfFirst { it.cast.casterId == player.uniqueId })
                    effects.remove(removed.visualId)
                }
                val heldId = chargeScenes.remove(player.uniqueId)?.firstOrNull()
                val eye = player.eyeLocation
                val fallbackOffset = staffChargeOrientation(spell, eye.yaw,
                    org.joml.Vector3f(eye.direction.x.toFloat(), eye.direction.y.toFloat(), eye.direction.z.toFloat()))
                    .transform(staffEmberChargeOffset())
                val heldOrigin = effects.chargeOrigin(heldId) ?: eye.clone().add(
                    fallbackOffset.x.toDouble(), fallbackOffset.y.toDouble(), fallbackOffset.z.toDouble())
                val muzzleDelta = heldOrigin.toVector().subtract(eye.toVector())
                val muzzleDistance = muzzleDelta.length()
                val muzzleDirection = muzzleDelta.clone().normalize()
                // Check the held body's path out of cover before transferring its scene to flight.
                val muzzle = rayEnd(eye, muzzleDirection, muzzleDistance)
                val near = lineTargets(player, eye, muzzle, .25, cast).firstOrNull()
                if (near != null || eye.distanceSquared(muzzle) < muzzleDistance * muzzleDistance - .0001) {
                    val at = near?.boundingBox?.expand(.25)?.rayTrace(eye.toVector(), muzzleDirection, eye.distance(muzzle))
                        ?.hitPosition?.toLocation(eye.world) ?: muzzle
                    areaDamage(player, at, settings.emberRadius, cast, tuning, near)
                    visuals.withOwner(cast.casterId) { visuals.emberBurst(at, settings.emberRadius) }
                    if (effects.releaseCharge(heldId, EMBER_IMPACT_DURATION_TICKS) != null)
                        effects.impact(heldId, at, settings.emberRadius, EMBER_IMPACT_DURATION_TICKS)
                    else effects.play(player.uniqueId, spell, at, radius = settings.emberRadius,
                        durationTicks = EMBER_IMPACT_DURATION_TICKS, impact = true, charge = level)
                    return
                }
                val assisted = targeting.assistedDirection(player, spell, cast, from = muzzle)
                val target = rayEnd(eye, assisted, settings.range)
                val direction = target.toVector().subtract(muzzle.toVector()).normalize()
                val duration = kotlin.math.ceil(settings.range / settings.emberSpeed).toInt() + 4
                val visualId = effects.releaseCharge(heldId, duration, controllerDriven = true)
                    ?: effects.play(player.uniqueId, spell, muzzle,
                    muzzle.clone().add(direction), radius = 0.85,
                    durationTicks = duration, controllerDriven = true, charge = cast.visualCharge)
                val ember = PendingStaffEmber(muzzle, direction, cast, tuning,
                    settings.range - eye.distance(muzzle), visualId, muzzle,
                    target = targeting.homingTarget(player, spell, cast, from = muzzle))
                embers += ember
                visuals.withOwner(cast.casterId) { visuals.emberTrail(muzzle, muzzle) }
                // Seed the first forward keyframe on release instead of waiting for the two-tick frame cadence.
                tickEmbers(ember)
            }
            else -> Unit // Additional staff families have their own bounded attack lifecycle.

        }
        tasks.runLater(adjustedCooldownTicks) {
            if (ready(player) && StaffSpell.from(player.inventory.itemInMainHand) != null && damage.isCurrent(player, cast))
                player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.3f, 1.8f)
        }
    }

    internal fun duelItem(spell: StaffSpell) = config.duelItem(spell)

    internal fun duelFingerprint(pluginVersion: String) = settings.fingerprint(pluginVersion)

    internal fun resetPlayer(playerId: UUID) {
        cancel(playerId)
        defense.reset(playerId)
        cooldowns.clear(playerId)
        gcdOwners.remove(playerId)
        hotkeys.clear(playerId)
    }

    private fun beginGcd(playerId: UUID) {
        cooldowns.start(playerId, GLOBAL_COOLDOWN_TICKS)
        gcdOwners += playerId
    }

    internal fun skillRemaining(playerId: UUID, secondary: Boolean = false): Long =
        hotkeys.remaining(playerId, StaffAbility.attack(secondary))

    private fun attackRemaining(playerId: UUID, secondary: Boolean): Long =
        maxOf(cooldowns.remainingTicks(playerId), skillRemaining(playerId, secondary))

    private fun showCooldown(player: Player, spell: StaffSpell) {
        val skill = skillRemaining(player.uniqueId, player.isSneaking)
        val gcd = cooldowns.remainingTicks(player.uniqueId)
        val total = if (skill >= gcd) hotkeys.total(player.uniqueId, StaffAbility.attack(player.isSneaking))
            else GLOBAL_COOLDOWN_TICKS
        hud?.cooldown(player, spell, player.isSneaking, maxOf(skill, gcd), total.coerceAtLeast(1))
    }

    private fun hideHud(playerId: UUID) = hud?.clear(playerId)

    private fun visualStart(from: Location, to: Location): Location {
        val delta = to.toVector().subtract(from.toVector())
        val length = delta.length()
        return if (length < 0.001) to.clone()
        else from.clone().add(delta.multiply(min(1.4, length) / length))
    }

    private fun castSecondary(player: Player, spell: StaffSpell, cast: StaffSpellCast, tuning: StaffSpellTuning, level: Double) {
        when (spell) {
            StaffSpell.CHAIN -> chainStormNova(player, cast, tuning)
            StaffSpell.MARK -> {
                val at = target(player, cast)?.let { staffVisibleTargetPoint(player.eyeLocation, it) } ?: aimPoint(player).let { aimed ->
                    // A floor aim needs an airborne core; the upward ray still respects ceilings.
                    val floor = groundBelow(aimed.clone().add(0.0, .1, 0.0), .35)
                    if (floor == null) aimed else rayEnd(floor, Vector(0.0, 1.0, 0.0), 1.15)
                        .takeIf { visible(player.eyeLocation, it) } ?: aimed
                }
                cancelMark(player.uniqueId)
                val visualId = effects.play(player.uniqueId, spell, at, radius = STAFF_BLACK_HOLE_RADIUS,
                    durationTicks = STAFF_BLACK_HOLE_TICKS, secondary = true, charge = cast.visualCharge)
                marks[player.uniqueId] = PendingStaffMark(null, at, player.world.uid,
                    cast, tuning, STAFF_BLACK_HOLE_TICKS, visualId, blackHole = StaffBlackHole(cast.attackId))
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
            val assisted = targeting.assistedDirection(player, StaffSpell.LANCE, cast, range = range)
            val end = rayEnd(eye, assisted.rotateAroundY(Math.toRadians(angle)), range)
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
                        visible(player.eyeLocation, victim))
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
                    hit.filter { damage.eligible(player, it, cast) }.forEach {
                        if (it.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, settings.frostSlowTicks, 1)))
                            conjurations.rememberFrostSlow(player, it, settings.frostSlowTicks)
                    }
                }
                StaffSpell.FAULT -> visuals.withOwner(cast.casterId) { visuals.faultBurst(at, radius) }
                else -> Unit
            }
            effects.play(player.uniqueId, spell, at, radius = radius, durationTicks = 20, impact = true, secondary = secondary, charge = cast.visualCharge)
        }
    }

    private fun meteorPoints(player: Player): List<Location> {
        val selected = aimPoint(player)
        val center = groundBelow(selected.clone().add(0.0, 0.2, 0.0), 16.0) ?: selected.clone()
        return List(3) { index ->
            val offset = center.clone().add((index - 1) * 2.0, 4.0, if (index == 1) 1.6 else -0.8)
            groundBelow(offset, 12.0) ?: offset.clone().subtract(0.0, 4.0, 0.0)
        }
    }

    private fun meteorShower(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        // One snapshot drives the warning and every projectile: turning after release cannot move the zone.
        val impacts = meteorPoints(player)
        val worldId = player.world.uid
        val hitTargets = mutableSetOf<UUID>()
        delayedCasts[cast.casterId] = cast.attackId
        fun currentCaster(): Player? = Bukkit.getPlayer(cast.casterId)?.takeIf {
            delayedCasts[cast.casterId] == cast.attackId && ready(it) && it.world.uid == worldId && damage.isCurrent(it, cast)
        }
        impacts.forEach { at -> visuals.withOwner(cast.casterId) {
            visuals.particles(at, staffMeteorWarningParticles(0.0, locked = true))
        } }
        visuals.withOwner(cast.casterId) { visuals.cue(impacts[1], Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, .8f, 1.35f) }
        for (age in 2 until STAFF_METEOR_WARNING_TICKS + 12 step 2) tasks.runLater(age.toLong()) {
            if (currentCaster() != null) impacts.forEachIndexed { index, at ->
                if (age < STAFF_METEOR_WARNING_TICKS + index * 6) visuals.withOwner(cast.casterId) {
                    visuals.particles(at, staffMeteorWarningParticles(age.toDouble() / (STAFF_METEOR_WARNING_TICKS + index * 6), locked = true))
                }
            }
        }
        impacts.forEachIndexed { index, impact ->
            tasks.runLater((STAFF_METEOR_WARNING_TICKS + index * 6).toLong()) {
                val caster = Bukkit.getPlayer(cast.casterId)
                if (caster != null && currentCaster() != null && visible(caster.eyeLocation, impact)) {
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
                            travel, id, top, speed = 1.6, radius = STAFF_METEOR_RADIUS, hitTargets = hitTargets)
                        visuals.withOwner(cast.casterId) { visuals.emberTrail(top, top, meteor = true) }
                    }
                }
                if (index == 2 && delayedCasts[cast.casterId] == cast.attackId) delayedCasts.remove(cast.casterId)
            }
        }
    }

    private fun icicleChargeAnchor(player: Player, index: Int): Location {
        val eye = player.eyeLocation
        val direction = eye.direction
        val offset = staffChargeOrientation(StaffSpell.ICICLE, eye.yaw,
            org.joml.Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()))
            .transform(staffIcicleChargeAnchorOffset(index))
        return eye.add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
    }

    private fun frostIcicles(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning, count: Int = 7,
        chargedScenes: List<UUID?> = emptyList()) {
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val rotation = staffChargeOrientation(StaffSpell.ICICLE, eye.yaw,
            org.joml.Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()))
        // Depart away from the camera in the same frame as the visible charged crown.
        val visited = mutableSetOf<UUID>()
        val volley = PendingIcicleVolley().apply {
            // Capture a moving player at release, before the crown's staggered launch delay.
            target = targeting.homingTarget(player, StaffSpell.ICICLE, cast)
            focused = target != null
        }

        repeat(count) { index ->
            val heldId = chargedScenes.getOrNull(index)
            val offset = rotation.transform(if (chargedScenes.isNotEmpty()) staffIcicleChargeAnchorOffset(index)
                else staffIcicleAnchorOffset(index, count))
            val anchor = effects.chargeOrigin(heldId) ?: eye.clone()
                .add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
            if (!visible(eye, anchor)) { effects.remove(heldId); return@repeat }

            reserveBolt(player.uniqueId)
            val visualId = effects.releaseCharge(heldId, 80) ?: run {
                val fallback = effects.play(player.uniqueId, StaffSpell.ICICLE, anchor,
                    anchor.clone().add(direction), radius = 0.9, durationTicks = 80,
                    charging = true, charge = cast.visualCharge, chargeSlot = index)
                effects.releaseCharge(fallback, 80) ?: fallback
            }
            bolts += PendingStaffBolt(anchor, direction.clone(), null, cast, tuning, 1.0, 1,
                visited, settings.range, visualId, spell = StaffSpell.ICICLE,
                volley = volley,
                launchDelayTicks = staffIcicleLaunchDelay(index), holdForwardTicks = 4)
        }
        chargedScenes.drop(count).forEach(effects::remove)
    }

    private fun hail(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning, count: Int = 5) {
        val selected = target(player, cast)?.let { staffVisibleTargetPoint(player.eyeLocation, it) } ?: aimPoint(player)
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
            val selected = target(player, cast)?.let { staffVisibleTargetPoint(player.eyeLocation, it) } ?: aimPoint(player)
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

    private fun chain(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        val direction = targeting.assistedDirection(player, StaffSpell.CHAIN, cast)
        launchBolt(player, player.eyeLocation, direction,
            targeting.homingTarget(player, StaffSpell.CHAIN, cast), cast, tuning,
            1.0, settings.chainTargets, mutableSetOf(), settings.range)
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
        val advancedVolleys = mutableSetOf<PendingIcicleVolley>()
        bolts.toList().forEach { bolt ->
            if (bolt !in bolts) return@forEach
            val player = Bukkit.getPlayer(bolt.cast.casterId)
            if (player == null || !ready(player) || player.world != bolt.position.world ||
                !bolt.position.world.isChunkLoaded(bolt.position.blockX shr 4, bolt.position.blockZ shr 4) ||
                !damage.isCurrent(player, bolt.cast) ||
                (bolt.spell != StaffSpell.ICICLE && bolt.target?.let { !damage.eligible(player, it, bolt.cast) } == true)) {
                bolts.remove(bolt); effects.remove(bolt.visualId); return@forEach
            }
            if (bolt.launchDelayTicks > 0) {
                bolt.launchDelayTicks--
                if (bolt.launchDelayTicks > 0) return@forEach
            }
            val from = bolt.position
            val volley = bolt.volley
            if (volley != null && advancedVolleys.add(volley))
                volley.searchCooldown = (volley.searchCooldown - 1).coerceAtLeast(0)
            if (bolt.age == 0 && volley != null) {
                // Empty-space shots keep their launch aim; a real target belongs to the whole volley.
                val eye = player.eyeLocation
                val assisted = targeting.assistedDirection(player, StaffSpell.ICICLE, bolt.cast, from = from)
                bolt.fixedAimPoint = rayEnd(eye, assisted, settings.range)
            }
            val targetAt = if (volley != null) icicleVolleyTarget(player, bolt, volley) else {
                val target = bolt.target
                if (target is Player && targeting.homingEnabled(bolt.spell)) {
                    targeting.homingPoint(player, target, bolt.cast, from, bolt.direction,
                        bolt.launchDirection, bolt.remainingDistance).also { if (it == null) bolt.target = null }
                } else target?.takeIf { damage.eligible(player, it, bolt.cast) }
                    ?.let { staffVisibleTargetPoint(from, it, player.eyeLocation) } ?: bolt.fixedAimPoint
            }
            bolt.age++
            if (targetAt != null && bolt.age > bolt.holdForwardTicks) {
                val desired = targetAt.toVector().subtract(from.toVector())
                val playerLock = (volley?.target ?: bolt.target) is Player && targeting.homingEnabled(bolt.spell)
                steerStaffBolt(bolt.direction, desired, bolt.age, if (playerLock) settings.aimAssist.strength else 0.0)
            }
            val step = min(if (bolt.spell == StaffSpell.ICICLE) staffIcicleStep(bolt.age)
                else staffLightningStep(bolt.age), bolt.remainingDistance)
            var blockEnd = rayEnd(from, bolt.direction, step)
            if (volley != null && targetAt != null && from.distanceSquared(blockEnd) < step * step - 0.0001) {
                val direct = targetAt.toVector().subtract(from.toVector())
                if (direct.lengthSquared() > 1e-8 &&
                    direct.lengthSquared() <= bolt.remainingDistance * bolt.remainingDistance &&
                    direct.clone().normalize().dot(bolt.direction) >= 0.7071067811865476 && visible(from, targetAt)) {
                    // A clear diagonal may miss the column that the launch impulse would touch.
                    // Use that straight line early, never a route around cover or a backward turn.
                    bolt.direction.copy(direct.normalize())
                    blockEnd = rayEnd(from, bolt.direction, step)
                }
            }
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
                if (victim != null && visible(player.eyeLocation, victim)) {
                    if (frostIcicle) {
                        bolt.volley?.takeIf { it.target == null && victim.uniqueId !in bolt.visited }?.let {
                            // A real collision also establishes focus after a merely assisted launch.
                            it.target = victim
                            it.focused = true
                            it.searchCooldown = 0
                        }
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
                            .firstOrNull { visible(end, it) && visible(player.eyeLocation, it) }
                        if (next != null) {
                            val destination = staffVisibleTargetPoint(end, next, player.eyeLocation) ?: return@forEach
                            launchBolt(player, end,
                                destination.toVector().subtract(end.toVector()).normalize().rotateAroundY(-0.4),
                                next, bolt.cast, bolt.tuning, bolt.scale * settings.chainDecay, bolt.jumps - 1,
                                bolt.visited, settings.chainRadius * 2.0)
                        }
                    }
                }
            } else bolt.position = end
        }
    }

    private fun icicleVolleyTarget(player: Player, bolt: PendingStaffBolt, volley: PendingIcicleVolley): Location? {
        val eye = player.eyeLocation
        val focus = volley.target
        if (focus != null && (focus.world != player.world || !damage.eligible(player, focus, bolt.cast) ||
                (!(focus is Player && targeting.homingEnabled(StaffSpell.ICICLE)) &&
                    !icicleWithinAim(player, focus, retaining = true)) || !visible(eye, focus))) {
            volley.target = null
            volley.searchCooldown = 0
        }
        if (volley.target == null && (bolt.age == 0 || volley.focused && volley.searchCooldown == 0)) {
            val direct = lineTargets(player, eye, aimPoint(player), .25, bolt.cast).firstOrNull {
                (!volley.focused || it.uniqueId !in bolt.visited) && icicleFlightPoint(player, bolt, it) != null
            }
            val assist = settings.aimAssist
            val playerTarget = if (direct == null) targeting.homingTarget(player, StaffSpell.ICICLE,
                bolt.cast, bolt.position, excluded = if (volley.focused) bolt.visited else emptySet())
                ?.takeIf { icicleFlightPoint(player, bolt, it) != null } else null
            val replacement = direct ?: playerTarget ?: if (volley.focused && assist.enabled && assist.strength > 0.0 &&
                StaffSpell.ICICLE.id !in assist.excludedSpells) {
                aimed(player, settings.range, minOf(12.0, assist.halfAngleDegrees), bolt.cast).firstOrNull {
                    it.uniqueId !in bolt.visited && icicleWithinAim(player, it, retaining = false) &&
                        icicleFlightPoint(player, bolt, it) != null
                }
            } else null
            volley.target = replacement
            if (replacement != null) volley.focused = true
            volley.searchCooldown = 4
        }
        // Once any shard has acquired a target, no shard may revive its obsolete launch point.
        if (volley.focused) bolt.fixedAimPoint = null
        return volley.target?.let { icicleFlightPoint(player, bolt, it) } ?: bolt.fixedAimPoint
    }

    private fun icicleWithinAim(player: Player, target: LivingEntity, retaining: Boolean): Boolean {
        val eye = player.eyeLocation
        val look = eye.direction
        if (target.boundingBox.expand(.25).rayTrace(eye.toVector(), look, settings.range) != null) return true
        val assist = settings.aimAssist
        if (!assist.enabled || assist.strength <= 0.0 || StaffSpell.ICICLE.id in assist.excludedSpells) return false
        val offset = center(target).toVector().subtract(eye.toVector())
        val along = offset.dot(look)
        if (along <= .1 || along > settings.range) return false
        val allowance = if (retaining) 1.5 else 1.0
        val angle = minOf(if (retaining) 18.0 else 12.0, assist.halfAngleDegrees * allowance)
        val miss = offset.clone().subtract(look.clone().multiply(along)).length()
        return miss <= assist.maxMiss * allowance && miss <= along * tan(Math.toRadians(angle))
    }

    private fun icicleFlightPoint(player: Player, bolt: PendingStaffBolt, target: LivingEntity): Location? {
        if (target is Player && targeting.homingEnabled(StaffSpell.ICICLE))
            return targeting.homingPoint(player, target, bolt.cast, bolt.position, bolt.direction,
                bolt.launchDirection, bolt.remainingDistance)
        val point = staffVisibleTargetPoint(bolt.position, target, player.eyeLocation) ?: return null
        val offset = point.toVector().subtract(bolt.position.toVector())
        // Retarget only through visible air that this shard can still reach; never turn back
        // through the caster or extend the original travel budget to chase a moving target.
        if (offset.lengthSquared() > bolt.remainingDistance * bolt.remainingDistance ||
            offset.dot(bolt.direction) <= 0.0) return null
        return point
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

    private fun chainStormNova(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        val origin = player.location.clone()
        val radius = minOf(STORM_NOVA_RADIUS, settings.range)
        if (!radius.isFinite() || radius <= 0.0) return
        val candidates = nearby(player, origin, radius + STORM_NOVA_VERTICAL_REACH, cast)
            .filter { target ->
                val at = center(target)
                horizontalDistance(origin, at) <= radius && abs(at.y - origin.y) <= STORM_NOVA_VERTICAL_REACH
            }
            .sortedBy { horizontalDistance(origin, center(it)) }
            .take(MAX_WAVE_CANDIDATES)
        val limits = List(STAFF_STORM_NOVA_RAY_COUNT) { index ->
            val angle = index * Math.PI * 2.0 / STAFF_STORM_NOVA_RAY_COUNT
            val direction = Vector(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle))
            rayEnd(origin.clone().add(0.0, STORM_NOVA_RAY_HEIGHT, 0.0), direction, radius)
                .let { ray -> horizontalDistance(origin, ray) }
                .coerceIn(0.0, radius)
        }
        val reducedTuning = tuning.copy(
            power = tuning.power * STORM_NOVA_DAMAGE_SCALE,
            vanillaDamage = tuning.vanillaDamage * STORM_NOVA_DAMAGE_SCALE,
        )
        visuals.withOwner(cast.casterId) {
            visuals.particles(origin, staffStormNovaFrame(0, radius, limits), clearance = STORM_NOVA_PARTICLE_CLEARANCE)
        }
        waves += PendingStaffWave(
            casterId = player.uniqueId,
            worldId = player.world.uid,
            spell = StaffSpell.CHAIN,
            origin = origin,
            forward = Vector(),
            range = radius,
            candidates = candidates,
            cast = cast,
            tuning = reducedTuning,
            visualId = null,
            startDelayTicks = STORM_NOVA_BUILDUP_TICKS,
            travelTicks = STORM_NOVA_TRAVEL_TICKS,
            durationTicks = STORM_NOVA_BUILDUP_TICKS + STORM_NOVA_TRAVEL_TICKS,
            directional = false,
            frontProgressStartTicks = STORM_NOVA_BUILDUP_TICKS,
            visualRayLimits = limits,
            hitStartTicks = STORM_NOVA_FIRST_HIT_TICK,
        )
    }

    private fun frost(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning,
        range: Double = settings.frostRange, degrees: Double = settings.frostDegrees) {
        val origin = player.location.clone()
        val yaw = Math.toRadians(origin.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw)).normalize()
        val clipped = rayEnd(origin.clone().add(0.0, .5, 0.0), forward, range)
        val reach = clipped.distance(origin.clone().add(0.0, .5, 0.0))
        if (reach < .1) return
        val end = origin.clone().add(forward.clone().multiply(reach))
        val spread = tan(Math.toRadians(degrees))
        val candidates = nearby(player, origin, range, cast)
            .filter { inFrostCone(origin, forward, center(it), range, spread) }
            .sortedBy { horizontalDistance(origin, center(it)) }
            .take(MAX_WAVE_CANDIDATES)
        val radius = spread * reach
        visuals.withOwner(cast.casterId) { visuals.frost(origin, end, degrees, WAVE_START_DELAY_TICKS, FROST_TRAVEL_TICKS) }
        val visualId = effects.play(player.uniqueId, StaffSpell.FROST, origin, end,
            radius = radius, durationTicks = FROST_DURATION_TICKS, charge = cast.visualCharge)
        waves += PendingStaffWave(player.uniqueId, player.world.uid, StaffSpell.FROST, origin, forward,
            reach, candidates, cast, tuning, visualId, WAVE_START_DELAY_TICKS,
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
            if (wave.spell == StaffSpell.CHAIN && wave.visualRayLimits != null) {
                visuals.withOwner(wave.casterId) {
                    visuals.particles(wave.origin,
                        staffStormNovaFrame(age, wave.range, wave.visualRayLimits),
                        clearance = STORM_NOVA_PARTICLE_CLEARANCE)
                    val strikes = staffStormNovaStrikes(age, wave.range, wave.visualRayLimits)
                    strikes.forEach { offset ->
                        val at = wave.origin.clone().add(offset)
                        val world = at.world ?: return@forEach
                        if (!world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) return@forEach
                        val floor = targeting.groundBelow(at.clone().add(0.0, 2.0, 0.0), 6.0) ?: at
                        // Native sky geometry only: no vanilla lightning damage, fire, or repeat flashes.
                        world.strikeLightningEffect(floor).apply {
                            isSilent = true
                            flashes = 0
                        }
                    }
                    if (strikes.isNotEmpty()) {
                        visuals.cue(wave.origin, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, .60f, 1.25f)
                        if (age == 6) visuals.cue(wave.origin, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, .45f, 1.55f)
                    }
                }
            }
            val previousFront = wave.frontAt(previousAge)
            val front = wave.frontAt(age)
            if (age >= wave.hitStartTicks && age <= wave.startDelayTicks + wave.travelTicks &&
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
                if (target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, wave.slowTicks, 1, false, true)) &&
                    wave.spell == StaffSpell.FROST) conjurations.rememberFrostSlow(player, target, wave.slowTicks)
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
        // Damage callbacks can synchronously cancel or replace a mark; never retain a map iterator.
        marks.toList().forEach { (playerId, mark) ->
            if (marks[playerId] !== mark) return@forEach
            val player = Bukkit.getPlayer(playerId)
            val origin = mark.target?.let(::center) ?: mark.point
            if (player == null || !ready(player) || player.world.uid != mark.worldId ||
                !damage.isCurrent(player, mark.cast) ||
                (mark.target != null && !damage.eligible(player, mark.target, mark.cast)) ||
                player.eyeLocation.distanceSquared(origin) > (settings.range + 1) * (settings.range + 1)) {
                cancelMark(playerId)
                return@forEach
            }
            effects.move(mark.visualId, origin)
            mark.remainingTicks -= 2
            if (mark.blackHole != null) {
                tickBlackHole(player, mark)
                return@forEach
            }
            if (mark.remainingTicks <= 0) {
                marks.remove(playerId)
                areaDamage(player, origin, settings.markRadius, mark.cast, mark.tuning, mark.target)
                if (!damage.isCurrent(player, mark.cast)) return@forEach
                visuals.withOwner(mark.cast.casterId) { visuals.markBurst(origin, settings.markRadius) }
                effects.impact(mark.visualId, origin, settings.markRadius, MARK_IMPACT_DURATION_TICKS)
            } else visuals.withOwner(mark.cast.casterId) {
                visuals.markCharge(origin, 1.0 - mark.remainingTicks.toDouble() / settings.markTicks)
            }
        }
    }

    private fun tickBlackHole(player: Player, mark: PendingStaffMark) {
        val hole = requireNotNull(mark.blackHole)
        if (!visible(player.eyeLocation, mark.point)) { cancelMark(player.uniqueId); return }
        val releasing = mark.remainingTicks <= 0
        val age = STAFF_BLACK_HOLE_TICKS - mark.remainingTicks
        // Ten-tick spacing respects native hurt immunity; four admitted pulses retain the 0.48 budget.
        if (mark.remainingTicks == 0 || mark.remainingTicks > 0 && (age - 2) % 10 == 0) {
            blackHolePulse(player, mark, if (releasing) .65 else .12)
            if (marks[player.uniqueId] !== mark) return
            if (!ready(player) || player.world.uid != mark.worldId || !damage.isCurrent(player, mark.cast)) {
                cancelMark(player.uniqueId)
                return
            }
        }
        if (mark.remainingTicks == 0) {
            hole.captives.values.forEach { it.releaseScale = it.scale }
            visuals.withOwner(mark.cast.casterId) { visuals.markBurst(mark.point, STAFF_BLACK_HOLE_RADIUS, blackhole = true) }
            effects.impact(mark.visualId, mark.point, STAFF_BLACK_HOLE_RADIUS, MARK_IMPACT_DURATION_TICKS)
        }
        hole.captives.values.toList().forEach { captive ->
            val mob = captive.mob
            if (!damage.eligible(player, mob, mark.cast) || mob.world.uid != mark.worldId ||
                !visible(mark.point, mob) || !visible(player.eyeLocation, mob) ||
                center(mob).distanceSquared(mark.point) > STAFF_BLACK_HOLE_RADIUS * STAFF_BLACK_HOLE_RADIUS) {
                hole.release(mob.uniqueId)
                return@forEach
            }
            val offset = mark.point.toVector().subtract(center(mob).toVector())
            val velocity = if (releasing) captive.outward.clone().multiply(.22).apply { y = .12 }
                else staffBlackHoleVelocity(offset)
            if (!captive.canMove(velocity)) { hole.release(mob.uniqueId); return@forEach }
            val scale = if (releasing) staffBlackHoleReleaseScale(captive.releaseScale, -mark.remainingTicks)
                else staffBlackHoleScale(offset.length(), captive.scale)
            hole.resize(captive, scale)
            mob.velocity = velocity
            if (!releasing && offset.lengthSquared() <= STAFF_BLACK_HOLE_CORE_RADIUS * STAFF_BLACK_HOLE_CORE_RADIUS &&
                scale <= .35) hole.fed += mob.uniqueId
        }
        hole.growth = minOf(staffBlackHoleGrowth(hole.fed.size), hole.growth + .04)
        effects.blackHoleGrowth(mark.visualId, hole.growth)
        if (mark.remainingTicks > 0 && mark.remainingTicks % 4 == 0)
            visuals.withOwner(mark.cast.casterId) {
                visuals.gravity(mark.point, STAFF_BLACK_HOLE_RADIUS, 1.0 - mark.remainingTicks.toDouble() / STAFF_BLACK_HOLE_TICKS)
            }
        if (mark.remainingTicks <= -STAFF_BLACK_HOLE_RELEASE_TICKS) {
            marks.remove(player.uniqueId)
            hole.releaseAll() // The already-running collapse display finishes on its own clock.
        }
    }

    private fun blackHolePulse(player: Player, mark: PendingStaffMark, scale: Double) {
        val hole = requireNotNull(mark.blackHole)
        val limit = minOf(settings.maxAreaTargets, STAFF_BLACK_HOLE_TARGETS)
        val candidates = nearby(player, mark.point, STAFF_BLACK_HOLE_RADIUS, mark.cast)
            .filter { center(it).distanceSquared(mark.point) <= STAFF_BLACK_HOLE_RADIUS * STAFF_BLACK_HOLE_RADIUS }
            .sortedBy { center(it).distanceSquared(mark.point) }.take(MAX_WAVE_CANDIDATES)
        for (victim in candidates) {
            if (marks[player.uniqueId] !== mark || !ready(player) || player.world.uid != mark.worldId ||
                !damage.isCurrent(player, mark.cast)) return
            if (victim.uniqueId !in hole.targets && hole.targets.size >= limit) continue
            if (!damage.eligible(player, victim, mark.cast) || !visible(mark.point, victim) ||
                !visible(player.eyeLocation, victim)) continue
            // Existing marks are the ownership registry: two holes never stack scale or fight over one mob.
            if (marks.values.any { it !== mark && it.blackHole?.captives?.containsKey(victim.uniqueId) == true }) continue
            hole.targets += victim.uniqueId
            val hit = damage.hit(player, victim, mark.cast, mark.tuning.power * scale, mark.tuning.vanillaDamage * scale)
            if (marks[player.uniqueId] !== mark || !damage.isCurrent(player, mark.cast)) return
            if (!hit) {
                if (mark.remainingTicks > 0) hole.release(victim.uniqueId)
                continue // A rejected terminal hit still gets the same harmless, gradual release.
            }
            if (!damage.eligible(player, victim, mark.cast) || !visible(mark.point, victim) ||
                !visible(player.eyeLocation, victim) || mark.remainingTicks <= 0) continue
            if (victim is Mob) hole.capture(victim, mark.point)
            else if (victim is Player) {
                // Players use bounded pulse pull after an accepted hit; only mobs are resized/held.
                val pull = mark.point.toVector().subtract(center(victim).toVector())
                if (pull.lengthSquared() > .04) victim.velocity = pull.normalize().multiply(.48)
                    .apply { y = y.coerceIn(-.15, .18) }
            }
        }
    }

    private fun cancelMark(playerId: UUID) {
        val mark = marks.remove(playerId) ?: return
        mark.blackHole?.releaseAll()
        effects.remove(mark.visualId)
    }

    private fun tickEmbers(launching: PendingStaffEmber? = null) {
        val impacts = mutableListOf<Triple<Player, PendingStaffEmber, Pair<Location, LivingEntity?>>>()
        val iterator = embers.iterator()
        while (iterator.hasNext()) {
            val ember = iterator.next()
            if (launching != null && ember !== launching) continue
            val player = Bukkit.getPlayer(ember.cast.casterId)
            if (player == null || !ready(player) || player.world != ember.position.world ||
                !damage.isCurrent(player, ember.cast)) {
                iterator.remove()
                effects.remove(ember.visualId)
                continue
            }
            val from = ember.position
            ember.age++
            ember.target?.let { target ->
                val point = targeting.homingPoint(player, target, ember.cast, from, ember.direction,
                    ember.launchDirection, ember.remainingDistance)
                if (point == null) ember.target = null
                else steerStaffBolt(ember.direction, point.toVector().subtract(from.toVector()),
                    ember.age, settings.aimAssist.strength)
            }
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
            if (ember.age > 1 && (ember.age - 1) % StaffSpellDisplayEffects.FRAME_TICKS == 0) visuals.withOwner(ember.cast.casterId) {
                visuals.emberTrail(ember.visualPosition, from, meteor = ember.radius != null)
                ember.visualPosition = end.clone()
            }
            ember.remainingDistance -= distance
            if (victim != null || from.distanceSquared(blockEnd) < distance * distance - 0.0001 || ember.remainingDistance <= 0.001) {
                iterator.remove()
                impacts += Triple(player, ember, end to victim)
            } else {
                ember.position = end
                if ((ember.age - 1) % StaffSpellDisplayEffects.FRAME_TICKS == 0) {
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
            .filter { visible(origin, it) && visible(player.eyeLocation, it) }
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
        player.gameMode != GameMode.SPECTATOR && player.isValid && damage.inputAllowed(player)

    private fun cancel(playerId: UUID) {
        input.cancel(playerId)
        scenery?.cancel(playerId)
        arsenal.cancel(playerId)
        melee.cancel(playerId)
        defense.cancel(playerId)
        channels.cancel(playerId)
        channelInputs.remove(playerId)
        summons.cancel(playerId)
        conjurations.cancel(playerId)
        techniques.cancel(playerId)
        mobility.cancel(playerId)
        StaffMagicBarriers.cancel(playerId)
        chargeScenes.remove(playerId)?.forEach(effects::remove)
        lances.removeAll { it.cast.casterId == playerId }
        cancelMark(playerId)
        embers.removeAll { it.cast.casterId == playerId }
        waves.removeAll { it.casterId == playerId }
        bolts.removeAll { it.cast.casterId == playerId }
        delayedCasts.remove(playerId)
        hideHud(playerId)
        visuals.cancel(playerId)
        effects.cancel(playerId)
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        cancel(event.player.uniqueId)
    }
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) = cancel(event.player.uniqueId)
    @EventHandler fun onDeath(event: PlayerDeathEvent) = cancel(event.entity.uniqueId)

    @EventHandler fun onMarkedMobDeath(event: EntityDeathEvent) {
        marks.values.toList().forEach { it.blackHole?.release(event.entity.uniqueId) }
    }

    override fun close() {
        mobCasters.close()
        input.close()
        hud?.close()
        visuals.close()
        scenery?.close()
        arsenal.close()
        melee.close()
        defense.close()
        channels.close()
        channelInputs.clear()
        summons.close()
        conjurations.close()
        techniques.close()
        mobility.close()
        hotkeys.clear()
        StaffMagicBarriers.clear()
        damage.summonCast = { null }
        chargeScenes.values.flatten().forEach(effects::remove)
        chargeScenes.clear()
        lances.clear()
        tasks.close()
        marks.keys.toList().forEach(::cancelMark)
        embers.clear()
        waves.clear()
        bolts.clear()
        delayedCasts.clear()
        gcdOwners.forEach(cooldowns::clear)
        gcdOwners.clear()
        effects.close()
    }

    private companion object {
        const val GLOBAL_COOLDOWN_TICKS = 4L
        val CONJURATION_SPELLS = setOf(StaffSpell.MARK, StaffSpell.FROST, StaffSpell.NOVA, StaffSpell.FAULT,
            StaffSpell.TIDE, StaffSpell.REQUIEM, StaffSpell.OSSUARY)
        val ARSENAL_SPELLS = setOf(StaffSpell.NOVA, StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM, StaffSpell.PRISM, StaffSpell.OSSUARY)
        const val WAVE_STEP_TICKS = 2
        const val WAVE_START_DELAY_TICKS = 2
        const val WAVE_BODY_TOLERANCE = 0.6
        const val STORM_NOVA_RADIUS = STAFF_STORM_NOVA_RADIUS
        const val STORM_NOVA_BUILDUP_TICKS = STAFF_STORM_NOVA_BUILDUP_TICKS
        const val STORM_NOVA_TRAVEL_TICKS = STAFF_STORM_NOVA_TRAVEL_TICKS
        const val STORM_NOVA_FIRST_HIT_TICK = STORM_NOVA_BUILDUP_TICKS + WAVE_STEP_TICKS
        const val STORM_NOVA_DAMAGE_SCALE = 0.75
        const val STORM_NOVA_RAY_HEIGHT = 0.1
        const val STORM_NOVA_PARTICLE_CLEARANCE = 0.55
        const val STORM_NOVA_VERTICAL_REACH = 2.0
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
    val cast: StaffSpellCast, val tuning: StaffSpellTuning, var remainingTicks: Int, val visualId: UUID?,
    val blackHole: StaffBlackHole? = null)

private data class PendingStaffEmber(var position: Location, val direction: Vector,
    val cast: StaffSpellCast, val tuning: StaffSpellTuning, var remainingDistance: Double, val visualId: UUID?,
    var visualPosition: Location, val speed: Double? = null, val radius: Double? = null,
    val hitTargets: MutableSet<UUID>? = null, var age: Int = 0, var target: Player? = null,
    val launchDirection: Vector = direction.clone())

private data class PendingStaffBolt(var position: Location, val direction: Vector, var target: LivingEntity?,
    val cast: StaffSpellCast, val tuning: StaffSpellTuning, val scale: Double, val jumps: Int,
    val visited: MutableSet<UUID>, var remainingDistance: Double, val visualId: UUID?, var age: Int = 0,
    val trail: MutableList<Location> = mutableListOf(position.clone()), val spell: StaffSpell = StaffSpell.CHAIN,
    var fixedAimPoint: Location? = null, var launchDelayTicks: Int = 0, val holdForwardTicks: Int = 0,
    val burstRadius: Double = 0.0, val volley: PendingIcicleVolley? = null,
    val launchDirection: Vector = direction.clone())

private class PendingIcicleVolley {
    var target: LivingEntity? = null
    var focused = false
    var searchCooldown = 0
}

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
    val frontProgressStartTicks: Int = 2,
    val visualRayLimits: List<Double>? = null,
    val hitStartTicks: Int = startDelayTicks,
) {
    val attemptedTargets = mutableSetOf<UUID>()
    var elapsedTicks = 0
    var targetAttempts = 0

    fun frontAt(ageTicks: Int): Double {
        if (ageTicks < startDelayTicks) return 0.0
        val progressAge = ageTicks - frontProgressStartTicks + 2
        val expansion = staffWaveFront(progressAge, range, travelTicks)
        return if (initialFront > 0.0) initialFront + staffWaveFront(progressAge,
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

/** Immediate forward impulse grows into a fast discharge; collision sweeps the full variable-length step. */
internal fun staffEmberStep(age: Int, speed: Double, meteor: Boolean = false): Double {
    val t = ((age - 1).coerceAtLeast(0) / if (meteor) 8.0 else 12.0).coerceIn(0.0, 1.0)
    val eased = t * t * (3.0 - 2.0 * t)
    return speed * if (meteor) 0.75 + eased else 0.65 + 1.25 * eased
}

/** Shared by live pursuit and the offline flight fixture; changes only the supplied heading. */
internal fun steerStaffBolt(direction: Vector, offset: Vector, age: Int, minimumTurn: Double = 0.0) {
    if (offset.lengthSquared() <= 0.000001) return
    val turn = maxOf(minimumTurn.coerceIn(0.0, .8),
        if (offset.lengthSquared() < 36.0) 0.72 else if (age < 4) 0.10 else 0.42)
    direction.multiply(1.0 - turn).add(offset.clone().normalize().multiply(turn)).normalize()
}

/** A short probing leader followed by a fast discharge, repeated along the homing route. */
internal fun staffLightningStep(age: Int): Double = when ((age - 1).coerceAtLeast(0) % 4) {
    0 -> 1.5
    1 -> 5.5
    2 -> 0.75
    else -> 5.5
}
