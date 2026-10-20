package ru.arc.hooks

import net.william278.huskhomes.api.HuskHomesAPI
import net.william278.huskhomes.position.Home
import net.william278.huskhomes.position.Position
import net.william278.huskhomes.event.HomeCreateEvent
import net.william278.huskhomes.event.RandomTeleportEvent
import net.william278.huskhomes.event.TeleportBackEvent
import net.william278.huskhomes.event.TeleportEvent
import net.william278.huskhomes.event.TeleportWarmupEvent
import net.william278.huskhomes.teleport.Teleport
import net.william278.huskhomes.teleport.TimedTeleport
import net.william278.huskhomes.user.BukkitUser
import net.william278.huskhomes.user.OnlineUser
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.Portal
import ru.arc.PortalData
import ru.arc.PortalData.ActionType.HUSK
import ru.arc.common.ServerLocation
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.core.whenCompleteSync
import ru.arc.onboarding.OnboardingService
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import kotlin.math.abs
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.UUID

class HuskHomesHook internal constructor(
    private val homeConfirmations: HuskHomeCreationConfirmation = HuskHomeCreationConfirmation(),
    private val portalLauncher: (UUID, HuskTeleport) -> Unit,
) : Listener, AutoCloseable {

    private val localTeleports = HuskHomesLocalTeleportObserver()

    constructor() : this(
        portalLauncher = { playerId, teleport -> Portal(playerId, PortalData(HUSK, teleport, null, null)) },
    )

    @EventHandler
    fun husk(event: TeleportWarmupEvent) {
        intercept(event, event.timedTeleport)
    }

    @EventHandler
    fun husk(event: TeleportEvent) {
        intercept(event, event.teleport)
    }

    @EventHandler
    fun husk(event: TeleportBackEvent) {
        intercept(event, event.teleport)
    }

    @EventHandler
    fun husk(event: RandomTeleportEvent) {
        intercept(event, event.teleport)
    }

    private fun intercept(event: Cancellable, teleport: Teleport) {
        if (teleport is ObservedHuskTeleport) {
            localTeleports.nativeEvent(event, teleport)
            return
        }
        val timedTeleport = teleport as? TimedTeleport ?: return
        val teleporter = timedTeleport.teleporter as? BukkitUser ?: return
        if (teleporter.player.hasPermission(PORTAL_BYPASS_PERMISSION)) return

        event.isCancelled = true
        portalLauncher(teleporter.uuid, HuskTeleport(timedTeleport, teleporter.position))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun observeTeleport(event: PlayerTeleportEvent) = localTeleports.bukkitEvent(event)

    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        localTeleports.quit(event.player)
        homeConfirmations.forget(event.player.uniqueId)
    }

    override fun close() {
        homeConfirmations.close()
        localTeleports.close()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun homeCreate(event: HomeCreateEvent) {
        val ownerId = event.owner.uuid
        val questRun = OnboardingService.currentRunId(ownerId)
        val homeName = event.name
        val expected = HomePositionSnapshot.from(event.position)
        val setRespawn = homeName.endsWith("home")

        // HuskHomes 4.9.10 starts its async save after this event; one next-tick read can precede it.
        homeConfirmations.observe(ownerId, homeName, expected, questRun) { player, home ->
            val world = Bukkit.getWorld(home.world.name) ?: return@observe
            val location = Location(world, home.x, home.y, home.z, home.yaw, home.pitch)
            if (setRespawn) {
                info("Setting respawn location for {} {}", player.name, location)
                player.setRespawnLocation(location, true)
            }
            val verifiedFoothold =
                runCatching { HookRegistry.landsHook?.isProtectedFor(player, location) == true }
                    .onFailure { hookFailure ->
                        error("Could not verify Lands protection for new HuskHomes home of {}", player.name, hookFailure)
                    }.getOrDefault(false)
            // The service fences the captured run; independent legacy respawn does not require a quest.
            OnboardingService.recordHomeCreated(
                player, home.world.name, location.blockX, location.blockZ, verifiedFoothold,
                expectedRunId = questRun,
            )
        }
    }

    fun teleport(teleport: HuskTeleport, player: Player) {
        val api = HuskHomesAPI.getInstance()
        val onlineUser = api.adaptUser(player)
        val original = teleport.teleport
        val lastPosition =
            if ((original.teleporter as? OnlineUser)?.uuid == player.uniqueId) {
                teleport.departure
            } else {
                onlineUser.position
            }

        val replay = api.teleportBuilder(onlineUser)
            .target(original.target)
            .type(original.type)
            .actions(*original.actions.toTypedArray())
            .updateLastPosition(false)
            .toTeleport()

        val observed = localTeleports.prepare(replay, player) {
            if (original.isUpdateLastPosition && replay.plugin.settings.general.backCommand.canReturnToWorld(lastPosition.world)) {
                api.setUserLastPosition(onlineUser, lastPosition)
            }
        }
        if (observed != null) {
            observed.execute()
            return
        }

        // Username targets and cross-server routing retain HuskHomes' native lifecycle.
        replay.execute()

        if (original.isUpdateLastPosition) {
            api.setUserLastPosition(onlineUser, lastPosition)
        }
    }

    /** Starts HuskHomes' normal teleport flow, including its cross-server portal handoff. */
    fun teleport(player: Player, destination: ServerLocation): Boolean =
        HuskHomesTeleporter.teleport(player, destination)

    fun hasHome(player: Player): CompletableFuture<Boolean> {
        val user = HuskHomesAPI.getInstance().adaptUser(player)
        return HuskHomesAPI.getInstance().getUserHomes(user)
            .orTimeout(3, TimeUnit.SECONDS)
            .thenApply { it.isNotEmpty() }
    }

    fun createDefaultHome(player: Player, location: Location) {
        try {
            info("Creating default home for player {} at {}", player.name, location)
            val user = HuskHomesAPI.getInstance().adaptUser(player)
            HuskHomesAPI.getInstance().createHome(user, "home", HuskHomesAPI.getInstance().adaptPosition(location))
        } catch (e: Exception) {
            error("Error creating default home for player {}", player.name, e)
        }
    }

    class HuskTeleport(
        val teleport: TimedTeleport,
        internal val departure: Position,
    ) {
        fun getPlayer(): OfflinePlayer =
            Bukkit.getOfflinePlayer((teleport.teleporter as OnlineUser).uuid)
    }

    companion object {
        private const val PORTAL_BYPASS_PERMISSION = "arc.portal.bypass"
    }
}

/** Main-thread named-home confirmation; 20 serial reads, each with a 3s timeout, 10 ticks apart after completion. */
internal class HuskHomeCreationConfirmation(
    private val scheduler: () -> TaskScheduler = { Tasks.scheduler },
    private val livePlayer: (UUID) -> Player? = Bukkit::getPlayer,
    private val readHome: (Player, String) -> CompletableFuture<Home?> = { player, name ->
        val api = HuskHomesAPI.getInstance()
        api.getHome(api.adaptUser(player), name).thenApply { it.orElse(null) }
    },
    private val diagnostic: (String, Throwable?) -> Unit = { message, failure ->
        if (failure == null) warn(message) else warn(message, failure)
    },
) : AutoCloseable {
    private val scope = lazy { LifecycleTaskScope(scheduler()) }
    private val pending = mutableMapOf<Pair<UUID, String>, Attempt>()
    private var closed = false

    fun observe(playerId: UUID, name: String, expected: HomePositionSnapshot, runId: String?, confirmed: (Player, Home) -> Unit) {
        if (closed) return
        val key = playerId to name.lowercase(Locale.ROOT)
        pending.remove(key)?.scheduled?.cancel()
        val player = livePlayer(playerId)?.takeIf { it.isOnline } ?: return
        val attempt = Attempt(key, player, name, expected, runId, confirmed)
        pending[key] = attempt
        attempt.scheduled = scope.value.runLater(1L) { read(attempt) }
    }

    fun forget(playerId: UUID) {
        pending.values.filter { it.key.first == playerId }.forEach {
            pending.remove(it.key)
            it.scheduled?.cancel()
        }
    }

    override fun close() {
        closed = true
        pending.clear()
        if (scope.isInitialized()) scope.value.close()
    }

    private fun current(attempt: Attempt): Boolean {
        if (closed || pending[attempt.key] !== attempt) return false
        if (!attempt.player.isOnline || livePlayer(attempt.key.first) !== attempt.player) {
            pending.remove(attempt.key)
            return false
        }
        return true
    }

    private fun read(attempt: Attempt) {
        if (!current(attempt)) return
        attempt.reads++
        val result: CompletableFuture<Home?> = try { readHome(attempt.player, attempt.name).orTimeout(3, TimeUnit.SECONDS) }
        catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
        result.whenCompleteSync(scope.value) { home, failure ->
            if (!current(attempt)) return@whenCompleteSync
            if (failure == null && home != null && attempt.expected.matches(home)) {
                pending.remove(attempt.key)
                attempt.confirmed(attempt.player, home)
            } else if (attempt.reads < MAX_READS) {
                attempt.scheduled = scope.value.runLater(RETRY_TICKS) { read(attempt) }
            } else {
                pending.remove(attempt.key)
                diagnostic("HuskHomes home confirmation exhausted: player=${attempt.key.first} home=${attempt.name}" +
                    " run=${attempt.runId} target=${attempt.expected} attempts=${attempt.reads}" +
                    " reason=${if (failure == null) "home_not_confirmed" else "read_failed"}", failure)
            }
        }
    }

    private class Attempt(
        val key: Pair<UUID, String>,
        val player: Player,
        val name: String,
        val expected: HomePositionSnapshot,
        val runId: String?,
        val confirmed: (Player, Home) -> Unit,
    ) {
        var reads = 0
        var scheduled: ScheduledTask? = null
    }

    private companion object {
        const val MAX_READS = 20
        const val RETRY_TICKS = 10L
    }
}

internal data class HomePositionSnapshot(
    val server: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun matches(position: Position): Boolean =
        server == position.server &&
            world == position.world.name &&
            abs(x - position.x) < POSITION_EPSILON &&
            abs(y - position.y) < POSITION_EPSILON &&
            abs(z - position.z) < POSITION_EPSILON

    companion object {
        private const val POSITION_EPSILON = 0.000_001

        fun from(position: Position): HomePositionSnapshot =
            HomePositionSnapshot(position.server, position.world.name, position.x, position.y, position.z)
    }
}
