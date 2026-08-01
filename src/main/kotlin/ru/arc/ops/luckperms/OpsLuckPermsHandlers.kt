package ru.arc.ops.luckperms

import ru.arc.ARC
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class OpsLuckPermsHandlers(
    private val gateway: LuckPermsSubjectGateway,
    private val applyService: LuckPermsApplyService,
    private val migrations: LuckPermsMigrationService,
) {
    fun groups(): Map<String, Any?> {
        val groups = gateway.listGroups().join()
        return mapOf(
            "version" to 1,
            "server" to ARC.serverName,
            "digest" to groupDigest(groups),
            "groups" to groups.associate { it.subject.identifier to OpsLuckPermsJson.snapshotMap(it) },
        )
    }

    fun group(name: String): Map<String, Any?> =
        gateway.get(LpSubjectRef(LpSubjectType.GROUP, name)).join()?.let(OpsLuckPermsJson::snapshotMap)
            ?: throw NoSuchElementException("Unknown LuckPerms group: $name")

    fun user(uuid: String): Map<String, Any?> =
        gateway.get(LpSubjectRef(LpSubjectType.USER, uuid)).join()?.let(OpsLuckPermsJson::snapshotMap)
            ?: throw NoSuchElementException("Unknown LuckPerms user UUID: $uuid")

    fun userLookup(name: String): Map<String, Any?> =
        gateway.lookupUser(name).join()?.let {
            mapOf("uuid" to it.uuid.toString(), "username" to it.username)
        } ?: throw NoSuchElementException("Unknown LuckPerms username: $name")

    fun check(body: String): Map<String, Any?> =
        gateway.check(OpsLuckPermsJson.parseCheck(body)).join()?.let(OpsLuckPermsJson::checkResultMap)
            ?: throw NoSuchElementException("Unknown LuckPerms user")

    fun preview(
        ref: LpSubjectRef,
        body: String,
    ): Map<String, Any?> =
        OpsLuckPermsJson.reviewMap(applyService.preview(OpsLuckPermsJson.parseMutation(ref, body)).join())

    fun apply(
        ref: LpSubjectRef,
        body: String,
    ): Map<String, Any?> {
        val (token, key) = OpsLuckPermsJson.parseApply(body)
        return OpsLuckPermsJson.applyResultMap(applyService.apply(token, key, ref).join())
    }

    fun migrationPreview(body: String): Map<String, Any?> =
        OpsLuckPermsJson.migrationStatusMap(migrations.previewMigration(OpsLuckPermsJson.parseMigration(body)).join())

    fun migrationApply(
        jobId: String,
        idempotencyKey: String,
    ): Map<String, Any?> =
        OpsLuckPermsJson.migrationStatusMap(migrations.startMigration(jobId, idempotencyKey))

    fun migrationStatus(jobId: String): Map<String, Any?> =
        OpsLuckPermsJson.migrationStatusMap(migrations.status(jobId))

    fun migrationRollback(
        jobId: String,
        idempotencyKey: String,
    ): Map<String, Any?> =
        OpsLuckPermsJson.migrationStatusMap(migrations.rollbackMigration(jobId, idempotencyKey))

    companion object {
        @Volatile
        private var runtime: OpsLuckPermsHandlers? = null

        fun current(): OpsLuckPermsHandlers =
            runtime
                ?: synchronized(this) {
                    runtime
                        ?: run {
                            val gateway = NativeLuckPermsSubjectGateway()
                            val reviews = LuckPermsReviewStore()
                            val apply = LuckPermsApplyService(gateway, reviews) { ARC.serverName }
                            val store =
                                LuckPermsMigrationStore(
                                    ARC.instance.dataPath.resolve("data/permission-migrations"),
                                )
                            OpsLuckPermsHandlers(gateway, apply, LuckPermsMigrationService(apply, store)).also {
                                runtime = it
                            }
                        }
                }

        fun resetForTest() {
            runtime = null
        }
    }
}

private fun groupDigest(groups: List<LpSubjectSnapshot>): String {
    val canonical =
        groups
            .sortedBy { it.subject.identifier }
            .joinToString("\n") { snapshot -> "${snapshot.subject.identifier}:${snapshotDigest(snapshot)}" }
    return "sha256:" +
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
