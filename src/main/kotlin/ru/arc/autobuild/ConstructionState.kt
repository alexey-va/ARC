package ru.arc.autobuild

import ru.arc.core.delayed
import ru.arc.core.ticks
import ru.arc.util.CooldownManager
import ru.arc.onboarding.OnboardingService
import ru.arc.util.Logging.debug

/**
 * State Pattern implementation for construction site lifecycle.
 *
 * Lifecycle: Created -> DisplayingOutline -> Confirmation -> Building -> Done
 *                                         \-> Cancelled
 *                            \-> Cancelled
 */
sealed class ConstructionState {

    /** Called when entering this state */
    open fun enter(site: ConstructionSite) {}

    /** Called when exiting this state */
    open fun exit(site: ConstructionSite) {}

    /** Returns set of states this state can transition to */
    abstract fun allowedTransitions(): Set<ConstructionState>

    /** Check if can transition to target state */
    fun canTransitionTo(target: ConstructionState): Boolean =
        target::class in allowedTransitions().map { it::class }

    // ==================== State Implementations ====================

    /** Initial state after creation */
    data object Created : ConstructionState() {
        override fun allowedTransitions() = setOf(DisplayingOutline, Cancelled)
    }

    /** Showing border particles to player */
    data object DisplayingOutline : ConstructionState() {
        override fun enter(site: ConstructionSite) {
            site.display = Display(site).also { it.showBorder(site.displaySeconds) }

            site.phaseTimeoutTask =
                delayed((site.displaySeconds * 20L).ticks) {
                    if (site.state == DisplayingOutline) {
                        debug(
                            "[autobuild] outline timeout for {} building={}",
                            site.player.name,
                            site.building.fileName,
                        )
                        site.player.sendMessage(BuildConfig.Messages.inactivity())
                        site.transitionTo(Cancelled)
                    }
                }
        }

        override fun exit(site: ConstructionSite) {
            site.phaseTimeoutTask?.cancel()
            site.phaseTimeoutTask = null
        }

        override fun allowedTransitions() = setOf(Confirmation, Created, Cancelled)
    }

    /** NPC spawned, waiting for player confirmation */
    data object Confirmation : ConstructionState() {
        override fun enter(site: ConstructionSite) {
            site.timestamp = System.currentTimeMillis()
            site.display?.showBorderAndDisplay(site.confirmSeconds)
            site.construction = Construction(site).also {
                site.npcId = it.createNpc(site.centerBlock, site.confirmSeconds)
            }
            site.player.sendMessage(BuildConfig.Messages.confirm())

            site.phaseTimeoutTask =
                delayed((site.confirmSeconds * 20L).ticks) {
                    if (site.state == Confirmation) {
                        debug(
                            "[autobuild] confirmation timeout for {} building={} npcId={}",
                            site.player.name,
                            site.building.fileName,
                            site.npcId,
                        )
                        site.player.sendMessage(BuildConfig.Messages.inactivity())
                        site.transitionTo(Cancelled)
                    }
                }
        }

        override fun exit(site: ConstructionSite) {
            site.phaseTimeoutTask?.cancel()
            site.phaseTimeoutTask = null
        }

        override fun allowedTransitions() = setOf(Building, Created, Cancelled)
    }

    /** Actively building */
    data object Building : ConstructionState() {
        override fun enter(site: ConstructionSite) {
            site.display?.stop()
            site.forceloadChunks()
            site.construction?.startBuilding()

            if (site.cooldownSeconds > 0 && !site.player.hasPermission("arc.buildings.cooldown.bypass")) {
                CooldownManager.addCooldown(
                    site.player.uniqueId,
                    "building_cooldown",
                    BuildCooldownPolicy.toTicks(site.cooldownSeconds),
                )
            }

            site.player.sendMessage(BuildConfig.Messages.startBuild())
        }

        override fun allowedTransitions() = setOf(Done, Cancelled)
    }

    /** Building completed successfully */
    data object Done : ConstructionState() {
        override fun enter(site: ConstructionSite) {
            site.player.sendMessage(BuildConfig.Messages.finished())
            if (site.completionCause == ConstructionSite.CompletionCause.NATURAL) {
                OnboardingService.recordAutoBuildComplete(site.player, site.centerBlock)
            }
            site.launchFireworks()
            delayed(60.ticks) { site.construction?.destroyNpc() }
            site.cleanup(60)
        }

        override fun allowedTransitions() = emptySet<ConstructionState>()
    }

    /** Construction was cancelled */
    data object Cancelled : ConstructionState() {
        override fun enter(site: ConstructionSite) {
            site.display?.stop()
            site.construction?.destroyNpc()
            if (!site.suppressCancelMessage) site.player.sendMessage(BuildConfig.Messages.cancelled())
            site.cleanup(0)
        }

        override fun allowedTransitions() = emptySet<ConstructionState>()
    }
}
