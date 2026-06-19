package ru.arc.ai

import org.bukkit.Location
import java.util.UUID

data class Conversation(
    val playerUuid: UUID? = null,
    val location: Location? = null,
    val radius: Double = 0.0,
    val gptId: String? = null,
    val archetype: String? = null,
    var lastMessageTime: Long = 0L,
    val lifeTime: Long = 0L,
    val talkerName: String? = null,
    val npcId: Int? = null,
    val endMessage: String? = null,
    val privateConversation: Boolean = true
)
