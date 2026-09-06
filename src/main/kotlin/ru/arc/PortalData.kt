package ru.arc

import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import ru.arc.hooks.HuskHomesHook

data class PortalData(
    val actionType: ActionType? = null,
    val huskTeleport: HuskHomesHook.HuskTeleport? = null,
    val location: Location? = null,
    val command: String? = null,
    /** Personal, server-owned action; foreign players can never consume this portal. */
    val ownerAction: ((Player) -> Unit)? = null,
) {
    internal fun accepts(owner: UUID, visitor: Player): Boolean = ownerAction == null || visitor.uniqueId == owner

    internal fun executeOwnerAction(owner: UUID, visitor: Player) {
        if (accepts(owner, visitor)) ownerAction?.invoke(visitor)
    }

    enum class ActionType { COMMAND, HUSK, TELEPORT }
}
