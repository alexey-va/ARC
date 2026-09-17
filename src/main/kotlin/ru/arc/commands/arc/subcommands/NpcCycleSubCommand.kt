package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.origin.scene.OriginAmbientScenesModule
import ru.arc.origin.scene.OriginSceneCycleKey
import ru.arc.origin.scene.OriginSceneStartResult
import ru.arc.util.TextUtil

/** Administrative trigger for one configured Origin ambient NPC cycle. */
object NpcCycleSubCommand : SubCommand {
    override val configKey = "npccycle"
    override val defaultName = "npccycle"
    override val defaultPermission = "arc.admin"
    override val defaultDescription = "Запустить настроенный цикл NPC"
    override val defaultUsage = "/arc npccycle <list|цикл|сцена цикл>"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val keys = OriginAmbientScenesModule.cycleKeys()
        if (args.isEmpty() || args.singleOrNull()?.equals("list", ignoreCase = true) == true) {
            if (keys.isEmpty()) {
                sender.sendMessage(TextUtil.mm("<red>Движок NPC-сцен сейчас не готов.", true))
            } else {
                sender.sendMessage(TextUtil.mm("<gold>Циклы NPC:<gray> " + keys.joinToString(", ") { "${it.sceneId}/${it.cycleId}" }, true))
            }
            return true
        }

        val key = resolveOriginSceneCycle(keys, args)
        if (key == null) {
            sender.sendMessage(TextUtil.mm("<red>Цикл не найден или имя неоднозначно.", true))
            sendUsage(sender)
            return true
        }

        when (val result = OriginAmbientScenesModule.startCycle(key.sceneId, key.cycleId)) {
            is OriginSceneStartResult.Started -> sender.sendMessage(
                TextUtil.mm("<green>Запущен цикл <white>${result.key.sceneId}/${result.key.cycleId}<green>.", true),
            )
            is OriginSceneStartResult.Busy -> sender.sendMessage(
                TextUtil.mm("<yellow>Цикл занят: <white>${result.reason}<yellow>.", true),
            )
            is OriginSceneStartResult.Unknown -> sender.sendMessage(
                TextUtil.mm("<red>Неизвестный цикл <white>${result.sceneId}/${result.cycleId}<red>.", true),
            )
            is OriginSceneStartResult.Unavailable -> sender.sendMessage(
                TextUtil.mm("<red>Цикл недоступен: <white>${result.reason}<red>.", true),
            )
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> {
        val keys = OriginAmbientScenesModule.cycleKeys()
        return when (args.size) {
            1 -> (listOf("list") + legacyAliases.keys + keys.map(OriginSceneCycleKey::sceneId) + keys.map(OriginSceneCycleKey::cycleId))
                .distinct()
                .tabComplete(args[0])
            2 -> keys.filter { it.sceneId.equals(args[0], ignoreCase = true) }
                .map(OriginSceneCycleKey::cycleId)
                .tabComplete(args[1])
            else -> emptyList()
        }
    }
}

internal val legacyAliases = mapOf(
    "forge-master" to OriginSceneCycleKey("forge", "master-anvil"),
    "forge-mila" to OriginSceneCycleKey("forge", "ledger-orders"),
    "forge-luka" to OriginSceneCycleKey("forge", "apprentice-engraving"),
    "forge-savva" to OriginSceneCycleKey("forge", "ore-inspection"),
    "forge-bran" to OriginSceneCycleKey("forge", "blade-practice"),
    "forge-doran" to OriginSceneCycleKey("forge", "hammer-rhythm"),
    "forge-yar" to OriginSceneCycleKey("forge", "furnace-check"),
)

internal fun resolveOriginSceneCycle(keys: List<OriginSceneCycleKey>, args: Array<String>): OriginSceneCycleKey? {
    if (args.size == 1) {
        legacyAliases[args[0].lowercase()]?.let { alias -> return alias.takeIf(keys::contains) }
        return keys.singleOrNull { it.cycleId.equals(args[0], ignoreCase = true) }
    }
    if (args.size == 2) {
        return keys.singleOrNull {
            it.sceneId.equals(args[0], ignoreCase = true) && it.cycleId.equals(args[1], ignoreCase = true)
        }
    }
    return null
}
