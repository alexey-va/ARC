package ru.arc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Duration

/**
 * Kotlin extensions for Player and CommandSender.
 *
 * Provides convenient messaging and effect methods.
 */

// === Messaging ===

/**
 * Sends a MiniMessage formatted message.
 *
 * @param message the message in MiniMessage format
 */
fun CommandSender.sendMM(message: String) {
    sendMessage(TextUtil.mm(message))
}

/**
 * Sends a MiniMessage formatted message with placeholders.
 *
 * @param message the message in MiniMessage format
 * @param placeholders pairs of placeholder name to value
 */
fun CommandSender.sendMM(
    message: String,
    vararg placeholders: Pair<String, Any>,
) {
    var result = message
    placeholders.forEach { (key, value) ->
        result = result.replace("%$key%", value.toString())
    }
    sendMessage(TextUtil.mm(result))
}

/**
 * Sends multiple MiniMessage formatted messages.
 *
 * @param messages the messages in MiniMessage format
 */
fun CommandSender.sendMM(vararg messages: String) {
    messages.forEach { sendMM(it) }
}

/**
 * Sends an action bar message in MiniMessage format.
 *
 * @param message the message in MiniMessage format
 */
fun Player.sendActionBarMM(message: String) {
    sendActionBar(TextUtil.mm(message))
}

/**
 * Sends an action bar message with placeholders.
 *
 * @param message the message in MiniMessage format
 * @param placeholders pairs of placeholder name to value
 */
fun Player.sendActionBarMM(
    message: String,
    vararg placeholders: Pair<String, Any>,
) {
    var result = message
    placeholders.forEach { (key, value) ->
        result = result.replace("%$key%", value.toString())
    }
    sendActionBar(TextUtil.mm(result))
}

// === Titles ===

/**
 * Shows a title with MiniMessage formatting.
 *
 * @param title the main title in MiniMessage format
 * @param subtitle the subtitle in MiniMessage format (optional)
 * @param fadeIn fade in duration in ticks (default 10)
 * @param stay stay duration in ticks (default 70)
 * @param fadeOut fade out duration in ticks (default 20)
 */
fun Player.showTitleMM(
    title: String,
    subtitle: String = "",
    fadeIn: Int = 10,
    stay: Int = 70,
    fadeOut: Int = 20,
) {
    val titleComponent = TextUtil.mm(title)
    val subtitleComponent = if (subtitle.isNotEmpty()) TextUtil.mm(subtitle) else Component.empty()

    val times =
        Title.Times.times(
            Duration.ofMillis(fadeIn * 50L),
            Duration.ofMillis(stay * 50L),
            Duration.ofMillis(fadeOut * 50L),
        )

    showTitle(Title.title(titleComponent, subtitleComponent, times))
}

/**
 * Clears the current title.
 */
fun Player.clearTitleNow() {
    clearTitle()
}

// === Effects ===

/**
 * Plays a sound at the player's location.
 *
 * @param soundName the sound name
 * @param volume the volume
 * @param pitch the pitch
 * @return true if played
 */
fun Player.playSoundSelf(
    soundName: String,
    volume: Float = 1.0f,
    pitch: Float = 1.0f,
): Boolean = SoundUtils.playSound(this, soundName, volume, pitch)

// === Permissions ===

/**
 * Checks if the sender has any of the given permissions.
 *
 * @param permissions the permissions to check
 * @return true if the sender has at least one permission
 */
fun CommandSender.hasAnyPermission(vararg permissions: String): Boolean = permissions.any { hasPermission(it) }

/**
 * Checks if the sender has all of the given permissions.
 *
 * @param permissions the permissions to check
 * @return true if the sender has all permissions
 */
fun CommandSender.hasAllPermissions(vararg permissions: String): Boolean = permissions.all { hasPermission(it) }

// === Player Checks ===

/**
 * Executes a block if the sender is a player.
 *
 * @param block the block to execute with the player
 * @return the result of the block, or null if not a player
 */
inline fun <T> CommandSender.ifPlayer(block: (Player) -> T): T? = if (this is Player) block(this) else null

/**
 * Executes a block if the sender is a player, or sends an error message.
 *
 * @param errorMessage the error message if not a player
 * @param block the block to execute with the player
 * @return the result of the block, or null if not a player
 */
inline fun <T> CommandSender.requirePlayer(
    errorMessage: String = "<red>Эта команда доступна только игрокам!",
    block: (Player) -> T,
): T? =
    if (this is Player) {
        block(this)
    } else {
        sendMM(errorMessage)
        null
    }
