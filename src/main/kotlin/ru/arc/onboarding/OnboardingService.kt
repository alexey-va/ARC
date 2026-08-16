package ru.arc.onboarding

import org.bukkit.Bukkit
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
        val store = OnboardingStore.open(ARC.instance.dataPath.resolve("data/onboarding-v1.json"))
        runtime = Runtime(config, store)
        info("Configurable onboarding enabled for {} worlds; {} player states loaded", config.worlds.size, store.playerCount())
    }

    fun shutdown() {
        deliveryTasks.values.forEach { task -> runCatching { task.cancel() } }
        deliveryTasks.clear()
        runtime = null
    }

    fun isEnabled(): Boolean = runtime != null

    fun recordFirstRtp(player: Player) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.FIRST_RTP_COMPLETE,
            ProductFeature.RTP,
            ProductEntryPoint.GAMEPLAY,
        )
        observe(player, OnboardingMilestone.FIRST_RTP)
    }

    fun recordHomeCreated(player: Player) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.HOME_CREATED,
            ProductFeature.HOMES,
            ProductEntryPoint.GAMEPLAY,
        )
        observe(player, OnboardingMilestone.HOME_CREATED)
    }

    fun recordLandClaimed(player: Player) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.LAND_CLAIMED,
            ProductFeature.LANDS,
            ProductEntryPoint.GAMEPLAY,
        )
        observe(player, OnboardingMilestone.LAND_CLAIMED)
    }

    fun recordBuildBookOpened(player: Player) {
        MetricsModule.recordProductFeatureInterest(player, ProductFeature.AUTOBUILD, ProductEntryPoint.GAMEPLAY)
        val current = runtime ?: return
        if (!current.config.allowsWorld(player.world.name)) return
        observe(player, OnboardingMilestone.BUILD_BOOK_OPENED)
    }

    fun recordAutoBuildStarted(player: Player) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.AUTOBUILD_STARTED,
            ProductFeature.AUTOBUILD,
            ProductEntryPoint.GAMEPLAY,
        )
        observe(player, OnboardingMilestone.AUTOBUILD_STARTED)
    }

    fun recordAutoBuildComplete(player: Player) {
        MetricsModule.recordProductOutcome(
            player,
            ProductOutcome.AUTOBUILD_COMPLETE,
            ProductFeature.AUTOBUILD,
            ProductEntryPoint.GAMEPLAY,
        )
        observe(player, OnboardingMilestone.AUTOBUILD_COMPLETE)
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
    ) {
        val current = runtime ?: return
        if (!current.config.allowsWorld(player.world.name)) return

        val update =
            runCatching {
                current.store.observe(player.uniqueId, milestone, System.currentTimeMillis())
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
