package ru.arc.core

import ru.arc.util.Logging
import ru.arc.util.Logging.consoleLog
import ru.arc.util.Logging.error
import ru.arc.util.Logging.escapeMM

private const val SEP = "<dark_gray>" + "┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄" + "</dark_gray>"

/**
 * Central registry for all plugin modules.
 * Handles lifecycle management: initialization, reload, and shutdown.
 */
object ModuleRegistry {
    private val modules = mutableListOf<PluginModule>()
    private var initialized = false

    fun register(module: PluginModule) {
        if (initialized) {
            error("Cannot register module '{}' after initialization", module.name)
            return
        }
        modules.add(module)
    }

    fun registerAll(vararg modulesToRegister: PluginModule) {
        modulesToRegister.forEach { register(it) }
    }

    fun initAll() {
        if (initialized) {
            error("ModuleRegistry already initialized")
            return
        }

        val sorted = modules.filter { it.enabled }.sortedBy { it.priority }

        consoleLog(SEP)
        consoleLog("  <bold><white>Initializing <aqua>${sorted.size}</aqua> modules</white></bold>")
        consoleLog(SEP)

        data class Result(val name: String, val ms: Long, val error: Exception?)

        val startAll = System.currentTimeMillis()
        val results = sorted.map { module ->
            val start = System.currentTimeMillis()
            try {
                module.init()
                Result(module.name, System.currentTimeMillis() - start, null)
            } catch (e: Exception) {
                Result(module.name, System.currentTimeMillis() - start, e)
            }
        }
        val totalMs = System.currentTimeMillis() - startAll

        val nameWidth = results.maxOf { it.name.length }.coerceAtLeast(12)
        for (r in results) {
            val name = "<aqua>${escapeMM(r.name.padEnd(nameWidth))}</aqua>"
            when {
                r.error != null -> {
                    val msg = escapeMM(r.error.message ?: r.error::class.simpleName ?: "error")
                    consoleLog("  <red>✗  $name  $msg</red>")
                    Logging.error("Module '${r.name}' failed to initialize", r.error)
                }
                r.ms >= 200 -> consoleLog("  <yellow>✔  $name  ${r.ms}ms  ⚠</yellow>")
                r.ms >= 50  -> consoleLog("  <green>✔</green>  $name  <yellow>${r.ms}ms</yellow>")
                else        -> consoleLog("  <green>✔</green>  $name  <dark_gray>${r.ms}ms</dark_gray>")
            }
        }

        val failed = results.count { it.error != null }
        val ok = results.size - failed

        consoleLog(SEP)
        if (failed == 0) {
            consoleLog("  <bold><green>✔  All $ok modules ready</green></bold>  <dark_gray>(${totalMs}ms total)</dark_gray>")
        } else {
            consoleLog("  <bold><green>✔  $ok ok</green>  <red>$failed failed</red></bold>  <dark_gray>(${totalMs}ms)</dark_gray>")
        }
        consoleLog(SEP)

        initialized = true
    }

    fun reloadAll() {
        consoleLog("<aqua>↻  Reloading ${modules.size} modules...</aqua>")
        for (module in modules.filter { it.enabled }) {
            try {
                module.reload()
                consoleLog("  <green>↻  ${escapeMM(module.name)}</green>")
            } catch (e: Exception) {
                error("  ✗  ${module.name}", e)
            }
        }
        consoleLog("<green>↻  Reload complete</green>")
    }

    fun shutdownAll() {
        val sorted = modules.filter { it.enabled }.sortedByDescending { it.priority }
        consoleLog("<dark_gray>  Shutting down ${sorted.size} modules...</dark_gray>")
        for (module in sorted) {
            try {
                module.shutdown()
                consoleLog("  <dark_gray>✕  ${escapeMM(module.name)}</dark_gray>")
            } catch (e: Exception) {
                error("Failed to shutdown module '{}'", module.name, e)
            }
        }
        modules.clear()
        initialized = false
        consoleLog("<dark_gray>  Shutdown complete</dark_gray>")
    }

    fun getModules(): List<PluginModule> = modules.toList()
}
