package ru.arc.ops

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * SHA-256 hashes for config-like files under the server root (no DB/runtime junk).
 */
object OpsConfigHasher {
    private val allowedExtensions = setOf("yml", "yaml", "json", "properties", "toml", "dsc", "dscc", "conf")
    private val blockedPathSegments =
        setOf(
            "backups",
            "balance-history",
            "userdata",
            "playerdata",
            "cache",
            "plots",
            "schematics",
            ".archive-unpack",
            "sessions",
            "DataSyncer",
            "FileBackups",
            "sellLogs",
            "player_flags",
            "Saves",
            "advancements",
            "stats",
        )
    private val blockedExtensions = setOf("db", "sqlite", "sqlite3", "mv", "jar", "zip", "log", "lck")
    private const val maxPaths = 50
    private const val maxFileBytes = 2L * 1024 * 1024

    fun hashPaths(
        serverRoot: Path,
        paths: List<String>,
    ): Map<String, Any?> {
        if (paths.isEmpty()) {
            return mapOf("error" to "Query param path or prefix required", "example" to "/ops/config/hash?path=plugins/ARC/modules/farms.yml")
        }
        if (paths.size > maxPaths) {
            return mapOf("error" to "Too many paths (max $maxPaths)")
        }

        val results = mutableListOf<Map<String, Any?>>()
        val errors = mutableListOf<String>()

        for (raw in paths.distinct()) {
            val normalized = normalizeRelative(raw)
            if (normalized == null) {
                errors += "invalid path: $raw"
                continue
            }
        val file = serverRoot.resolve(normalized).normalize().toAbsolutePath()
        if (!file.startsWith(serverRoot)) {
                errors += "outside server root: $raw"
                continue
            }
            if (!Files.isRegularFile(file)) {
                errors += "not a file: $raw"
                continue
            }
            if (!isHashable(file)) {
                errors += "not hashable (blocked type/path): $raw"
                continue
            }
            results +=
                mapOf(
                    "path" to normalized,
                    "sha256" to sha256(file),
                    "bytes" to Files.size(file),
                    "modified" to Files.getLastModifiedTime(file).toMillis(),
                )
        }

        return mapOf(
            "count" to results.size,
            "files" to results,
            "errors" to errors,
        )
    }

    fun hashPrefixes(
        serverRoot: Path,
        prefixes: List<String>,
        limit: Int = 200,
    ): Map<String, Any?> {
        if (prefixes.isEmpty()) {
            return mapOf(
                "error" to "Query param prefix required",
                "examples" to listOf("plugins/ARC/modules", "plugins/Denizen/scripts", "plugins/Bank"),
            )
        }

        val cap = limit.coerceIn(1, 500)
        val files = linkedMapOf<String, Map<String, Any?>>()

        for (rawPrefix in prefixes.distinct()) {
            val prefix = normalizeRelative(rawPrefix)?.trimEnd('/') ?: continue
            val dir = serverRoot.resolve(prefix).normalize().toAbsolutePath()
            if (!dir.startsWith(serverRoot) || !Files.isDirectory(dir)) continue

            Files.walk(dir).use { stream ->
                stream
                    .filter { it.isRegularFile() && isHashable(it) }
                    .limit(cap.toLong())
                    .forEach { path ->
                        val rel = serverRoot.relativize(path).toString().replace('\\', '/')
                        if (files.size >= cap) return@forEach
                        files[rel] =
                            mapOf(
                                "sha256" to sha256(path),
                                "bytes" to Files.size(path),
                                "modified" to Files.getLastModifiedTime(path).toMillis(),
                            )
                    }
            }
            if (files.size >= cap) break
        }

        return mapOf("count" to files.size, "files" to files)
    }

    internal fun isHashable(path: Path): Boolean {
        val ext = path.extension.lowercase()
        if (ext in blockedExtensions) return false
        if (ext !in allowedExtensions) return false
        for (segment in path) {
            if (segment.name in blockedPathSegments) return false
        }
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        if (size > maxFileBytes) return false
        return true
    }

    internal fun normalizeRelative(raw: String): String? {
        val trimmed = raw.trim().removePrefix("/")
        if (trimmed.isEmpty() || trimmed.contains("..")) return null
        return trimmed.replace('\\', '/')
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
