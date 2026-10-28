package ru.arc.landsui

import java.util.Locale

internal enum class LandsUiDestination {
    ROOT, CURRENT, INVITES, OVERVIEW, ADD_MEMBER, MEMBERS, RULES, ROLES, ENVIRONMENT, TERRITORY,
    SETTINGS, RENAME, DESCRIPTION, LEAVE, DELETE, CREATE, GUIDE, AREAS,
}

internal data class LandsUiCommandRoute(
    val destination: LandsUiDestination,
    val areaName: String? = null,
)

/** Only UI entrypoints are intercepted; explicit native action arguments stay with Lands. */
internal fun landsUiCommandRoute(commandLine: String): LandsUiCommandRoute? {
    val parts = commandLine.trim().removePrefix("/").split(Regex("\\s+")).filter(String::isNotEmpty)
    val rawLabel = parts.firstOrNull()?.lowercase(Locale.ROOT) ?: return null
    val label = when {
        ':' !in rawLabel -> rawLabel
        rawLabel.startsWith("lands:") -> rawLabel.substringAfter(':')
        else -> return null
    }
    val arguments = parts.drop(1)
    if (label == "trust" || label == "untrust") {
        if (arguments.isNotEmpty()) return null
        return LandsUiCommandRoute(if (label == "trust") LandsUiDestination.ADD_MEMBER else LandsUiDestination.MEMBERS)
    }
    if (label != "lands" && label != "land") return null
    if (arguments.isEmpty()) return LandsUiCommandRoute(LandsUiDestination.ROOT)
    return landsUiSubcommandRoute(arguments)
}

private fun landsUiSubcommandRoute(arguments: List<String>, areaName: String? = null): LandsUiCommandRoute? {
    val action = arguments.first().lowercase(Locale.ROOT)
    val rest = arguments.drop(1)
    if (action == "area" && areaName == null) {
        if (rest.isEmpty()) return LandsUiCommandRoute(LandsUiDestination.AREAS)
        return if (rest.size == 1) LandsUiCommandRoute(LandsUiDestination.OVERVIEW, rest.first())
        else landsUiSubcommandRoute(rest.drop(1), rest.first())
    }
    if (action == "member") {
        if (rest.isEmpty() || rest.size == 1 && rest.first().equals("menu", ignoreCase = true)) {
            return LandsUiCommandRoute(LandsUiDestination.MEMBERS, areaName)
        }
        if (rest.first().lowercase(Locale.ROOT) !in setOf("trust", "untrust", "setrole", "setowner")) return null
        return landsUiSubcommandRoute(rest, areaName)
    }
    if (action == "invites" && (rest.isEmpty() || rest.size == 1 && rest.first().equals("menu", ignoreCase = true))) {
        return LandsUiCommandRoute(LandsUiDestination.INVITES)
    }
    if (action == "menu" && rest.map { it.lowercase(Locale.ROOT) } == listOf("personal", "invites")) {
        return LandsUiCommandRoute(LandsUiDestination.INVITES)
    }
    if (action == "menu" && rest.isNotEmpty()) {
        val page = rest.map { it.lowercase(Locale.ROOT) }.let {
            if (it.first() == "land") it.drop(1) else it
        }
        if (page.isEmpty()) return LandsUiCommandRoute(LandsUiDestination.OVERVIEW, areaName)
        if (page.size != 1) return null
        val destination = when (page.first()) {
            "lands", "list" -> LandsUiDestination.ROOT
            "here" -> LandsUiDestination.CURRENT
            "roles" -> LandsUiDestination.ROLES
            "areas" -> LandsUiDestination.AREAS
            "claims" -> LandsUiDestination.TERRITORY
            "options" -> LandsUiDestination.SETTINGS
            else -> return null
        }
        return LandsUiCommandRoute(destination, areaName)
    }
    if (rest.isNotEmpty()) return null
    val destination = when (action) {
        "menu" -> LandsUiDestination.OVERVIEW
        "trust" -> LandsUiDestination.ADD_MEMBER
        "untrust", "setrole", "setowner" -> LandsUiDestination.MEMBERS
        "info" -> LandsUiDestination.CURRENT
        "roles" -> LandsUiDestination.ROLES
        "rules", "flags" -> LandsUiDestination.RULES
        "environment" -> LandsUiDestination.ENVIRONMENT
        "settings" -> LandsUiDestination.SETTINGS
        "rename" -> LandsUiDestination.RENAME
        "description" -> LandsUiDestination.DESCRIPTION
        "leave" -> LandsUiDestination.LEAVE
        "delete" -> LandsUiDestination.DELETE
        "create" -> LandsUiDestination.CREATE
        "help" -> LandsUiDestination.GUIDE
        "edit" -> LandsUiDestination.ROOT
        else -> return null
    }
    return LandsUiCommandRoute(destination, areaName)
}
