package ru.arc.investigation

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

class InvestigationModuleConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun tearDown() {
        ConfigManager.clear()
    }

    @Test
    fun `disabled investigations do not require or load a story catalog`() {
        val modules = Files.createDirectories(tempDir.resolve("modules"))
        Files.writeString(modules.resolve("investigations.yml"), "enabled: false\n")

        val loaded =
            loadInvestigationRuntimeConfig(tempDir) {
                fail("Story catalog loader must not run while investigations are disabled")
            }

        assertFalse(loaded.config.enabled)
        assertNull(loaded.catalog)
    }

    @Test
    fun `enabled investigations load the catalog before witness validation`() {
        val modules = Files.createDirectories(tempDir.resolve("modules"))
        val bundledConfig =
            requireNotNull(InvestigationModuleConfigTest::class.java.getResourceAsStream("/modules/investigations.yml"))
                .bufferedReader()
                .use { it.readText() }
                .replaceFirst("enabled: false", "enabled: true")
        Files.writeString(modules.resolve("investigations.yml"), bundledConfig)
        val catalog = bundledInvestigationCatalogForTest
        var catalogLoaded = false

        val loaded =
            loadInvestigationRuntimeConfig(tempDir) {
                catalogLoaded = true
                catalog
            }

        assertTrue(catalogLoaded)
        assertTrue(loaded.config.enabled)
        assertSame(catalog, loaded.catalog)
    }
}
