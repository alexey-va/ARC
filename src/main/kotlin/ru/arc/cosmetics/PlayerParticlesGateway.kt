package ru.arc.cosmetics

import dev.esophose.playerparticles.PlayerParticles
import dev.esophose.playerparticles.api.PlayerParticlesAPI
import dev.esophose.playerparticles.config.Settings
import dev.esophose.playerparticles.manager.ParticleGroupPresetManager
import dev.esophose.playerparticles.particles.ParticlePair
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

internal data class ParticlePresetSignature(val effect: String, val style: String, val data: String)

internal data class ParticlePresetOption(
    val id: String,
    val name: String,
    val active: Boolean,
    internal val particles: List<ParticlePresetSignature> = emptyList(),
)

internal fun expectedParticlePresetTarget(
    active: Map<ParticlePresetSignature, Int>,
    particles: List<ParticlePresetSignature>,
    replacesActive: Boolean,
    onePerStyle: Boolean,
): Map<ParticlePresetSignature, Int> =
    if (replacesActive) particles.groupingBy { it }.eachCount()
    else active.toMutableMap().apply {
        for (particle in particles) {
            if (onePerStyle) keys.removeAll { it.style == particle.style }
            this[particle] = (this[particle] ?: 0) + 1
        }
    }

internal fun deriveParticlePresetOption(
    id: String,
    name: String,
    particles: List<ParticlePresetSignature>,
    active: Map<ParticlePresetSignature, Int>,
    replacesActive: Boolean,
    onePerStyle: Boolean,
): ParticlePresetOption {
    val equipped = if (replacesActive || onePerStyle) {
        expectedParticlePresetTarget(active, particles, replacesActive, onePerStyle) == active
    } else {
        expectedParticlePresetTarget(emptyMap(), particles, true, false)
            .all { (particle, count) -> (active[particle] ?: 0) >= count }
    }
    return ParticlePresetOption(id, name, equipped, particles)
}

internal data class ParticlePresetsSnapshot(
    val available: Boolean,
    val presets: List<ParticlePresetOption> = emptyList(),
    val activeCount: Int = 0,
    val visible: Boolean = false,
    val presetsReplaceActive: Boolean = true,
    internal val playerId: UUID? = null,
    internal val activeParticles: Map<ParticlePresetSignature, Int> = emptyMap(),
    internal val onePerStyle: Boolean = false,
    internal val shadowedPresetIds: Set<String> = emptySet(),
)

/** Applied means native cache readback matched; PlayerParticles owns its asynchronous database save. */
internal data class ParticlePresetChange(val applied: Boolean, val snapshot: ParticlePresetsSnapshot)

