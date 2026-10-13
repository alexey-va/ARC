package ru.arc.eliteloot

import ru.arc.core.PluginModule

object EliteLootModule : PluginModule {
    override val name = "EliteLoot"
    override val priority = 78

    override fun init() {
        EliteLootManager.init()
    }

    override fun shutdown() {
        EliteLootManager.shutdown()
    }
}
