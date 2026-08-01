package ru.arc.ops.luckperms

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.util.UUID

object OpsLuckPermsJson {
    fun parseMutation(
        subject: LpSubjectRef,
        body: String,
    ): LpMutationRequest {
        val root = parseObject(body, "mutation")
        root.requireOnly("version", "reason", "operations")
        require(root.requiredInt("version") == 1) { "Unsupported LuckPerms request version" }
        val reason = root.requiredString("reason")
        val operations = root.requiredArray("operations").map(::parseOperation)
        return LpMutationRequest(subject, operations, reason)
    }

    fun parseCheck(body: String): LpPermissionCheckRequest {
        val root = parseObject(body, "permission check")
        root.requireOnly("version", "uuid", "permission", "contexts")
        require(root.requiredInt("version") == 1) { "Unsupported LuckPerms request version" }
        return LpPermissionCheckRequest(
            userId = UUID.fromString(root.requiredString("uuid")),
            permission = root.requiredString("permission"),
            contexts = root.optionalContexts(),
        )
    }

    fun parseApply(body: String): Pair<String, String> {
        val root = parseObject(body, "apply")
        root.requireOnly("version", "reviewToken", "idempotencyKey")
        require(root.requiredInt("version") == 1) { "Unsupported LuckPerms request version" }
        return root.requiredString("reviewToken") to root.requiredString("idempotencyKey")
    }

    fun parseMigration(body: String): LpMigrationRequest {
        val root = parseObject(body, "migration")
        root.requireOnly("version", "id", "reason", "subjects")
        val version = root.requiredInt("version")
        val id = root.requiredString("id")
        val reason = root.requiredString("reason")
        val subjects =
            root.requiredArray("subjects").map { element ->
                val subject = element.asObject("migration subject")
                subject.requireOnly("type", "name", "uuid", "expected_name", "operations")
                val type = subject.requiredString("type")
                val ref =
                    when (type) {
                        "group" -> LpSubjectRef(LpSubjectType.GROUP, subject.requiredString("name"))
                        "user" -> LpSubjectRef(LpSubjectType.USER, subject.requiredString("uuid"))
                        else -> throw IllegalArgumentException("Unsupported LuckPerms subject type: $type")
                    }
                if (type == "group") require(!subject.has("uuid")) { "Group migration subject must not contain uuid" }
                if (type == "user") require(!subject.has("name")) { "User migration subject must use uuid, not name" }
                subject.get("expected_name")?.takeUnless(JsonElement::isJsonNull)?.asString?.let {
                    require(it.isNotBlank()) { "expected_name must not be blank" }
                }
                LpMutationRequest(ref, subject.requiredArray("operations").map(::parseOperation), reason)
            }
        return LpMigrationRequest(version, id, reason, subjects)
    }

    fun snapshotMap(snapshot: LpSubjectSnapshot): Map<String, Any?> =
        mapOf(
            "subject" to subjectMap(snapshot.subject),
            "digest" to snapshotDigest(snapshot),
            "nodes" to snapshot.nodes.sortedBy(LpNodeSpec::canonicalKey).map(::nodeMap),
        )

    fun reviewMap(review: LpReviewPlan): Map<String, Any?> =
        mapOf(
            "version" to 1,
            "reviewToken" to review.reviewToken,
            "liveDigest" to review.liveDigest,
            "planDigest" to review.planDigest,
            "expiresAt" to review.expiresAt.toString(),
            "warnings" to review.warnings,
            "plan" to planMap(review.plan),
        )

    fun applyResultMap(result: LpApplyResult): Map<String, Any?> =
        mapOf(
            "version" to 1,
            "subject" to subjectMap(result.subject),
            "status" to result.status.name,
            "beforeDigest" to result.beforeDigest,
            "afterDigest" to result.afterDigest,
            "applied" to result.applied.map(::operationMap),
            "message" to result.message,
        )

