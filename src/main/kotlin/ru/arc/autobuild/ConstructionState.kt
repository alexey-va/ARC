package ru.arc.autobuild

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.util.CooldownManager

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
        private var timeoutTask: BukkitTask? = null

        override fun enter(site: ConstructionSite) {
            site.display = Display(site).also { it.showBorder(site.displaySeconds) }

            // Schedule timeout
            timeoutTask = Bukkit.getScheduler().runTaskLater(ARC.plugin, Runnable {
                if (site.state == this) {
                    site.player.sendMessage(BuildConfig.Messages.inactivity())
                    site.transitionTo(Cancelled)
                }
            }, site.displaySeconds * 20L)
        }

        override fun exit(site: ConstructionSite) {
            timeoutTask?.cancel()
            timeoutTask = null
        }

        override fun allowedTransitions() = setOf(Confirmation, Created, Cancelled)
    }

    /** NPC spawned, waiting for player confirmation */
    data object Confirmation : ConstructionState() {
        private var timeoutTask: BukkitTask? = null

        override fun enter(site: ConstructionSite) {
            site.timestamp = System.currentTimeMillis()
            site.display?.showBorderAndDisplay(site.confirmSeconds)
            site.construction = Construction(site).also {
                site.npcId = it.createNpc(site.centerBlock, site.confirmSeconds)
            }
            site.player.sendMessage(BuildConfig.Messages.confirm())

            // Schedule timeout
            timeoutTask = Bukkit.getScheduler().runTaskLater(ARC.plugin, Runnable {
                if (site.state == this) {
                    site.player.sendMessage(BuildConfig.Messages.inactivity())
                    site.transitionTo(Cancelled)
                }
            }, site.confirmSeconds * 20L)
        }

        override fun exit(site: ConstructionSite) {
            timeoutTask?.cancel()
            timeoutTask = null
        }

        override fun allowedTransitions() = setOf(Building, Created, Cancelled)
    }

    /** Actively building */
    data object Building : ConstructionState() {
        override fun enter(site: ConstructionSite) {
            site.display?.stop()
            site.forceloadChunks()
            site.construction?.startBuilding()

            if (!site.player.hasPermission("arc.buildings.bypass-cooldown")) {
                CooldownManager.addCooldown(site.player.uniqueId, "building_cooldown", 20 * 60 * 60L)
            }

            site.player.sendMessage(BuildConfig.Messages.startBuild())
        }

        override fun allowedTransitions() = setOf(Done, Cancelled)
    }

    /** Building completed successfully */
    data object Done : ConstructionState() {
        override fun enter(site: ConstructionSite) {
            site.player.sendMessage(BuildConfig.Messages.finished())
            site.launchFireworks()
            Bukkit.getScheduler().runTaskLater(ARC.plugin, Runnable {
                site.construction?.destroyNpc()
            }, 60)
            site.cleanup(60)
        }

        override fun allowedTransitions() = emptySet<ConstructionState>()
    }

    /** Construction was cancelled */
    data object Cancelled : ConstructionState() {
        override fun enter(site: ConstructionSite) {
            site.display?.stop()
            site.construction?.destroyNpc()
            site.player.sendMessage(BuildConfig.Messages.cancelled())
            site.cleanup(0)
        }

        override fun allowedTransitions() = emptySet<ConstructionState>()
    }
}


