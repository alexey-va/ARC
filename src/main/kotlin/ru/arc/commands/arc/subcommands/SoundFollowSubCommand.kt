package ru.arc.commands.arc.subcommands

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers

/**
 * /arc soundfollow - проиграть звук игроку (следует за ним).
 *
 * Использование: /arc soundfollow <player> <sound>
 *
 * Примеры:
 * - /arc soundfollow Steve minecraft:music.game
 * - /arc soundfollow Steve custom:my_sound
 */
object SoundFollowSubCommand : SubCommand {

    override val configKey = "soundfollow"
    override val defaultName = "soundfollow"
    override val defaultPermission = "arc.sound-follow"
    override val defaultDescription = "Проиграть звук игроку"
    override val defaultUsage = "/arc soundfollow <player> <namespace:sound>"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size != 2) {
            sendUsage(sender)
            return true
        }

        val playerName = args[0]
        val player = Bukkit.getOnlinePlayers().find { it.name == playerName }
        if (player == null) {
            sender.sendMessage(CommandConfig.playerNotFound(playerName))
            return true
        }

        val soundParts = args[1].split(":")
        val sound = when (soundParts.size) {
            1 -> Sound.sound(Key.key(soundParts[0]), Sound.Source.MUSIC, 1f, 1f)
            2 -> Sound.sound(Key.key(soundParts[0], soundParts[1]), Sound.Source.MUSIC, 1f, 1f)
            else -> null
        }

        if (sound == null) {
            sender.sendMessage(
                CommandConfig.get(
                    "soundfollow.invalid-sound",
                    "<red>Неверный формат звука: <white>%sound%",
                    "%sound%",
                    args[1]
                )
            )
            return true
        }

        player.playSound(sound, Sound.Emitter.self())
        sender.sendMessage(
            CommandConfig.get(
                "soundfollow.played", "<gray>Звук <white>%sound%<gray> проигран игроку <white>%player%",
                "%sound%", args[1], "%player%", player.name
            )
        )

        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> tabCompletePlayers(args[0])
            2 -> listOf("minecraft:music.game", "minecraft:entity.experience_orb.pickup").tabComplete(args[1])
            else -> null
        }
    }
}


