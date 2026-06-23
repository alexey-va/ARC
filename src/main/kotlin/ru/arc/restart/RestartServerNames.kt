package ru.arc.restart

import ru.arc.ARC
import ru.arc.commands.arc.tabComplete
import ru.arc.xserver.playerlist.PlayerManager

object RestartServerNames {
    fun suggestions(config: RestartConfig): List<String> {
        val names = linkedSetOf<String>()
        names += "all"
        names += config.knownServers.map { it.lowercase() }
        names +=
            PlayerManager
                .getServerNames()
                .filterNotNull()
                .filter { it.isNotBlank() }
                .map { it.lowercase() }
        ARC.serverName
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()
            ?.let { names += it }
        return names.sorted()
    }

    fun tabComplete(
        config: RestartConfig,
        partial: String,
    ): List<String> {
        val suggestions = suggestions(config)
        if (!partial.contains(',')) {
            return suggestions.tabComplete(partial)
        }

        val prefix = partial.substringBeforeLast(',') + ","
        val token = partial.substringAfterLast(',')
        return suggestions
            .filter { it.startsWith(token, ignoreCase = true) }
            .map { prefix + it }
            .sortedWith(compareBy({ it.length }, { it.lowercase() }))
    }
}
