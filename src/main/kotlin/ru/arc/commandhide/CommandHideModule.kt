package ru.arc.commandhide

import ru.arc.core.PluginModule

object CommandHideModule : PluginModule {
    override val name = "CommandHide"
    override val priority = 67

    override fun init() {
        CommandHideManager.init()
    }

    override fun reload() {
        CommandHideManager.reload()
    }

    override fun shutdown() {
        CommandHideManager.shutdown()
    }
}
