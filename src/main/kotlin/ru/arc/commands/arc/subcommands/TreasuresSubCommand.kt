package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import ru.arc.treasure.core.gui.MainTreasuresGui
import ru.arc.treasure.core.gui.PoolGui
import ru.arc.util.GuiUtils
import ru.arc.util.ItemUtils

/**
 * /arc treasures - управление пулами наград.
 *
 * Действия:
 * - (без аргументов) - открыть GUI / показать список
 * - list - список пулов с размерами
 * - reload - перезагрузить пулы
 * - <pool_id> - открыть/создать пул
 * - <pool_id> add [-weight:N] - добавить из руки (короткий алиас)
 * - <pool_id> addhand [-weight:N] [-quantity:N] - добавить из руки
 * - <pool_id> addchest [-weight:N] - добавить из сундука
 * - <pool_id> addsubpool <subpool_id> [-weight:N] - вложенный пул
 * - <pool_id> give [player] - выдать награду
 * - <pool_id> info - информация о пуле
 */
object TreasuresSubCommand : SubCommand {

    override val configKey = "treasures"
    override val defaultName = "treasures"
    override val defaultPermission = "arc.treasures.admin"
    override val defaultDescription = "Управление пулами наград: просмотр, создание, добавление предметов, выдача"
    override val defaultUsage = "/arc treasures [list|reload|<pool> [add|addhand|addchest|addsubpool <subpool>|give [player]|info] [-weight:N] [-quantity:N]]"

    private val actions = listOf("add", "addhand", "addchest", "addsubpool", "give", "info")

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        // /arc treasures - открыть GUI или показать список
        if (args.isEmpty()) {
            val player = sender.player
            if (player != null) {
                GuiUtils.constructAndShowAsync({ MainTreasuresGui.create(player) }, player)
            } else {
                showList(sender)
            }
            return true
        }

        val firstArg = args[0].lowercase()

        when (firstArg) {
            "list" -> {
                showList(sender)
                return true
            }

            "reload" -> {
                Treasures.reload()
                sender.sendMessage(CommandConfig.treasuresReloaded())
                return true
            }
        }

        val poolId = args[0]

        // /arc treasures <pool_id> - открыть GUI или показать info
        if (args.size == 1) {
            val pool = Treasures.getPool(poolId)
            if (pool == null) {
                sender.sendMessage(CommandConfig.treasuresPoolNotFound(poolId))
                sender.sendMessage(
                    CommandConfig.get(
                        "treasures.create-hint",
                        "<gray>Создать: <white>/arc treasures %pool_id% add", "%pool_id%", poolId
                    )
                )
                return true
            }
            val player = sender.player
            if (player != null) {
                GuiUtils.constructAndShowAsync({ PoolGui.create(player, pool) }, player)
            } else {
                showPoolInfo(sender, pool, poolId)
            }
            return true
        }

        // Получаем или создаём пул
        val pool =
            Treasures.getPool(poolId) ?: run {
                sender.sendMessage(CommandConfig.treasuresPoolCreating(poolId))
                Treasures.getOrCreate(poolId)
            }