/** Owning-thread preset adapter. Native player commands retain PlayerParticles' permission checks. */
internal class PlayerParticlesGateway(
    private val backend: Backend,
    scheduler: TaskScheduler,
) : AutoCloseable {
    internal interface Backend {
        fun snapshot(player: Player): ParticlePresetsSnapshot
        fun execute(player: Player, command: String): Boolean
        fun current(player: Player): Boolean
        fun failure(player: Player, operation: String, failure: Throwable?)
    }

    constructor(plugin: Plugin) : this(BukkitBackend(plugin), Tasks.scheduler)

    private val scope = LifecycleTaskScope(scheduler, true)
    private val pending = ConcurrentHashMap<UUID, CompletableFuture<ParticlePresetChange>>()
    private var closed = false

    fun snapshot(player: Player): ParticlePresetsSnapshot {
        if (closed || !backend.current(player)) return ParticlePresetsSnapshot(false)
        return try { backend.snapshot(player) }
        catch (failure: Exception) {
            backend.failure(player, "snapshot", failure)
            ParticlePresetsSnapshot(false)
        }
    }

    fun applyPreset(player: Player, id: String, expected: ParticlePresetsSnapshot): CompletableFuture<ParticlePresetChange> {
        val before = snapshot(player)
        val preset = before.presets.firstOrNull { it.id == id }
        val captured = expected.presets.firstOrNull { it.id == id }
        if (!fresh(player, expected, before) || preset == null || captured?.particles != preset.particles ||
            id in before.shadowedPresetIds || id.isBlank() ||
            id.any { it.isWhitespace() || Character.isISOControl(it) }) return rejected(before)
        val target = expectedParticlePresetTarget(before.activeParticles, preset.particles,
            before.presetsReplaceActive, before.onePerStyle)
        return change(player, "pp group load $id") { after -> after.activeParticles == target }
    }

    fun reset(player: Player, expected: ParticlePresetsSnapshot): CompletableFuture<ParticlePresetChange> {
        val before = snapshot(player)
        if (!fresh(player, expected, before)) return rejected(before)
        return change(player, "pp reset") { it.activeCount == 0 }
    }

    fun setVisible(player: Player, visible: Boolean): CompletableFuture<ParticlePresetChange> {
        val before = snapshot(player)
        if (!before.available) return rejected(before)
        return change(player, "pp toggle ${if (visible) "on" else "off"}") { it.visible == visible }
    }

    private fun fresh(player: Player, expected: ParticlePresetsSnapshot, current: ParticlePresetsSnapshot): Boolean =
        expected.available && current.available && expected.playerId == player.uniqueId &&
            expected.activeParticles == current.activeParticles &&
            expected.presetsReplaceActive == current.presetsReplaceActive && expected.onePerStyle == current.onePerStyle

    private fun change(
        player: Player,
        command: String,
        confirmed: (ParticlePresetsSnapshot) -> Boolean,
    ): CompletableFuture<ParticlePresetChange> {
        val result = CompletableFuture<ParticlePresetChange>()
        if (pending.putIfAbsent(player.uniqueId, result) != null) return rejected(snapshot(player))
        fun finish(applied: Boolean, after: ParticlePresetsSnapshot) {
            pending.remove(player.uniqueId, result)
            result.complete(ParticlePresetChange(applied, after))
        }
        lateinit var observe: (Int) -> Unit
        fun schedule(reads: Int) {
            try {
                if (scope.runLater(1L) { observe(reads) } == null) finish(false, ParticlePresetsSnapshot(false))
            } catch (failure: Exception) {
                backend.failure(player, "readback_schedule", failure)
                finish(false, ParticlePresetsSnapshot(false))
            }
        }
        observe = observe@ { reads ->
            if (closed || pending[player.uniqueId] !== result) return@observe
            val after = snapshot(player)
            when {
                !after.available -> finish(false, after)
                confirmed(after) -> finish(true, after)
                reads >= 40 -> {
                    backend.failure(player, "readback_unconfirmed:$command", null)
                    finish(false, after)
                }
                else -> schedule(reads + 1)
            }
        }
        // A successful dispatch is not a successful mutation: /pp runs asynchronously.
        try {
            if (!backend.execute(player, command)) finish(false, snapshot(player)) else schedule(1)
        } catch (failure: Exception) {
            backend.failure(player, "command:$command", failure)
            finish(false, snapshot(player))
        }
        return result
    }

    override fun close() {
        closed = true
        try { scope.close() }
        finally {
            val outstanding = pending.values.toList()
            pending.clear()
            outstanding.forEach { it.complete(ParticlePresetChange(false, ParticlePresetsSnapshot(false))) }
        }
    }

    private fun rejected(snapshot: ParticlePresetsSnapshot) =
        CompletableFuture.completedFuture(ParticlePresetChange(false, snapshot))

    private class BukkitBackend(private val plugin: Plugin) : Backend {
        override fun snapshot(player: Player): ParticlePresetsSnapshot {
            val provider = plugin.server.pluginManager.getPlugin("PlayerParticles")
            if (provider == null || !provider.isEnabled || provider.description.version != "8.13" ||
                !player.hasPermission("playerparticles.basecommand")) return ParticlePresetsSnapshot(false)
            return NativeAccess.snapshot(player)
        }

        override fun execute(player: Player, command: String): Boolean = player.performCommand(command)
        override fun current(player: Player): Boolean =
            plugin.isEnabled && player.isOnline && !player.isDead && plugin.server.getPlayer(player.uniqueId) === player

        override fun failure(player: Player, operation: String, failure: Throwable?) {
            plugin.logger.log(Level.WARNING, "PlayerParticles cosmetics $operation: player=${player.uniqueId}", failure)
        }
    }

    /** Provider-specific descriptors stay isolated so the menu loads when PlayerParticles is absent. */
    private object NativeAccess {
        fun snapshot(player: Player): ParticlePresetsSnapshot {
            val api = PlayerParticlesAPI.getInstance()
            val actor = api.getPPlayer(player) ?: return ParticlePresetsSnapshot(false)
            val active = signatures(api.getActivePlayerParticles(player)).groupingBy { it }.eachCount()
            val replacesActive = !Settings.PRESET_GROUPS_ALLOW_OVERLAPPING.get()
            val onePerStyle = Settings.PRESET_GROUPS_OVERLAPPING_ONE_PER_STYLE.get()
            val presets = PlayerParticles.getInstance().getManager(ParticleGroupPresetManager::class.java)
                .getPresetGroupsForPlayer(actor).map { preset ->
                    val particles = signatures(preset.group.particles.values)
                    val name = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', preset.displayName)).orEmpty()
                    deriveParticlePresetOption(preset.group.name, name, particles, active, replacesActive, onePerStyle)
                }.sortedWith(compareBy<ParticlePresetOption, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })
            return ParticlePresetsSnapshot(
                true, presets, active.values.sum(), actor.canSeeParticles(), replacesActive,
                player.uniqueId, active, onePerStyle,
                presets.mapNotNull { preset -> actor.getParticleGroupByName(preset.id)?.let { preset.id } }.toSet(),
            )
        }

        private fun signatures(particles: Collection<ParticlePair>): List<ParticlePresetSignature> =
            particles.map { ParticlePresetSignature(it.effect.internalName, it.style.internalName, it.dataString) }
    }
}
