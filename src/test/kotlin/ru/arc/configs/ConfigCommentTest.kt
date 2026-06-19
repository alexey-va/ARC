package ru.arc.configs

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that comments in YAML configs work correctly:
 *  1. setComment() writes a block comment above a key on the next save()
 *  2. Comments already in the YAML file survive a save/reload cycle unchanged
 *  3. Programmatic comments take priority over existing YAML comments for the same key
 *  4. Comments survive across multiple save/reload cycles
 *  5. Nested-key comments are written at the correct indent level
 */
class ConfigCommentTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var config: Config

    @BeforeEach
    fun setUp() {
        ConfigManager.clear()
    }

    @AfterEach
    fun tearDown() {
        ConfigManager.clear()
    }

    private fun makeConfig(initialYaml: String? = null): Config {
        val file = tempDir.resolve("test.yml")
        if (initialYaml != null) file.toFile().writeText(initialYaml)
        else Files.createFile(file)
        return ConfigManager.create(tempDir, "test.yml", "test")
    }

    private fun readFile() = tempDir.resolve("test.yml").toFile().readText()

    // ── 1. setComment() appears above the key after save ──────────────────

    @Test
    fun `setComment writes block comment above key`() {
        config = makeConfig()
        config.setComment("timeout", "How long to wait (in ticks)")
        config.setInt("timeout", 20)
        config.save()

        val content = readFile()
        println("=== output ===\n$content")
        assertTrue(content.contains("# How long to wait (in ticks)"),
            "Expected block comment in output:\n$content")
        assertTrue(content.contains("timeout: 20"))
    }

    // ── 2. Existing YAML comments survive save unchanged ──────────────────

    @Test
    fun `existing YAML comments survive save`() {
        config = makeConfig("""
            # This is a top-level comment
            enabled: true
            # Radius in blocks
            radius: 5
        """.trimIndent())

        // Just save without touching anything
        config.reload()
        config.save()

        val content = readFile()
        println("=== output ===\n$content")
        assertTrue(content.contains("# This is a top-level comment"),
            "Top-level comment lost:\n$content")
        assertTrue(content.contains("# Radius in blocks"),
            "Inline comment lost:\n$content")
    }

    // ── 3. Programmatic comment overrides existing YAML comment ───────────

    @Test
    fun `programmatic comment overrides existing YAML comment`() {
        config = makeConfig("""
            # old comment
            greeting: hello
        """.trimIndent())

        config.reload()
        config.setComment("greeting", "new comment")
        config.save()

        val content = readFile()
        println("=== output ===\n$content")
        assertTrue(content.contains("# new comment"), "New comment not found:\n$content")
        assertTrue(!content.contains("# old comment"), "Old comment still present:\n$content")
    }

    // ── 4. Comments survive multiple save/reload cycles ───────────────────

    @Test
    fun `comments survive multiple save reload cycles`() {
        config = makeConfig()
        config.setComment("port", "Redis port")
        config.setInt("port", 6379)
        config.save()

        // Reload and save again without touching comments
        config.reload()
        config.save()
        config.reload()
        config.save()

        val content = readFile()
        println("=== output ===\n$content")
        assertTrue(content.contains("# Redis port"), "Comment lost after re-save:\n$content")
        assertEquals(6379, config.int("port"))
    }

    // ── 5. Nested key comments ────────────────────────────────────────────

    @Test
    fun `nested key comments appear at correct indent`() {
        config = makeConfig()
        config.setComment("database.host", "Hostname of the database server")
        config.setString("database.host", "localhost")
        config.setComment("database.port", "Port (default: 5432)")
        config.setInt("database.port", 5432)
        config.save()

        val content = readFile()
        println("=== output ===\n$content")
        assertTrue(content.contains("# Hostname of the database server"),
            "Nested comment not found:\n$content")
        assertTrue(content.contains("# Port (default: 5432)"),
            "Second nested comment not found:\n$content")
        assertEquals("localhost", config.string("database.host"))
        assertEquals(5432, config.int("database.port"))
    }

    // ── 6. Comment via accessor parameter ────────────────────────────────

    @Test
    fun `comment parameter on accessor writes comment`() {
        config = makeConfig()
        config.int("max-players", 20, "Maximum number of players allowed")
        config.save()

        val content = readFile()
        println("=== output ===\n$content")
        assertTrue(content.contains("# Maximum number of players allowed"),
            "Comment from accessor parameter not found:\n$content")
        assertTrue(content.contains("max-players: 20"))
    }
}