        // Обрабатываем действие
        when (args[1].lowercase()) {
            "add", "addhand" -> addFromHand(sender, pool, poolId, args)
            "addchest" -> addFromChest(sender, pool, poolId, args)
            "addsubpool" -> addSubpool(sender, pool, poolId, args)
            "give" -> giveReward(sender, pool, poolId, args)
            "info" -> showPoolInfo(sender, pool, poolId)
            else -> sendUnknownAction(sender, args[1])
        }
        return true
    }

    private fun showList(sender: CommandSender) {
        val pools = Treasures.getAllPools()

        if (pools.isEmpty()) {
            sender.sendMessage(CommandConfig.get("treasures.no-pools", "<gray>Нет пулов наград"))
            return
        }

        sender.sendMessage(
            CommandConfig.get(
                "treasures.list-header",
                "<gold>Пулы наград (%count%):",
                "%count%",
                pools.size.toString(),
            ),
        )
        pools.sortedBy { it.id }.forEach { pool ->
            sender.sendMessage(
                CommandConfig.get(
                    "treasures.list-item",
                    "<gray>• <white>%pool_id% <gray>(%size% предметов)",
                    "%pool_id%",
                    pool.id,
                    "%size%",
                    pool.size.toString(),
                ),
            )
        }
    }

    private fun showPoolInfo(sender: CommandSender, pool: TreasurePool, poolId: String) {
        sender.sendMessage(
            CommandConfig.get(
                "treasures.info-header",
                "<gold>Пул: <white>%pool_id%",
                "%pool_id%",
                poolId
            )
        )
        sender.sendMessage(
            CommandConfig.get(
                "treasures.info-size",
                "<gray>Предметов: <white>%size%",
                "%size%",
                pool.size.toString(),
            ),
        )
        sender.sendMessage(
            CommandConfig.get(
                "treasures.info-actions",
                "<gray>Действия: <white>add, give, addchest, addsubpool"
            )
        )
    }

    private fun addFromHand(sender: CommandSender, pool: TreasurePool, poolId: String, args: Array<String>) {
        val player = requirePlayer(sender) ?: return

        val item = player.inventory.itemInMainHand.clone()
        if (item.type.isAir) {
            player.sendMessage(CommandConfig.treasuresNoItemInHand())
            return
        }

        val flags = parseFlagsAsInt(args)
        val quantity: Int = flags["quantity"] ?: item.amount
        val weight: Int = flags["weight"] ?: 1

        val treasure =
            Treasure.Item(
                stack = item,
                min = quantity,
                max = quantity,
                weight = weight,
            )

        Treasures.addTreasure(poolId, treasure)
        player.sendMessage(CommandConfig.treasuresItemAdded(poolId, item.type.name))
    }

    private fun addFromChest(sender: CommandSender, pool: TreasurePool, poolId: String, args: Array<String>) {
        val player = requirePlayer(sender) ?: return

        val block = player.getTargetBlockExact(5)
        if (block == null) {
            player.sendMessage(CommandConfig.treasuresNoTargetBlock())
            return
        }

        val flags = parseFlagsAsInt(args)
        val weight: Int = flags["weight"] ?: 1

        val items = ItemUtils.connectedChests(block)
            .flatMap { ItemUtils.extractItems(it) }
            .filterNotNull()
            .filter { !it.type.isAir }
            .map { it.clone() }

        var added = 0
        for (item in items) {
            val treasure =
                Treasure.Item(
                    stack = item,
                    min = item.amount,
                    max = item.amount,
                    weight = weight,
                )
            Treasures.addTreasure(poolId, treasure)
            added++
        }

        player.sendMessage(CommandConfig.treasuresItemsAdded(poolId, added))
    }

    private fun addSubpool(sender: CommandSender, pool: TreasurePool, poolId: String, args: Array<String>) {
        if (args.size < 3) {
            sender.sendMessage(CommandConfig.usage("/arc treasures $poolId addsubpool <subpool_id>"))
            return
        }

        val subPoolId = args[2]
        if (Treasures.getPool(subPoolId) == null) {
            sender.sendMessage(CommandConfig.treasuresPoolNotFound(subPoolId))
            return
        }

        val flags = parseFlagsAsInt(args)
        val weight: Int = flags["weight"] ?: 1

        val treasure =
            Treasure.SubPool(
                poolId = subPoolId,
                weight = weight,
            )

        Treasures.addTreasure(poolId, treasure)
        sender.sendMessage(CommandConfig.treasuresSubpoolAdded(poolId, subPoolId))
    }

    private fun giveReward(sender: CommandSender, pool: TreasurePool, poolId: String, args: Array<String>) {
        val targetName = args.getOrNull(2)
        val target: Player? = when {
            targetName != null -> Bukkit.getPlayerExact(targetName)
            sender is Player -> sender
            else -> null
        }

        if (target == null) {
            sender.sendMessage(CommandConfig.playerNotFound(targetName ?: "неизвестен"))
            return
        }

        if (pool.isEmpty()) {
            sender.sendMessage(CommandConfig.treasuresPoolEmpty(poolId))
            return
        }

        Treasures.service.giveFromPool(poolId, target)
        sender.sendMessage(CommandConfig.treasuresGiven(target.name))
    }

    /**
     * Parses -key:value flags from args and converts to Int.
     */
    private fun parseFlagsAsInt(args: Array<String>): Map<String, Int> {
        return parseFlags(args).mapNotNull { (key, value) ->
            value.toIntOrNull()?.let { key to it }
        }.toMap()
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? {
        val pools = Treasures.getAllPools().map { it.id }

        return when (args.size) {
            1 -> (listOf("list", "reload") + pools).tabComplete(args[0])
            2 -> actions.tabComplete(args[1])
            3 -> when (args[1].lowercase()) {
                "addsubpool" -> pools.filter { !it.equals(args[0], ignoreCase = true) }.tabComplete(args[2])
                "give" -> tabCompletePlayers(args[2])
                else -> null
            }

            else -> suggestFlags(args)
        }
    }

    private fun suggestFlags(args: Array<String>): List<String> {
        val existing = args.toSet()
        val action = args.getOrNull(1)?.lowercase()
        val suggestions = mutableListOf<String>()

        if (action in listOf("add", "addhand", "addchest", "addsubpool")) {
            if (existing.none { it.startsWith("-weight:") }) {
                suggestions.add("-weight:1")
            }
        }
        if (action in listOf("add", "addhand") && existing.none { it.startsWith("-quantity:") }) {
            suggestions.add("-quantity:1")
        }

        return suggestions.tabComplete(args.last())
    }
}
