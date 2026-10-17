package ru.arc.onboarding

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.ScheduledTask
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.ProductEntryPoint
import ru.arc.metrics.ProductFeature
import ru.arc.metrics.ProductOutcome
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

enum class OnboardingResetResult {
    DISABLED,
    NOT_FOUND,
    RESET,
}

object OnboardingService {
    private data class Runtime(
        val persistence: OnboardingPersistence,
        val tasks: LifecycleTaskScope,
        var config: OnboardingConfig? = null,
    ) {
        val ready = CompletableFuture<Void>()
        val delivering = mutableSetOf<UUID>()
        val revisions = mutableMapOf<UUID, Long>()
    }

    @Volatile
    private var runtime: Runtime? = null
    private val deliveryTasks = linkedMapOf<UUID, ScheduledTask>()
    private var drained = CompletableFuture.completedFuture(Unit)

    internal fun closeCompletion(): CompletableFuture<Unit> = drained.copy()

    internal fun init(onReady: (Set<Int>) -> Unit) {
        shutdown()
        val dataPath = ARC.instance.dataPath
        val landsConfig = Bukkit.getPluginManager().getPlugin("Lands")?.dataFolder?.let { File(it, "config.yml") }
        val persistence = OnboardingPersistence(drained) {
            val config = OnboardingConfig.load(ru.arc.config.Config(
                dataPath, ru.arc.config.ConfigManager.moduleYamlRelative(dataPath, "onboarding.yml"),
            ))
            config.validate()
            val store = if (config.enabled) OnboardingStore.open(dataPath.resolve("data/onboarding-v2.json")) else null
            val colors = landsConfig?.let { claimGuideParticleColors(YamlConfiguration.loadConfiguration(it)) }.orEmpty()
            PreparedOnboarding(config, store, colors)
        }
        val current = Runtime(persistence, LifecycleTaskScope())
        runtime = current
        try {
            // Startup/manual reload may wait for file IO; the native binding stays on this thread.
            val prepared = persistence.ready.join()
            current.config = prepared.config
            onReady(prepared.particleColors)
            current.ready.complete(null)
            info("Configurable onboarding ready; enabled={}", prepared.config.enabled)
        } catch (failure: Throwable) {
            current.ready.completeExceptionally(failure)
            error("Could not initialize onboarding", failure)
        }
    }

    internal fun readiness(): CompletableFuture<Void> = runtime?.ready?.copy()
        ?: CompletableFuture.failedFuture(IllegalStateException("Onboarding is unavailable"))

    fun shutdown() {
        val previous = runtime
        runtime = null
        previous?.ready?.completeExceptionally(java.util.concurrent.CancellationException("Onboarding is stopping"))
        previous?.tasks?.close()
        deliveryTasks.values.forEach { task -> runCatching { task.cancel() } }
        deliveryTasks.clear()
        if (previous != null) {
            drained = previous.persistence.closeAsync()
            drained.whenComplete { _, failure -> if (failure != null) error("Could not drain onboarding state", failure) }
        }
    }

    fun isEnabled(): Boolean = runtime?.config?.enabled == true

    internal fun guideConfig(): OnboardingConfig? = runtime?.config

    /** Read-only hint for the main player hub; it does not consume or reschedule the hint. */
    fun nextPendingHintId(playerId: UUID): String? = runtime?.persistence?.nextHint(playerId)?.id

    fun reset(playerId: UUID): CompletableFuture<OnboardingResetResult> {
        val current = runtime ?: return CompletableFuture.completedFuture(OnboardingResetResult.DISABLED)
        current.revisions[playerId] = (current.revisions[playerId] ?: 0L) + 1L
        deliveryTasks.remove(playerId)?.cancel()
        return current.persistence.submit(playerId) { prepared ->
            val store = prepared.store ?: return@submit OnboardingResetResult.DISABLED
            if (store.reset(playerId)) OnboardingResetResult.RESET else OnboardingResetResult.NOT_FOUND
        }
    }

