package ru.arc.commands.arc.subcommands

import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.commands.arc.tabComplete
import ru.arc.common.locationpools.LocationPoolManager

/**
 * /arc locpool - управление пулами локаций.
 *
 * Использование:
 * - (без аргументов) - показать статус и список
 * - list - показать все пулы
 * - <pool_id> - начать/остановить редактирование
 * - delete <pool_id> - удалить пул
 */
object LocpoolSubCommand : SubCommand {

    override val configKey = "locpool"
    override val defaultName = "locpool"
    override val defaultPermission = "arc.locpool.admin"
    override val defaultDescription = "Управление пулами локаций"
    override val defaultUsage = "/arc locpool [list|delete|<pool_id>]"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true
        val currentPool = LocationPoolManager.getEditing(player.uniqueId)

        // /arc locpool - показать статус
        if (args.isEmpty()) {
            showStatus(player, currentPool)
            return true
        }

        when (args[0].lowercase()) {
            "list" -> showList(player)
            "delete" -> {
                val poolId = args.getOrNull(1)
                if (poolId == null) {
                    player.sendMessage(CommandConfig.locpoolSpecifyPool())
                } else {
                    deletePool(player, poolId)
                }
            }

            "edit" -> {
                // Для обратной совместимости
                val poolId = args.getOrNull(1)
                if (poolId == null) {
                    if (currentPool != null) {
                        stopEditing(player, currentPool)
                    } else {
                        player.sendMessage(CommandConfig.locpoolNotEditing())
                    }
                } else {
                    toggleEditing(player, poolId, currentPool)
                }
            }

            else -> {
                // /arc locpool <pool_id> - начать/остановить редактирование
                toggleEditing(player, args[0], currentPool)
            }
        }
        return true
    }

    private fun showStatus(player: Player, currentPool: String?) {
        val pools = LocationPoolManager.getAll()

        player.sendMessage(CommandConfig.get("locpool.status-header", "<gold>═══ Пулы локаций ═══"))

        if (currentPool != null) {
            val pool = LocationPoolManager.getPool(currentPool)
            val size = pool?.locations?.size() ?: 0
            player.sendMessage(
                CommandConfig.get(
                    "locpool.currently-editing",
                    "<green>Редактируешь: <white>%pool_id% <gray>(%size% локаций)",
                    "%pool_id%", currentPool,
                    "%size%", size.toString()
                )
            )
            player.sendMessage(
                CommandConfig.get(
                    "locpool.stop-hint",
                    "<gray>Для остановки: <white>/arc locpool %pool_id%",
                    "%pool_id%",
                    currentPool
                )
            )
        } else {
            player.sendMessage(CommandConfig.get("locpool.not-editing-status", "<gray>Сейчас ничего не редактируешь"))
        }

        player.sendMessage(
            CommandConfig.get(
                "locpool.pools-count",
                "<gray>Всего пулов: <white>%count%",
                "%count%",
                pools.size.toString()
            )
        )
        player.sendMessage(CommandConfig.get("locpool.help", "<gray>Команды: <white>list, delete, <pool_id>"))
    }

    private fun showList(player: Player) {
        val pools = LocationPoolManager.getAll()

        if (pools.isEmpty()) {
            player.sendMessage(CommandConfig.get("locpool.no-pools", "<gray>Нет пулов локаций"))
            return
        }

        player.sendMessage(CommandConfig.get("locpool.list-header", "<gold>Пулы локаций:"))
        pools.sortedBy { it.id }.forEach { pool ->
            player.sendMessage(
                CommandConfig.get(
                    "locpool.list-item",
                    "<gray>• <white>%pool_id% <gray>(%size% локаций)",
                    "%pool_id%", pool.id,
                    "%size%", pool.locations.size().toString()
                )
            )
        }
    }

    private fun toggleEditing(player: Player, poolId: String, currentPool: String?) {
        when {
            poolId == currentPool -> stopEditing(player, currentPool)
            currentPool != null -> {
                // Сначала остановить текущее, потом начать новое
                LocationPoolManager.cancelEditing(player.uniqueId, false)
                player.sendMessage(CommandConfig.locpoolEditingCancelled(currentPool))
                startEditing(player, poolId)
            }

            else -> startEditing(player, poolId)
        }
    }

    private fun startEditing(player: Player, poolId: String) {
        LocationPoolManager.setEditing(player.uniqueId, poolId)
        player.inventory.addItem(
            ItemStack.of(Material.GOLD_BLOCK),
            ItemStack.of(Material.REDSTONE_BLOCK)
        )
        player.sendMessage(CommandConfig.locpoolEditingStarted(poolId))
    }

    private fun stopEditing(player: Player, poolId: String) {
        LocationPoolManager.cancelEditing(player.uniqueId, false)
        player.sendMessage(CommandConfig.locpoolEditingCancelled(poolId))
    }

    private fun deletePool(player: Player, poolId: String) {
        if (LocationPoolManager.delete(poolId)) {
            player.sendMessage(CommandConfig.locpoolDeleted(poolId))
        } else {
            player.sendMessage(CommandConfig.locpoolNotFound(poolId))
        }
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        val pools = LocationPoolManager.getAll().map { it.id }
        val player = sender.player
        val currentPool = player?.let { LocationPoolManager.getEditing(it.uniqueId) }

        return when (args.size) {
            1 -> {
                // Собираем предложения с приоритетом
                val prioritized = mutableListOf<String>()

                // Текущий редактируемый пул - самый высокий приоритет
                if (currentPool != null) {
                    prioritized.add(currentPool)
                }

                // Команды
                prioritized.addAll(listOf("list", "delete"))

                // Остальные пулы
                pools.filter { it != currentPool }.sorted().forEach { prioritized.add(it) }

                prioritized.tabComplete(args[0])
            }

            2 -> when (args[0].lowercase()) {
                "delete" -> pools.tabComplete(args[1])
                else -> null
            }

            else -> null
        }
    }
}
