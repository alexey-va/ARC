package ru.arc.onboarding

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.ScheduledTask
import ru.arc.core.delayed
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.ProductEntryPoint
import ru.arc.metrics.ProductFeature
import ru.arc.metrics.ProductOutcome
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.util.UUID

object OnboardingService {
    private data class Runtime(
        val config: OnboardingConfig,
        val store: OnboardingStore,
    )

    @Volatile
    private var runtime: Runtime? = null
    private val deliveryTasks = linkedMapOf<UUID, ScheduledTask>()

    fun init() {
        shutdown()
        val config = OnboardingConfig.load()
        if (!config.enabled) {
            info("Configurable onboarding disabled")
            return
        }
        config.validate()
        val store = OnboardingStore.open(ARC.instance.dataPath.resolve("data/onboarding-v2.json"))
        runtime = Runtime(config, store)
        info("Configurable onboarding enabled for {} worlds; {} player states loaded", config.worlds.size, store.playerCount())
    }

    fun shutdown() {
        deliveryTasks.values.forEach { task -> runCatching { task.cancel() } }
        deliveryTasks.clear()
        runtime = null
    }

    fun isEnabled(): Boolean = runtime != null

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
        if (!current.config.allowsWorld(player.world.name)) return
        if (current.store.nextHint(player.uniqueId) != null) {
            schedule(player.uniqueId, current.config.resumeDelayTicks)
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
        if (!current.config.allowsWorld(worldName)) return

        val update =
            runCatching {
                current.store.observe(
                    player.uniqueId,
                    milestone,
                    System.currentTimeMillis(),
                    place,
                    verifiedFoothold,
                )
            }.onFailure { failure ->
                error("Could not persist onboarding milestone {} for {}", milestone.id, player.name, failure)
            }.getOrNull() ?: return

        if (OnboardingMilestone.FOOTHOLD_COMPLETE in update.addedMilestones) {
            MetricsModule.recordProductOutcome(
                player,
                ProductOutcome.FOOTHOLD_COMPLETE,
                ProductFeature.LANDS,
                ProductEntryPoint.GAMEPLAY,
            )
        }
        if (update.queuedHints.isNotEmpty()) {
            schedule(player.uniqueId, current.config.firstDelayTicks)
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
        if (playerId in deliveryTasks) return
        deliveryTasks[playerId] =
            delayed(delayTicks.coerceAtLeast(1L)) {
                deliveryTasks.remove(playerId)
                deliverNext(playerId)
            }
    }

    private fun deliverNext(playerId: UUID) {
        val current = runtime ?: return
        val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline) ?: return
        if (!current.config.allowsWorld(player.world.name)) return

        while (true) {
            val hint = current.store.nextHint(playerId) ?: return
            val message =
                runCatching {
                    if (current.config.hintEnabled(hint)) current.config.message(hint) else null
                }.onFailure { failure ->
                    error("Could not render onboarding hint {} for {}", hint.id, player.name, failure)
                }.getOrNull()

            val marked =
                runCatching { current.store.markDelivered(playerId, hint, System.currentTimeMillis()) }
                    .onFailure { failure ->
                        error("Could not persist onboarding delivery {} for {}", hint.id, player.name, failure)
                    }.getOrDefault(false)
            if (!marked) return
            if (message == null) continue

            player.sendMessage(message)
            if (current.store.nextHint(playerId) != null) {
                schedule(playerId, current.config.betweenMessagesTicks)
            }
            return
        }
    }
}