    fun recordFirstRtp(
        player: Player,
        location: Location,
    ) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.FIRST_RTP_COMPLETE,
            ProductFeature.RTP,
            ProductEntryPoint.GAMEPLAY,
        )
        val place = place(player, location.world?.name, location.blockX, location.blockZ) ?: return
        observe(player, OnboardingMilestone.FIRST_RTP, place)
    }

    fun recordHomeCreated(
        player: Player,
        worldName: String,
        blockX: Int,
        blockZ: Int,
        verifiedFoothold: Boolean,
    ) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.HOME_CREATED,
            ProductFeature.HOMES,
            ProductEntryPoint.GAMEPLAY,
        )
        val place = place(player, worldName, blockX, blockZ) ?: return
        observe(player, OnboardingMilestone.HOME_CREATED, place, verifiedFoothold)
    }

    fun recordLandClaimed(
        player: Player,
        worldName: String,
        chunkX: Int,
        chunkZ: Int,
    ) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.LAND_CLAIMED,
            ProductFeature.LANDS,
            ProductEntryPoint.GAMEPLAY,
        )
        val place =
            runCatching { OnboardingPlace.fromChunk(worldName, chunkX, chunkZ) }
                .onFailure { failure -> error("Could not normalize Lands onboarding place for {}", player.name, failure) }
                .getOrNull() ?: return
        observe(player, OnboardingMilestone.LAND_CLAIMED, place)
    }

    fun recordBuildBookOpened(
        player: Player,
        location: Location,
    ) {
        MetricsModule.recordProductFeatureInterest(player, ProductFeature.AUTOBUILD, ProductEntryPoint.GAMEPLAY)
        val place = place(player, location.world?.name, location.blockX, location.blockZ) ?: return
        observe(player, OnboardingMilestone.BUILD_BOOK_OPENED, place)
    }

    fun recordAutoBuildStarted(
        player: Player,
        location: Location,
    ) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.AUTOBUILD_STARTED,
            ProductFeature.AUTOBUILD,
            ProductEntryPoint.GAMEPLAY,
        )
        val place = place(player, location.world?.name, location.blockX, location.blockZ) ?: return
        observe(player, OnboardingMilestone.AUTOBUILD_STARTED, place)
    }

    fun recordAutoBuildComplete(
        player: Player,
        location: Location,
    ) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.AUTOBUILD_COMPLETE,
            ProductFeature.AUTOBUILD,
            ProductEntryPoint.GAMEPLAY,
        )
        val place = place(player, location.world?.name, location.blockX, location.blockZ) ?: return
        observe(player, OnboardingMilestone.AUTOBUILD_COMPLETE, place)
    }

    fun resume(player: Player) {
        val current = runtime ?: return
        val config = current.config ?: return
        if (!config.allowsWorld(player.world.name)) return
        if (current.persistence.nextHint(player.uniqueId) != null) {
            schedule(player.uniqueId, config.resumeDelayTicks)
        }
    }

    private fun observe(
        player: Player,
        milestone: OnboardingMilestone,
        place: OnboardingPlace? = null,
        verifiedFoothold: Boolean = false,
    ) {
        val current = runtime ?: return
        val worldName = place?.world ?: player.world.name
        val playerId = player.uniqueId
        val playerName = player.name
        val now = System.currentTimeMillis()
        current.persistence.submit(playerId) { prepared ->
            val store = prepared.store
            if (store == null || !prepared.config.allowsWorld(worldName)) OnboardingJourneyUpdate(changed = false)
            else store.observe(
                    playerId,
                    milestone,
                    now,
                    place,
                    verifiedFoothold,
                )
        }.whenComplete { _, failure ->
            if (failure != null) error("Could not persist onboarding milestone {} for {}", milestone.id, playerName, failure)
        }.whenCompleteSync(current.tasks) { update, failure ->
            if (failure != null || update == null) return@whenCompleteSync
            if (OnboardingMilestone.FOOTHOLD_COMPLETE in update.addedMilestones) {
                MetricsModule.recordProductOutcome(
                    player,
                    ProductOutcome.FOOTHOLD_COMPLETE,
                    ProductFeature.LANDS,
                    ProductEntryPoint.GAMEPLAY,
                )
            }
            if (update.recoveredFoothold) {
                MetricsModule.recordProductOutcome(
                    player,
                    ProductOutcome.FOOTHOLD_RECOVERED,
                    ProductFeature.LANDS,
                    ProductEntryPoint.GAMEPLAY,
                )
            }
            if (update.queuedHints.isNotEmpty()) {
                current.config?.let { schedule(playerId, it.firstDelayTicks) }
            }
        }
    }

    private fun place(
        player: Player,
        worldName: String?,
        blockX: Int,
        blockZ: Int,
    ): OnboardingPlace? =
        runCatching {
            OnboardingPlace.fromBlock(requireNotNull(worldName) { "world is unavailable" }, blockX, blockZ)
        }.onFailure { failure ->
            error("Could not normalize onboarding place for {}", player.name, failure)
        }.getOrNull()

    private fun schedule(
        playerId: UUID,
        delayTicks: Long,
    ) {
        val current = runtime ?: return
        if (playerId in deliveryTasks || playerId in current.delivering) return
        current.tasks.runLater(delayTicks.coerceAtLeast(1L)) {
            deliveryTasks.remove(playerId)
            deliverNext(playerId)
        }?.let { deliveryTasks[playerId] = it }
    }

    private fun deliverNext(playerId: UUID) {
        val current = runtime ?: return
        val config = current.config ?: return
        val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline) ?: return
        if (!config.allowsWorld(player.world.name) || !current.delivering.add(playerId)) return
        val revision = current.revisions[playerId]
        val hint = current.persistence.nextHint(playerId)
        if (hint == null) { current.delivering.remove(playerId); return }
        val message = runCatching {
            if (config.hintEnabled(hint)) config.message(hint) else null
        }.onFailure { failure ->
            error("Could not render onboarding hint {} for {}", hint.id, player.name, failure)
        }.getOrNull()

        current.persistence.reserveDelivery(playerId, hint, System.currentTimeMillis())
            .whenComplete { _, failure ->
                if (failure != null) error("Could not persist onboarding delivery {} for {}", hint.id, playerId, failure)
            }.whenCompleteSync(current.tasks) { marked, failure ->
            current.delivering.remove(playerId)
            if (failure != null || marked != true) return@whenCompleteSync
            val recipient = Bukkit.getPlayer(playerId)?.takeIf { it.isOnline && config.allowsWorld(it.world.name) }
            if (recipient == null || revision != current.revisions[playerId]) {
                current.persistence.returnDelivery(playerId).whenComplete { _, returnFailure ->
                    if (returnFailure != null) error("Could not return undelivered onboarding hint for {}", playerId, returnFailure)
                }
                return@whenCompleteSync
            }
            if (message != null) {
                recipient.sendMessage(message)
                MetricsModule.recordProductOnboardingHint(recipient, hint.productHint)
            }
            current.persistence.acknowledgeDelivery(playerId)
            if (current.persistence.nextHint(playerId) != null) {
                schedule(playerId, if (message == null) 1L else config.betweenMessagesTicks)
            }
        }
    }
}
