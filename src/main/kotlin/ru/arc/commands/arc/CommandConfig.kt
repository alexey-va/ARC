package ru.arc.commands.arc

import net.kyori.adventure.text.Component
import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager

/**
 * Centralized message and metadata configuration for all /arc commands.
 * Messages are loaded from `config/commands.yml`.
 */
object CommandConfig {

    private val config: Config
        get() = ConfigManager.of(ARC.plugin.dataFolder.toPath().resolve("config"), "commands.yml")

    // ==================== Command Metadata ====================

    /**
     * Gets command metadata from config.
     */
    fun getCommandName(commandKey: String, default: String): String {
        return config.string("commands.$commandKey.name", default)
    }

    fun getCommandPermission(commandKey: String, default: String?): String? {
        val value = config.string("commands.$commandKey.permission", default ?: "")
        return value.ifEmpty { null }
    }

    fun getCommandDescription(commandKey: String, default: String): String {
        return config.string("commands.$commandKey.description", default)
    }

    fun getCommandUsage(commandKey: String, default: String): String {
        return config.string("commands.$commandKey.usage", default)
    }

    fun isPlayerOnly(commandKey: String, default: Boolean): Boolean {
        return config.bool("commands.$commandKey.player-only", default)
    }

    fun getAliases(commandKey: String): List<String> {
        return config.stringList("commands.$commandKey.aliases")
    }

    // ==================== Message Methods ====================

    /**
     * Gets a message component from the config.
     * @param key The message key (e.g., "common.no-permission")
     * @param default The default message if key is not found
     * @param replacers Pairs of placeholder and value (e.g., "%player%", "Steve")
     */
    fun get(key: String, default: String, vararg replacers: String): Component {
        return config.componentDef("messages.$key", default, *replacers)
    }

    // ==================== Common Messages ====================

    fun noPermission() = get("common.no-permission", "<red>У вас нет прав для этой команды!")
    fun playerOnly() = get("common.player-only", "<red>Эта команда доступна только игрокам!")
    fun playerNotFound(name: String) =
        get("common.player-not-found", "<red>Игрок <white>%player%<red> не найден!", "%player%", name)

    fun unknownAction(action: String) =
        get("common.unknown-action", "<red>Неизвестное действие: <white>%action%", "%action%", action)

    fun usage(usage: String) = get("common.usage", "<red>Использование: <gray>%usage%", "%usage%", usage)
    fun hookNotLoaded(hook: String) = get("common.hook-not-loaded", "<red>%hook% не загружен!", "%hook%", hook)

    // ==================== Arc Command ====================

    fun arcUsage() = get("arc.usage", "<red>Использование: <gray>/arc <команда> [аргументы...]")
    fun arcAvailable(list: String) = get("arc.available", "<gray>Доступные: <white>%list%", "%list%", list)
    fun arcUnknownCommand(cmd: String) =
        get("arc.unknown-command", "<red>Неизвестная команда: <white>%command%", "%command%", cmd)

    // ==================== Reload ====================

    fun reloadSuccess() = get("reload.success", "<gold>Перезагрузка успешна!")

    // ==================== Repo ====================

    fun repoSaved() = get("repo.saved", "<green>Все репозитории сохранены!")
    fun repoSize(name: String, bytes: Long) =
        get("repo.size", "<gray>%name%: <white>%bytes% байт", "%name%", name, "%bytes%", bytes.toString())

    fun repoTotal(total: Long) = get("repo.total", "<yellow>Итого: <white>%total% байт", "%total%", total.toString())

    // ==================== Logger ====================

    fun loggerLevelSet(level: String) =
        get("logger.level-set", "<gray>Уровень логов установлен на <white>%level%<gray>!", "%level%", level)

    fun loggerInvalidLevel(level: String) =
        get("logger.invalid-level", "<red>Неверный уровень логов: <white>%level%", "%level%", level)

    fun loggerAvailableLevels(levels: String) =
        get("logger.available-levels", "<gray>Доступные уровни: <white>%levels%", "%levels%", levels)

    // ==================== Locpool ====================

    fun locpoolList(pools: String) = get("locpool.list", "<gray>Текущие пулы локаций: <white>%pools%", "%pools%", pools)
    fun locpoolNotEditing() = get("locpool.not-editing", "<gray>Вы не редактируете никакой пул локаций!")
    fun locpoolEditingCancelled(poolId: String) = get(
        "locpool.editing-cancelled",
        "<gray>Редактирование пула <white>%pool_id%<gray> отменено!",
        "%pool_id%",
        poolId
    )

    fun locpoolEditingStarted(poolId: String) = get(
        "locpool.editing-started",
        "<gray>Вы начали редактировать пул <white>%pool_id%<gray>! Золотой блок = добавить, Красный блок = удалить.",
        "%pool_id%",
        poolId
    )

    fun locpoolDeleted(poolId: String) =
        get("locpool.deleted", "<gray>Пул <white>%pool_id%<gray> удален успешно!", "%pool_id%", poolId)

    fun locpoolNotFound(poolId: String) =
        get("locpool.not-found", "<red>Пул <white>%pool_id%<red> не найден!", "%pool_id%", poolId)

    fun locpoolSpecifyPool() = get("locpool.specify-pool", "<gray>Укажите пул локаций!")

