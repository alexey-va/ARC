package ru.arc.core

import ru.arc.util.Logging.error
import ru.arc.util.Logging.info

/**
 * Central registry for all plugin modules.
 * Handles lifecycle management: initialization, reload, and shutdown.
 */
object ModuleRegistry {

    private val modules = mutableListOf<PluginModule>()
    private var initialized = false

    /**
     * Register a module. Must be called before init().
     */
    fun register(module: PluginModule) {
        if (initialized) {
            error("Cannot register module '{}' after initialization", module.name)
            return
        }
        modules.add(module)
    }

    /**
     * Register multiple modules at once.
     */
    fun registerAll(vararg modulesToRegister: PluginModule) {
        modulesToRegister.forEach { register(it) }
    }

    /**
     * Initialize all registered modules in priority order.
     * Lower priority values are initialized first.
     */
    fun initAll() {
        if (initialized) {
            error("ModuleRegistry already initialized")
            return
        }

        val sorted = modules
            .filter { it.enabled }
            .sortedBy { it.priority }

        info("Initializing {} modules...", sorted.size)

        for (module in sorted) {
            try {
                info("  → {}", module.name)
                module.init()
            } catch (e: Exception) {
                error("Failed to initialize module '{}'", module.name, e)
            }
        }

        initialized = true
        info("All modules initialized")
    }

    /**
     * Reload all modules.
     */
    fun reloadAll() {
        info("Reloading {} modules...", modules.size)

        for (module in modules.filter { it.enabled }) {
            try {
                info("  ↻ {}", module.name)
                module.reload()
            } catch (e: Exception) {
                error("Failed to reload module '{}'", module.name, e)
            }
        }

        info("All modules reloaded")
    }

    /**
     * Shutdown all modules in reverse priority order.
     * Higher priority values are shut down first.
     */
    fun shutdownAll() {
        val sorted = modules
            .filter { it.enabled }
            .sortedByDescending { it.priority }

        info("Shutting down {} modules...", sorted.size)

        for (module in sorted) {
            try {
                info("  ✕ {}", module.name)
                module.shutdown()
            } catch (e: Exception) {
                error("Failed to shutdown module '{}'", module.name, e)
            }
        }

        modules.clear()
        initialized = false
        info("All modules shut down")
    }

    /**
     * Get all registered modules.
     */
    fun getModules(): List<PluginModule> = modules.toList()
}

