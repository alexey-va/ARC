package ru.arc.scheduled

import ru.arc.ARC
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.PluginModule

object ScheduledCommandsModule : PluginModule {
    override val name = "ScheduledCommands"
    override val priority = 73

    override fun init() {
        ScheduledCommandsManager.init(
            dataPath = ARC.instance.dataPath,
            taskScheduler = BukkitTaskScheduler(ARC.instance),
        )
    }

    override fun reload() {
        ScheduledCommandsManager.reload()
    }

    override fun shutdown() {
        ScheduledCommandsManager.shutdown()
    }
}
