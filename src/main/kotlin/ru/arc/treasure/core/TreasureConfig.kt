package ru.arc.treasure.core

import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager

/**
 * Configuration for the treasure module.
 */
object TreasureConfig {
    private val config: Config
        get() = ConfigManager.of(
            ARC.instance.dataFolder.toPath().resolve("treasures"),
            "config.yml",
        )

    // ==================== GUI Settings ====================

    object Gui {
        val mainTitle: String get() = config.string("gui.main.title", "Пулы сокровищ")
        val mainPoolLore: List<String>
            get() =
                config
                    .stringList("gui.main.pool-lore")
                    .ifEmpty { listOf("<gray>Предметов: <white>%size%") }
        val mainBackCommand: String get() = config.string("gui.main.back-command", "")
        val mainCreatePool: String get() = config.string("gui.main.create-pool", "<green>Создать пул")
        val mainCreatePoolLore: List<String>
            get() =
                config
                    .stringList("gui.main.create-pool-lore")
                    .ifEmpty { listOf("<gray>Нажмите чтобы создать") }
        val mainCreatePoolStart: String get() = config.string("gui.main.create-pool-start", "<green>Введите ID пула")
        val mainCreatePoolDeny: String
            get() =
                config.string(
                    "gui.main.create-pool-deny",
                    "<red>Пул с таким ID уже существует",
                )

        val poolTitle: String get() = config.string("gui.pool.title", "Пул: %pool%")
        val poolAddItem: String get() = config.string("gui.pool.add-item", "<green>Добавить предмет")
        val poolAddMoney: String get() = config.string("gui.pool.add-money", "<gold>Добавить деньги")
        val poolAddCommand: String get() = config.string("gui.pool.add-command", "<aqua>Добавить команду")
        val poolDelete: String get() = config.string("gui.pool.delete", "<red>Удалить пул")
        val poolDeleteConfirm: String
            get() =
                config.string(
                    "gui.pool.delete-confirm",
                    "<red>Shift+Click для подтверждения",
                )
        val poolMessages: String get() = config.string("gui.pool.messages", "<yellow>Сообщения пула")
        val poolMessagesLore: List<String>
            get() =
                config
                    .stringList("gui.pool.messages-lore")
                    .ifEmpty { listOf("<gray>Сообщения при выдаче", "<gray>любой награды из пула") }

        val treasureTitle: String get() = config.string("gui.treasure.title", "Редактирование")
        val treasureDelete: String get() = config.string("gui.treasure.delete", "<red>Удалить")
        val treasureWeight: String get() = config.string("gui.treasure.weight", "<yellow>Вес: <white>%weight%")
        val treasureAmount: String
            get() =
                config.string(
                    "gui.treasure.amount",
                    "<yellow>Количество: <white>%min%-%max%",
                )
        val treasureMessages: String get() = config.string("gui.treasure.messages", "<yellow>Сообщения")
        val treasureMessagesLore: List<String>
            get() =
                config
                    .stringList("gui.treasure.messages-lore")
                    .ifEmpty { listOf("<gray>Сообщения при получении", "<gray>этой награды") }
    }

    // ==================== Input Prompts ====================

    object Input {
        val inputWeight: String get() = config.string("input.weight", "<green>Введите вес (число)")
        val invalidWeight: String get() = config.string("input.weight-invalid", "<red>Вес должен быть числом")

        val inputAmount: String get() = config.string("input.amount", "<green>Введите количество (число или мин-макс)")
        val invalidAmount: String get() = config.string("input.amount-invalid", "<red>Неверный формат количества")

        val inputMessage: String get() = config.string("input.message", "<green>Введите текст сообщения")
        val invalidMessage: String get() = config.string("input.message-invalid", "<red>Неверное сообщение")

        val inputCommand: String get() = config.string("input.command", "<green>Введите команду")
        val invalidCommand: String get() = config.string("input.command-invalid", "<red>Неверная команда")

        val inputMoneyAmount: String
            get() =
                config.string(
                    "input.money-amount",
                    "<green>Введите сумму (число или мин-макс)",
                )
        val invalidMoneyAmount: String get() = config.string("input.money-amount-invalid", "<red>Неверный формат суммы")
    }

    // ==================== Default Messages ====================

    object DefaultMessages {
        val itemReceived: String
            get() =
                config.string(
                    "defaults.item-received",
                    "<green>Вы получили: <yellow>%item% x%amount%",
                )
        val moneyReceived: String
            get() =
                config.string(
                    "defaults.money-received",
                    "<dark_green>Вы получили <yellow>%amount%<dark_green> монет",
                )
        val enchantReceived: String
            get() =
                config.string(
                    "defaults.enchant-received",
                    "<light_purple>Вы получили зачарованную книгу!",
                )
        val potionReceived: String get() = config.string("defaults.potion-received", "<dark_purple>Вы получили зелье!")

        val globalAnnounce: String
            get() =
                config.string(
                    "defaults.global-announce",
                    "<gold>%player% <yellow>получил награду!",
                )
    }

    // ==================== Admin Messages ====================

    object Messages {
        val poolCreated: String get() = config.string("messages.pool-created", "<green>Пул создан: <yellow>%pool%")
        val poolDeleted: String get() = config.string("messages.pool-deleted", "<red>Пул удален: <yellow>%pool%")
        val treasureAdded: String get() = config.string("messages.treasure-added", "<green>Награда добавлена")
        val treasureRemoved: String get() = config.string("messages.treasure-removed", "<red>Награда удалена")
        val treasureUpdated: String get() = config.string("messages.treasure-updated", "<yellow>Награда обновлена")
    }

    // ==================== Message Destinations ====================

    object MessageTypes {
        val chat: String get() = config.string("message-types.chat", "Чат")
        val actionBar: String get() = config.string("message-types.action-bar", "Экшн бар")
        val bossBar: String get() = config.string("message-types.boss-bar", "Босс бар")
        val title: String get() = config.string("message-types.title", "Заголовок")
    }

    object MessageTargets {
        val player: String get() = config.string("message-targets.player", "Игроку")
        val server: String get() = config.string("message-targets.server", "Серверу")
        val global: String get() = config.string("message-targets.global", "Всем серверам")
        val nearby: String get() = config.string("message-targets.nearby", "Ближайшим")
    }
}
