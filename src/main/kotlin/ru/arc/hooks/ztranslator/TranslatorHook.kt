package ru.arc.hooks.ztranslator

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import java.nio.file.Files

class TranslatorHook {

    private val map: Map<String, String>
    private val gson = Gson()

    init {
        val path = ARC.instance.dataPath.resolve("lang.json")
        if (!Files.exists(path)) {
            warn("lang.json is not present in ARC folder! Disabling translating materials...")
            map = emptyMap()
        } else {
            var loaded: Map<String, String> = emptyMap()
            try {
                val s = Files.readString(path)
                val token = object : TypeToken<Map<String, String>>() {}
                loaded = gson.fromJson(s, token)
            } catch (e: Exception) {
                error("Error loading translations", e)
            }
            map = loaded
        }
    }

    fun translate(stack: ItemStack?): String {
        if (stack == null) return "Null"
        return map.getOrDefault(stack.translationKey(), toReadableName(stack.type.name))
    }

    fun translate(material: Material): String =
        map.getOrDefault(material.translationKey(), toReadableName(material.name))

    private fun toReadableName(name: String): String {
        val sb = StringBuilder()
        var start = true
        for (c in name) {
            when {
                start -> { sb.append(c.uppercaseChar()); start = false }
                c == '_' -> { sb.append(' '); start = true }
                else -> sb.append(c.lowercaseChar())
            }
        }
        return sb.toString()
    }
}
