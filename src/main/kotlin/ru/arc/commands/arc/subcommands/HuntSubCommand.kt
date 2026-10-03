package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.common.locationpools.LocationPool
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.treasurechests.TreasureHuntRegistry
import ru.arc.util.Logging.error

internal sealed interface HuntNamedArgumentsResult {
    data class Parsed(val values: Map<String, String>) : HuntNamedArgumentsResult

    data class Invalid(val reason: String) : HuntNamedArgumentsResult
}

internal object HuntNamedArguments {
    fun parse(tokens: List<String>, allowedKeys: Set<String>): HuntNamedArgumentsResult {
        val values = linkedMapOf<String, String>()
        val allowed = allowedKeys.mapTo(mutableSetOf()) { it.lowercase() }

        for (token in tokens) {
            val separator = token.indexOf('=')
            if (separator < 0) {
                return HuntNamedArgumentsResult.Invalid(
                    "используйте key=value и не смешивайте именованные аргументы с позиционными",
                )
            }
            if (token.indexOf('=', separator + 1) >= 0) {
                return HuntNamedArgumentsResult.Invalid("в одном аргументе допустим только один знак '='")
            }

            val key = token.substring(0, separator).trim().lowercase()
            val value = token.substring(separator + 1).trim()
            if (key.isEmpty()) return HuntNamedArgumentsResult.Invalid("укажите имя аргумента перед '='")
            if (key !in allowed) {
                return HuntNamedArgumentsResult.Invalid("допустимые ключи: ${allowed.sorted().joinToString(", ")}")
            }
            if (key in values) return HuntNamedArgumentsResult.Invalid("ключ '$key' указан повторно")
            if (value.isEmpty()) return HuntNamedArgumentsResult.Invalid("для '$key' укажите значение после '='")
            values[key] = value
        }

        return HuntNamedArgumentsResult.Parsed(values)
    }

    fun positiveChestCount(raw: String): Int? = raw.toIntOrNull()?.takeIf { it > 0 }

    fun strictBoolean(raw: String): Boolean? =
        when (raw) {
            "true" -> true
            "false" -> false
            else -> null
        }

    fun finiteNumber(raw: String): Double? = raw.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/**
 * /arc hunt - управление охотой на сокровища.
 *
 * - start preset=<preset> [chests=<count>] [replace=true|false] — пресет из treasure-hunt.yml
 * - start custom pool=<location_pool> chests=<count> chest=<model> loot=<pool> [replace=true|false]
 * - start custom generate here radius=<radius> chests=<count> chest=<model> loot=<pool> [replace=true|false]
 * - start custom generate world=<world> x=<x> y=<y> z=<z> radius=<radius>
 *   chests=<count> chest=<model> loot=<pool> [replace=true|false]
 *
 * [chest] — модель сундука: alias из treasure-hunt.yml (pumpkin_1, easter) или vanilla.
 */
object HuntSubCommand : SubCommand {
    private const val CUSTOM = "custom"
    private const val GENERATE = "generate"
    private val HERE_TOKENS = setOf("here", "@here")