    fun migrationStatusMap(status: LpMigrationStatus): Map<String, Any?> =
        mapOf(
            "version" to 1,
            "jobId" to status.jobId,
            "migrationId" to status.migrationId,
            "contentHash" to status.contentHash,
            "state" to status.state.name,
            "totalSubjects" to status.totalSubjects,
            "completedSubjects" to status.completedSubjects,
            "rollbackCompletedSubjects" to status.rollbackCompletedSubjects,
            "currentSubject" to status.currentSubject?.let(::subjectMap),
            "failures" to status.failures,
        )

    fun checkResultMap(result: LpPermissionCheckResult): Map<String, Any?> =
        mapOf(
            "result" to result.result.name.lowercase(),
            "directMatches" to result.directMatches.map(::nodeMap),
            "inheritedMatches" to
                result.inheritedMatches.map {
                    mapOf("group" to it.group.identifier, "node" to nodeMap(it.node))
                },
        )

    fun operationMap(operation: LpOperation): Map<String, Any?> {
        val base = nodeMap(operation.node).toMutableMap()
        val kind = base.remove("kind") as String
        base["op"] = "${if (operation.action == LpOperationAction.SET) "set" else "unset"}_$kind"
        return base.toSortedMap()
    }

    fun mutationMap(request: LpMutationRequest): Map<String, Any?> =
        mapOf(
            "version" to 1,
            "subject" to subjectMap(request.subject),
            "reason" to request.reason,
            "operations" to request.operations.map(::operationMap),
        )

    fun migrationMap(request: LpMigrationRequest): Map<String, Any?> =
        mapOf(
            "version" to request.version,
            "id" to request.id,
            "reason" to request.reason,
            "subjects" to
                request.subjects.map { subject ->
                    buildMap<String, Any?> {
                        put("type", subject.subject.type.name.lowercase())
                        when (subject.subject.type) {
                            LpSubjectType.GROUP -> put("name", subject.subject.identifier)
                            LpSubjectType.USER -> put("uuid", subject.subject.identifier)
                        }
                        put("operations", subject.operations.map(::operationMap))
                    }
                },
        )

    private fun planMap(plan: LpPlan): Map<String, Any?> =
        mapOf(
            "subject" to subjectMap(plan.subject),
            "reason" to plan.reason,
            "operations" to plan.operations.map(::operationMap),
        )

    private fun subjectMap(ref: LpSubjectRef): Map<String, String> =
        mapOf("type" to ref.type.name.lowercase(), "id" to ref.identifier)

