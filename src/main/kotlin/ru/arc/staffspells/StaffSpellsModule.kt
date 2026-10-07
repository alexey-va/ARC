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
import kotlin.math.min

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
        tasks.runTimer(2, 2) { tickMarks() }
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
            StaffSpell.FROST -> {
                aimed(player, settings.frostRange, settings.frostDegrees).take(settings.maxAreaTargets).forEach { target ->
                    if (damage.hit(player, target, cast, tuning.power, tuning.vanillaDamage)) {
                        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, settings.frostSlowTicks, 1, false, true))
                    }
                }
                visuals.frost(player.eyeLocation, settings.frostRange, settings.frostDegrees)
                effects.play(player.uniqueId, spell, player.eyeLocation,
                    player.eyeLocation.add(player.eyeLocation.direction.multiply(settings.frostRange)),
                    radius = kotlin.math.tan(Math.toRadians(settings.frostDegrees)) * settings.frostRange)
            }
            StaffSpell.LANCE -> {
                val eye = player.eyeLocation
                val end = aimPoint(player)
                lineTargets(player, eye, end, settings.lanceWidth).take(settings.lanceTargets).forEach {
                    damage.hit(player, it, cast, tuning.power, tuning.vanillaDamage)
                }
                visuals.lance(eye, end)
                effects.play(player.uniqueId, spell, eye, end, radius = 0.75, durationTicks = 12, impact = true)
            }
            StaffSpell.EMBER -> {
                // The direction is captured once. Flight never steers towards a nearby mob.
                if (embers.count { it.cast.casterId == player.uniqueId } >= 8) {
                    val removed = embers.removeAt(embers.indexOfFirst { it.cast.casterId == player.uniqueId })
                    effects.remove(removed.visualId)
                }
                val visualId = effects.play(player.uniqueId, spell, player.eyeLocation,
                    player.eyeLocation.add(player.eyeLocation.direction), radius = 0.85,
                    durationTicks = kotlin.math.ceil(settings.range / settings.emberSpeed).toInt() + 4)
                embers += PendingStaffEmber(player.eyeLocation, player.eyeLocation.direction, cast, tuning, settings.range, visualId)
                visuals.emberTrail(player.eyeLocation, player.eyeLocation)
            }
            StaffSpell.NOVA -> {
                val origin = player.location.add(0.0, 0.8, 0.0)
                areaDamage(player, origin, settings.novaRadius, cast, tuning)
                visuals.nova(origin, settings.novaRadius)
                // Put the vortex inside the forward part of the wave so its silhouette is visible to the caster.
                // The damage and particle ring keep their original caster-centered area.
                val yaw = Math.toRadians(player.location.yaw.toDouble())
                val forward = Vector(-kotlin.math.sin(yaw), 0.0, kotlin.math.cos(yaw))
                val vortex = rayEnd(player.eyeLocation, forward, min(3.5, settings.novaRadius * 0.6)).apply {
                    y = player.location.y
                }
                effects.play(player.uniqueId, spell, vortex, radius = min(1.8, settings.novaRadius * 0.3),
                    durationTicks = 28, impact = true)
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
            visuals.lightning(player.eyeLocation, end)
            effects.play(player.uniqueId, StaffSpell.CHAIN, player.eyeLocation, end, radius = 0.65, durationTicks = 12)
            return
        }
        val visited = mutableSetOf<UUID>()
        var current: LivingEntity? = first
        var origin = player.eyeLocation
        var scale = 1.0
        repeat(settings.chainTargets) {
            val victim = current ?: return
            val endpoint = center(victim)
            visited += victim.uniqueId
            visuals.lightning(origin, endpoint)
            effects.play(player.uniqueId, StaffSpell.CHAIN, origin, endpoint, radius = 0.65, durationTicks = 12, impact = true)
            if (!damage.hit(player, victim, cast, tuning.power * scale, tuning.vanillaDamage * scale)) return
            origin = endpoint
            current = nearby(player, endpoint, settings.chainRadius)
                .filter { it.uniqueId !in visited }
                .sortedBy { center(it).distanceSquared(endpoint) }
                .firstOrNull { visible(endpoint, center(it)) && visible(player.eyeLocation, center(it)) }
            scale *= settings.chainDecay
        }
    }

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
                effects.remove(mark.visualId)
                due += player to mark
            } else {
                visuals.markCharge(origin, 1.0 - mark.remainingTicks.toDouble() / settings.markTicks)
            }
        }
        due.forEach { (player, mark) ->
            val origin = mark.target?.let(::center) ?: mark.point
            areaDamage(player, origin, settings.markRadius, mark.cast, mark.tuning, mark.target)
            visuals.markBurst(origin, settings.markRadius)
            effects.play(player.uniqueId, StaffSpell.MARK, origin, radius = settings.markRadius, durationTicks = 16, impact = true)
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
            visuals.emberTrail(from, end)
            ember.remainingDistance -= distance
            if (victim != null || from.distanceSquared(blockEnd) < distance * distance - 0.0001 || ember.remainingDistance <= 0.001) {
                iterator.remove()
                effects.remove(ember.visualId)
                impacts += Triple(player, ember, end to victim)
            } else {
                ember.position = end
                effects.move(ember.visualId, end)
            }
        }
        // Damage events can themselves trigger quit/teleport cleanup; no active list iterator here.
        impacts.forEach { (player, ember, impact) ->
            areaDamage(player, impact.first, settings.emberRadius, ember.cast, ember.tuning, impact.second)
            visuals.emberBurst(impact.first, settings.emberRadius)
            effects.play(player.uniqueId, StaffSpell.EMBER, impact.first, radius = settings.emberRadius, durationTicks = 16, impact = true)
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
        effects.cancel(playerId)
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) = cancel(event.player.uniqueId)
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) = cancel(event.player.uniqueId)
    @EventHandler fun onDeath(event: PlayerDeathEvent) = cancel(event.entity.uniqueId)

    override fun close() {
        tasks.close()
        marks.clear()
        embers.clear()
        effects.close()
    }

}

private data class PendingStaffMark(val target: LivingEntity?, val point: Location, val worldId: UUID,
    val cast: StaffSpellCast, val tuning: StaffSpellTuning, var remainingTicks: Int, val visualId: UUID?)

private data class PendingStaffEmber(var position: Location, val direction: Vector,
    val cast: StaffSpellCast, val tuning: StaffSpellTuning, var remainingDistance: Double, val visualId: UUID?)

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
