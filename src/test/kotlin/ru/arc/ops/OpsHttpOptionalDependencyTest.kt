package ru.arc.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

class OpsHttpOptionalDependencyTest : FreeSpec({
    "module construction and disabled staff lifecycle do not load optional LuckPerms classes" {
        val loader = LuckPermsAbsentClassLoader(javaClass.classLoader)
        val moduleType = loader.loadClass("ru.arc.ops.OpsHttpModule")
        moduleType.getField("INSTANCE").get(null)
        val serverType = loader.loadClass("ru.arc.ops.OpsHttpServer")
        val constructor = serverType.getConstructor(Function0::class.java, Function0::class.java)
        val executorFactory = { Executors.newSingleThreadExecutor() }

        for (enabled in listOf(false, true)) {
            val config = TestOpsHttpConfig(enabled = enabled, staffCommandsEnabled = false)
            val server = constructor.newInstance(executorFactory, { config })
            try {
                serverType.getMethod("invalidateStaffCommands").invoke(server)
                serverType.getMethod("start").invoke(server)
                if (enabled) {
                    val port = serverType.getMethod("getActualPort").invoke(server)
                    val connection = URI("http://127.0.0.1:$port/ops/staff-command").toURL().openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Authorization", "Bearer ${config.token}")
                    connection.connectTimeout = 2_000
                    connection.readTimeout = 2_000
                    try { connection.responseCode shouldBe 403 } finally { connection.disconnect() }
                }
            } finally {
                serverType.getMethod("stop").invoke(server)
                serverType.getMethod("stop").invoke(server)
            }
        }
        loader.blockedClasses shouldBe emptyList()
    }
})

/** Load the owning classes again as Paper does, with the optional provider genuinely absent. */
private class LuckPermsAbsentClassLoader(private val source: ClassLoader) : ClassLoader(source) {
    val blockedClasses = mutableListOf<String>()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("net.luckperms.")) {
            blockedClasses += name
            throw ClassNotFoundException(name)
        }
        val isolated = listOf("ru.arc.ops.OpsHttpModule", "ru.arc.ops.OpsHttpServer", "ru.arc.ops.OpsStaffCommandHandlers")
            .any { name == it || name.startsWith("$it\$") }
        if (!isolated) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val loaded = findLoadedClass(name) ?: source.getResourceAsStream(name.replace('.', '/') + ".class")
                ?.use { it.readBytes() }
                ?.let { defineClass(name, it, 0, it.size) }
                ?: throw ClassNotFoundException(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}