    private fun nodeMap(node: LpNodeSpec): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        when (node) {
            is PermissionNodeSpec -> {
                result["kind"] = "permission"
                result["permission"] = node.permission
            }
            is InheritanceNodeSpec -> {
                result["kind"] = "parent"
                result["group"] = node.groupName
            }
            is MetaNodeSpec -> {
                result["kind"] = "meta"
                result["key"] = node.key
                result["metaValue"] = node.metaValue
            }
            is PrefixNodeSpec -> {
                result["kind"] = "prefix"
                result["priority"] = node.priority
                result["text"] = node.prefix
            }
            is SuffixNodeSpec -> {
                result["kind"] = "suffix"
                result["priority"] = node.priority
                result["text"] = node.suffix
            }
            is WeightNodeSpec -> {
                result["kind"] = "weight"
                result["weight"] = node.weight
            }
            is DisplayNameNodeSpec -> {
                result["kind"] = "display_name"
                result["displayName"] = node.displayName
            }
        }
        result["value"] = node.value
        result["contexts"] = node.contexts.asMap()
        result["expiresAt"] = node.expiresAt?.toString()
        return result
    }

    private fun parseOperation(element: JsonElement): LpOperation {
        val objectValue = element.asObject("operation")
        val op = objectValue.requiredString("op")
        val separator = op.indexOf('_')
        require(separator > 0) { "LuckPerms operation must be set_<kind> or unset_<kind>" }
        val action =
            when (op.substring(0, separator)) {
                "set" -> LpOperationAction.SET
                "unset" -> LpOperationAction.UNSET
                else -> throw IllegalArgumentException("Unsupported LuckPerms operation: $op")
            }
        val kind = op.substring(separator + 1)
        val common = setOf("op", "value", "contexts", "expiresAt")
        val value = objectValue.optionalBoolean("value", true)
        val contexts = objectValue.optionalContexts()
        val expiry = objectValue.optionalString("expiresAt")?.let(Instant::parse)
        val node =
            when (kind) {
                "permission" -> {
                    objectValue.requireOnly(*(common + "permission").toTypedArray())
                    PermissionNodeSpec(objectValue.requiredString("permission"), value, contexts, expiry)
                }
                "parent" -> {
                    objectValue.requireOnly(*(common + "group").toTypedArray())
                    InheritanceNodeSpec(objectValue.requiredString("group"), contexts, expiry, value)
                }
                "meta" -> {
                    objectValue.requireOnly(*(common + setOf("key", "metaValue")).toTypedArray())
                    MetaNodeSpec(
                        objectValue.requiredString("key"),
                        objectValue.requiredString("metaValue"),
                        contexts,
                        expiry,
                        value,
                    )
                }
                "prefix" -> {
                    objectValue.requireOnly(*(common + setOf("priority", "text")).toTypedArray())
                    PrefixNodeSpec(objectValue.requiredInt("priority"), objectValue.requiredString("text"), contexts, expiry, value)
                }
                "suffix" -> {
                    objectValue.requireOnly(*(common + setOf("priority", "text")).toTypedArray())
                    SuffixNodeSpec(objectValue.requiredInt("priority"), objectValue.requiredString("text"), contexts, expiry, value)
                }
                "weight" -> {
                    objectValue.requireOnly(*(common + "weight").toTypedArray())
                    WeightNodeSpec(objectValue.requiredInt("weight"), contexts, expiry, value)
                }
                "display_name" -> {
                    objectValue.requireOnly(*(common + "displayName").toTypedArray())
                    DisplayNameNodeSpec(objectValue.requiredString("displayName"), contexts, expiry, value)
                }
                else -> throw IllegalArgumentException("Unsupported LuckPerms node kind: $kind")
            }
        return LpOperation(action, node)
    }

    private fun parseObject(
        body: String,
        label: String,
    ): JsonObject =
        runCatching { JsonParser.parseString(body).asObject(label) }
            .getOrElse { throw IllegalArgumentException("Invalid LuckPerms $label JSON", it) }

    private fun JsonElement.asObject(label: String): JsonObject {
        require(isJsonObject) { "LuckPerms $label must be a JSON object" }
        return asJsonObject
    }

    private fun JsonObject.requireOnly(vararg allowed: String) {
        val extras = keySet() - allowed.toSet()
        require(extras.isEmpty()) { "Unknown LuckPerms JSON fields: ${extras.sorted().joinToString(",")}" }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.asString?.also {
            require(it.isNotBlank()) { "LuckPerms field '$name' must not be blank" }
        } ?: throw IllegalArgumentException("Missing LuckPerms field '$name'")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.asInt
            ?: throw IllegalArgumentException("Missing LuckPerms field '$name'")

    private fun JsonObject.optionalBoolean(
        name: String,
        default: Boolean,
    ): Boolean = get(name)?.takeUnless(JsonElement::isJsonNull)?.asBoolean ?: default

    private fun JsonObject.requiredArray(name: String): JsonArray =
        get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?: throw IllegalArgumentException("Missing LuckPerms array '$name'")

    private fun JsonObject.optionalContexts(): LpContextSet {
        val element = get("contexts") ?: return LpContextSet()
        require(element.isJsonObject) { "LuckPerms contexts must be an object" }
        val contexts =
            element.asJsonObject.entrySet().associate { (key, values) ->
                require(values.isJsonArray) { "LuckPerms context '$key' must be an array" }
                key to values.asJsonArray.map { it.asString }
            }
        return LpContextSet(contexts)
    }
}
