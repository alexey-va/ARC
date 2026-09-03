package ru.arc.helpcenter

import java.text.Normalizer
import java.util.Locale

enum class HelpCenterPage(vararg val aliases: String) {
    ROOT("root", "главная"),
    GUIDE("guide", "гайд", "start", "начало"),
    COMMANDS("commands", "команды"),
    TRAVEL("travel", "перемещения", "homes", "дома"),
    PRIVAT("privat", "приват", "lands", "земли"),
    ;

    companion object {
        fun from(value: String): HelpCenterPage? {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { page -> page.aliases.any { it == normalized } }
        }
    }
}

enum class HelpCenterCategory(val configId: String) {
    START("start"),
    TRAVEL("travel"),
    PROTECTION("protection"),
    TRADE("trade"),
    PROGRESS("progress"),
    SOCIAL("social"),
}

data class HelpCenterCommand(
    val id: String,
    val category: HelpCenterCategory,
    val command: String,
    val label: String,
    val description: String,
    val keywords: String,
)

data class HelpCenterHome(
    val name: String,
    val server: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
)

data class HelpCenterHomes(
    val homes: List<HelpCenterHome>,
    val usedSlots: Int,
    val maxSlots: Int,
)

object HelpCenterPlanner {
    fun search(entries: List<HelpCenterCommand>, query: String, limit: Int): List<HelpCenterCommand> {
        require(limit in 1..32) { "Help center search limit must be in 1..32" }
        val needle = normalize(query).removePrefix("/")
        if (needle.isBlank()) return entries.take(limit)
        return entries
            .asSequence()
            .map { entry ->
                val command = normalize(entry.command).removePrefix("/")
                val haystack = normalize("${entry.label} ${entry.description} ${entry.keywords}")
                val score = when {
                    command == needle -> 0
                    command.startsWith(needle) -> 1
                    normalize(entry.label).startsWith(needle) -> 2
                    haystack.contains(needle) -> 3
                    else -> Int.MAX_VALUE
                }
                entry to score
            }
            .filter { (_, score) -> score != Int.MAX_VALUE }
            .sortedWith(compareBy<Pair<HelpCenterCommand, Int>> { it.second }.thenBy { it.first.id })
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .trim()
}

object HelpCenterCommands {
    private val homeName = Regex("[\\p{L}\\p{N}_-]{1,32}")
    private val executable = Regex("[a-z0-9:_-]+(?: [a-z0-9:_-]+)*")

    fun home(name: String): String = "home ${safeHome(name)}"

    fun createHome(name: String): String = "sethome ${safeHome(name)}"

    fun deleteHome(name: String): String = "delhome ${safeHome(name)}"

    fun relocateHome(name: String): String = "edithome ${safeHome(name)} relocate"

    fun execute(command: String): String {
        require(executable.matches(command)) { "Unsafe help center command" }
        return command
    }

    private fun safeHome(name: String): String {
        require(homeName.matches(name)) { "Unsafe home name" }
        return name
    }
}
