package ru.arc.configs

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.ARC

class BoardConfig {

    init { loadConfig() }

    fun loadConfig() {
        val data = ARC.instance.dataFolder.toPath()
        val path = ConfigManager.moduleYamlPath(data, "board.yml")
        val file = path.toFile()
        if (!file.exists()) {
            file.parentFile.mkdirs()
            ARC.instance.saveResource(ConfigManager.bundledModuleResource("board.yml"), false)
        }
        val cfg = YamlConfiguration.loadConfiguration(file)

        display = cfg.getString("item.display", "KEK")!!
        lore = cfg.getStringList("item.lore")
        descriptionPrefix = cfg.getString("item.description-prefix") ?: ""
        editBottom = cfg.getStringList("item.click-to-edit")
        rateBottom = cfg.getStringList("item.click-to-rate")
        mainMenuBackCommand = cfg.getString("main-menu-back-command", "menu")!!
        shortNameLength = cfg.getInt("short-name-length", 20)
        publishCost = cfg.getDouble("publish-cost", 25000.0)
        editCost = cfg.getDouble("edit-cost", 1000.0)
        secondsLifetime = cfg.getInt("entry-lifetime-seconds", 86400)
        mainServer = cfg.getBoolean("main-server", false)
        secondsAnnounce = cfg.getInt("seconds-announce", 600)
        receivePermission = cfg.getString("receive-permission", "arc.board-announce")!!

        createEntryGuiName = cfg.getString("create-entry-gui-name", "&7Создать объявление")!!.miniToLegacy()
        editEntryGuiName = cfg.getString("edit-entry-gui-name", "&7Редактировать объявление")!!.miniToLegacy()
        boardGuiName = cfg.getString("board-gui-name", "&7Доска объявлений")!!.miniToLegacy()
        rateGuiName = cfg.getString("rate-gui-name", "&7Оценить объявление")!!.miniToLegacy()

        rawConfig = cfg
    }

    companion object {
        @JvmField var lore: List<String> = emptyList()
        @JvmField var display: String = ""
        @JvmField var descriptionPrefix: String = ""
        @JvmField var editBottom: List<String> = emptyList()
        @JvmField var rateBottom: List<String> = emptyList()
        @JvmField var mainMenuBackCommand: String = "menu"
        @JvmField var shortNameLength: Int = 20
        @JvmField var publishCost: Double = 25000.0
        @JvmField var editCost: Double = 1000.0
        @JvmField var createEntryGuiName: String = ""
        @JvmField var editEntryGuiName: String = ""
        @JvmField var rateGuiName: String = ""
        @JvmField var boardGuiName: String = ""
        @JvmField var mainServer: Boolean = false
        @JvmField var secondsLifetime: Int = 86400
        @JvmField var secondsAnnounce: Int = 600
        @JvmField var receivePermission: String = "arc.board-announce"

        private var rawConfig: YamlConfiguration = YamlConfiguration()

        @JvmStatic
        fun getStringList(key: String): List<String> {
            if (!rawConfig.contains(key)) {
                println("Locale does not contain list key: $key")
                return emptyList()
            }
            return if (rawConfig.isString(key)) listOf(rawConfig.getString(key, key)!!)
            else rawConfig.getStringList(key)
        }

        @JvmStatic
        fun getString(key: String): String {
            if (!rawConfig.contains(key)) {
                println("Locale does not contain key: $key")
                return key
            }
            return rawConfig.getString(key) ?: key
        }

        private fun String.miniToLegacy(): String =
            LegacyComponentSerializer.legacyAmpersand()
                .serialize(MiniMessage.miniMessage().deserialize(this))
    }
}
