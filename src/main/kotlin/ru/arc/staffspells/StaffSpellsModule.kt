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
import ru.arc.util.CooldownManager
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

object StaffSpellsModule : PluginModule {
    override val name = "StaffSpells"
    override val priority = 85
    private var controller: StaffSpellController? = null

    override fun init() {
        val config = StaffSpellConfig(ConfigManager.of(ARC.instance.dataPath, "modules/staff-spells.yml"))
        val settings = config.settings // Validate the replacement before closing the active generation.
        shutdown()
        controller = StaffSpellController(config, settings, StaffSpellDamage()).also {
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
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val marks = mutableMapOf<UUID, PendingStaffMark>()
    private val cooldownId = "staff-spells"

    fun start() {
        tasks.runTimer(4, 4) {
            Bukkit.getOnlinePlayers().forEach { player ->
                val spell = StaffSpell.from(player.inventory.itemInMainHand)
                if (ready(player) && spell != null) {
                    castTargets(player, spell).forEach { selected ->
                        player.spawnParticle(Particle.END_ROD, center(selected).add(0.0, selected.height * 0.55, 0.0),
                            2, 0.13, 0.05, 0.13, 0.0)
                    }
                }
            }
        }
        tasks.runTimer(2, 2) { tickMarks() }
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
        val targets = castTargets(player, spell)
        if (targets.isEmpty()) {
            player.sendActionBar(config.text("no-target"))
            return
        }
        val cast = damage.capture(player) ?: run {
            player.sendActionBar(config.text("unavailable"))
            return
        }
        val tuning = settings.tuning.getValue(spell)
        CooldownManager.addCooldown(player.uniqueId, cooldownId, tuning.cooldownTicks)
        when (spell) {
            StaffSpell.CHAIN -> chain(player, targets.first(), cast, tuning)
            StaffSpell.MARK -> {
                marks[player.uniqueId] = PendingStaffMark(targets.first(), player.world.uid, cast, tuning, settings.markTicks)
                beam(player.eyeLocation, center(targets.first()), Particle.ENCHANT)
                player.world.playSound(targets.first().location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.5f, 1.5f)
            }
            StaffSpell.FROST -> {
                targets.forEach { target ->
                    if (damage.hit(player, target, cast, tuning.power, tuning.vanillaDamage)) {
                        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, settings.frostSlowTicks, 1, false, true))
                    }
                }
                frostVisual(player)
            }
        }
        tasks.runLater(tuning.cooldownTicks) {
            if (ready(player) && StaffSpell.from(player.inventory.itemInMainHand) != null)
                player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.3f, 1.8f)
        }
    }

    private fun chain(player: Player, first: LivingEntity, cast: StaffSpellCast, tuning: StaffSpellTuning) {
        val visited = mutableSetOf<UUID>()
        var current: LivingEntity? = first
        var origin = player.eyeLocation
        var scale = 1.0
        repeat(settings.chainTargets) {
            val victim = current ?: return
            val endpoint = center(victim)
            visited += victim.uniqueId
            if (!damage.hit(player, victim, cast, tuning.power * scale, tuning.vanillaDamage * scale)) return
            beam(origin, endpoint, Particle.ELECTRIC_SPARK)
            endpoint.world.playSound(endpoint, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.4f, 1.5f)
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
            if (player == null || !ready(player) || player.world.uid != mark.worldId ||
                !damage.eligible(player, mark.target) ||
                player.location.distanceSquared(mark.target.location) > settings.range * settings.range) {
                iterator.remove()
                continue
            }
            mark.remainingTicks -= 2
            if (mark.remainingTicks <= 0) {
                iterator.remove()
                due += player to mark
            } else {
                ring(center(mark.target), 0.65, Particle.WITCH, 10)
            }
        }
        due.forEach { (player, mark) ->
            val origin = center(mark.target)
            if (!visible(player.eyeLocation, origin)) return@forEach
            val targets = (listOf(mark.target) + nearby(player, origin, settings.markRadius)
                .sortedBy { center(it).distanceSquared(origin) }).distinctBy { it.uniqueId }
                .filter { visible(origin, center(it)) && visible(player.eyeLocation, center(it)) }
                .take(settings.maxAreaTargets)
            targets.forEach { damage.hit(player, it, mark.cast, mark.tuning.power, mark.tuning.vanillaDamage) }
            ring(origin, settings.markRadius, Particle.WITCH, 40)
            origin.world.spawnParticle(Particle.FLASH, origin, 1)
            origin.world.playSound(origin, Sound.ENTITY_EVOKER_CAST_SPELL, 0.7f, 0.7f)
        }
    }

    private fun target(player: Player) = aimed(player, settings.range, settings.aimDegrees).firstOrNull()

    private fun castTargets(player: Player, spell: StaffSpell) =
        if (spell == StaffSpell.FROST) aimed(player, settings.frostRange, settings.frostDegrees)
            .take(settings.maxAreaTargets) else listOfNotNull(target(player))

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

    private fun frostVisual(player: Player) {
        val eye = player.eyeLocation
        val forward = eye.direction
        val right = org.bukkit.util.Vector(-forward.z, 0.0, forward.x)
        if (right.lengthSquared() < 0.001) right.setX(1.0)
        right.normalize()
        for (distance in 1..settings.frostRange.toInt()) {
            for (step in -5..5) {
                val radians = Math.toRadians(step * settings.frostDegrees / 5)
                val direction = forward.clone().multiply(cos(radians)).add(right.clone().multiply(sin(radians)))
                eye.world.spawnParticle(Particle.SNOWFLAKE, eye.clone().add(direction.multiply(distance.toDouble())), 1, 0.0, 0.1, 0.0, 0.0)
            }
        }
        eye.world.playSound(eye, Sound.BLOCK_GLASS_BREAK, 0.7f, 0.6f)
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) { marks.remove(event.player.uniqueId) }
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) { marks.remove(event.player.uniqueId) }
    @EventHandler fun onDeath(event: PlayerDeathEvent) { marks.remove(event.entity.uniqueId) }

    override fun close() {
        tasks.close()
        marks.clear()
    }
}

