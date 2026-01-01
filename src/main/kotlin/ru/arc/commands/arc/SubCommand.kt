package ru.arc.commands.arc

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Interface for /arc subcommands.
 * Each subcommand handles a specific functionality of the /arc command.
 *
 * All metadata (name, permission, description, usage) is loaded from config
 * using the [configKey] property.
 *
 * @see CommandConfig for message configuration
 * @see ArcCommand for the main command router
 */
interface SubCommand {

    // ==================== Configuration ====================

    /** Key used to load metadata from config (e.g., "reload", "hunt") */
    val configKey: String

    /** Default values for metadata (used if not in config) */
    val defaultName: String get() = configKey
    val defaultPermission: String? get() = null
    val defaultDescription: String get() = ""
    val defaultUsage: String get() = "/arc $name"
    val defaultPlayerOnly: Boolean get() = false

    // ==================== Loaded from Config ====================

    /** The name of the subcommand (loaded from config) */
    val name: String get() = CommandConfig.getCommandName(configKey, defaultName)

    /** Alternative names that also trigger this subcommand */
    val aliases: List<String> get() = CommandConfig.getAliases(configKey)

    /** Permission required to use this subcommand (null = no permission required) */
    val permission: String? get() = CommandConfig.getCommandPermission(configKey, defaultPermission)

    /** Whether this command requires a player (not console) */
    val playerOnly: Boolean get() = CommandConfig.isPlayerOnly(configKey, defaultPlayerOnly)

    /** Short description for help */
    val description: String get() = CommandConfig.getCommandDescription(configKey, defaultDescription)

    /** Usage syntax */
    val usage: String get() = CommandConfig.getCommandUsage(configKey, defaultUsage)

    // ==================== Core Methods ====================

    /**
     * Execute the subcommand.
     * @param sender The command sender
     * @param args Arguments after the subcommand name
     * @return true if command was handled
     */
    fun execute(sender: CommandSender, args: Array<String>): Boolean

    /**
     * Provide tab completions for this subcommand.
     * @param sender The command sender
     * @param args Arguments after the subcommand name
     * @return List of completions or null
     */
    fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? = null

    // ==================== Helper Methods ====================

    /**
     * Sends usage message to sender.
     */
    fun sendUsage(sender: CommandSender) {
        sender.sendMessage(CommandConfig.usage(usage))
    }

    /**
     * Checks if sender is a player, sends error if not.
     * @return Player or null if sender is not a player
     */
    fun requirePlayer(sender: CommandSender): Player? {
        val player = sender.player
        if (player == null) {
            sender.sendMessage(CommandConfig.playerOnly())
        }
        return player
    }

    /**
     * Gets online player by name, sends error if not found.
     * @return Player or null if not found
     */
    fun getOnlinePlayer(sender: CommandSender, name: String): Player? {
        val player = Bukkit.getPlayerExact(name)
        if (player == null) {
            sender.sendMessage(CommandConfig.playerNotFound(name))
        }
        return player
    }

    /**
     * Sends unknown action error message.
     */
    fun sendUnknownAction(sender: CommandSender, action: String) {
        sender.sendMessage(CommandConfig.unknownAction(action))
        sendUsage(sender)
    }

    /**
     * Checks if minimum number of args provided, sends usage if not.
     * @return true if args are sufficient, false otherwise
     */
    fun requireArgs(sender: CommandSender, args: Array<String>, minArgs: Int): Boolean {
        if (args.size < minArgs) {
            sendUsage(sender)
            return false
        }
        return true
    }

    /**
     * Parses -key:value flags from args.
     * @return Map of flag names to values
     */
    fun parseFlags(args: Array<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (arg in args) {
            if (arg.startsWith("-")) {
                val parts = arg.substring(1).split(":", limit = 2)
                if (parts.size == 2) {
                    result[parts[0].lowercase()] = parts[1]
                }
            }
        }
        return result
    }

    /**
     * Gets non-flag arguments.
     */
    fun getNonFlagArgs(args: Array<String>): List<String> {
        return args.filter { !it.startsWith("-") }
    }
}

// ==================== Extensions ====================

/**
 * Extension to safely cast sender to Player.
 */
val CommandSender.player: Player?
    get() = this as? Player

/**
 * Extension to check if sender has permission.
 */
fun CommandSender.checkPermission(permission: String?): Boolean {
    return permission == null || hasPermission(permission)
}

/**
 * Smart tab completion: filters by prefix and sorts by relevance.
 *
 * Sorting priority:
 * 1. Exact case match at start (highest)
 * 2. Case-insensitive match at start
 * 3. Shorter completions first (easier to type)
 * 4. Alphabetical order
 *
 * @param input The current user input to match against
 * @return Filtered and sorted list of completions
 */
fun List<String>.tabComplete(input: String): List<String> {
    if (input.isEmpty()) {
        return this.sortedWith(compareBy({ it.length }, { it.lowercase() }))
    }

    val inputLower = input.lowercase()

    return this
        .filter { it.lowercase().startsWith(inputLower) }
        .sortedWith(
            compareBy(
                // 1. Exact case match first
                { !it.startsWith(input) },
                // 2. Shorter strings first (more likely what user wants)
                { it.length },
                // 3. Alphabetical
                { it.lowercase() }
            )
        )
}

/**
 * Simple prefix filter (case-insensitive).
 * Use [tabComplete] for smarter sorting.
 */
fun List<String>.filterByPrefix(prefix: String): List<String> {
    return tabComplete(prefix)
}

/**
 * Returns list of online player names, sorted for tab completion.
 */
fun onlinePlayerNames(): List<String> {
    return Bukkit.getOnlinePlayers().map { it.name }.sorted()
}

/**
 * Tab complete player names with smart matching.
 */
fun tabCompletePlayers(input: String): List<String> {
    return onlinePlayerNames().tabComplete(input)
}
