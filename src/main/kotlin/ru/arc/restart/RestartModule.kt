package ru.arc.restart

import ru.arc.ARC
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.PluginModule

object RestartModule : PluginModule {
    override val name = "Restart"
    override val priority = 74

    override fun init() {
        RestartManager.init(
            dataPath = ARC.instance.dataPath,
            taskScheduler = BukkitTaskScheduler(ARC.instance),
        )
    }

    override fun reload() {
        RestartManager.reload()
    }

    override fun shutdown() {
        RestartManager.shutdown()
    }
}
