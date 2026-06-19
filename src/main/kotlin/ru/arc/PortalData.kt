package ru.arc

import org.bukkit.Location
import ru.arc.hooks.HuskHomesHook

data class PortalData(
    val actionType: ActionType? = null,
    val huskTeleport: HuskHomesHook.HuskTeleport? = null,
    val location: Location? = null,
    val command: String? = null,
) {
    enum class ActionType { COMMAND, HUSK, TELEPORT }
}
