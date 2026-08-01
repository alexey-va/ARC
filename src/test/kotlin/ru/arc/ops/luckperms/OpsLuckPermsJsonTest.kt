package ru.arc.ops.luckperms

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class OpsLuckPermsJsonTest : FreeSpec({
    "strict mutation JSON round-trips typed node identity" {
        val ref = LpSubjectRef(LpSubjectType.GROUP, "builder")
        val body =
            """
            {
              "version": 1,
              "reason": "temporary build access",
              "operations": [{
                "op": "set_permission",
                "permission": "worldedit.navigation.jumpto.tool",
                "value": false,
                "contexts": {"server": ["spawn"]},
                "expiresAt": "2099-08-03T12:00:00Z"
              }]
            }
            """.trimIndent()

        val request = OpsLuckPermsJson.parseMutation(ref, body)

        request.subject shouldBe ref
        request.operations.single() shouldBe
            LpOperation(
                LpOperationAction.SET,
                PermissionNodeSpec(
                    "worldedit.navigation.jumpto.tool",
                    false,
                    LpContextSet(mapOf("server" to listOf("spawn"))),
                    Instant.parse("2099-08-03T12:00:00Z"),
                ),
            )
    }

    "unknown fields are rejected" {
        shouldThrow<IllegalArgumentException> {
            OpsLuckPermsJson.parseMutation(
                LpSubjectRef(LpSubjectType.GROUP, "builder"),
                """{"version":1,"reason":"x","operations":[],"command":"lp group clear"}""",
            )
        }
    }

    "JSON scalar types are strict" {
        val ref = LpSubjectRef(LpSubjectType.GROUP, "builder")
        listOf(
            """{"version":"1","reason":"x","operations":[{"op":"set_permission","permission":"a"}]}""",
            """{"version":1,"reason":123,"operations":[{"op":"set_permission","permission":"a"}]}""",
            """{"version":1,"reason":"x","operations":[{"op":"set_permission","permission":"a","value":"false"}]}""",
            """{"version":1,"reason":"x","operations":[{"op":"set_permission","permission":"a","contexts":{"server":[1]}}]}""",
            """{"version":1,"reason":"x","operations":[{"op":"set_prefix","priority":1.5,"text":"x"}]}""",
        ).forEach { body ->
            shouldThrow<IllegalArgumentException> {
                OpsLuckPermsJson.parseMutation(ref, body)
            }
        }
    }

    "group migration rejects user-only expected_name" {
        shouldThrow<IllegalArgumentException> {
            OpsLuckPermsJson.parseMigration(
                """
                {
                  "version":1,
                  "id":"bad-group-info",
                  "reason":"invalid",
                  "subjects":[{
                    "type":"group",
                    "name":"builder",
                    "expected_name":"Steve",
                    "operations":[{"op":"set_permission","permission":"example.node"}]
                  }]
                }
                """.trimIndent(),
            )
        }
    }

    "migration control JSON rejects coerced scalar types" {
        shouldThrow<IllegalArgumentException> {
            OpsLuckPermsJson.parseMigrationApply(
                """{"version":"1","jobId":"job","idempotencyKey":"key"}""",
            )
        }
        shouldThrow<IllegalArgumentException> {
            OpsLuckPermsJson.parseMigrationApply(
                """{"version":1,"jobId":123,"idempotencyKey":"key"}""",
            )
        }
        shouldThrow<IllegalArgumentException> {
            OpsLuckPermsJson.parseMigrationControl(
                """{"version":1,"idempotencyKey":123}""",
            )
        }
    }

    "migration users require UUID and duplicate subjects fail" {
        shouldThrow<IllegalArgumentException> {
            OpsLuckPermsJson.parseMigration(
                """
                {
                  "version":1,
                  "id":"name-only",
                  "reason":"invalid",
                  "subjects":[{
                    "type":"user",
                    "name":"Steve",
                    "operations":[{"op":"set_permission","permission":"example.node"}]
                  }]
                }
                """.trimIndent(),
            )
        }

        shouldThrow<IllegalArgumentException> {
            OpsLuckPermsJson.parseMigration(
                """
                {
                  "version":1,
                  "id":"duplicates",
                  "reason":"invalid",
                  "subjects":[
                    {"type":"group","name":"builder","operations":[{"op":"set_permission","permission":"a"}]},
                    {"type":"group","name":"builder","operations":[{"op":"set_permission","permission":"b"}]}
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    "migration normalization parses back to the same request" {
        val request =
            LpMigrationRequest(
                version = 1,
                id = "round-trip",
                reason = "round trip",
                subjects =
                    listOf(
                        LpMutationRequest(
                            LpSubjectRef(LpSubjectType.GROUP, "builder"),
                            listOf(LpOperation(LpOperationAction.SET, PrefixNodeSpec(100, "<gold>Builder"))),
                            "round trip",
                        ),
                    ),
            )
        val json = com.google.gson.Gson().toJson(OpsLuckPermsJson.migrationMap(request))

        val parsed = OpsLuckPermsJson.parseMigration(json)
        parsed.version shouldBe request.version
        parsed.id shouldBe request.id
        parsed.reason shouldBe request.reason
        parsed.subjects.map { it.subject to it.operations } shouldBe
            request.subjects.map { it.subject to it.operations }
    }

    "migration normalization preserves expected_name as immutable review information" {
        val user =
            LpMutationRequest(
                subject =
                    LpSubjectRef(
                        LpSubjectType.USER,
                        "00000000-0000-0000-0000-000000000123",
                    ),
                operations = listOf(LpOperation(LpOperationAction.SET, PermissionNodeSpec("example.user"))),
                reason = "review username",
                expectedName = "Example",
            )
        val request = LpMigrationRequest(1, "expected-name", "review username", listOf(user))

        val json = com.google.gson.Gson().toJson(OpsLuckPermsJson.migrationMap(request))
        val parsed = OpsLuckPermsJson.parseMigration(json)

        parsed.subjects.single().expectedName shouldBe "Example"
    }

    "all supported operation kinds survive normalized JSON exactly" {
        val contexts = LpContextSet(mapOf("server" to listOf("spawn")))
        val expiresAt = Instant.parse("2099-08-03T12:00:00Z")
        val operations =
            listOf(
                LpOperation(LpOperationAction.SET, PermissionNodeSpec("example.node", false, contexts, expiresAt)),
                LpOperation(LpOperationAction.UNSET, InheritanceNodeSpec("member", contexts, expiresAt)),
                LpOperation(LpOperationAction.SET, MetaNodeSpec("rank", "builder", contexts, expiresAt)),
                LpOperation(LpOperationAction.UNSET, PrefixNodeSpec(100, "<gold>Builder", contexts, expiresAt)),
                LpOperation(LpOperationAction.SET, SuffixNodeSpec(50, "<gray>[B]", contexts, expiresAt)),
                LpOperation(LpOperationAction.UNSET, WeightNodeSpec(100, contexts, expiresAt)),
                LpOperation(LpOperationAction.SET, DisplayNameNodeSpec("Билдер", contexts, expiresAt)),
            )
        val body =
            com.google.gson.Gson().toJson(
                mapOf(
                    "version" to 1,
                    "reason" to "round-trip all node kinds",
                    "operations" to operations.map(OpsLuckPermsJson::operationMap),
                ),
            )

        OpsLuckPermsJson
            .parseMutation(LpSubjectRef(LpSubjectType.GROUP, "builder"), body)
            .operations shouldBe operations
    }

    "duplicate or conflicting operations for one exact node are rejected" {
        val node = PermissionNodeSpec("example.node")

        shouldThrow<IllegalArgumentException> {
            LpMutationRequest(
                subject = LpSubjectRef(LpSubjectType.GROUP, "builder"),
                operations =
                    listOf(
                        LpOperation(LpOperationAction.SET, node),
                        LpOperation(LpOperationAction.UNSET, node),
                    ),
                reason = "contradictory request",
            )
        }
    }
})
