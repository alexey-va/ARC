package ru.arc.commands.arc.subcommands

import com.jeff_media.customblockdata.CustomBlockData
import de.tr7zw.changeme.nbtapi.NBT
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.bschests.PersonalLootManager
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.leafdecay.LeafDecayManager

/**
 * /arc test - команды для тестирования и отладки.
 *
 * Действия:
 * - nbt - показать NBT предмета в руке
 * - leaf - проверить decay листвы
 * - ploot - сгенерировать персональный лут
 * - blockdata - показать CustomBlockData блока
 */
object TestSubCommand : SubCommand {

    override val configKey = "test"
    override val defaultName = "test"
    override val defaultPermission = "arc.test"
    override val defaultDescription = "Команды для отладки"
    override val defaultUsage = "/arc test <nbt|leaf|ploot|blockdata>"
    override val defaultPlayerOnly = true

    private val actions = listOf("nbt", "leaf", "ploot", "blockdata")

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true

        if (args.isEmpty()) {
            player.sendMessage(CommandConfig.get("test.hello", "<gray>Hello, World!"))
            return true
        }

        when (args[0].lowercase()) {
            "nbt" -> showNbt(player)
            "leaf" -> checkLeafDecay(player)
            "ploot" -> processPloot(player)
            "blockdata" -> showBlockData(player)
            else -> sendUnknownAction(sender, args[0])
        }

        return true
    }

    private fun showNbt(player: Player) {
        val hand = player.inventory.itemInMainHand
        if (hand.type.isAir) {
            player.sendMessage(CommandConfig.get("test.no-item", "<red>В руке нет предмета!"))
            return
        }

        val nbt = NBT.readNbt(hand)
        player.sendMessage(CommandConfig.get("test.nbt-header", "<gray>NBT данные:"))
        player.sendMessage(nbt.toString())

        for (key in nbt.keys) {
            player.sendMessage("$key: ${nbt.getString(key)}")
        }
    }

    private fun checkLeafDecay(player: Player) {
        val targetBlock = player.getTargetBlockExact(5) ?: run {
            player.sendMessage(CommandConfig.get("test.no-block", "<red>Блок не найден!"))
            return
        }

        val shouldDecay = LeafDecayManager.leafChecker.shouldDecay(targetBlock.location, emptySet())
        val floatingBlobs = LeafDecayManager.leafChecker.findFloatingBlobs(
            targetBlock.location, emptySet(), 100, 10, HashSet(), true
        )

        player.sendMessage(
            CommandConfig.get(
                "test.leaf-blobs",
                "<gray>Плавающие блобы: <white>%count%",
                "%count%",
                floatingBlobs.size.toString()
            )
        )
        player.sendMessage(
            CommandConfig.get(
                "test.leaf-decay",
                "<gray>Должен decay: <white>%value%",
                "%value%",
                shouldDecay.toString()
            )
        )
    }

    private fun processPloot(player: Player) {
        val targetBlock = player.getTargetBlockExact(5) ?: run {
            player.sendMessage(CommandConfig.get("test.no-block", "<red>Блок не найден!"))
            return
        }

        player.sendMessage(
            CommandConfig.get(
                "test.block-info",
                "<gray>Блок: <white>%block%",
                "%block%",
                targetBlock.toString()
            )
        )
        PersonalLootManager.processChestGen(targetBlock)
    }

    private fun showBlockData(player: Player) {
        val targetBlock = player.getTargetBlockExact(5) ?: run {
            player.sendMessage(CommandConfig.get("test.no-block", "<red>Блок не найден!"))
            return
        }

        player.sendMessage(
            CommandConfig.get(
                "test.block-info",
                "<gray>Блок: <white>%block%",
                "%block%",
                targetBlock.toString()
            )
        )

        val data = CustomBlockData(targetBlock, ARC.plugin)
        if (data.keys.isEmpty()) {
            player.sendMessage(CommandConfig.get("test.no-data", "<gray>Нет CustomBlockData"))
            return
        }

        data.keys.forEach { key ->
            val dataType = data.getDataType(key)
            val value = data.get(key, dataType)
            player.sendMessage("<gray>$key: <white>$value")
        }
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> actions.tabComplete(args[0])
            else -> null
        }
    }
}


