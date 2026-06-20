package ru.arc.hooks.lands

import ru.arc.common.ServerLocation
import java.util.UUID

data class LandsRequest(
    var uuid: UUID? = null,
    var playerUuid: UUID? = null,
    var serverLocation: ServerLocation? = null,
)