    override val configKey = "hunt"
    override val defaultName = "hunt"
    override val defaultPermission = "arc.treasure.hunt.admin"
    override val defaultDescription = "Управление охотой на сокровища (запуск, остановка, статус)"
    override val defaultUsage =
        "/arc hunt [status|types|stopall|start preset=<id> chests=<count>|start custom ...|stop pool=<id>]"

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
                "<gray>Команды: <white>types, start preset=<id>, start custom ..., stop pool=<id>, stopall",
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
                    "<gray>  loot: <white>%treasure_pools%",
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
                    "<gray>  → <white>/arc hunt start preset=%type% [chests=...]",
                    "%type%",
                    typeId,
                ),
            )
            sender.sendMessage(CommandConfig.get("hunt.types-blank", ""))
        }

        sender.sendMessage(CommandConfig.get("hunt.types-custom-header", "<gold>─── Своя охота ───"))
        sender.sendMessage(CommandConfig.get("hunt.custom-hint", CommandConfig.huntCustomHintDefault()))
        sender.sendMessage(CommandConfig.get("hunt.generate-hint", CommandConfig.huntGenerateHintDefault()))
        sender.sendMessage(CommandConfig.get("hunt.chest-hint", CommandConfig.huntChestHintDefault()))
        sender.sendMessage(
            CommandConfig.get(
                "hunt.types-stop-hint",
                "<gray>Стоп: <white>/arc hunt stop pool=<location_pool>"
            )
        )
    }

    private fun handleStart(
        sender: CommandSender,
        args: Array<String>,
    ) {
        val identifier = args.getOrNull(1) ?: run {
            showTypes(sender)
            return
        }

        when {
            identifier.equals(CUSTOM, ignoreCase = true) -> startCustom(sender, args)
            args.drop(1).any { '=' in it } -> startNamedPreset(sender, args.drop(1))
            else -> startByPreset(sender, identifier, args)
        }
    }

    private fun startByPreset(
        sender: CommandSender,
        presetId: String,
        args: Array<String>,
    ) {
        if (args.size !in 2..3) {
            sendArgumentError(sender, "используйте /arc hunt start preset=<id> chests=<count>")
            return
        }
        val chests = args.getOrNull(2)?.let { parseChestCount(sender, it) ?: return } ?: 0
        val huntType = TreasureHuntManager.getTreasureHuntType(presetId) ?: run {
            sender.sendMessage(CommandConfig.huntTypeNotFound())
            return
        }
        val locationPool = huntType.getLocationPool() ?: run {
            sender.sendMessage(CommandConfig.huntLocationPoolNotFound(huntType.locationPoolId))
            return
        }

        TreasureHuntManager.startHunt(presetId, chests, sender)
        reportStartResult(sender, locationPool)
    }

    private fun startNamedPreset(sender: CommandSender, tokens: List<String>) {
        val args = parseNamedArguments(sender, tokens, setOf("preset", "chests", "replace")) ?: return
        if (!requireArguments(sender, args, listOf("preset"))) return

        val chests = args["chests"]?.let { parseChestCount(sender, it) ?: return } ?: 0
        val replaceExisting = parseReplace(sender, args["replace"]) ?: return
        val presetId = args.getValue("preset")
        val huntType = TreasureHuntManager.getTreasureHuntType(presetId) ?: run {
            sender.sendMessage(CommandConfig.huntTypeNotFound())
            return
        }
        val locationPool = huntType.getLocationPool() ?: run {
            sender.sendMessage(CommandConfig.huntLocationPoolNotFound(huntType.locationPoolId))
            return
        }

        if (skipIfAlreadyActive(sender, locationPool, replaceExisting)) return
        TreasureHuntManager.startHunt(presetId, chests, sender, replaceExisting)
        reportStartResult(sender, locationPool)
    }

    private fun startCustom(sender: CommandSender, args: Array<String>) {
        val mode = args.getOrNull(2)
        if (args.drop(2).any { '=' in it }) {
            if (mode?.equals(GENERATE, ignoreCase = true) == true) {
                startCustomGenerateNamed(sender, args.drop(3))
            } else {
                startCustomPoolNamed(sender, args.drop(2))
            }
            return
        }

        when {
            mode?.equals(GENERATE, ignoreCase = true) == true -> startCustomGenerate(sender, args)
            mode == null -> sender.sendMessage(CommandConfig.huntCustomNotEnoughArgs())
            else -> startCustomPool(sender, args)
        }
    }

    private fun startCustomPool(sender: CommandSender, args: Array<String>) {
        if (args.size != 6 || args.drop(2).any(String::isBlank)) {
            sender.sendMessage(CommandConfig.huntCustomNotEnoughArgs())
            return
        }
        val locationPool = LocationPoolManager.getPool(args[2]) ?: run {
            sender.sendMessage(CommandConfig.huntLocationPoolNotFound(args[2]))
            return
        }
        val chests = parseChestCount(sender, args[3]) ?: return
        val chestModel = resolveChestModel(args[4])

        TreasureHuntManager.startHunt(locationPool, chests, chestModel, args[5], sender)
        reportStartResult(sender, locationPool)
    }

    private fun startCustomPoolNamed(sender: CommandSender, tokens: List<String>) {
        val args = parseNamedArguments(sender, tokens, setOf("pool", "chests", "chest", "loot", "replace")) ?: return
        if (!requireArguments(sender, args, listOf("pool", "chests", "chest", "loot"))) return

        val replaceExisting = parseReplace(sender, args["replace"]) ?: return
        val chests = parseChestCount(sender, args.getValue("chests")) ?: return
        val poolId = args.getValue("pool")
        val locationPool = LocationPoolManager.getPool(poolId) ?: run {
            sender.sendMessage(CommandConfig.huntLocationPoolNotFound(poolId))
            return
        }
        val chestModel = resolveChestModel(args.getValue("chest"))
        if (skipIfAlreadyActive(sender, locationPool, replaceExisting)) return
        TreasureHuntManager.startHunt(locationPool, chests, chestModel, args.getValue("loot"), sender, replaceExisting)
        reportStartResult(sender, locationPool)
    }

    private fun startCustomGenerate(sender: CommandSender, args: Array<String>) {
        when {
            args.size == 8 && args[3].lowercase() in HERE_TOKENS -> {
                val player = sender as? Player ?: run {
                    sender.sendMessage(CommandConfig.huntGeneratePlayerOnly())
                    return
                }
                val radius = parseRadius(sender, args[4]) ?: return
                val chests = parseChestCount(sender, args[5]) ?: return
                val chestModel = resolveChestModel(args[6])
                runGeneratedHunt(sender, player.location, radius, chests, chestModel, args[7])
            }

            args.size == 10 -> {
                val x = parseCoordinate(sender, "x", args[3]) ?: return
                val y = parseCoordinate(sender, "y", args[4]) ?: return
                val z = parseCoordinate(sender, "z", args[5]) ?: return
                val radius = parseRadius(sender, args[6]) ?: return
                val chests = parseChestCount(sender, args[7]) ?: return
                val chestModel = resolveChestModel(args[8])
                val world = (sender as? Player)?.world ?: Bukkit.getWorlds().firstOrNull() ?: run {
                    sender.sendMessage(CommandConfig.huntGenerateNoWorld())
                    return
                }
                runGeneratedHunt(sender, Location(world, x, y, z), radius, chests, chestModel, args[9])
            }

            else -> sender.sendMessage(CommandConfig.huntGenerateNotEnoughArgs())
        }
    }

    private fun startCustomGenerateNamed(sender: CommandSender, tokens: List<String>) {
        if (tokens.firstOrNull()?.lowercase()?.let { it in HERE_TOKENS } == true) {
            val args = parseNamedArguments(
                sender,
                tokens.drop(1),
                setOf("radius", "chests", "chest", "loot", "replace"),
            ) ?: return
            if (!requireArguments(sender, args, listOf("radius", "chests", "chest", "loot"))) return
            val player = sender as? Player ?: run {
                sender.sendMessage(CommandConfig.huntGeneratePlayerOnly())
                return
            }
            val radius = parseRadius(sender, args.getValue("radius")) ?: return
            val chests = parseChestCount(sender, args.getValue("chests")) ?: return
            val replaceExisting = parseReplace(sender, args["replace"]) ?: return
            val chestModel = resolveChestModel(args.getValue("chest"))
            runGeneratedHunt(
                sender,
                player.location,
                radius,
                chests,
                chestModel,
                args.getValue("loot"),
                replaceExisting,
            )
            return
        }

        val args = parseNamedArguments(
            sender,
            tokens,
            setOf("world", "x", "y", "z", "radius", "chests", "chest", "loot", "replace"),
        ) ?: return
        if (!requireArguments(sender, args, listOf("world", "x", "y", "z", "radius", "chests", "chest", "loot"))) return
        val x = parseCoordinate(sender, "x", args.getValue("x")) ?: return
        val y = parseCoordinate(sender, "y", args.getValue("y")) ?: return
        val z = parseCoordinate(sender, "z", args.getValue("z")) ?: return
        val radius = parseRadius(sender, args.getValue("radius")) ?: return
        val chests = parseChestCount(sender, args.getValue("chests")) ?: return
        val replaceExisting = parseReplace(sender, args["replace"]) ?: return
        val chestModel = resolveChestModel(args.getValue("chest"))
        val world = Bukkit.getWorld(args.getValue("world")) ?: run {
            sender.sendMessage(CommandConfig.huntWorldNotFound())
            return
        }
        runGeneratedHunt(
            sender,
            Location(world, x, y, z),
            radius,
            chests,
            chestModel,
            args.getValue("loot"),
            replaceExisting,
        )
    }

    private fun parseNamedArguments(
        sender: CommandSender,
        tokens: List<String>,
        allowed: Set<String>,
    ): Map<String, String>? =
        when (val result = HuntNamedArguments.parse(tokens, allowed)) {
            is HuntNamedArgumentsResult.Parsed -> result.values
            is HuntNamedArgumentsResult.Invalid -> {
                sendArgumentError(sender, result.reason)
                null
            }
        }

    private fun requireArguments(
        sender: CommandSender,
        arguments: Map<String, String>,
        required: List<String>,
    ): Boolean {
        val missing = required.filterNot(arguments::containsKey)
        if (missing.isEmpty()) return true
        sendArgumentError(sender, "укажите обязательные аргументы: ${missing.joinToString(", ")}")
        return false
    }

    private fun sendArgumentError(sender: CommandSender, reason: String) {
        sender.sendMessage(CommandConfig.huntArgumentsError(reason))
    }

    private fun reportStartResult(sender: CommandSender, locationPool: LocationPool) {
        if (TreasureHuntManager.getByLocationPool(locationPool) != null) {
            sender.sendMessage(CommandConfig.huntStarted())
        } else {
            sender.sendMessage(CommandConfig.huntStartFailed())
        }
    }

    private fun parseChestCount(sender: CommandSender, raw: String): Int? =
        HuntNamedArguments.positiveChestCount(raw) ?: run {
            sendArgumentError(sender, "chests должно быть положительным целым числом")
            null
        }

    private fun parseReplace(sender: CommandSender, raw: String?): Boolean? {
        if (raw == null) return true
        return HuntNamedArguments.strictBoolean(raw) ?: run {
            sendArgumentError(sender, "replace должно быть true или false")
            null
        }
    }

    private fun skipIfAlreadyActive(
        sender: CommandSender,
        locationPool: LocationPool,
        replaceExisting: Boolean,
    ): Boolean {
        if (replaceExisting || TreasureHuntManager.getByLocationPool(locationPool) == null) return false
        sender.sendMessage(
            CommandConfig.get(
                "hunt.already-active",
                "<yellow>Охота в пуле <white>%location_pool%<yellow> уже активна; " +
                    "запуск пропущен.",
                "%location_pool%",
                locationPool.id,
            ),
        )
        return true
    }

    private fun parseRadius(sender: CommandSender, raw: String): Double? {
        val radius = HuntNamedArguments.finiteNumber(raw)?.takeIf { it > 0 }
        if (radius == null) sendArgumentError(sender, "radius должен быть конечным числом больше нуля")
        return radius
    }

    private fun parseCoordinate(sender: CommandSender, key: String, raw: String): Double? {
        val coordinate = HuntNamedArguments.finiteNumber(raw)
        if (coordinate == null) sendArgumentError(sender, "$key должен быть конечным числом")
        return coordinate
    }

    private fun runGeneratedHunt(
        sender: CommandSender,
        center: Location,
        radius: Double,
        chests: Int,
        chestModel: String,
        treasurePoolId: String,
        replaceExisting: Boolean = true,
    ) {
        val hunt =
            TreasureHuntManager.startGeneratedHunt(
                center,
                radius,
                chests,
                chestModel,
                treasurePoolId,
                sender,
                replaceExisting,
            )
        if (hunt != null) {
            sender.sendMessage(CommandConfig.huntStarted())
        }
    }

    private fun resolveChestModel(raw: String): String = TreasureHuntRegistry.getAliases()[raw] ?: raw

    private fun handleStop(
        sender: CommandSender,
        args: Array<String>,
    ) {
        val locationPoolId = if (args.drop(1).any { '=' in it }) {
            val named = parseNamedArguments(sender, args.drop(1), setOf("pool")) ?: return
            named["pool"] ?: run {
                sendArgumentError(sender, "укажите обязательный аргумент: pool")
                return
            }
        } else {
            if (args.size > 2 || args.getOrNull(1)?.isBlank() == true) {
                sendArgumentError(sender, "используйте /arc hunt stop pool=<id>")
                return
            }
            args.getOrNull(1) ?: run {
                sender.sendMessage(CommandConfig.huntSpecifyLocationPool())
                return
            }
        }

        val locationPool =
            LocationPoolManager.getPool(locationPoolId) ?: run {
                sender.sendMessage(CommandConfig.huntLocationPoolNotFound(locationPoolId))
                return
            }

        val hunt = TreasureHuntManager.getByLocationPool(locationPool)
        if (hunt == null) {
            sender.sendMessage(CommandConfig.huntNotFound())
            return
        }

        TreasureHuntManager.stopHunt(hunt)
        sender.sendMessage(CommandConfig.huntStopped())
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? = HuntSubCommandTabComplete.complete(sender, args)
}
