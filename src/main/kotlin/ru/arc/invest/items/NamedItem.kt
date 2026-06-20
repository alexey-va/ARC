package ru.arc.invest.items

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

class NamedItem(private val item: ItemStack, private val name: String) : GenericItem() {

    override fun stack(): ItemStack = item.clone()

    override fun fits(stack: ItemStack): Boolean = item == stack.asOne()

    override fun toString(): String = "NamedItem{name=$name}"

    companion object {
        private val namedItemMap = HashMap<String, NamedItem>()

        @JvmStatic
        fun find(name: String): NamedItem? = namedItemMap[name.lowercase()]

        @JvmStatic
        fun deserialize(map: Map<String, Any>, id: String): NamedItem {
            val stack = ItemStack.deserialize(map)
            return NamedItem(stack, id)
        }

        @JvmStatic
        fun load() {
            namedItemMap.clear()
            val path = Paths.get(ARC.instance.dataFolder.toString(), "investing", "items")
            if (!Files.exists(path)) {
                try {
                    Files.createDirectories(path)
                } catch (e: IOException) {
                    throw RuntimeException(e)
                }
            }

            try {
                Files.walk(path, 3).use { stream ->
                    stream.filter { it.toString().endsWith(".yml") }
                        .map { it.toFile() }
                        .map { YamlConfiguration.loadConfiguration(it) }
                        .forEach { readFile(it) }
                }
            } catch (e: Exception) {
                ARC.instance.logger.warning("Error loading named items: ${e.message}")
            }

            println("Loaded named items: $namedItemMap")
        }

        @Suppress("UNCHECKED_CAST")
        private fun readFile(configuration: YamlConfiguration) {
            for (key in configuration.getKeys(false)) {
                val value = configuration.get(key)
                if (value !is Map<*, *>) continue
                val typedMap = value as Map<String, Any>
                namedItemMap[key.lowercase()] = deserialize(typedMap, key)
            }
        }
    }
}