    // ==================== Hunt ====================

    fun huntStarted() = get("hunt.started", "<green>Охота на сокровища запущена!")
    fun huntStopped() = get("hunt.stopped", "<gray>Охота на сокровища остановлена!")
    fun huntNotFound() = get("hunt.not-found", "<red>Охота на сокровища не найдена!")
    fun huntTypeNotFound() = get("hunt.type-not-found", "<red>Тип охоты на сокровища не найден!")
    fun huntPoolNotFound(poolId: String) =
        get("hunt.pool-not-found", "<red>Пул локаций <white>%pool_id%<red> не найден!", "%pool_id%", poolId)

    fun huntNotEnoughArgs() = get(
        "hunt.not-enough-args",
        "<red>Недостаточно аргументов! <gray>Синтаксис: /arc hunt start <pool_id> <chests> <namespace> <treasure_pool>"
    )

    fun huntInvalidChests(value: String) =
        get("hunt.invalid-chests", "<red>Неверное количество сундуков: <white>%value%", "%value%", value)

    fun huntSpecifyPool() = get("hunt.specify-pool", "<red>Укажите пул локаций для остановки!")
    fun huntError() = get("hunt.error", "<red>Ошибка выполнения команды!")
    // New hunt messages are accessed via get() directly in the command

    // ==================== Treasures ====================

    fun treasuresReloaded() = get("treasures.reloaded", "<gray>Награды перезагружены!")
    fun treasuresPoolNotFound(poolId: String) =
        get("treasures.pool-not-found", "<red>Пул <white>%pool_id%<red> не найден!", "%pool_id%", poolId)

    fun treasuresPoolCreating(poolId: String) = get(
        "treasures.pool-creating",
        "<yellow>Пул <white>%pool_id%<yellow> не найден! Создаем...",
        "%pool_id%",
        poolId
    )

    fun treasuresNoItemInHand() = get("treasures.no-item-in-hand", "<red>В руке нет предмета!")
    fun treasuresNoTargetBlock() = get("treasures.no-target-block", "<red>Не найден блок!")
    fun treasuresItemAdded(poolId: String, item: String) = get(
        "treasures.item-added",
        "<gray>Предмет <white>%item%<gray> добавлен в пул <white>%pool_id%<gray>!",
        "%pool_id%",
        poolId,
        "%item%",
        item
    )

    fun treasuresItemAlreadyAdded(poolId: String, item: String) = get(
        "treasures.item-already-added",
        "<red>Предмет <white>%item%<red> уже добавлен в пул <white>%pool_id%<red>!",
        "%pool_id%",
        poolId,
        "%item%",
        item
    )

    fun treasuresItemsAdded(poolId: String, amount: Int) = get(
        "treasures.items-added",
        "<gray>%amount% предметов добавлены в пул <white>%pool_id%<gray>!",
        "%pool_id%",
        poolId,
        "%amount%",
        amount.toString()
    )

    fun treasuresSubpoolAdded(poolId: String, subpoolId: String) = get(
        "treasures.subpool-added",
        "<gray>Сабпул <white>%subpool_id%<gray> добавлен в пул <white>%pool_id%<gray>!",
        "%pool_id%",
        poolId,
        "%subpool_id%",
        subpoolId
    )

    fun treasuresSubpoolAlreadyAdded(poolId: String, subpoolId: String) = get(
        "treasures.subpool-already-added",
        "<red>Сабпул <white>%subpool_id%<red> уже добавлен в пул <white>%pool_id%<red>!",
        "%pool_id%",
        poolId,
        "%subpool_id%",
        subpoolId
    )

    fun treasuresPoolEmpty(poolId: String) =
        get("treasures.pool-empty", "<red>Пул <white>%pool_id%<red> пуст!", "%pool_id%", poolId)

    fun treasuresGiven(player: String) =
        get("treasures.given", "<gray>Награда выдана игроку <white>%player%<gray>!", "%player%", player)

    // ==================== Emshop ====================

    fun emshopReset() = get("emshop.reset", "<green>Магазин сброшен!")

    // ==================== Jobsboosts ====================

    fun jobsboostsReset(player: String) =
        get("jobsboosts.reset", "<green>Бусты сброшены для игрока: <white>%player%", "%player%", player)

    fun jobsboostsSpecifyPlayer() = get("jobsboosts.specify-player", "<red>Укажите имя игрока!")
    fun jobsboostsPlayerNotFoundOrConsole() =
        get("jobsboosts.player-not-found-or-console", "<red>Игрок не найден или вы консоль!")

    // ==================== Audit ====================

    fun auditCleared() = get("audit.cleared", "<gray>Аудит очищен!")
    fun auditClearedFor(player: String) =
        get("audit.cleared-for", "<gray>Аудит очищен для игрока <white>%player%<gray>!", "%player%", player)

    fun auditInvalidPage(text: String) =
        get("audit.invalid-page", "<red>Неверный формат страницы: <white>%text%", "%text%", text)

    // ==================== RespawnOnRtp ====================

    fun rtpAdded(player: String) =
        get("rtp.added", "<green>Игрок <white>%player%<green> добавлен в список RTP-респауна!", "%player%", player)
}
