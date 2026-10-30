package ru.arc.rtp

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class RtpOptionalProviderTest :
    FreeSpec({
        "network listener and regular service load with both optional APIs absent" {
            val loader = RtpProvidersAbsentLoader(javaClass.classLoader)
            for (name in listOf("ru.arc.rtp.RegularRtpService", "ru.arc.xserver.PluginMessenger")) {
                val type = loader.loadClass(name)
                type.declaredMethods.toList()
            }
            loader.blockedClasses shouldBe emptyList()
        }
    })

private class RtpProvidersAbsentLoader(private val source: ClassLoader) : ClassLoader(source) {
    val blockedClasses = mutableListOf<String>()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("io.github.dailystruggle.rtp.") || name.startsWith("net.luckperms.")) {
            blockedClasses += name
            throw ClassNotFoundException(name)
        }
        val isolated = listOf("ru.arc.rtp.RegularRtpService", "ru.arc.xserver.PluginMessenger")
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
