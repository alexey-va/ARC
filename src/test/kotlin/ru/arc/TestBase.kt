package ru.arc

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

abstract class TestBase {

    companion object {
        init {
            // Disable Loki appender in tests to avoid OOM from direct buffer allocation
            Logging.disableLokiAppender = true
        }
    }

    protected lateinit var server: ServerMock
    protected var plugin: ARC? = null
    protected lateinit var dataPath: Path

    @BeforeEach
    protected open fun setUpBase() {
        server = MockBukkit.mock()

        // Create a temporary directory for plugin data
        dataPath = Files.createTempDirectory("arc-test")

        // Set server name first
        ARC.serverName = "test-server"

        // Try to load the plugin with MockBukkit (now that ARC is not final)
        // This might fail due to missing compileOnly dependencies, so we'll handle that
        plugin = try {
            MockBukkit.load(ARC::class.java).also {
                ARC.plugin = it
                // Create mock data files after plugin is loaded
                createMockDataFiles(it.dataFolder)
            }
        } catch (e: Exception) {
            // If loading fails due to missing dependencies, create a minimal setup
            // We'll skip plugin-dependent tests in this case
            ARC.plugin = null
            null
        }
    }

    /**
     * Creates mock data files needed for tests.
     * Override this method in subclasses to add additional files.
     */
    protected open fun createMockDataFiles(dataFolder: File) {
        // Create schematics folder and copy test schematics
        val schematicsDir = File(dataFolder, "schematics")
        schematicsDir.mkdirs()
        copyTestResource("amogus_1.schem", File(schematicsDir, "amogus_1.schem"))

        // Create common config files with minimal content
        createConfigFile(
            dataFolder, "logging.yml", """
            enabled: false
            host: localhost
            port: 3100
            labels: {}
        """.trimIndent()
        )

        createConfigFile(dataFolder, "announce.yml", "announcements: []")
        createConfigFile(dataFolder, "board.yml", "boards: {}")
        createConfigFile(dataFolder, "farms.yml", "farms: {}")
        createConfigFile(dataFolder, "auction.yml", "enabled: false")
        createConfigFile(dataFolder, "treasure-hunt.yml", "hunts: {}")

        // Create subdirectories
        File(dataFolder, "lootchests").mkdirs()
        File(dataFolder, "stocks").mkdirs()
        File(dataFolder, "treasures").mkdirs()
    }

    /**
     * Helper to create a config file with content.
     */
    protected fun createConfigFile(dataFolder: File, name: String, content: String) {
        val file = File(dataFolder, name)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    /**
     * Creates a mock schematic file for testing.
     * Note: This is an empty file and won't work with WorldEdit's ClipboardFormats.
     * For tests that need real schematics, use copyTestResource.
     */
    protected fun createMockSchematic(dataFolder: File, name: String) {
        val schematicsDir = File(dataFolder, "schematics")
        schematicsDir.mkdirs()
        File(schematicsDir, name).createNewFile()
    }

    /**
     * Copies a file from test resources to a destination file.
     */
    protected fun copyTestResource(resourceName: String, destination: File) {
        val resourceStream = javaClass.classLoader.getResourceAsStream(resourceName)
        if (resourceStream != null) {
            destination.parentFile?.mkdirs()
            resourceStream.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    @AfterEach
    fun tearDownBase() {
        try {
            plugin?.onDisable()
        } catch (e: Exception) {
            // Ignore disable errors
        }
        MockBukkit.unmock()
        ARC.plugin = null
        ARC.serverName = null
    }
}

