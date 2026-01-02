package ru.arc

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.inventory.ItemStackMock
import ru.arc.autobuild.ClipboardLoaders
import ru.arc.autobuild.MockClipboardLoader
import ru.arc.gui.GuiItems
import ru.arc.gui.MockGuiItemFactory
import ru.arc.util.ItemStackFactory
import ru.arc.util.Logging
import java.io.File
import java.nio.file.Path

abstract class TestBase {

    companion object {
        private val mockGuiItemFactory = MockGuiItemFactory()
        private val mockClipboardLoader = MockClipboardLoader()
        
        init {
            // Disable Loki appender in tests to avoid OOM from direct buffer allocation
            Logging.disableLokiAppender = true

            // Reduce console spam in tests - only show warnings and errors
            Logging.quietMode = false

            // Use MockBukkit's ItemStackMock instead of real ItemStack to avoid Paper ClassLoader issues
            ItemStackFactory.factory = { material, amount -> ItemStackMock(material, amount) }

            // Use mock GuiItem factory to avoid Paper ClassLoader issues with InventoryFramework
            GuiItems.factory = mockGuiItemFactory

            // Use mock ClipboardLoader to avoid WorldEdit platform initialization issues
            ClipboardLoaders.loader = mockClipboardLoader
        }
    }

    protected lateinit var server: ServerMock
    protected lateinit var plugin: ARC
    protected lateinit var dataPath: Path

    @BeforeEach
    protected open fun setUpBase() {
        // Ensure clean state before mocking
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock()
        }
        ru.arc.configs.ConfigManager.clear()
        
        server = MockBukkit.mock()

        // Set server name first
        ARC.serverName = "test-server"

        // Load the plugin - must succeed for tests to run
        plugin = MockBukkit.load(ARC::class.java)
        ARC.plugin = plugin
        dataPath = plugin.dataFolder.toPath()

        // Copy test schematics after plugin is loaded
        createMockDataFiles(plugin.dataFolder)
    }

    /**
     * Creates mock data files needed for tests.
     * Config files are created by the plugin in onLoad().
     * This method only creates test-specific files like schematics.
     */
    protected open fun createMockDataFiles(dataFolder: File) {
        // Create schematics folder and copy test schematics
        val schematicsDir = File(dataFolder, "schematics")
        schematicsDir.mkdirs()
        copyTestResource("amogus_1.schem", File(schematicsDir, "amogus_1.schem"))

        // Create subdirectories
        File(dataFolder, "lootchests").mkdirs()
        File(dataFolder, "stocks").mkdirs()
        File(dataFolder, "treasures").mkdirs()

        // Create empty lang.json to suppress warning
        File(dataFolder, "lang.json").writeText("{}")

        // Fix announce.yml if it has wrong format (Array instead of Map)
        val announceFile = File(dataFolder, "announce.yml")
        if (announceFile.exists()) {
            val content = announceFile.readText()
            if (content.contains("messages: []")) {
                announceFile.writeText(content.replace("messages: []", "messages: {}"))
            }
        }
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
            plugin.onDisable()
        } catch (e: Exception) {
            // Ignore disable errors
        }
        MockBukkit.unmock()
        ARC.plugin = null
        ARC.serverName = null
        // Clear cached configs to ensure clean state for next test
        ru.arc.configs.ConfigManager.clear()
        // Clear GUI backgrounds cache (may contain test mocks)
        ru.arc.util.GuiUtils.clearBackgrounds()
        // Clear mock GUI factory state
        mockGuiItemFactory.clear()
        // Clear mock clipboard loader state
        mockClipboardLoader.clear()
    }
}
