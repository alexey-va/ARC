package ru.arc.ai.assistant

import com.google.gson.JsonElement
import java.util.UUID

data class ToolMessageRaw(
    val uuid: UUID? = null,
    val toolName: String? = null,
    val toolDto: JsonElement? = null,
    val timestamp: Long = 0L
)
