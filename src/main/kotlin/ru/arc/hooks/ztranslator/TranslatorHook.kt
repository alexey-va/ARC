package ru.arc.hooks.ztranslator

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import java.nio.file.Files

class TranslatorHook(private val map: Map<String, String> = ARC.instance.materialTranslations) {

    companion object {
        /** Disk and JSON work must finish before native hooks attach. */
        internal fun prepare(root: java.nio.file.Path): Map<String, String> {
            val path = root.resolve("lang.json")
            if (!Files.exists(path)) {
                warn("Required lang.json is not present in the ARC folder; material translation is disabled")
                return emptyMap()
            }
            return try {
                val token = object : TypeToken<Map<String, String>>() {}
                Gson().fromJson<Map<String, String>>(Files.readString(path), token.type).orEmpty().toMap()
            } catch (failure: Exception) {
                error("Error loading translations", failure)
                emptyMap()
            }
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
