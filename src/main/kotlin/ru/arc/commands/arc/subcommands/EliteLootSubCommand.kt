package ru.arc.commands.arc.subcommands

import de.tr7zw.changeme.nbtapi.NBT
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.LeatherArmorMeta
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.eliteloot.EliteLootGui
import ru.arc.eliteloot.EliteLootManager
import ru.arc.util.GuiUtils

/**
 * /arc eliteloot - управление декоративным лутом.
 *
 * Действия:
 * - list - открыть GUI с лутом
 * - add [weight] - добавить предмет из руки
 */
object EliteLootSubCommand : SubCommand {

    override val configKey = "eliteloot"
    override val defaultName = "eliteloot"
    override val defaultPermission = "arc.eliteloot"
    override val defaultDescription = "Управление декоративным лутом"
    override val defaultUsage = "/arc eliteloot <list|add> [weight]"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true

        if (args.isEmpty()) {
            openList(player)
            return true
        }

        when (args[0].lowercase()) {
            "list" -> openList(player)
            "add" -> addFromHand(player, args)
            else -> sendUnknownAction(sender, args[0])
        }

        return true
    }

    private fun openList(player: Player) {
        GuiUtils.constructAndShowAsync({ EliteLootGui(player) }, player)
    }

    private fun addFromHand(player: Player, args: Array<String>) {
        val hand = player.inventory.itemInMainHand
        if (hand.type == Material.AIR) {
            player.sendMessage(CommandConfig.get("eliteloot.no-item", "<red>В руке нет предмета!"))
            return
        }

        val lootType = EliteLootManager.toLootType(hand)
        if (lootType == null) {
            player.sendMessage(
                CommandConfig.get(
                    "eliteloot.not-supported",
                    "<red>Этот предмет не поддерживается EliteLoot"
                )
            )
            return
        }

        player.sendMessage(
            CommandConfig.get(
                "eliteloot.item-type",
                "<gray>Тип предмета: <white>%type%",
                "%type%",
                lootType.name
            )
        )

        val weight = args.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        val modelId = if (hand.itemMeta?.hasCustomModelData() == true) hand.itemMeta.customModelData else 0

        var iaNamespace: String? = null
        var iaId: String? = null
        var color: org.bukkit.Color? = null

        // ItemsAdder NBT
        val nbt = NBT.readNbt(hand)
        if (nbt.hasTag("itemsadder")) {
            val itemsadder = nbt.getCompound("itemsadder")
            if (itemsadder != null) {
                iaNamespace = itemsadder.getString("namespace")
                iaId = itemsadder.getString("id")
            }
        }

        // Leather color
        val meta = hand.itemMeta
        if (meta is LeatherArmorMeta) {
            color = meta.color
        }

        // Use helper method for Kotlin interop
        val added = EliteLootManager.addDecorItem(lootType, hand.type, weight, modelId, color, iaNamespace, iaId)

        if (!added) {
            player.sendMessage(
                CommandConfig.get(
                    "eliteloot.already-added",
                    "<red>Этот предмет уже добавлен в EliteLoot"
                )
            )
            return
        }
        player.sendMessage(CommandConfig.get("eliteloot.added", "<green>Декор добавлен в EliteLoot!"))
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> listOf("list", "add").tabComplete(args[0])
            2 -> if (args[0].equals("add", ignoreCase = true)) {
                listOf("1.0", "0.5", "0.1").tabComplete(args[1])
            } else null

            else -> null
        }
    }
}

