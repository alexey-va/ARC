package ru.arc.commands.arc.subcommands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.worldcontent.CleanupConfirmationRegistry
import ru.arc.worldcontent.CleanupConfirmationResult
import ru.arc.worldcontent.CleanupScan
import ru.arc.worldcontent.CleanupTarget
import ru.arc.worldcontent.FurnitureCleanupInput
import ru.arc.worldcontent.FurnitureCleanupAtInput
import ru.arc.worldcontent.FurnitureCleanupService
import java.util.UUID

object FurnitureSubCommand : SubCommand {
    override val configKey = "furniture"
    override val defaultPermission = "arc.furniture.admin"
    override val defaultDescription = "Безопасная очистка ItemsAdder-мебели и оставшихся barrier hitbox"
    override val defaultUsage = "/arc furniture cleanup <1-24> [confirm <token>]"
    override val defaultPlayerOnly = true

    private val confirmations = CleanupConfirmationRegistry()
    private val consoleOwner = UUID.fromString("00000000-0000-0000-0000-00000000c0de")
    private const val MAX_CONSOLE_ROOTS = 16

    override fun isAvailable(): Boolean = Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        if (allowsConsole(args)) return executeConsole(sender, args)
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

    /** Only the explicit coordinate action may pass ArcCommand's player-only gate. */
    internal fun allowsConsole(args: Array<String>): Boolean =
        args.firstOrNull()?.equals("cleanup-at", ignoreCase = true) == true

    internal fun isLocalConsoleSender(sender: CommandSender): Boolean =
        sender is ConsoleCommandSender && sender !is RemoteConsoleCommandSender

    private fun executeConsole(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        if (!isLocalConsoleSender(sender)) {
            sender.sendMessage(Component.text("cleanup-at доступна только локальной серверной консоли.", NamedTextColor.RED))
            return true
        }
        val input =
            try {
                FurnitureCleanupAtInput.parse(args)
            } catch (e: IllegalArgumentException) {
                sender.sendMessage(Component.text(e.message ?: "Неверные аргументы", NamedTextColor.RED))
                sender.sendMessage(
                    Component.text(
                        "Использование: /arc furniture cleanup-at <world> <x> <y> <z> <radius> [confirm <token>]",
                        NamedTextColor.GRAY,
                    ),
                )
                return true
            }
        val world = Bukkit.getWorld(input.worldName)
        if (world == null) {
            sender.sendMessage(Component.text("Мир не загружен: ${input.worldName}", NamedTextColor.RED))
            return true
        }
        val scan =
            try {
                FurnitureCleanupService.scan(Location(world, input.x, input.y, input.z), input.radius)
            } catch (e: IllegalStateException) {
                sender.sendMessage(Component.text(e.message ?: "ItemsAdder недоступен", NamedTextColor.RED))
                return true
            } catch (e: IllegalArgumentException) {
                sender.sendMessage(Component.text(e.message ?: "Неверная координата", NamedTextColor.RED))
                return true
            }
        if (scan.skippedUnloadedChunks > 0) {
            sender.sendMessage(
                Component.text(
                    "Отмена: в радиусе есть незагруженные чанки (${scan.skippedUnloadedChunks}). Команда не загружает миры или чанки.",
                    NamedTextColor.RED,
                ),
            )
            return true
        }
        val plan = scan.plan
        if (plan.targets.isEmpty()) {
            sender.sendMessage(Component.text("В указанном радиусе ничего подходящего не найдено; подтверждение не создано.", NamedTextColor.GRAY))
            return true
        }
        if (plan.furnitureCount > MAX_CONSOLE_ROOTS) {
            sender.sendMessage(
                Component.text(
                    "Отмена: найдено слишком много корней мебели (${plan.furnitureCount}); уменьши радиус до 16 корней или меньше.",
                    NamedTextColor.RED,
                ),
            )
            return true
        }
        return when (input) {
            is FurnitureCleanupAtInput.Preview -> consolePreview(sender, input, plan)
            is FurnitureCleanupAtInput.Confirm -> consoleConfirm(sender, input, plan)
        }
    }

    private fun consolePreview(
        sender: CommandSender,
        input: FurnitureCleanupAtInput.Preview,
        plan: ru.arc.worldcontent.FurnitureCleanupPlan,
    ): Boolean {
        sender.sendMessage(Component.text("Предпросмотр cleanup-at: мебель ${plan.furnitureCount}, barrier ${plan.barrierCount}.", NamedTextColor.GOLD))
        plan.targets.forEach { target ->
            when (target) {
                is CleanupTarget.Furniture ->
                    sender.sendMessage(
                        Component.text(
                            "root ${target.rootUuid} ${target.namespacedId ?: "unknown"}",
                            NamedTextColor.GRAY,
                        ),
                    )
                is CleanupTarget.Barrier ->
                    sender.sendMessage(
                        Component.text(
                            "barrier ${target.position.world} ${target.position.x} ${target.position.y} ${target.position.z}",
                            NamedTextColor.GRAY,
                        ),
                    )
            }
        }
        val confirmation = confirmations.issue(consoleOwner, plan.center, plan.radius, plan.digest)
        sender.sendMessage(
            Component.text(
                "Для удаления в течение 30 секунд: /arc furniture cleanup-at ${input.worldName} ${input.x} ${input.y} ${input.z} ${input.radius} confirm ${confirmation.token}",
                NamedTextColor.RED,
            ),
        )
        return true
    }

    private fun consoleConfirm(
        sender: CommandSender,
        input: FurnitureCleanupAtInput.Confirm,
        plan: ru.arc.worldcontent.FurnitureCleanupPlan,
    ): Boolean {
        when (
            val result = confirmations.consume(consoleOwner, plan.center, input.radius, plan.digest, input.token)
        ) {
            CleanupConfirmationResult.Accepted -> {
                val execution = FurnitureCleanupService.execute(plan)
                val color = if (execution.failedFurniture.isEmpty()) NamedTextColor.GREEN else NamedTextColor.YELLOW
                sender.sendMessage(
                    Component.text(
                        "Удалено: мебель ${execution.removedFurniture}, barrier ${execution.removedBarriers}; ошибок мебели ${execution.failedFurniture.size}.",
                        color,
                    ),
                )
            }
            is CleanupConfirmationResult.Rejected ->
                sender.sendMessage(
                    Component.text(
                        "Очистка отменена (${result.reason}). Сделай новый предпросмотр.",
                        NamedTextColor.RED,
                    ),
                )
        }
        return true
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
            1 -> listOf("cleanup", "cleanup-at").tabComplete(args[0])
            2 -> when {
                args[0].equals("cleanup", true) -> listOf("4", "8", "12", "16", "24").tabComplete(args[1])
                args[0].equals("cleanup-at", true) -> Bukkit.getWorlds().map { it.name }.tabComplete(args[1])
                else -> emptyList()
            }
            3 -> if (args[0].equals("cleanup", true)) listOf("confirm").tabComplete(args[2]) else emptyList()
            7 -> if (args[0].equals("cleanup-at", true)) listOf("confirm").tabComplete(args[6]) else emptyList()
            else -> emptyList()
        }
}
