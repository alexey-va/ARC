package ru.arc.config

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import ru.arc.ARC

object BoardConfig {

    private val config: Config
        get() = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "board.yml")

    // ── Scalars ──────────────────────────────────────────────────────────────

    val shortNameLength: Int get() = config.integer("short-name-length", 20)
    val publishCost: Double get() = config.double("publish-cost", 25000.0)
    val editCost: Double get() = config.double("edit-cost", 1000.0)
    val mainServer: Boolean get() = config.bool("main-server", false)
    val secondsLifetime: Int get() = config.integer("entry-lifetime-seconds", 86400)
    val secondsAnnounce: Int get() = config.integer("seconds-announce", 600)
    val receivePermission: String get() = config.string("receive-permission", "arc.board-announce")
    val mainMenuBackCommand: String get() = config.string("main-menu-back-command", "menu")

    // ── Item fields ───────────────────────────────────────────────────────────

    val display: String get() = config.string("item.display", "<yellow><bold>Доска объявлений")
    val descriptionPrefix: String get() = config.string("item.description-prefix", "")
    val lore: List<String> get() = config.stringList("item.lore")
    val editBottom: List<String> get() = config.stringList("item.click-to-edit")
    val rateBottom: List<String> get() = config.stringList("item.click-to-rate")

    // ── GUI names (ChestGui requires legacy §-format) ─────────────────────────

    val createEntryGuiName: String
        get() = config.string("create-entry-gui-name", "<gray>Создать объявление").miniToLegacy()
    val editEntryGuiName: String
        get() = config.string("edit-entry-gui-name", "<gray>Редактировать объявление").miniToLegacy()
    val boardGuiName: String
        get() = config.string("board-gui-name", "<gray>Доска объявлений").miniToLegacy()
    val rateGuiName: String
        get() = config.string("rate-gui-name", "<gray>Оценить объявление").miniToLegacy()

    // ── Arbitrary key lookup (for locale strings in board YAMLs) ─────────────

    @JvmStatic
    fun getString(key: String): String = config.string(key, key)

    @JvmStatic
    fun getStringList(key: String): List<String> = config.stringList(key)

    /** Module YAML — use [ru.arc.util.fromConfig] with item paths (`add-menu.publish`, …). */
    @JvmStatic
    fun config(): Config = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "board.yml")

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.miniToLegacy(): String =
        LegacyComponentSerializer.legacyAmpersand()
            .serialize(MiniMessage.miniMessage().deserialize(this))
}
