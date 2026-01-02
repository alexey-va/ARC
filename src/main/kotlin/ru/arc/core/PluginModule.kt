package ru.arc.core

/**
 * Interface for plugin modules with standardized lifecycle.
 * Each module handles a specific feature of the plugin.
 *
 * Lifecycle: init() -> [reload()] -> shutdown()
 */
interface PluginModule {

    /** Unique name of this module for logging and debugging */
    val name: String

    /** Priority for initialization order (lower = earlier). Default is 100. */
    val priority: Int get() = 100

    /** Whether this module is enabled. Disabled modules are skipped. */
    val enabled: Boolean get() = true

    /**
     * Initialize the module. Called once during plugin enable.
     * Should set up listeners, tasks, and load initial data.
     */
    fun init()

    /**
     * Reload the module configuration and data.
     * Called on plugin reload command.
     * Default implementation calls shutdown() then init().
     */
    fun reload() {
        shutdown()
        init()
    }

    /**
     * Shutdown the module. Called during plugin disable.
     * Should cancel tasks, save data, and clean up resources.
     */
    fun shutdown()
}


