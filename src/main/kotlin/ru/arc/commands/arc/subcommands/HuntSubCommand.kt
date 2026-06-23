package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.treasurechests.TreasureHuntRegistry
import ru.arc.util.Logging.error

/**
 * /arc hunt - управление охотой на сокровища.
 *
 * - start <preset> [chests] — пресет из treasure-hunt.yml
 * - start custom <location_pool> <chests> <chest> <treasure_pool> — готовый пул локаций
 * - start custom generate here <radius> <chests> <chest> <treasure_pool>
 * - start custom generate <x> <y> <z> <radius> <chests> <chest> <treasure_pool>
 *
 * [chest] — модель сундука: alias из treasure-hunt.yml (pumpkin_1, easter) или vanilla.
 */
object HuntSubCommand : SubCommand {
    private const val CUSTOM = "custom"
    private const val GENERATE = "generate"
    private val HERE_TOKENS = setOf("here", "@here")

    override val configKey = "hunt"
    override val defaultName = "hunt"
    override val defaultPermission = "arc.treasure-hunt"
    override val defaultDescription = "Управление охотой на сокровища (запуск, остановка, статус)"
    override val defaultUsage =
        "/arc hunt [status|types|stopall|start <preset|custom ...>|stop <location_pool>]"

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        if (args.isEmpty()) {
            showStatus(sender)
            return true
        }

