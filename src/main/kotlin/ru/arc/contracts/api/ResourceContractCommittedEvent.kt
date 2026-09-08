package ru.arc.contracts.api

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Emitted after a resource contract submission has been durably committed.
 * Duplicate outcomes carry the original receipt and the same submission ID.
 */
class ResourceContractCommittedEvent(
    val submissionId: String,
    val playerId: UUID,
    val contractId: String,
    val quantity: Long,
) : Event(false) {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
