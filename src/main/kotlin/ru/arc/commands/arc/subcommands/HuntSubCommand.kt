package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.hooks.HookRegistry
import ru.arc.treasurechests.ChestType
import ru.arc.treasurechests.ChestVariant
import ru.arc.treasurechests.TreasureHuntConfig
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
                    "используйте только key=value; позиционные значения удалены",
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

/** Named-only `/arc hunt` administration commands. */
object HuntSubCommand : SubCommand {
    private val startKeys =
        setOf(
            "preset",
            "locations",
            "chests",
            "chest",
            "rewards",
            "replace",
            "generate",
            "center",
            "world",
            "x",
            "y",
            "z",
            "radius",
        )
    private val generatedKeys = setOf("preset", "generate", "center", "world", "x", "y", "z", "radius", "chests", "chest", "rewards", "replace")
    private val geometryKeys = setOf("center", "world", "x", "y", "z", "radius")

    override val configKey = "hunt"
    override val defaultName = "hunt"
    override val defaultPermission = "arc.treasure.hunt.admin"
    override val defaultDescription = "Управление охотой на сокровища (запуск, остановка, статус)"
    override val defaultUsage =
        "/arc hunt [status|types|start preset=<id> chests=<count> ...|start preset=<id> generate=true ... chests=<count>|start locations=<pool> ...|start generate=true ...|stop locations=<active-pool>|stopall]"

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
                "types" -> if (args.size == 1) showTypes(sender) else rejectExtraArguments(sender, "types")
                "status" -> if (args.size == 1) showStatus(sender) else rejectExtraArguments(sender, "status")
                "start" -> handleStart(sender, args.drop(1))
                "stop" -> handleStop(sender, args.drop(1))
                "stopall" -> {
                    if (args.size != 1) rejectExtraArguments(sender, "stopall") else {
                        TreasureHuntManager.stopAll()
                        sender.sendMessage(CommandConfig.get("hunt.all-stopped", "<gray>Все охоты остановлены!"))
                    }
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
        val presets = TreasureHuntManager.getTreasureHuntTypes()

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
                presets.size.toString(),
            ),
        )
        sender.sendMessage(
            CommandConfig.get(
                "hunt.commands-hint",
                "<gray>Команды: <white>types, start preset=<id>, start locations=<pool> ..., " +
                    "start generate=true ..., stop locations=<active-pool>, stopall",
            ),
        )
    }

    private fun showTypes(sender: CommandSender) {
        val presetIds = TreasureHuntManager.getTreasureHuntTypes()
        sender.sendMessage(CommandConfig.get("hunt.types-header", "<gold>═══ Пресеты охот ═══"))

        if (presetIds.isEmpty()) {
            sender.sendMessage(CommandConfig.get("hunt.no-types", "<gray>Нет настроенных пресетов охот"))
        } else {
            presetIds.forEach { presetId ->
                val config = TreasureHuntManager.getTreasureHuntType(presetId) ?: return@forEach
                val poolSize = config.getLocationPool()?.size
                sender.sendMessage(CommandConfig.get("hunt.type-name", "<yellow>%type%", "%type%", presetId))
                sender.sendMessage(
                    CommandConfig.huntTypeLocation(
                        config.locationPoolId,
                        HuntTypesFormatter.locationPoolSizeSuffix(poolSize),
                    ),
                )
                sender.sendMessage(
                    CommandConfig.get(
                        "hunt.type-treasure",
                        "<gray>  rewards pools: <white>%treasure_pools%",
                        "%treasure_pools%",
                        HuntTypesFormatter.treasurePools(config),
                    ),
                )
                sender.sendMessage(
                    CommandConfig.get(
                        "hunt.type-chest",
                        "<gray>  appearances: <white>%chests%",
                        "%chests%",
                        HuntTypesFormatter.chestModels(config, TreasureHuntRegistry.getAliases()),
                    ),
                )
                sender.sendMessage(
                    CommandConfig.get(
                        "hunt.type-start",
                        "<gray>  → <white>/arc hunt start preset=%type% chests=<count>",
                        "%type%",
                        presetId,
                    ),
                )
            }
        }

        sender.sendMessage(CommandConfig.get("hunt.types-custom-header", "<gold>─── Своя охота ───"))
        sender.sendMessage(CommandConfig.get("hunt.custom-hint", CommandConfig.huntCustomHintDefault()))
        sender.sendMessage(CommandConfig.get("hunt.generate-hint", CommandConfig.huntGenerateHintDefault()))
        sender.sendMessage(CommandConfig.get("hunt.chest-hint", CommandConfig.huntChestHintDefault()))
        sender.sendMessage(
            CommandConfig.get(
                "hunt.types-stop-hint",
                "<gray>Стоп: <white>/arc hunt stop locations=<active_location_pool>",
            ),
        )
    }

    private fun handleStart(sender: CommandSender, tokens: List<String>) {
        if (tokens.isEmpty()) {
            sendArgumentError(sender, "укажите preset=<id>, locations=<pool> ... или generate=true ...; подробности: /arc hunt types")
            return
        }

        val arguments = parseNamedArguments(sender, tokens, startKeys) ?: return
        val replaceExisting = parseReplace(sender, arguments["replace"]) ?: return
        val generate = arguments["generate"]?.let {
            HuntNamedArguments.strictBoolean(it) ?: run {
                sendArgumentError(sender, "generate должно быть true; без генерации используйте locations=<pool>")
                return
            }
        }
        if (generate == false) {
            sendArgumentError(sender, "generate принимает только true; без генерации используйте locations=<pool>")
            return
        }
        if (generate == true && "locations" in arguments) {
            sendArgumentError(sender, "locations конфликтует с generate=true")
            return
        }

        when {
            "preset" in arguments -> {
                if (!allowOnly(sender, arguments, setOf("preset", "locations", "chests", "chest", "rewards", "replace", "generate", "center", "world", "x", "y", "z", "radius"))) return
                startPreset(sender, arguments, replaceExisting)
            }

            generate == true -> {
                if (!allowOnly(sender, arguments, generatedKeys)) return
                startGenerated(sender, arguments, replaceExisting)
            }

            else -> {
                if (generate == false) {
                    sendArgumentError(sender, "без пресета укажите locations=<pool> либо generate=true")
                    return
                }
                if (arguments.keys.any { it in geometryKeys }) {
                    sendArgumentError(sender, "центр и радиус допустимы только вместе с generate=true")
                    return
                }
                if (!allowOnly(sender, arguments, setOf("locations", "chests", "chest", "rewards", "replace"))) return
                startCustomPool(sender, arguments, replaceExisting)
            }
        }
    }

    private fun startPreset(
        sender: CommandSender,
        arguments: Map<String, String>,
        replaceExisting: Boolean,
    ) {
        if (!requireArguments(sender, arguments, listOf("preset", "chests"))) return
        val presetId = arguments.getValue("preset")
        val preset = TreasureHuntManager.getTreasureHuntType(presetId) ?: run {
            sender.sendMessage(CommandConfig.huntTypeNotFound())
            return
        }
        if (arguments["generate"] == "true") {
            val chestCount = parseChestCount(sender, arguments.getValue("chests")) ?: return
            val rewardOverride = arguments["rewards"]
            if (rewardOverride != null && !knownRewardPool(sender, rewardOverride)) return
            val chestOverride = arguments["chest"]?.let { chestAppearance(sender, it, preset) ?: return }
            val config = HuntTypesFormatter.withOverrides(preset, preset.locationPoolId, chestOverride, rewardOverride)
            if (!validateConfig(sender, config)) return
            startGenerated(sender, arguments, config, chestCount, replaceExisting)
            return
        }
        if (arguments.keys.any { it in geometryKeys }) {
            sendArgumentError(sender, "центр и радиус допустимы только вместе с generate=true")
            return
        }
        val locationPoolId = arguments["locations"] ?: preset.locationPoolId
        val locationPool = LocationPoolManager.getPool(locationPoolId) ?: run {
            sender.sendMessage(CommandConfig.huntLocationPoolNotFound(locationPoolId))
            return
        }
        if (locationPool.size == 0) {
            sender.sendMessage(CommandConfig.get("hunt.location-pool-empty", "<red>Пул локаций пуст: <white>%location_pool%", "%location_pool%", locationPoolId))
            return
        }

        val chestCount = parseChestCount(sender, arguments.getValue("chests")) ?: return
        val rewardOverride = arguments["rewards"]
        if (rewardOverride != null && !knownRewardPool(sender, rewardOverride)) return
        val chestOverride = arguments["chest"]?.let { chestAppearance(sender, it, preset) ?: return }
        val config = HuntTypesFormatter.withOverrides(preset, locationPoolId, chestOverride, rewardOverride)
        if (!validateConfig(sender, config)) return
        startOnPool(sender, locationPool, config, chestCount, replaceExisting)
    }

    private fun startCustomPool(
        sender: CommandSender,
        arguments: Map<String, String>,
        replaceExisting: Boolean,
    ) {
        val required = listOf("locations", "chests", "chest", "rewards")
        if (!requireArguments(sender, arguments, required)) return
        val chestCount = parseChestCount(sender, arguments["chests"] ?: return)
            ?: return
        val rewardPoolId = arguments.getValue("rewards")
        if (!knownRewardPool(sender, rewardPoolId)) return
        val chestType = chestAppearance(sender, arguments.getValue("chest"), null, rewardPoolId) ?: return
        val locationPoolId = arguments.getValue("locations")
        val locationPool = LocationPoolManager.getPool(locationPoolId) ?: run {
            sender.sendMessage(CommandConfig.huntLocationPoolNotFound(locationPoolId))
            return
        }
        if (locationPool.size == 0) {
            sender.sendMessage(CommandConfig.get("hunt.location-pool-empty", "<red>Пул локаций пуст: <white>%location_pool%", "%location_pool%", locationPoolId))
            return
        }

        val config = TreasureHuntConfig.simple("custom-${System.nanoTime()}", locationPoolId, chestType)
        if (!validateConfig(sender, config)) return
        startOnPool(sender, locationPool, config, chestCount, replaceExisting)
    }

    private fun startGenerated(
        sender: CommandSender,
        arguments: Map<String, String>,
        replaceExisting: Boolean,
    ) {
        if (!requireArguments(sender, arguments, listOf("generate", "radius", "chests", "chest", "rewards"))) return
        val chestCount = parseChestCount(sender, arguments.getValue("chests")) ?: return
        val rewardPoolId = arguments.getValue("rewards")
        if (!knownRewardPool(sender, rewardPoolId)) return
        val chestType = chestAppearance(sender, arguments.getValue("chest"), null, rewardPoolId) ?: return
        val config = TreasureHuntConfig.simple("generated-${System.nanoTime()}", "", chestType)
        if (!validateConfig(sender, config)) return
        startGenerated(sender, arguments, config, chestCount, replaceExisting)
    }

    private fun startGenerated(
        sender: CommandSender,
        arguments: Map<String, String>,
        config: TreasureHuntConfig,
        chestCount: Int,
        replaceExisting: Boolean,
    ) {
        if (!requireArguments(sender, arguments, listOf("generate", "radius"))) return
        val radius = parseRadius(sender, arguments.getValue("radius")) ?: return
        val center = parseCenter(sender, arguments) ?: return

        val hunt = TreasureHuntManager.startGeneratedHunt(center, radius, chestCount, config, sender, replaceExisting)
        if (hunt != null) sender.sendMessage(CommandConfig.huntStarted())
    }

    private fun parseCenter(sender: CommandSender, arguments: Map<String, String>): Location? {
        val center = arguments["center"]
        if (center != null) {
            if (!center.equals("here", ignoreCase = true)) {
                sendArgumentError(sender, "center принимает только значение here")
                return null
            }
            if (arguments.keys.any { it in setOf("world", "x", "y", "z") }) {
                sendArgumentError(sender, "center=here конфликтует с world, x, y и z")
                return null
            }
            val player = sender as? Player ?: run {
                sender.sendMessage(CommandConfig.huntGeneratePlayerOnly())
                return null
            }
            return player.location
        }

        if (!requireArguments(sender, arguments, listOf("world", "x", "y", "z"))) return null
        val world = Bukkit.getWorld(arguments.getValue("world")) ?: run {
            sender.sendMessage(CommandConfig.huntWorldNotFound())
            return null
        }
        val x = parseCoordinate(sender, "x", arguments.getValue("x")) ?: return null
        val y = parseCoordinate(sender, "y", arguments.getValue("y")) ?: return null
        val z = parseCoordinate(sender, "z", arguments.getValue("z")) ?: return null
        return Location(world, x, y, z)
    }

    private fun chestAppearance(
        sender: CommandSender,
        model: String,
        preset: TreasureHuntConfig?,
        rewardPoolId: String? = null,
    ): ChestType? {
        val modelReward = rewardPoolId ?: preset?.chestTypes?.values()?.firstOrNull()?.treasurePoolId.orEmpty()
        if (model == "vanilla") return ChestType.vanilla(modelReward)

        val namespaceId = TreasureHuntRegistry.getAliases()[model] ?: run {
            sender.sendMessage(
                CommandConfig.get(
                    "hunt.chest-model-not-found",
                    "<red>Неизвестный вид сундука. Доступны только настроенные aliases и vanilla; список: /arc hunt types",
                ),
            )
            return null
        }
        if (HookRegistry.itemsAdderHook == null) {
            sender.sendMessage(CommandConfig.get("hunt.chest-model-unavailable", "<red>Модели сундуков ItemsAdder сейчас недоступны."))
            return null
        }
        return ChestType.itemsAdder(namespaceId, modelReward)
    }

    private fun validateConfig(sender: CommandSender, config: TreasureHuntConfig): Boolean {
        val chestTypes = config.chestTypes.values()
        if (chestTypes.isEmpty()) {
            sender.sendMessage(CommandConfig.get("hunt.no-chest-types", "<red>У пресета не настроен ни один вид сундука."))
            return false
        }
        val rewardPoolIds = TreasureHuntManager.getTreasurePools().mapTo(mutableSetOf()) { it.id }
        for (chestType in chestTypes) {
            if (chestType.treasurePoolId !in rewardPoolIds) {
                sender.sendMessage(CommandConfig.huntRewardPoolNotFound(chestType.treasurePoolId))
                return false
            }
            if (chestType.type == ChestVariant.ITEMS_ADDER && HookRegistry.itemsAdderHook == null) {
                sender.sendMessage(CommandConfig.get("hunt.chest-model-unavailable", "<red>Модели сундуков ItemsAdder сейчас недоступны."))
                return false
            }
        }
        return true
    }

    private fun knownRewardPool(sender: CommandSender, poolId: String): Boolean {
        if (TreasureHuntManager.getTreasurePools().any { it.id == poolId }) return true
        sender.sendMessage(CommandConfig.huntRewardPoolNotFound(poolId))
        return false
    }

    private fun startOnPool(
        sender: CommandSender,
        locationPool: ru.arc.common.locationpools.LocationPool,
        config: TreasureHuntConfig,
        chestCount: Int,
        replaceExisting: Boolean,
    ) {
        if (skipIfAlreadyActive(sender, locationPool, replaceExisting)) return
        val hunt = TreasureHuntManager.startHunt(config, chestCount, sender, replaceExisting)
        if (hunt != null) sender.sendMessage(CommandConfig.huntStarted())
    }

    private fun handleStop(sender: CommandSender, tokens: List<String>) {
        if (tokens.isEmpty()) {
            sendArgumentError(sender, "укажите locations=<active_location_pool>")
            return
        }
        val arguments = parseNamedArguments(sender, tokens, setOf("locations")) ?: return
        if (!requireArguments(sender, arguments, listOf("locations"))) return
        val locationPoolId = arguments.getValue("locations")
        val locationPool = LocationPoolManager.getPool(locationPoolId) ?: run {
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

    private fun allowOnly(
        sender: CommandSender,
        arguments: Map<String, String>,
        allowed: Set<String>,
    ): Boolean {
        val irrelevant = arguments.keys - allowed
        if (irrelevant.isEmpty()) return true
        sendArgumentError(sender, "для выбранного режима неприменимы ключи: ${irrelevant.sorted().joinToString(", ")}")
        return false
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

    private fun rejectExtraArguments(sender: CommandSender, action: String) {
        sendArgumentError(sender, "$action не принимает аргументы; действия: status, types, start, stop, stopall")
    }

    private fun sendArgumentError(sender: CommandSender, reason: String) {
        sender.sendMessage(CommandConfig.huntArgumentsError(reason))
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

    private fun skipIfAlreadyActive(
        sender: CommandSender,
        locationPool: ru.arc.common.locationpools.LocationPool,
        replaceExisting: Boolean,
    ): Boolean {
        if (replaceExisting || TreasureHuntManager.getByLocationPool(locationPool) == null) return false
        sender.sendMessage(
            CommandConfig.get(
                "hunt.already-active",
                "<yellow>Охота в пуле <white>%location_pool%<yellow> уже активна; запуск пропущен.",
                "%location_pool%",
                locationPool.id,
            ),
        )
        return true
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String> = HuntSubCommandTabComplete.complete(sender, args)
}