        try {
            when (args[0].lowercase()) {
                "types" -> {
                    showTypes(sender)
                }

                "status" -> {
                    showStatus(sender)
                }

                "start" -> {
                    handleStart(sender, args)
                }

                "stop" -> handleStop(sender, args)

                "stopall" -> {
                    TreasureHuntManager.stopAll()
                    sender.sendMessage(CommandConfig.get("hunt.all-stopped", "<gray>Все охоты остановлены!"))
                }

                else -> sendUnknownAction(sender, args[0])
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
                    "%count%",
                    activeHunts.size.toString(),
                ),
            )
            activeHunts.forEach { hunt ->
                sender.sendMessage(
                    CommandConfig.get(
                        "hunt.active-item",
                        "<gray>• <white>%location_pool% <gray>- <yellow>%chests%<gray> сундуков",
                        "%location_pool%",
                        hunt.config.locationPoolId,
                        "%chests%",
                        hunt.remainingChests.toString(),
                    ),
                )
            }
        }

        sender.sendMessage(
            CommandConfig.get(
                "hunt.types-count",
                "<gray>Доступных пресетов: <white>%count%",
                "%count%",
                types.size.toString(),
            ),
        )
        sender.sendMessage(
            CommandConfig.get(
                "hunt.commands-hint",
                "<gray>Команды: <white>types, start <preset|custom>, stop <location_pool>, stopall",
            ),
        )
    }

    private fun showTypes(sender: CommandSender) {
        val types = TreasureHuntManager.getTreasureHuntTypes()

        if (types.isEmpty()) {
            sender.sendMessage(CommandConfig.get("hunt.no-types", "<gray>Нет настроенных пресетов охот"))
            return
        }

        sender.sendMessage(CommandConfig.get("hunt.types-header", "<gold>═══ Пресеты охот ═══"))
        sender.sendMessage(CommandConfig.get("hunt.types-blank", ""))

        types.forEach { typeId ->
            val config = TreasureHuntManager.getTreasureHuntType(typeId) ?: return@forEach
            val poolSize = config.getLocationPool()?.size
            val poolSuffix = HuntTypesFormatter.locationPoolSizeSuffix(poolSize)

            sender.sendMessage(
                CommandConfig.get(
                    "hunt.type-name",
                    "<yellow>%type%",
                    "%type%",
                    typeId,
                ),
            )
            sender.sendMessage(
                CommandConfig.huntTypeLocation(config.locationPoolId, poolSuffix),
            )
            sender.sendMessage(
                CommandConfig.get(
                    "hunt.type-treasure",
                    "<gray>  treasure_pool: <white>%treasure_pools%",
                    "%treasure_pools%",
                    HuntTypesFormatter.treasurePools(config),
                ),
            )
            sender.sendMessage(
                CommandConfig.get(
                    "hunt.type-chest",
                    "<gray>  chest: <white>%chests%",
                    "%chests%",
                    HuntTypesFormatter.chestModels(config),
                ),
            )
            sender.sendMessage(
                CommandConfig.get(
                    "hunt.type-start",
                    "<gray>  → <white>/arc hunt start %type% [сундуков]",
                    "%type%",
                    typeId,
                ),
            )
            sender.sendMessage(CommandConfig.get("hunt.types-blank", ""))
        }

        sender.sendMessage(CommandConfig.get("hunt.types-custom-header", "<gold>─── Custom ───"))
        sender.sendMessage(CommandConfig.get("hunt.custom-hint", CommandConfig.huntCustomHintDefault()))
        sender.sendMessage(CommandConfig.get("hunt.generate-hint", CommandConfig.huntGenerateHintDefault()))
        sender.sendMessage(CommandConfig.get("hunt.chest-hint", CommandConfig.huntChestHintDefault()))
        sender.sendMessage(
            CommandConfig.get(
                "hunt.types-stop-hint",
                "<gray>Стоп: <white>/arc hunt stop <location_pool>"
            )
        )
    }

    private fun handleStart(
        sender: CommandSender,
        args: Array<String>,
    ) {
        val identifier =
            args.getOrNull(1) ?: run {
                showTypes(sender)
                return
            }

        when {
            identifier.equals(CUSTOM, ignoreCase = true) -> {
                startCustom(sender, args)
            }

            else -> {
                val huntType = TreasureHuntManager.getTreasureHuntType(identifier)
                if (huntType != null) {
                    startByPreset(sender, identifier, args)
                } else {
                    sender.sendMessage(CommandConfig.huntTypeNotFound())
                }
            }
        }
    }

    private fun startByPreset(
        sender: CommandSender,
        presetId: String,
        args: Array<String>,
    ) {
        val huntType =
            TreasureHuntManager.getTreasureHuntType(presetId) ?: run {
                sender.sendMessage(CommandConfig.huntTypeNotFound())
                return
            }

        if (huntType.getLocationPool() == null) {
            sender.sendMessage(CommandConfig.huntLocationPoolNotFound(huntType.locationPoolId))
            return
        }

        val chestsOverride = args.getOrNull(2)?.toIntOrNull() ?: 0
        TreasureHuntManager.startHunt(presetId, chestsOverride, sender)
        sender.sendMessage(CommandConfig.huntStarted())
    }

    private fun startCustom(
        sender: CommandSender,
        args: Array<String>,
    ) {
        when (args.getOrNull(2)?.lowercase()) {
            GENERATE -> startCustomGenerate(sender, args)
            null -> sender.sendMessage(CommandConfig.huntCustomNotEnoughArgs())
            else -> startCustomPool(sender, args)
        }
    }

    private fun startCustomPool(
        sender: CommandSender,
        args: Array<String>,
    ) {
        if (args.size < 6) {
            sender.sendMessage(CommandConfig.huntCustomNotEnoughArgs())
            return
        }

        val locationPoolId = args[2]
        val locationPool =
            LocationPoolManager.getPool(locationPoolId) ?: run {
                sender.sendMessage(CommandConfig.huntLocationPoolNotFound(locationPoolId))
                return
            }

        val chests = parseChestCount(sender, args[3]) ?: return
        val chestModel = resolveChestModel(args[4])
        val treasurePoolId = args[5]

        TreasureHuntManager.startHunt(locationPool, chests, chestModel, treasurePoolId, sender)
        sender.sendMessage(CommandConfig.huntStarted())
    }

    private fun startCustomGenerate(
        sender: CommandSender,
        args: Array<String>,
    ) {
        when {
            args.size >= 8 && args[3].lowercase() in HERE_TOKENS -> {
                val player =
                    sender as? Player ?: run {
                        sender.sendMessage(CommandConfig.huntGeneratePlayerOnly())
                        return
                    }
                val radius =
                    args[4].toDoubleOrNull()?.takeIf { it > 0 } ?: run {
                        sender.sendMessage(CommandConfig.huntInvalidRadius(args[4]))
                        return
                    }
                val chests = parseChestCount(sender, args[5]) ?: return
                val chestModel = resolveChestModel(args[6])
                val treasurePoolId = args[7]

                runGeneratedHunt(sender, player.location, radius, chests, chestModel, treasurePoolId)
            }

            args.size >= 10 -> {
                val x = args[3].toDoubleOrNull()
                val y = args[4].toDoubleOrNull()
                val z = args[5].toDoubleOrNull()
                if (x == null || y == null || z == null) {
                    sender.sendMessage(CommandConfig.huntGenerateNotEnoughArgs())
                    return
                }

                val radius =
                    args[6].toDoubleOrNull()?.takeIf { it > 0 } ?: run {
                        sender.sendMessage(CommandConfig.huntInvalidRadius(args[6]))
                        return
                    }
                val chests = parseChestCount(sender, args[7]) ?: return
                val chestModel = resolveChestModel(args[8])
                val treasurePoolId = args[9]

                val world =
                    (sender as? Player)?.world
                        ?: Bukkit.getWorlds().firstOrNull() ?: run {
                        sender.sendMessage(CommandConfig.huntGenerateNoWorld())
                        return
                    }

                runGeneratedHunt(sender, Location(world, x, y, z), radius, chests, chestModel, treasurePoolId)
            }

            else -> {
                sender.sendMessage(CommandConfig.huntGenerateNotEnoughArgs())
            }
        }
    }

    private fun runGeneratedHunt(
        sender: CommandSender,
        center: Location,
        radius: Double,
        chests: Int,
        chestModel: String,
        treasurePoolId: String,
    ) {
        val hunt =
            TreasureHuntManager.startGeneratedHunt(
                center,
                radius,
                chests,
                chestModel,
                treasurePoolId,
                sender,
            )
        if (hunt != null) {
            sender.sendMessage(CommandConfig.huntStarted())
        }
    }

    private fun parseChestCount(
        sender: CommandSender,
        raw: String,
    ): Int? =
        raw.toIntOrNull()?.takeIf { it > 0 } ?: run {
            sender.sendMessage(CommandConfig.huntInvalidChests(raw))
            null
        }

    private fun resolveChestModel(raw: String): String = TreasureHuntRegistry.getAliases()[raw] ?: raw

    private fun handleStop(
        sender: CommandSender,
        args: Array<String>,
    ) {
        val locationPoolId =
            args.getOrNull(1) ?: run {
                sender.sendMessage(CommandConfig.huntSpecifyLocationPool())
                return
            }

        val locationPool =
            LocationPoolManager.getPool(locationPoolId) ?: run {
                sender.sendMessage(CommandConfig.huntLocationPoolNotFound(locationPoolId))
                return
            }

        TreasureHuntManager.getByLocationPool(locationPool).ifPresentOrElse(
            { hunt ->
                TreasureHuntManager.stopHunt(hunt)
                sender.sendMessage(CommandConfig.huntStopped())
            },
            { sender.sendMessage(CommandConfig.huntNotFound()) },
        )
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? = HuntSubCommandTabComplete.complete(sender, args)
}
