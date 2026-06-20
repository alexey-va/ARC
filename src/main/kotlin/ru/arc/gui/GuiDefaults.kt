package ru.arc.gui

import org.bukkit.Material
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.util.ConfigItemSpec
import java.nio.file.Path

/**
 * Default values for GUI elements.
 *
 * Loaded from `guis/defaults.yml` config file.
 * Provides fallback defaults if config is not loaded.
 */
object GuiDefaults {
    private var config: Config? = null

    /**
     * Initialize from plugin data folder.
     */
    @JvmStatic
    fun init(dataPath: Path) {
        config = ConfigManager.of(dataPath, "guis/defaults.yml")
    }

    /**
     * Initialize with custom config (for testing).
     */
    @JvmStatic
    fun init(customConfig: Config) {
        config = customConfig
    }

    /**
     * Reset to defaults (for testing).
     */
    @JvmStatic
    fun reset() {
        config = null
    }

    // ==================== Background ====================

    object Background {
        val material: Material
            get() =
                itemSpec("background")?.material
                    ?: Material.GRAY_STAINED_GLASS_PANE

        val modelData: Int
            get() = itemSpec("background")?.modelData ?: 11000

        val contentMaterial: Material
            get() =
                itemSpec("background.content")?.material
                    ?: Material.LIGHT_GRAY_STAINED_GLASS_PANE

        val contentModelData: Int
            get() = itemSpec("background.content")?.modelData ?: 0
    }

    // ==================== Back Button ====================

    object BackButton {
        val material: Material
            get() = buttonSpec("back")?.material ?: Material.BLUE_STAINED_GLASS_PANE

        val modelData: Int
            get() = buttonSpec("back")?.modelData ?: 11013

        val defaultDisplay: String
            get() = buttonSpec("back")?.display ?: "<gray>« Назад"

        val defaultCommand: String
            get() = config?.string("buttons.back.default-command", "menu") ?: "menu"
    }

    // ==================== Previous Page Button ====================

    object PrevButton {
        val material: Material
            get() = buttonSpec("prev")?.material ?: Material.BLUE_STAINED_GLASS_PANE

        val modelData: Int
            get() = buttonSpec("prev")?.modelData ?: 11009

        val defaultDisplay: String
            get() = buttonSpec("prev")?.display ?: "<gray>« Предыдущая"
    }

    // ==================== Next Page Button ====================

    object NextButton {
        val material: Material
            get() = buttonSpec("next")?.material ?: Material.BLUE_STAINED_GLASS_PANE

        val modelData: Int
            get() = buttonSpec("next")?.modelData ?: 11008

        val defaultDisplay: String
            get() = buttonSpec("next")?.display ?: "<gray>Следующая »"
    }

    // ==================== Confirm Button ====================

    object ConfirmButton {
        val material: Material
            get() = buttonSpec("confirm")?.material ?: Material.LIME_STAINED_GLASS_PANE

        val modelData: Int
            get() = buttonSpec("confirm")?.modelData ?: 0

        val defaultDisplay: String
            get() = buttonSpec("confirm")?.display ?: "<green>Подтвердить"
    }

    // ==================== Cancel Button ====================

    object CancelButton {
        val material: Material
            get() = buttonSpec("cancel")?.material ?: Material.RED_STAINED_GLASS_PANE

        val modelData: Int
            get() = buttonSpec("cancel")?.modelData ?: 0

        val defaultDisplay: String
            get() = buttonSpec("cancel")?.display ?: "<red>Отмена"
    }

    // ==================== Slots ====================

    object Slots {
        val back: Int
            get() = config?.integer("slots.back", 0) ?: 0

        val prev: Int
            get() = config?.integer("slots.prev", 3) ?: 3

        val next: Int
            get() = config?.integer("slots.next", 5) ?: 5

        val confirm: Int
            get() = config?.integer("slots.confirm", 2) ?: 2

        val cancel: Int
            get() = config?.integer("slots.cancel", 6) ?: 6
    }

    // ==================== Messages ====================

    object Messages {
        val cooldown: String
            get() =
                config?.string("messages.cooldown", "<red>Подождите...")
                    ?: "<red>Подождите..."

        val error: String
            get() =
                config?.string("messages.error", "<red>Ошибка")
                    ?: "<red>Ошибка"

        val noPermission: String
            get() =
                config?.string("messages.no-permission", "<red>Нет прав")
                    ?: "<red>Нет прав"
    }

    // ==================== Helper ====================

    private fun itemSpec(path: String): ConfigItemSpec? =
        config?.let { ConfigItemSpec.readFromConfig(it, path) }

    private fun buttonSpec(name: String): ConfigItemSpec? = itemSpec("buttons.$name")
}
