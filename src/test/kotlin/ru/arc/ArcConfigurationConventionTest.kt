package ru.arc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.io.File
import java.util.zip.ZipFile

class ArcConfigurationConventionTest {
    private companion object {
        const val STATIC_PERMISSION_PATTERN = "arc\\.[a-z0-9]+(?:-[a-z0-9]+)*(?:\\.[a-z0-9]+(?:-[a-z0-9]+)*)*"
    }

    @Test
    fun `feature yaml resources live below named directories`() {
        val resourceRoot = Path.of("src", "main", "resources")
        val rootYaml =
            Files.list(resourceRoot).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .map { it.fileName.toString() }
                    .filter { it.endsWith(".yml") || it.endsWith(".yaml") }
                    .sorted()
                    .toList()
            }

        assertEquals(listOf("plugin.yml"), rootYaml)
    }

    @Test
    fun `static ARC permissions use dotted lowercase segments with internal hyphens`() {
        val mainRoot = Path.of("src", "main")
        val permissionToken = Regex("arc\\.[a-z0-9_.-]+")
        val canonicalPermission = Regex(STATIC_PERMISSION_PATTERN)
        val kotlinPermissionMarker =
            Regex(
                "hasPermission|defaultPermission|permission\\s*[=:]|permission\\(|permissionPrefix|PERMISSION",
                RegexOption.IGNORE_CASE,
            )
        val offenders = mutableListOf<String>()

        Files.walk(mainRoot).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".yml") }
                .forEach { path ->
                    Files.readAllLines(path).forEachIndexed { index, line ->
                        val inspectLine = path.toString().endsWith(".yml") || kotlinPermissionMarker.containsMatchIn(line)
                        if (!inspectLine) return@forEachIndexed

                        permissionToken.findAll(line).forEach { match ->
                            val candidate = match.value.trimEnd('.')
                            if (!canonicalPermission.matches(candidate)) {
                                offenders += "${path}:${index + 1}:$candidate"
                            }
                        }
                    }
                }
        }

        assertTrue(
            offenders.isEmpty(),
            "ARC permission nodes must use only dot-separated lowercase segments:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `deprecated ARC permission names are absent from runtime sources`() {
        val deprecated =
            setOf(
                "arc.admin.givejobsboost",
                "arc.baltop",
                "arc.board-announce",
                "arc.boost.large",
                "arc.buildertools",
                "arc.buildings.bypass-cooldown",
                "arc.buildings.build",
                "arc.bypass-invulnerable",
                "arc.bypass-portal",
                "arc.chat-notify",
                "arc.command.buildbook",
                "arc.deconstruction",
                "arc.eliteloot",
                "arc.give",
                "arc.hide",
                "arc.items-catalog",
                "arc.jobsboosts",
                "arc.join-message-gui",
                "arc.leafdecay.bypass",
                "arc.locpool.admin",
                "arc.portal.origin-gate",
                "arc.portal.tp-by-other",
                "arc.portal.tp-other",
                "arc.pouch",
                "arc.rate-own",
                "arc.rtp-respawn",
                "arc.sound-follow",
                "arc.stocks.prunehistory",
                "arc.stocks.update-images",
                "arc.treasure-hunt",
                "arc.treasures.admin",
            )
        val offenders = mutableListOf<String>()

        Files.walk(Path.of("src", "main")).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".yml") }
                .forEach { path ->
                    val text = Files.readString(path)
                    deprecated.forEach { permission ->
                        val token = Regex("(?<![a-z0-9_.-])${Regex.escape(permission)}(?![a-z0-9_.-])")
                        if (token.containsMatchIn(text)) offenders += "${path}:$permission"
                    }
                }
        }

        assertTrue(
            offenders.isEmpty(),
            "Deprecated ARC permission nodes remain in runtime sources:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `explicit static ARC permissions are declared with defaults`() {
        val mainRoot = Path.of("src", "main")
        val pluginPath = mainRoot.resolve(Path.of("resources", "plugin.yml"))
        val descriptors = mutableMapOf(pluginPath.toString() to Files.readAllLines(pluginPath))
        val domainPluginJars = requireNotNull(System.getProperty("arc.domainPluginJars")) {
            "The test task must supply the extracted domain plugin artifacts"
        }
        domainPluginJars.split(File.pathSeparator).forEach { jarPath ->
            ZipFile(jarPath).use { jar ->
                val descriptor = requireNotNull(jar.getEntry("plugin.yml")) { "$jarPath has no plugin.yml" }
                descriptors[jarPath] = jar.getInputStream(descriptor).bufferedReader().use { it.readLines() }
            }
        }
        val declaration = Regex("^  ($STATIC_PERMISSION_PATTERN):$")
        val declared = mutableSetOf<String>()
        val missingDefaults = mutableListOf<String>()
        descriptors.forEach { (descriptor, pluginLines) ->
            val declarationIndexes =
                pluginLines.mapIndexedNotNull { index, line ->
                    declaration.matchEntire(line)?.groupValues?.get(1)?.let { index to it }
                }
            declarationIndexes.forEachIndexed { position, (start, permission) ->
                declared += permission
                val end = declarationIndexes.getOrNull(position + 1)?.first ?: pluginLines.size
                if (pluginLines.subList(start + 1, end).none { it.startsWith("    default:") }) {
                    missingDefaults += "$descriptor:$permission"
                }
            }
        }

        val kotlinLiteral = Regex("\\\"($STATIC_PERMISSION_PATTERN)\\\"")
        val yamlToken = Regex(STATIC_PERMISSION_PATTERN)
        val kotlinPermissionMarker =
            Regex(
                "hasPermission|defaultPermission|permission\\s*[=:]|permission\\(|permissionPrefix|PERMISSION",
                RegexOption.IGNORE_CASE,
            )
        val yamlPermissionEntry = Regex("^\\s*-\\s+arc\\.")
        val referenced = mutableSetOf<String>()
        Files.walk(mainRoot).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it != pluginPath }
                .forEach { path ->
                    Files.readAllLines(path).forEach { line ->
                        when {
                            path.toString().endsWith(".yml") &&
                                !line.trimStart().startsWith("#") &&
                                (line.contains("permission", ignoreCase = true) || yamlPermissionEntry.containsMatchIn(line)) ->
                                yamlToken.findAll(line).forEach { referenced += it.value }
                            path.toString().endsWith(".kt") && kotlinPermissionMarker.containsMatchIn(line) ->
                                kotlinLiteral.findAll(line).forEach { referenced += it.groupValues[1] }
                        }
                    }
                }
        }

        assertTrue(
            missingDefaults.isEmpty(),
            "ARC plugin descriptors have permissions without an explicit default: ${missingDefaults.sorted()}",
        )
        assertTrue(
            declared.containsAll(referenced),
            "ARC and domain plugin descriptors are missing static permissions: ${(referenced - declared).sorted()}",
        )
    }

    @Test
    fun `particle builders are queued before they are spawned`() {
        val eagerQueueCall = Regex("ParticleManager\\.queue(?:Sync)?\\(.{0,1200}?\\.spawn\\(\\)\\)")
        val offenders = mutableListOf<Path>()

        Files.walk(Path.of("src", "main", "kotlin")).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.toString().endsWith(".kt") }
                .forEach { path ->
                    val compact = Files.readString(path).replace(Regex("\\s+"), "")
                    if (eagerQueueCall.containsMatchIn(compact)) offenders.add(path)
                }
        }

        assertTrue(
            offenders.isEmpty(),
            "ParticleManager owns spawn timing; do not call spawn() before queueing:\n${offenders.joinToString("\n")}",
        )
    }
}
