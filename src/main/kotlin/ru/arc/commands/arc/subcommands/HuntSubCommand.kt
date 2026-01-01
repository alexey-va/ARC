package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.treasurechests.TreasureHunt
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.util.Logging.error

/**
 * /arc hunt - управление охотой на сокровища.
 *
 * Использование:
 * - types - показать доступные типы охот
 * - start <type> [chests] - запустить по типу
 * - start <pool> <chests> <namespace> <treasure_pool> - полная форма
 * - stop <pool> - остановить охоту
 */
object HuntSubCommand : SubCommand {

    override val configKey = "hunt"
    override val defaultName = "hunt"
    override val defaultPermission = "arc.treasure-hunt"
    override val defaultDescription = "Управление охотой на сокровища"
    override val defaultUsage = "/arc hunt [types|start|stop]"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        // Без аргументов - показать статус
        if (args.isEmpty()) {
            showStatus(sender)
            return true
        }

        try {
            when (args[0].lowercase()) {
                "types" -> showTypes(sender)
                "status" -> showStatus(sender)
                "start" -> handleStart(sender, args)
                "stop" -> handleStop(sender, args)
                "stopall" -> {
                    TreasureHuntManager.stopAll()
                    sender.sendMessage(CommandConfig.get("hunt.all-stopped", "<gray>Все охоты остановлены!"))
                }

                else -> {
                    // Попробовать как тип охоты: /arc hunt daily
                    val huntType = TreasureHuntManager.getTreasureHuntType(args[0])
                    if (huntType != null) {
                        startByType(sender, args[0], args)
                    } else {
                        sendUnknownAction(sender, args[0])
                    }
                }
            }
        } catch (e: Exception) {
            sender.sendMessage(CommandConfig.huntError())
            error("Error in hunt command: ", e)
        }
        return true
    }

    private fun showStatus(sender: CommandSender) {
        val activeHunts = TreasureHuntManager.getActiveHunts()
        val types = TreasureHuntManager.getTreasureHuntTypes()

        sender.sendMessage(CommandConfig.get("hunt.status-header", "<gold>═══ Охота на сокровища ═══"))

        if (activeHunts.isEmpty()) {
            sender.sendMessage(CommandConfig.get("hunt.no-active", "<gray>Нет активных охот"))
        } else {
            sender.sendMessage(
                CommandConfig.get(
                    "hunt.active-count",
                    "<gray>Активных охот: <white>%count%",
                    "%count%", activeHunts.size.toString()
                )
            )
            activeHunts.forEach { hunt ->
                sender.sendMessage(
                    CommandConfig.get(
                        "hunt.active-item",
                        "<gray>• <white>%pool% <gray>- <yellow>%chests%<gray> сундуков",
                        "%pool%", hunt.locationPool.id,
                        "%chests%", hunt.remainingChests.toString()
                    )
                )
            }
        }

        sender.sendMessage(
            CommandConfig.get(
                "hunt.types-count",
                "<gray>Доступных типов: <white>%count%",
                "%count%", types.size.toString()
            )
        )
        sender.sendMessage(CommandConfig.get("hunt.commands-hint", "<gray>Команды: <white>types, start, stop, stopall"))
    }

    private fun showTypes(sender: CommandSender) {
        val types = TreasureHuntManager.getTreasureHuntTypes()

        if (types.isEmpty()) {
            sender.sendMessage(CommandConfig.get("hunt.no-types", "<gray>Нет настроенных типов охот"))
            return
        }

        sender.sendMessage(CommandConfig.get("hunt.types-header", "<gold>Доступные типы охот:"))
        types.forEach { typeId ->
            val type = TreasureHuntManager.getTreasureHuntType(typeId)
            val poolName = type?.locationPool?.id ?: "?"
            sender.sendMessage(
                CommandConfig.get(
                    "hunt.type-item",
                    "<gray>• <white>%type% <gray>(пул: %pool%)",
                    "%type%", typeId,
                    "%pool%", poolName
                )
            )
        }
        sender.sendMessage(CommandConfig.get("hunt.start-hint", "<gray>Запуск: <white>/arc hunt <тип> [сундуков]"))
    }

    private fun handleStart(sender: CommandSender, args: Array<String>) {
        val identifier = args.getOrNull(1) ?: run {
            showTypes(sender)
            return
        }

        val huntType = TreasureHuntManager.getTreasureHuntType(identifier)
        if (huntType != null) {
            startByType(sender, identifier, args)
        } else {
            startFull(sender, identifier, args)
        }
    }

    private fun startByType(sender: CommandSender, typeId: String, args: Array<String>) {
        val huntType = TreasureHuntManager.getTreasureHuntType(typeId)!!

        if (huntType.locationPool == null) {
            sender.sendMessage(CommandConfig.huntPoolNotFound(typeId))
            return
        }

        val chestsOverride = args.getOrNull(if (args[0].equals("start", true)) 2 else 1)?.toIntOrNull() ?: 0
        TreasureHuntManager.startHunt(typeId, chestsOverride, sender)
    }

    private fun startFull(sender: CommandSender, poolId: String, args: Array<String>) {
        if (args.size < 5) {
            sender.sendMessage(CommandConfig.huntNotEnoughArgs())
            return
        }

        val pool = LocationPoolManager.getPool(poolId) ?: run {
            sender.sendMessage(CommandConfig.huntPoolNotFound(poolId))
            return
        }

        val chests = args[2].toIntOrNull()?.takeIf { it > 0 } ?: run {
            sender.sendMessage(CommandConfig.huntInvalidChests(args[2]))
            return
        }

        val namespace = TreasureHunt.aliases()[args[3]] ?: args[3]
        val treasurePoolId = args[4]

        TreasureHuntManager.startHunt(pool, chests, namespace, treasurePoolId, sender)
        sender.sendMessage(CommandConfig.huntStarted())
    }

    private fun handleStop(sender: CommandSender, args: Array<String>) {
        val poolId = args.getOrNull(1) ?: run {
            sender.sendMessage(CommandConfig.huntSpecifyPool())
            return
        }

        val pool = LocationPoolManager.getPool(poolId) ?: run {
            sender.sendMessage(CommandConfig.huntPoolNotFound(poolId))
            return
        }

        TreasureHuntManager.getByLocationPool(pool).ifPresentOrElse(
            { hunt ->
                TreasureHuntManager.stopHunt(hunt)
                sender.sendMessage(CommandConfig.huntStopped())
            },
            { sender.sendMessage(CommandConfig.huntNotFound()) }
        )
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        val allPools = LocationPoolManager.getAll().map { it.id }
        val huntTypes = TreasureHuntManager.getTreasureHuntTypes()
        val namespaces = TreasureHunt.aliases().keys + listOf("vanilla", "ItemsAdderId")
        val treasurePools = TreasureHuntManager.getTreasurePools().map { it.getId() }

        return when (args.size) {
            // Приоритет: status, types, start, stop, stopall, затем типы охот
            1 -> (listOf("status", "types", "start", "stop", "stopall") + huntTypes).tabComplete(args[0])
            2 -> when (args[0].lowercase()) {
                // Сначала типы (чаще используются), потом пулы
                "start" -> (huntTypes + allPools).tabComplete(args[1])
                "stop" -> allPools.tabComplete(args[1])
                else -> null
            }

            3 -> listOf(LocationPoolManager.getPool(args[1])?.locations?.size()?.toString() ?: "10")
            4 -> namespaces.toList().tabComplete(args[3])
            5 -> treasurePools.tabComplete(args[4])
            else -> null
        }
    }
}
