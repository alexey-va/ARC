package ru.arc.citizens

import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks

object NpcChunkTicketModule : PluginModule {
    override val name = "CitizensChunkTickets"
    override val priority = 21

    private var config = NpcChunkTicketConfig.load(ARC.instance.dataPath)
    private val manager = NpcChunkTicketManager { config }
    private var reconcileTask: ScheduledTask? = null

    override fun init() {
        config = NpcChunkTicketConfig.load(ARC.instance.dataPath)
        startTask()
    }

    override fun reload() {
        config = NpcChunkTicketConfig.load(ARC.instance.dataPath)
        startTask()
        manager.reconcile()
    }

    override fun shutdown() {
        reconcileTask?.cancel()
        reconcileTask = null
        manager.shutdown()
    }

    private fun startTask() {
        reconcileTask?.cancel()
        reconcileTask =
            repeating(period = config.reconcileIntervalTicks.ticks, delay = 40.ticks) {
                manager.reconcile()
            }
    }
}
