package ru.arc.commands.arc.subcommands

import de.tr7zw.changeme.nbtapi.NBTItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.autobuild.Building
import ru.arc.autobuild.BuildingManager
import ru.arc.commands.arc.*
import ru.arc.configs.ConfigManager
import ru.arc.util.TextUtil.strip
import java.util.Collections

/**
 * /arc buildbook - создание книги строительства.
 *
 * Использование: /arc buildbook <building> <model-id> [rotation] [y-offset] [name...]
 */
object BuildBookSubCommand : SubCommand {

    override val configKey = "buildbook"
    override val defaultName = "buildbook"
    override val defaultPermission = "arc.command.buildbook"
    override val defaultDescription = "Создать книгу строительства"
    override val defaultUsage = "/arc buildbook <building> <model-id> [rotation] [y-offset] [name...]"
    override val defaultPlayerOnly = true

    private val config get() = ConfigManager.of(ARC.plugin.dataPath, "auto-build.yml")

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true

        if (args.size < 2) {
            sendUsage(sender)
            return true
        }

        val fileName = args[0]
        val building = BuildingManager.getBuilding(fileName)
        if (building == null) {
            sender.sendMessage(
                CommandConfig.get(
                    "buildbook.not-found",
                    "<red>Строение <white>%name%<red> не найдено!",
                    "%name%",
                    fileName
                )
            )
            return true
        }

        val modelId = args[1].toIntOrNull() ?: run {
            sender.sendMessage(
                CommandConfig.get(
                    "buildbook.invalid-model",
                    "<red>Неверный model-id: <white>%value%",
                    "%value%",
                    args[1]
                )
            )
            return true
        }

        // Собираем имя из оставшихся аргументов (начиная с 5-го)
        val name = if (args.size > 4) {
            "&7" + args.drop(4).joinToString(" ")
        } else {
            "&7" + config.string("build-book.default-name", "Дом")
        }

        // Создаём предмет
        val stack = ItemStack(Material.BOOK)
        val nbtItem = NBTItem(stack)
        nbtItem.setString("arc:building_key", fileName)

        // Rotation
        if (args.size >= 3) {
            nbtItem.setString("arc:rotation", args[2])
        }

        // Y-offset
        if (args.size >= 4) {
            nbtItem.setString("arc:y_offset", args[3])
        }

        nbtItem.applyNBT(stack)

        // Настраиваем мету
        val meta = stack.itemMeta ?: return true
        val longName = createLongName(name)

        val display = config.componentDef(
            "build-book.display-name",
            "     <gray>\uD83D\uDEE0 <gold>Книга строительства <gray>\uD83D\uDEE0",
            TagResolver.builder()
                .tag("building", Tag.inserting(Component.text(fileName, NamedTextColor.GOLD)))
                .build()
        )

        val lore = config.stringList(
            "build-book.lore", listOf(
                " ",
                "      <gray>Эта книга позволяет вам",
                "    <gray>возвести готовое строение    ",
                " ",
                "<long_name>",
                " ",
                "        <gray>Нажмите <green>ПКМ <gray>по земле"
            )
        ).map { str ->
            MiniMessage.miniMessage().deserialize(
                str, TagResolver.builder()
                    .tag("name", Tag.inserting(LegacyComponentSerializer.legacyAmpersand().deserialize(name)))
                    .tag("long_name", Tag.inserting(longName))
                    .build()
            )
        }.map { strip(it) }

        meta.displayName(display)
        meta.lore(lore)
        if (modelId != 0) meta.setCustomModelData(modelId)
        stack.itemMeta = meta

        player.inventory.addItem(stack)

        val message = config.componentDef(
            "build-book.received", "<green>Вы получили книгу для <building>",
            TagResolver.builder()
                .tag("building", Tag.inserting(Component.text(fileName, NamedTextColor.GOLD)))
                .build()
        )
        sender.sendMessage(message)

        return true
    }

    private fun createLongName(name: String): Component {
        val bName = LegacyComponentSerializer.legacyAmpersand().deserialize(name)
        val length = (bName as? net.kyori.adventure.text.TextComponent)?.content()?.length ?: name.length
        var len2 = maxOf(0, (37 - length - 4) / 2)
        if (length < 9) len2 += 1
        if (length < 13) len2 += 1

        return strip(
            Component.text(Collections.nCopies(len2, " ").joinToString(""))
                .append(Component.text("\uD83D\uDEE0 ", NamedTextColor.GREEN))
                .append(bName)
                .append(Component.text(" \uD83D\uDEE0", NamedTextColor.GREEN))
        ) ?: Component.empty()
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> BuildingManager.getBuildings().map { it.fileName }.tabComplete(args[0])
            2 -> listOf("0", "1", "2").tabComplete(args[1])
            3 -> listOf("0", "90", "180", "270").tabComplete(args[2])
            4 -> listOf("-1", "0", "1").tabComplete(args[3])
            5 -> listOf("[name]")
            else -> null
        }
    }
}

