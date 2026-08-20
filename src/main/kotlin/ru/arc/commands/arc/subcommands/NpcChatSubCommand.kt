package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.ARC
import ru.arc.ai.GPTManager
import ru.arc.ai.config.NpcChatConfig
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.hooks.HookRegistry

object NpcChatSubCommand : SubCommand {
    override val configKey = "npc-chat"
    override val defaultName = "npc-chat"
    override val defaultDescription = "Разговор с жителем Origin"
    override val defaultUsage = "/arc npc-chat <start <роль>|stop [разговор|all]>"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true
        return when (args.firstOrNull()?.lowercase()) {
            "start" -> {
                val personaId = args.getOrNull(1)?.lowercase()
                if (personaId == null) {
                    sendUsage(sender)
                    true
                } else {
                    start(player, personaId)
                    true
                }
            }

            "stop" -> {
                val id = args.getOrNull(1)
                when {
                    id == null || id.equals("all", ignoreCase = true) -> GPTManager.endAllConversations(player)
                    else -> GPTManager.endConversation(player, id)
                }
                true
            }

            else -> {
                sendUsage(sender)
                true
            }
        }
    }

    private fun start(
        player: org.bukkit.entity.Player,
        personaId: String,
    ) {
        if (player.world.name != ORIGIN_WORLD) {
            player.sendMessage(
                CommandConfig.get(
                    "npc-chat.origin-only",
                    "<gray>Этот разговор доступен только в Origin.",
                ),
            )
            return
        }
        val config = NpcChatConfig.load(ARC.instance.dataPath)
        val persona = config.persona(personaId)
        if (persona == null) {
            player.sendMessage(
                CommandConfig.get(
                    "npc-chat.unknown-persona",
                    "<gray>Этот житель сейчас не разговаривает.",
                ),
            )
            return
        }
        val hook = HookRegistry.citizensHook
        val npc = hook?.findNearestNpc(player.location, persona.npcName, persona.radius)
        if (npc == null) {
            player.sendMessage(
                CommandConfig.get(
                    "npc-chat.too-far",
                    "<gray>Подойди ближе к жителю, чтобы поговорить.",
                ),
            )
            return
        }

        val conversationId = "npc-${npc.id}-${persona.id}"
        GPTManager.startConversation(
            player = player,
            id = conversationId,
            archetype = persona.id,
            talkerName = persona.displayName,
            location = npc.location,
            radius = persona.radius,
            lifeTime = persona.lifeTimeMillis,
            initialMessage = persona.openingLine,
            endMessage = persona.closingLine,
            npcId = npc.id,
            privateConversation = persona.privateConversation,
        )
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? =
        when (args.size) {
            1 -> listOf("start", "stop").tabComplete(args[0])
            2 ->
                when (args[0].lowercase()) {
                    "start" -> NpcChatConfig.load(ARC.instance.dataPath).personaIds().toList().tabComplete(args[1])
                    "stop" -> listOf("all").tabComplete(args[1])
                    else -> null
                }
            else -> null
        }

    private const val ORIGIN_WORLD = "rc_origin_spawn"
}
