package ru.arc.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import java.nio.file.Files
import kotlin.io.path.writeText

class OpsConfigHasherTest : FreeSpec({

    "OpsConfigHasher" - {
        "should hash allowed yaml file" {
            val dir = Files.createTempDirectory("ops-hash")
            val file = dir.resolve("plugins/ARC/modules/test.yml")
            Files.createDirectories(file.parent)
            file.writeText("enabled: true\n")
            val rel = dir.relativize(file).toString().replace('\\', '/')

            val result = OpsConfigHasher.hashPaths(dir, listOf(rel))
            val files = result["files"] as List<*>
            files.size shouldBe 1
            val entry = files.first() as Map<*, *>
            (entry["sha256"] as String).shouldHaveLength(64)
        }

        "should reject path traversal" {
            val dir = Files.createTempDirectory("ops-hash-bad")
            val result = OpsConfigHasher.hashPaths(dir, listOf("../etc/passwd"))
            val errors = result["errors"] as List<*>
            errors shouldContain "invalid path: ../etc/passwd"
        }

        "should reject sqlite files" {
            val dir = Files.createTempDirectory("ops-hash-db")
            val file = dir.resolve("plugins/Bank/data.db")
            Files.createDirectories(file.parent)
            Files.writeString(file, "binary")
            val rel = dir.relativize(file).toString().replace('\\', '/')

            val result = OpsConfigHasher.hashPaths(dir, listOf(rel))
            val errors = result["errors"] as List<*>
            errors.any { it.toString().contains("not hashable") } shouldBe true
        }

        "should filter log buffer by grep" {
            OpsLogBuffer.clear()
            OpsLogBuffer.resize(10)
            OpsLogBuffer.append("ERROR", "StoreModule failed for player")
            OpsLogBuffer.append("WARN", "Redis reconnect")

            val filtered = OpsHttpHandlers.errors(limit = 10, grep = "storemodule", sinceMs = null)
            val entries = filtered["entries"] as List<*>
            entries.size shouldBe 1
        }
    }
})
