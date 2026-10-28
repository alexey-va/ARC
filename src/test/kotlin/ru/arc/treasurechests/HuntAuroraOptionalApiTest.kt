package ru.arc.treasurechests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class HuntAuroraOptionalApiTest : StringSpec({
    "the optional API presence probe accepts an available provider contract" {
        huntAuroraApiAvailable(HuntAuroraOptionalApiTest::class.java.classLoader) shouldBe true
    }

    "hunt listener initialization and reflective discovery work with the Aurora API absent" {
        val parent = HuntAuroraOptionalApiTest::class.java.classLoader
        val isolated = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                if (name.startsWith("ru.arc.aurora.api.")) throw ClassNotFoundException(name)
                if (!name.startsWith("ru.arc.treasurechests.TreasureHuntRegistry") &&
                    name != "ru.arc.treasurechests.HuntAuroraEventAdapterKt"
                ) return@synchronized super.loadClass(name, resolve)
                val loaded = findLoadedClass(name) ?: parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use {
                    val bytes = it.readBytes()
                    defineClass(name, bytes, 0, bytes.size)
                }
                if (resolve) resolveClass(loaded)
                loaded
            }
        }
        huntAuroraApiAvailable(isolated) shouldBe false
        val registry = Class.forName("ru.arc.treasurechests.TreasureHuntRegistry", true, isolated)
        Listener::class.java.isAssignableFrom(registry) shouldBe true
        registry.declaredMethods.count { it.isAnnotationPresent(EventHandler::class.java) } shouldBe 4
        registry.getField("INSTANCE").get(null).javaClass shouldBe registry
    }
})
