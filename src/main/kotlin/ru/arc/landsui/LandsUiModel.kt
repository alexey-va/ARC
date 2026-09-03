package ru.arc.landsui

import java.util.UUID

data class LandsUiLand(
    val id: Int,
    val name: String,
    val ownerId: UUID,
    val chunks: Int,
    val memberIds: Set<UUID>,
    val maxMembers: Int,
    val balance: Double,
)

data class LandsUiPlayer(val id: UUID, val name: String)

object LandsUiPlanner {
    fun addablePlayers(
        viewerId: UUID,
        land: LandsUiLand,
        onlinePlayers: Collection<LandsUiPlayer>,
    ): List<LandsUiPlayer> = onlinePlayers
        .asSequence()
        .filter { it.id != viewerId && it.id !in land.memberIds }
        .distinctBy { it.id }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .toList()
}

object LandsUiCommands {
    private val landToken = Regex("[\\p{L}\\p{N}_-]{1,32}")
    private val memberToken = Regex("[A-Za-z0-9_]{3,16}")
    private val fixedArgument = Regex("[a-z]+")

    fun create(name: String): String = "lands create ${landName(name)}"

    fun rename(newName: String): String = "lands land rename ${landName(newName)}"

    fun addMember(playerName: String): String = "lands land member add ${member(playerName)}"

    fun removeMember(playerName: String): String = "lands land member remove ${member(playerName)}"

    fun menu(): String = "lands menu"

    fun land(vararg arguments: String): String {
        require(arguments.isNotEmpty()) { "Lands command action cannot be empty" }
        arguments.forEach { argument ->
            val valid = fixedArgument.matches(argument) && argument == argument.lowercase()
            require(valid) { "Unsafe Lands command argument: '$argument'" }
        }
        return "lands land ${arguments.joinToString(" ")}"
    }

    fun member(name: String): String {
        require(memberToken.matches(name)) { "Invalid Minecraft player name: '$name'" }
        return name
    }

    fun landName(name: String): String {
        require(landToken.matches(name)) { "Invalid Lands settlement name: '$name'" }
        return name
    }
}
