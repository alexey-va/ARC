package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.common.treasure.TreasurePool
import ru.arc.common.treasure.gui.MainTreasuresGui
import ru.arc.common.treasure.gui.PoolGui
import ru.arc.common.treasure.impl.SubPoolTreasure
import ru.arc.common.treasure.impl.TreasureItem
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
    override val defaultDescription = "Управление пулами наград"
    override val defaultUsage = "/arc treasures [list|reload|<pool_id>]"

    private val actions = listOf("add", "addhand", "addchest", "addsubpool", "give", "info")

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        // /arc treasures - открыть GUI или показать список
        if (args.isEmpty()) {
            val player = sender.player
            if (player != null) {
                GuiUtils.constructAndShowAsync({ MainTreasuresGui(player) }, player)
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
                TreasurePool.loadAllTreasures()
                sender.sendMessage(CommandConfig.treasuresReloaded())
                return true
            }
        }

        val poolId = args[0]

        // /arc treasures <pool_id> - открыть GUI или показать info
        if (args.size == 1) {
            val pool = TreasurePool.getTreasurePool(poolId)
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
                GuiUtils.constructAndShowAsync({ PoolGui(player, pool) }, player)
            } else {
                showPoolInfo(sender, pool, poolId)
            }
            return true
        }

        // Получаем или создаём пул
        val pool = TreasurePool.getTreasurePool(poolId) ?: run {
            sender.sendMessage(CommandConfig.treasuresPoolCreating(poolId))
            TreasurePool.getOrCreate(poolId)
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
        val pools = TreasurePool.getTreasurePools()

        if (pools.isEmpty()) {
            sender.sendMessage(CommandConfig.get("treasures.no-pools", "<gray>Нет пулов наград"))
            return
        }

        sender.sendMessage(
            CommandConfig.get(
                "treasures.list-header",
                "<gold>Пулы наград (%count%):",
                "%count%",
                pools.size.toString()
            )
        )
        pools.sortedBy { it.getId() }.forEach { pool ->
            sender.sendMessage(
                CommandConfig.get(
                    "treasures.list-item",
                    "<gray>• <white>%pool_id% <gray>(%size% предметов)",
                    "%pool_id%", pool.getId(),
                    "%size%", pool.size().toString()
                )
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
                pool.size().toString()
            )
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

        val treasure = TreasureItem.create(item, quantity, quantity, null).apply {
            this.weight = weight
        }

        if (pool.add(treasure)) {
            player.sendMessage(CommandConfig.treasuresItemAdded(poolId, item.type.name))
        } else {
            player.sendMessage(CommandConfig.treasuresItemAlreadyAdded(poolId, item.type.name))
        }
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
            val treasure = TreasureItem.create(item, item.amount, item.amount, null).apply {
                this.weight = weight
            }
            if (pool.add(treasure)) {
                added++
            }
        }

        player.sendMessage(CommandConfig.treasuresItemsAdded(poolId, added))
    }

    private fun addSubpool(sender: CommandSender, pool: TreasurePool, poolId: String, args: Array<String>) {
        if (args.size < 3) {
            sender.sendMessage(CommandConfig.usage("/arc treasures $poolId addsubpool <subpool_id>"))
            return
        }

        val subPoolId = args[2]
        if (TreasurePool.getTreasurePool(subPoolId) == null) {
            sender.sendMessage(CommandConfig.treasuresPoolNotFound(subPoolId))
            return
        }

        val flags = parseFlagsAsInt(args)
        val weight: Int = flags["weight"] ?: 1

        val treasure = SubPoolTreasure.create(subPoolId).apply {
            this.weight = weight
        }

        if (pool.add(treasure)) {
            sender.sendMessage(CommandConfig.treasuresSubpoolAdded(poolId, subPoolId))
        } else {
            sender.sendMessage(CommandConfig.treasuresSubpoolAlreadyAdded(poolId, subPoolId))
        }
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

        if (pool.size() == 0) {
            sender.sendMessage(CommandConfig.treasuresPoolEmpty(poolId))
            return
        }

        pool.random()?.give(target)
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

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        val pools = TreasurePool.getTreasurePools().map { it.getId() }

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
