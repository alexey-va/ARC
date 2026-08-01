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
                    require(key.length <= MAX_CONTEXT_PART_LENGTH) {
                        "LuckPerms context key '$key' exceeds $MAX_CONTEXT_PART_LENGTH characters"
                    }
                    require(rawValues.isNotEmpty()) { "LuckPerms context '$key' must have at least one value" }
                    require(rawValues.none { it.isBlank() }) { "LuckPerms context '$key' must not contain blank values" }
                    require(rawValues.none { it.length > MAX_CONTEXT_PART_LENGTH }) {
                        "LuckPerms context '$key' contains a value exceeding $MAX_CONTEXT_PART_LENGTH characters"
                    }
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
    val value: Boolean
    val contexts: LpContextSet
    val expiresAt: Instant?

    fun canonicalKey(): String
}

data class PermissionNodeSpec(
    val permission: String,
    override val value: Boolean = true,
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
    override val value: Boolean = true,
) : LpNodeSpec {
    init {
        requireSafeGroupName(groupName)
    }

    override fun canonicalKey(): String = canonicalKey("inheritance", groupName, value.toString())
}

data class MetaNodeSpec(
    val key: String,
    val metaValue: String,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
    override val value: Boolean = true,
) : LpNodeSpec {
    init {
        require(key.isNotBlank()) { "LuckPerms meta key must not be blank" }
        require(metaValue.isNotBlank()) { "LuckPerms meta value must not be blank" }
    }

    override fun canonicalKey(): String = canonicalKey("meta", key, metaValue, value.toString())
}

data class PrefixNodeSpec(
    val priority: Int,
    val prefix: String,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
    override val value: Boolean = true,
) : LpNodeSpec {
    init {
        require(prefix.isNotBlank()) { "LuckPerms prefix must not be blank" }
    }

    override fun canonicalKey(): String = canonicalKey("prefix", priority.toString(), prefix, value.toString())
}

data class SuffixNodeSpec(
    val priority: Int,
    val suffix: String,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
    override val value: Boolean = true,
) : LpNodeSpec {
    init {
        require(suffix.isNotBlank()) { "LuckPerms suffix must not be blank" }
    }

    override fun canonicalKey(): String = canonicalKey("suffix", priority.toString(), suffix, value.toString())
}

data class WeightNodeSpec(
    val weight: Int,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
    override val value: Boolean = true,
) : LpNodeSpec {
    override fun canonicalKey(): String = canonicalKey("weight", weight.toString(), value.toString())
}

data class DisplayNameNodeSpec(
    val displayName: String,
    override val contexts: LpContextSet = LpContextSet(),
    override val expiresAt: Instant? = null,
    override val value: Boolean = true,
) : LpNodeSpec {
    init {
        require(displayName.isNotBlank()) { "LuckPerms display name must not be blank" }
    }

    override fun canonicalKey(): String = canonicalKey("display-name", displayName, value.toString())
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
    val reason: String,
    val expectedName: String? = null,
) {
    init {
        require(operations.isNotEmpty()) { "LuckPerms mutation must include at least one operation" }
        require(reason.isNotBlank()) { "LuckPerms mutation reason must not be blank" }
        if (expectedName != null) {
            require(subject.type == LpSubjectType.USER) { "expected_name is valid only for LuckPerms users" }
            require(expectedName.isNotBlank()) { "expected_name must not be blank" }
        }
        require(operations.map { it.node.canonicalKey() }.distinct().size == operations.size) {
            "LuckPerms mutation must not contain duplicate or conflicting operations for one exact node"
        }
    }
}

data class LpSubjectSnapshot(
    val subject: LpSubjectRef,
    val nodes: List<LpNodeSpec>,
    val inheritedGroups: List<LpSubjectRef> = emptyList(),
)

data class LpPlan(
    val subject: LpSubjectRef,
    val operations: List<LpOperation>,
    val reason: String,
)

data class LpReviewPlan(
    val reviewToken: String,
    val liveDigest: String,
    val planDigest: String,
    val plan: LpPlan,
    val warnings: List<String>,
    val expiresAt: Instant,
)

enum class LpApplyStatus {
    VERIFIED,
    ROLLED_BACK,
    PARTIAL_FATAL,
}

data class LpApplyResult(
    val subject: LpSubjectRef,
    val status: LpApplyStatus,
    val applied: List<LpOperation>,
    val beforeDigest: String,
    val afterDigest: String,
    val message: String? = null,
)

data class LpMigrationRequest(
    val version: Int,
    val id: String,
    val reason: String,
    val subjects: List<LpMutationRequest>,
) {
    init {
        require(version == 1) { "Unsupported LuckPerms migration version: $version" }
        require(SAFE_MIGRATION_ID.matches(id)) { "Unsafe LuckPerms migration id: $id" }
        require(reason.isNotBlank()) { "LuckPerms migration reason must not be blank" }
        require(subjects.isNotEmpty()) { "LuckPerms migration must include at least one subject" }
        require(subjects.map { it.subject }.distinct().size == subjects.size) {
            "LuckPerms migration must not contain duplicate subjects"
        }
    }
}

enum class LpMigrationState {
    PREVIEWING,
    PREVIEW_FAILED,
    READY,
    APPLYING,
    VERIFIED,
    ROLLING_BACK,
    ROLLED_BACK,
    RECOVERY_REQUIRED,
    PARTIAL_FATAL,
}

enum class LpMigrationRecoveryPhase {
    APPLY,
    ROLLBACK,
}

data class LpMigrationStatus(
    val jobId: String,
    val migrationId: String,
    val contentHash: String,
    val state: LpMigrationState,
    val totalSubjects: Int,
    val completedSubjects: Int,
    val rollbackCompletedSubjects: Int = 0,
    val currentSubject: LpSubjectRef? = null,
    val failures: List<String> = emptyList(),
)

class LpReviewTokenException(message: String) : IllegalStateException(message)

class LpStaleReviewException(message: String) : IllegalStateException(message)

class LpConcurrentApplyException(message: String) : IllegalStateException(message)

class LpWriteGateException(message: String) : IllegalStateException(message)

private val SAFE_GROUP_NAME = Regex("[a-z0-9][a-z0-9._-]*")
private val SAFE_MIGRATION_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*")
private const val MAX_CONTEXT_PART_LENGTH = 64

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
