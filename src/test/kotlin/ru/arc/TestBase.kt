package ru.arc

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
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
            }
        } catch (e: Exception) {
            // If loading fails due to missing dependencies, create a minimal setup
            // We'll skip plugin-dependent tests in this case
            ARC.plugin = null
            null
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

