package ru.arc.configs

import ru.arc.ARC

object AuctionConfig {

    private val config: Config
        get() = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "auction.yml")

    val broadcastItems: Boolean get() = config.bool("broadcast-items", false)
    val categories: List<String> get() = config.stringList("discord-categories")
    val refreshRate: Long get() = config.long("refresh-rate", 20L * 60)

    fun load() {
        // Ensure the file exists with defaults by touching the config.
        // ConfigManager.ofModule already handles bundled resource creation.
        config.bool("broadcast-items", false)
    }
}
