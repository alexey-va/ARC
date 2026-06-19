package ru.arc.configs

import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.ARC
import java.io.File

object AuctionConfig {

    @JvmField var broadcastItems: Boolean = false
    @JvmField var categories: List<String> = emptyList()
    @JvmField var refreshRate: Long = 20L * 60

    private lateinit var config: YamlConfiguration
    private lateinit var file: File

    fun load() {
        file = ConfigManager.moduleYamlPath(ARC.instance.dataFolder.toPath(), "auction.yml").toFile()
        if (!file.exists()) {
            file.parentFile.mkdirs()
            ARC.instance.saveResource(ConfigManager.bundledModuleResource("auction.yml"), false)
        }
        config = YamlConfiguration.loadConfiguration(file)
        loadConfig()
    }

    private fun loadConfig() {
        categories = config.getStringList("discord-categories")
        refreshRate = config.getLong("refresh-rate", 20L * 60)
        broadcastItems = config.getBoolean("broadcast-items", false)
    }
}
