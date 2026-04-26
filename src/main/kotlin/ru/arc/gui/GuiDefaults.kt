package ru.arc.gui

import org.bukkit.Material
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
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
                parseMaterial(
                    config?.string("background.material"),
                    Material.GRAY_STAINED_GLASS_PANE,
                )

        val modelData: Int
            get() = config?.integer("background.model-data", 11000) ?: 11000

        val contentMaterial: Material
            get() =
                parseMaterial(
                    config?.string("background.content-material"),
                    Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                )

        val contentModelData: Int
            get() = config?.integer("background.content-model-data", 0) ?: 0
    }

    // ==================== Back Button ====================

    object BackButton {
        val material: Material
            get() =
                parseMaterial(
                    config?.string("buttons.back.material"),
                    Material.BLUE_STAINED_GLASS_PANE,
                )

        val modelData: Int
            get() = config?.integer("buttons.back.model-data", 11013) ?: 11013

        val defaultDisplay: String
            get() =
                config?.string("buttons.back.default-display", "<gray>« Назад")
                    ?: "<gray>« Назад"

        val defaultCommand: String
            get() = config?.string("buttons.back.default-command", "menu") ?: "menu"
    }

    // ==================== Previous Page Button ====================

    object PrevButton {
        val material: Material
            get() =
                parseMaterial(
                    config?.string("buttons.prev.material"),
                    Material.BLUE_STAINED_GLASS_PANE,
                )

        val modelData: Int
            get() = config?.integer("buttons.prev.model-data", 11009) ?: 11009

        val defaultDisplay: String
            get() =
                config?.string("buttons.prev.default-display", "<gray>« Предыдущая")
                    ?: "<gray>« Предыдущая"
    }

    // ==================== Next Page Button ====================

    object NextButton {
        val material: Material
            get() =
                parseMaterial(
                    config?.string("buttons.next.material"),
                    Material.BLUE_STAINED_GLASS_PANE,
                )

        val modelData: Int
            get() = config?.integer("buttons.next.model-data", 11008) ?: 11008

        val defaultDisplay: String
            get() =
                config?.string("buttons.next.default-display", "<gray>Следующая »")
                    ?: "<gray>Следующая »"
    }

    // ==================== Confirm Button ====================

    object ConfirmButton {
        val material: Material
            get() =
                parseMaterial(
                    config?.string("buttons.confirm.material"),
                    Material.LIME_STAINED_GLASS_PANE,
                )

        val modelData: Int
            get() = config?.integer("buttons.confirm.model-data", 0) ?: 0

        val defaultDisplay: String
            get() =
                config?.string("buttons.confirm.default-display", "<green>Подтвердить")
                    ?: "<green>Подтвердить"
    }

    // ==================== Cancel Button ====================

    object CancelButton {
        val material: Material
            get() =
                parseMaterial(
                    config?.string("buttons.cancel.material"),
                    Material.RED_STAINED_GLASS_PANE,
                )

        val modelData: Int
            get() = config?.integer("buttons.cancel.model-data", 0) ?: 0

        val defaultDisplay: String
            get() =
                config?.string("buttons.cancel.default-display", "<red>Отмена")
                    ?: "<red>Отмена"
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

    private fun parseMaterial(
        name: String?,
        default: Material,
    ): Material {
        if (name.isNullOrBlank()) return default
        return try {
            Material.valueOf(name.uppercase())
        } catch (e: IllegalArgumentException) {
            default
        }
    }
}
