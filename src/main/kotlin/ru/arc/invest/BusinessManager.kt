package ru.arc.invest

import ru.arc.ARC
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

object BusinessManager {

    private val businessMap = ConcurrentHashMap<String, Business>()

    @JvmStatic
    fun byName(name: String): Business? = businessMap[name]

    @JvmStatic
    fun load() {
        val path1 = Paths.get(ARC.instance.dataFolder.toString(), "investing", "businesses")
        if (!Files.exists(path1)) {
            try {
                Files.createDirectories(path1)
                ARC.instance.saveResource("investing/businesses/farm.yml", false)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }

        val path2 = Paths.get(ARC.instance.dataFolder.toString(), "investing", "inventories")
        if (!Files.exists(path2)) {
            try {
                Files.createDirectories(path2)
                ARC.instance.saveResource("investing/inventories/farm.yml", false)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }

        try {
            Files.walk(
                Paths.get(ARC.instance.dataFolder.toString(), "investing", "businesses"), 3
            ).use { stream ->
                stream.filter { it.toString().endsWith(".yml") }
                    .map { it.fileName }
                    .map { it.toString().replace(".yml", "") }
                    .forEach { createFromName(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createFromName(name: String) {
        val business = Business(name)
        business.load()
        businessMap[name] = business
        println("Business $name loaded!")
    }
}
