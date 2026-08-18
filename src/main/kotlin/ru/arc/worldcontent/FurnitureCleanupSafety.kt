package ru.arc.worldcontent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CleanupCenter(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
)

data class BlockPosition(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
)

enum class FurnitureFamily {
    SIMPLE,
    COMPLEX,
}

sealed interface CleanupTarget {
    val stableKey: String

    data class Furniture(
        val rootUuid: UUID,
        val family: FurnitureFamily,
        val namespacedId: String?,
    ) : CleanupTarget {
        override val stableKey: String = "entity:$rootUuid"
    }

    data class Barrier(
        val position: BlockPosition,
    ) : CleanupTarget {
        override val stableKey: String = "barrier:${position.world}:${position.x}:${position.y}:${position.z}"
    }
}

data class FurnitureCleanupPlan(
    val center: CleanupCenter,
    val radius: Int,
    val targets: List<CleanupTarget>,
    val digest: String,
) {
    val furnitureCount: Int = targets.count { it is CleanupTarget.Furniture }
    val barrierCount: Int = targets.count { it is CleanupTarget.Barrier }

    companion object {
        fun create(
            center: CleanupCenter,
            radius: Int,
            candidates: List<CleanupTarget>,
        ): FurnitureCleanupPlan {
            require(radius in 1..MAX_RADIUS) { "radius must be in 1-$MAX_RADIUS" }
            val distinct = LinkedHashMap<String, CleanupTarget>()
            candidates.forEach { distinct.putIfAbsent(it.stableKey, it) }
            val targets = distinct.values.toList()
            val canonical =
                buildString {
                    append(center.world).append('|')
                    append(center.x).append('|').append(center.y).append('|').append(center.z).append('|')
                    append(radius).append('\n')
                    targets.map(CleanupTarget::stableKey).sorted().forEach { append(it).append('\n') }
                }
            return FurnitureCleanupPlan(center, radius, targets, sha256(canonical))
        }

        const val MAX_RADIUS = 24

        private fun sha256(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

enum class ProbeEntityType {
    ARMOR_STAND,
    ITEM_FRAME,
    ITEM_DISPLAY,
    LIVING_ENTITY,
    OTHER,
}

data class EntityProbe(
    val uuid: UUID,
    val type: ProbeEntityType,
    val simpleId: String?,
    val complexId: String?,
)

object ItemsAdderMarkerPolicy {
    fun classify(probe: EntityProbe): CleanupTarget.Furniture? =
        when {
            probe.simpleId != null &&
                probe.type in setOf(
                    ProbeEntityType.ARMOR_STAND,
                    ProbeEntityType.ITEM_FRAME,
                    ProbeEntityType.ITEM_DISPLAY,
                ) ->
                CleanupTarget.Furniture(probe.uuid, FurnitureFamily.SIMPLE, probe.simpleId)

            probe.complexId != null && probe.type == ProbeEntityType.LIVING_ENTITY ->
                CleanupTarget.Furniture(probe.uuid, FurnitureFamily.COMPLEX, probe.complexId)

            else -> null
        }
}

data class CleanupConfirmation(
    val token: String,
    val expiresAt: Instant,
)

sealed interface CleanupConfirmationResult {
    data object Accepted : CleanupConfirmationResult

    data class Rejected(
        val reason: String,
    ) : CleanupConfirmationResult
}

class CleanupConfirmationRegistry(
    private val clock: () -> Instant = Instant::now,
    private val tokenFactory: () -> String = ::randomToken,
    private val ttl: Duration = Duration.ofSeconds(30),
) {
    private data class Pending(
        val token: String,
        val center: CleanupCenter,
        val radius: Int,
        val digest: String,
        val expiresAt: Instant,
    )

    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun issue(
        owner: UUID,
        center: CleanupCenter,
        radius: Int,
        digest: String,
    ): CleanupConfirmation {
        val issued =
            Pending(
                token = tokenFactory().uppercase(),
                center = center,
                radius = radius,
                digest = digest,
                expiresAt = clock().plus(ttl),
            )
        pending[owner] = issued
        return CleanupConfirmation(issued.token, issued.expiresAt)
    }

    fun consume(
        owner: UUID,
        center: CleanupCenter,
        radius: Int,
        digest: String,
        token: String,
    ): CleanupConfirmationResult {
        val expected = pending.remove(owner)
            ?: return CleanupConfirmationResult.Rejected("confirmation_not_found")
        if (clock().isAfter(expected.expiresAt)) {
            return CleanupConfirmationResult.Rejected("confirmation_expired")
        }
        if (!expected.token.equals(token, ignoreCase = true)) {
            return CleanupConfirmationResult.Rejected("token_mismatch")
        }
        if (expected.center != center || expected.radius != radius) {
            return CleanupConfirmationResult.Rejected("origin_changed")
        }
        if (expected.digest != digest) {
            return CleanupConfirmationResult.Rejected("world_state_changed")
        }
        return CleanupConfirmationResult.Accepted
    }

    companion object {
        private fun randomToken(): String =
            UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
    }
}

sealed interface FurnitureCleanupInput {
    val radius: Int

    data class Preview(
        override val radius: Int,
    ) : FurnitureCleanupInput

    data class Confirm(
        override val radius: Int,
        val token: String,
    ) : FurnitureCleanupInput

    companion object {
        fun parse(args: Array<String>): FurnitureCleanupInput {
            require(args.firstOrNull()?.equals("cleanup", ignoreCase = true) == true) {
                "expected cleanup action"
            }
            val radius = args.getOrNull(1)?.toIntOrNull()
                ?: throw IllegalArgumentException("radius must be an integer")
            require(radius in 1..FurnitureCleanupPlan.MAX_RADIUS) {
                "radius must be in 1-${FurnitureCleanupPlan.MAX_RADIUS}"
            }
            return when {
                args.size == 2 -> Preview(radius)
                args.size == 4 && args[2].equals("confirm", ignoreCase = true) -> {
                    val token = args[3].trim()
                    require(token.matches(Regex("[A-Za-z0-9]{6}"))) { "confirmation token must contain 6 characters" }
                    Confirm(radius, token.uppercase())
                }
                else -> throw IllegalArgumentException("expected cleanup <radius> [confirm <token>]")
            }
        }
    }
}
