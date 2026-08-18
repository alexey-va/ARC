package ru.arc.commands.arc.subcommands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.worldcontent.CleanupConfirmationRegistry
import ru.arc.worldcontent.CleanupConfirmationResult
import ru.arc.worldcontent.CleanupScan
import ru.arc.worldcontent.CleanupTarget
import ru.arc.worldcontent.FurnitureCleanupInput
import ru.arc.worldcontent.FurnitureCleanupService

object FurnitureSubCommand : SubCommand {
    override val configKey = "furniture"
    override val defaultPermission = "arc.furniture.admin"
    override val defaultDescription = "Безопасная очистка ItemsAdder-мебели и оставшихся barrier hitbox"
    override val defaultUsage = "/arc furniture cleanup <1-24> [confirm <token>]"
    override val defaultPlayerOnly = true

    private val confirmations = CleanupConfirmationRegistry()

    override fun isAvailable(): Boolean = Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        val player = requirePlayer(sender) ?: return true
        val input =
            try {
                FurnitureCleanupInput.parse(args)
            } catch (e: IllegalArgumentException) {
                player.sendMessage(Component.text(e.message ?: "Неверные аргументы", NamedTextColor.RED))
                sendUsage(player)
                return true
            }

        val scan =
            try {
                FurnitureCleanupService.scan(player.location, input.radius)
            } catch (e: IllegalStateException) {
                player.sendMessage(Component.text(e.message ?: "ItemsAdder недоступен", NamedTextColor.RED))
                return true
            }
        if (scan.skippedUnloadedChunks > 0) {
            player.sendMessage(
                Component.text(
                    "Отмена: в радиусе есть незагруженные чанки (${scan.skippedUnloadedChunks}). Уменьши радиус или загрузи область.",
                    NamedTextColor.RED,
                ),
            )
            return true
        }

        return when (input) {
            is FurnitureCleanupInput.Preview -> preview(player, scan)
            is FurnitureCleanupInput.Confirm -> confirm(player, input, scan)
        }
    }

    private fun preview(
        player: Player,
        scan: CleanupScan,
    ): Boolean {
        val plan = scan.plan
        if (plan.targets.isEmpty()) {
            player.sendMessage(Component.text("В радиусе ничего подходящего не найдено.", NamedTextColor.GRAY))
            return true
        }
        val confirmation = confirmations.issue(player.uniqueId, plan.center, plan.radius, plan.digest)
        player.sendMessage(Component.text("Предпросмотр очистки:", NamedTextColor.GOLD))
        player.sendMessage(
            Component.text(
                "ItemsAdder-мебель: ${plan.furnitureCount}, barrier-блоки: ${plan.barrierCount}.",
                NamedTextColor.YELLOW,
            ),
        )
        val sample =
            plan.targets.take(5).joinToString { target ->
                when (target) {
                    is CleanupTarget.Furniture -> target.namespacedId ?: target.rootUuid.toString()
                    is CleanupTarget.Barrier ->
                        "barrier ${target.position.x} ${target.position.y} ${target.position.z}"
                }
            }
        if (sample.isNotEmpty()) {
            player.sendMessage(Component.text("Первые цели: $sample", NamedTextColor.GRAY))
        }
        player.sendMessage(
            Component.text(
                "Для удаления в течение 30 секунд: /arc furniture cleanup ${plan.radius} confirm ${confirmation.token}",
                NamedTextColor.RED,
            ),
        )
        return true
    }

    private fun confirm(
        player: Player,
        input: FurnitureCleanupInput.Confirm,
        scan: CleanupScan,
    ): Boolean {
        val plan = scan.plan
        return when (
            val result =
                confirmations.consume(
                    player.uniqueId,
                    plan.center,
                    input.radius,
                    plan.digest,
                    input.token,
                )
        ) {
            CleanupConfirmationResult.Accepted -> {
                val execution = FurnitureCleanupService.execute(plan)
                val color = if (execution.failedFurniture.isEmpty()) NamedTextColor.GREEN else NamedTextColor.YELLOW
                player.sendMessage(
                    Component.text(
                        "Удалено: мебель ${execution.removedFurniture}, barrier ${execution.removedBarriers}; ошибок мебели ${execution.failedFurniture.size}.",
                        color,
                    ),
                )
                true
            }

            is CleanupConfirmationResult.Rejected -> {
                player.sendMessage(
                    Component.text(
                        "Очистка отменена (${result.reason}). Сделай новый предпросмотр.",
                        NamedTextColor.RED,
                    ),
                )
                true
            }
        }
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? =
        when (args.size) {
            1 -> listOf("cleanup").tabComplete(args[0])
            2 -> if (args[0].equals("cleanup", true)) listOf("4", "8", "12", "16", "24").tabComplete(args[1]) else emptyList()
            3 -> listOf("confirm").tabComplete(args[2])
            else -> emptyList()
        }
}
