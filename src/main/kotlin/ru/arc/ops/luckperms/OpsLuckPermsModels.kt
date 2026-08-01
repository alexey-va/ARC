package ru.arc.ops.luckperms

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.UUID

enum class LpSubjectType {
    USER,
    GROUP,
}

data class LpSubjectRef(
    val type: LpSubjectType,
    val identifier: String,
) {
    init {
        require(identifier.isNotBlank()) { "LuckPerms subject identifier must not be blank" }
        when (type) {
            LpSubjectType.USER -> UUID.fromString(identifier)
            LpSubjectType.GROUP -> requireSafeGroupName(identifier)
        }
    }
}

class LpContextSet(contexts: Map<String, List<String>> = emptyMap()) {
    private val values: Map<String, List<String>> =
        Collections.unmodifiableMap(
            contexts
                .map { (key, rawValues) ->
                    require(key.isNotBlank()) { "LuckPerms context key must not be blank" }
                    require(rawValues.isNotEmpty()) { "LuckPerms context '$key' must have at least one value" }
                    require(rawValues.none { it.isBlank() }) { "LuckPerms context '$key' must not contain blank values" }
                    require(rawValues.distinct().size == rawValues.size) {
                        "LuckPerms context '$key' must not contain duplicate values"
                    }
                    key to Collections.unmodifiableList(rawValues.sorted())
                }.sortedBy { it.first }
                .toMap(),
        )

    fun asMap(): Map<String, List<String>> = values

    fun canonicalKey(): String =
        values.entries.joinToString(";") { (key, contextValues) ->
            "${canonicalPart(key)}=${contextValues.joinToString(",", transform = ::canonicalPart)}"
        }

    override fun equals(other: Any?): Boolean = other is LpContextSet && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "LpContextSet($values)"
}

sealed interface LpNodeSpec {
    val contexts: LpContextSet
    val expiresAt: Instant?

    fun canonicalKey(): String
}

data class PermissionNodeSpec(
    val permission: String,
    val value: Boolean = true,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
) : LpNodeSpec {
    init {
        require(permission.isNotBlank()) { "LuckPerms permission must not be blank" }
    }

    override fun canonicalKey(): String = canonicalKey("permission", permission, value.toString())
}

data class InheritanceNodeSpec(
    val groupName: String,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
) : LpNodeSpec {
    init {
        requireSafeGroupName(groupName)
    }

    override fun canonicalKey(): String = canonicalKey("inheritance", groupName)
}

data class MetaNodeSpec(
    val key: String,
    val value: String,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
) : LpNodeSpec {
    init {
        require(key.isNotBlank()) { "LuckPerms meta key must not be blank" }
    }

    override fun canonicalKey(): String = canonicalKey("meta", key, value)
}

data class PrefixNodeSpec(
    val priority: Int,
    val value: String,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
) : LpNodeSpec {
    override fun canonicalKey(): String = canonicalKey("prefix", priority.toString(), value)
}

data class SuffixNodeSpec(
    val priority: Int,
    val value: String,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
) : LpNodeSpec {
    override fun canonicalKey(): String = canonicalKey("suffix", priority.toString(), value)
}

data class WeightNodeSpec(
    val weight: Int,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
) : LpNodeSpec {
    override fun canonicalKey(): String = canonicalKey("weight", weight.toString())
}

data class DisplayNameNodeSpec(
    val displayName: String,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
) : LpNodeSpec {
    override fun canonicalKey(): String = canonicalKey("display-name", displayName)
}

enum class LpOperationAction {
    SET,
    UNSET,
}

data class LpOperation(
    val action: LpOperationAction,
    val node: LpNodeSpec,
)

data class LpMutationRequest(
    val subject: LpSubjectRef,
    val operations: List<LpOperation>,
    val requestedAt: Instant = Instant.now(),
) {
    init {
        require(operations.isNotEmpty()) { "LuckPerms mutation must include at least one operation" }
        operations.filter { it.action == LpOperationAction.SET }.forEach { operation ->
            require(operation.node.expiresAt?.isAfter(requestedAt) != false) {
                "LuckPerms set operation cannot use an expired node"
            }
        }
    }
}

data class LpSubjectSnapshot(
    val subject: LpSubjectRef,
    val nodes: List<LpNodeSpec>,
)

data class LpPlan(
    val subject: LpSubjectRef,
    val operations: List<LpOperation>,
)

data class LpApplyResult(
    val subject: LpSubjectRef,
    val applied: List<LpOperation>,
)

private val SAFE_GROUP_NAME = Regex("[a-z0-9][a-z0-9._-]*")

private fun requireSafeGroupName(groupName: String) {
    require(SAFE_GROUP_NAME.matches(groupName)) { "Unsafe LuckPerms group name: $groupName" }
}

private fun LpNodeSpec.canonicalKey(type: String, vararg values: String): String =
    buildString {
        append(type)
        values.forEach { value -> append('|').append(canonicalPart(value)) }
        append("|contexts=").append(contexts.canonicalKey())
        append("|expiresAt=").append(expiresAt?.toString().orEmpty())
    }

private fun canonicalPart(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