private data class PendingStaffMark(val target: LivingEntity, val worldId: UUID,
    val cast: StaffSpellCast, val tuning: StaffSpellTuning, var remainingTicks: Int)

internal fun center(entity: LivingEntity) = entity.location.add(0.0, entity.height * 0.5, 0.0)

internal fun visible(from: Location, to: Location): Boolean {
    if (from.world != to.world) return false
    val offset = to.toVector().subtract(from.toVector())
    val distance = offset.length()
    if (distance < 0.01) return true
    return from.world.rayTraceBlocks(from, offset.multiply(1.0 / distance), distance,
        FluidCollisionMode.NEVER, true) == null
}

private fun beam(from: Location, to: Location, particle: Particle) {
    val offset = to.toVector().subtract(from.toVector())
    val count = ceil(offset.length() * 3).toInt().coerceIn(1, 80)
    for (i in 0..count) from.world.spawnParticle(particle,
        from.clone().add(offset.clone().multiply(i.toDouble() / count)), 1, 0.025, 0.025, 0.025, 0.0)
}

private fun ring(origin: Location, radius: Double, particle: Particle, count: Int) {
    repeat(count) { i ->
        val angle = i * Math.PI * 2 / count
        origin.world.spawnParticle(particle, origin.clone().add(cos(angle) * radius, 0.0, sin(angle) * radius),
            1, 0.0, 0.0, 0.0, 0.0)
    }
}

object StaffTestSubCommand : SubCommand {
    override val configKey = "stafftest"
    override val defaultPermission = "arc.test"
    override val defaultDescription = "Выдать посохи для пробы новых атак"
    override val defaultUsage = "/arc stafftest [all|chain|mark|frost] [игрок]"

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
