package ru.arc.ai.assistant

import java.util.*

data class ToolResponse (
    var uuid: UUID,
    var result: String,
    var serverName: String,
)
