package ru.arc.graves

import java.util.UUID

/** Read-only menu/network projection; item ownership remains in the corresponding durable store. */
internal data class GraveView(
    val id: UUID,
    val ownerId: UUID,
    val server: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val experience: Int,
    val items: List<GraveItem>,
) {
    fun active(now: Long): Boolean = expiresAt < 0 || expiresAt > now
}

internal data class GraveItem(val nameJson: String, val amount: Int)
