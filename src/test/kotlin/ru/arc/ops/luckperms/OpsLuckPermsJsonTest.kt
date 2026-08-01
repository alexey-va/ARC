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
                "expiresAt": "2026-08-03T12:00:00Z"
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
                    Instant.parse("2026-08-03T12:00:00Z"),
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
})
