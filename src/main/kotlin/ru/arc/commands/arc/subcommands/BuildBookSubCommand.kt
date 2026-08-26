package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.BuildBookTransform
import ru.arc.autobuild.BuildingManager
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete

/** Administrative creation of a book backed by an existing schematic. */
object BuildBookSubCommand : SubCommand {
    override val configKey = "buildbook"
    override val defaultName = "buildbook"
    override val defaultPermission = "arc.build.book.give"
    override val defaultDescription = "Создать книгу строительства"
    override val defaultUsage = "/arc buildbook <building> <model-id> [rotation] [y-offset] [name...]"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true
        if (args.size < 2) {
            sendUsage(sender)
            return true
        }

        val building = BuildingManager.getBuilding(args[0])
        if (building == null) {
            sender.sendMessage(
                CommandConfig.get(
                    "buildbook.not-found",
                    "<red>Строение <white>%name%<red> не найдено!",
                    "%name%",
                    args[0],
                ),
            )
            return true
        }

        val modelId = args[1].toIntOrNull()?.takeIf { it >= 0 }
        if (modelId == null) {
            sender.sendMessage(
                CommandConfig.get(
                    "buildbook.invalid-model",
                    "<red>Неверный model-id: <white>%value%",
                    "%value%",
                    args[1],
                ),
            )
            return true
        }

        val transform = BuildBookTransform.parseLegacy(args.getOrNull(2), args.getOrNull(3))
        if (transform == null) {
            sender.sendMessage(CommandConfig.get("buildbook.invalid-transform", "<red>Поворот или смещение книги вне допустимого диапазона."))
            return true
        }
        val title = args.drop(4).joinToString(" ").trim().ifEmpty { building.fileName }
        if (title.length > 48 || title.any(Char::isISOControl)) {
            sender.sendMessage(CommandConfig.get("buildbook.invalid-name", "<red>Название книги должно содержать от 1 до 48 обычных символов."))
            return true
        }

        val volume = building.volume
        val data = BuildBookData(
            buildingId = building.fileName,
            title = title,
            transform = transform,
            blockCount = volume.toInt().takeIf { volume <= 10_000 },
        ).validated()
        val leftovers = player.inventory.addItem(BuildBookItems.create(data, modelId))
        if (leftovers.isNotEmpty()) {
            sender.sendMessage(CommandConfig.get("buildbook.inventory-full", "<red>В инвентаре нет места для книги."))
            return true
        }
        sender.sendMessage(
            CommandConfig.get(
                "buildbook.received",
                "<green>Вы получили книгу для <white>%building%",
                "%building%",
                building.fileName,
            ),
        )
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? = when (args.size) {
        1 -> BuildingManager.getBuildings().map { it.fileName }.tabComplete(args[0])
        2 -> listOf("0", "1", "2").tabComplete(args[1])
        3 -> listOf("0", "90", "180", "270").tabComplete(args[2])
        4 -> listOf("-1", "0", "1").tabComplete(args[3])
        5 -> listOf("[name]")
        else -> null
    }
}
