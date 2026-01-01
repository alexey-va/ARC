package ru.arc.configs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.Particle
import org.jetbrains.annotations.NotNull
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.mm
import java.io.FileInputStream
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

@Suppress("UNCHECKED_CAST")
class Config internal constructor(
    private val folder: Path,
    private val filePath: String
) {
    var map: MutableMap<String, Any> = ConcurrentHashMap()

    init {
        copyDefaultConfig(filePath, folder, false)
        load()
    }

    fun integer(path: String, def: Int): Int {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def
        }
        if (o is Number) {
            return o.toInt()
        }
        // Try to parse as string
        return try {
            o.toString().toInt()
        } catch (e: NumberFormatException) {
            warn("Could not parse integer from path: {} (value: {}), using default: {}", path, o, def)
            injectDeepKey(path, def)
            def
        }
    }

    fun bool(path: String, def: Boolean): Boolean {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def
        }
        if (o is Boolean) {
            return o
        }
        // Try to parse as string
        if (o is String) {
            val s = o.trim().lowercase()
            if (s == "true" || s == "1" || s == "yes") {
                return true
            }
            if (s == "false" || s == "0" || s == "no") {
                return false
            }
        }
        warn("Could not parse boolean from path: {} (value: {}), using default: {}", path, o, def)
        injectDeepKey(path, def)
        return def
    }

    fun real(path: String, def: Double): Double {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def
        }
        if (o is Number) {
            return o.toDouble()
        }
        // Try to parse as string
        return try {
            o.toString().toDouble()
        } catch (e: NumberFormatException) {
            warn("Could not parse double from path: {} (value: {}), using default: {}", path, o, def)
            injectDeepKey(path, def)
            def
        }
    }

    fun string(path: String, def: String): String {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def
        }
        return try {
            o as? String ?: o.toString()
        } catch (e: Exception) {
            o.toString()
        }
    }

    fun component(path: String, vararg replacement: String): Component {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, path)
            return mm(path, true)
        }
        val s: String = if (o is String) o else o.toString()

        var result = s
        for (i in replacement.indices step 2) {
            if (i + 1 >= replacement.size) break
            result = result.replace(replacement[i], replacement[i + 1], ignoreCase = false)
        }

        return mm(result, true)
    }

    fun componentDef(path: String, def: String, tagResolver: TagResolver): Component {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return TextUtil.strip(mm(def, tagResolver))!!
        }
        val s = if (o is String) o else o.toString()
        return TextUtil.strip(mm(s, tagResolver))!!
    }

    fun componentDef(path: String, def: String, vararg replacers: String): Component {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return mm(def, true, *replacers)
        }
        val s = if (o is String) o else o.toString()
        return mm(s, true, *replacers)
    }

    fun component(path: String, tagResolver: TagResolver): Component {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, path)
            return TextUtil.strip(mm(path))!!
        }
        val s = if (o is String) o else o.toString()
        return TextUtil.strip(mm(s, tagResolver))!!
    }

    fun componentList(path: String, vararg replacement: String): List<Component> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, listOf(path))
            return listOf(TextUtil.strip(mm(path))!!)
        }

        val list = mutableListOf<Component>()
        when (o) {
            is String -> {
                var s: String = o
                for (i in replacement.indices step 2) {
                    if (i + 1 >= replacement.size) break
                    s = s.replace(replacement[i], replacement[i + 1], ignoreCase = false)
                }
                list.add(mm(s, true))
            }

            is List<*> -> {
                for (obj in o) {
                    if (obj is String) {
                        var s1: String = obj
                        for (i in replacement.indices step 2) {
                            if (i + 1 >= replacement.size) break
                            s1 = s1.replace(replacement[i], replacement[i + 1], ignoreCase = false)
                        }
                        list.add(mm(s1, true))
                    }
                }
            }
        }
        return list
    }

    fun componentList(path: String, tagResolver: TagResolver): List<Component> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, listOf(path))
            return listOf(TextUtil.strip(mm(path, tagResolver))!!)
        }

        val list = mutableListOf<Component>()
        when (o) {
            is String -> {
                list.add(TextUtil.strip(mm(o, tagResolver))!!)
            }

            is List<*> -> {
                for (obj in o) {
                    if (obj is String) {
                        list.add(TextUtil.strip(mm(obj, tagResolver))!!)
                    }
                }
            }
        }
        return list
    }

    fun componentListDef(path: String, def: List<String>, vararg replacement: String): List<Component> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return componentList(path, *replacement)
        }

        val list = mutableListOf<Component>()
        when (o) {
            is String -> {
                var s: String = o
                for (i in replacement.indices step 2) {
                    if (i + 1 >= replacement.size) break
                    s = s.replace(replacement[i], replacement[i + 1], ignoreCase = false)
                }
                list.add(mm(s, true))
            }

            is List<*> -> {
                for (obj in o) {
                    if (obj is String) {
                        var s1: String = obj
                        for (i in replacement.indices step 2) {
                            if (i + 1 >= replacement.size) break
                            s1 = s1.replace(replacement[i], replacement[i + 1], ignoreCase = false)
                        }
                        list.add(mm(s1, true))
                    }
                }
            }
        }
        return list
    }

    fun componentListDef(path: String, def: List<String>, tagResolver: TagResolver): List<Component> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return componentList(path, tagResolver)
        }

        val list = mutableListOf<Component>()
        when (o) {
            is String -> {
                list.add(TextUtil.strip(mm(o, tagResolver))!!)
            }

            is List<*> -> {
                for (obj in o) {
                    if (obj is String) {
                        list.add(TextUtil.strip(mm(obj, tagResolver))!!)
                    }
                }
            }
        }
        return list
    }

    fun stringList(path: String): List<String> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, ArrayList<String>())
            return ArrayList()
        }
        if (o is String) {
            return listOf(o)
        }
        return o as List<String>
    }

    fun stringSet(path: String): Set<String> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, ArrayList<Any>())
            return HashSet()
        }
        if (o is String) {
            return setOf(o)
        }
        return HashSet(o as Collection<String>)
    }

    fun stringList(path: String, def: List<String>): List<String> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def
        }
        if (o is String) {
            return listOf(o)
        }
        return o as List<String>
    }

    fun <T> map(path: String): Map<String, T> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, LinkedHashMap<String, T>())
            return LinkedHashMap()
        }
        // Collect keys to modify first to avoid ConcurrentModificationException
        val keysToReplace = mutableListOf<Any>()
        val mapObj = o as MutableMap<Any, Any>
        for (key in mapObj.keys) {
            if (key !is String) {
                keysToReplace.add(key)
            }
        }
        // Now modify the map
        var modified = false
        for (key in keysToReplace) {
            val stringKey = key.toString()
            val value = mapObj[key] ?: continue
            mapObj.remove(key)
            mapObj[stringKey] = value
            modified = true
        }
        if (modified) {
            injectDeepKey(path, o)
        }
        return o as Map<String, T>
    }

    fun <T> map(path: String, def: Map<String, T>): Map<String, T> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def
        }
        // Handle immutable empty maps from YAML parsing
        if (o is Map<*, *> && o.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            return o as Map<String, T>
        }
        if (o !is MutableMap<*, *>) {
            // Return as-is if not mutable (shouldn't happen with valid YAML, but be safe)
            @Suppress("UNCHECKED_CAST")
            return o as Map<String, T>
        }
        // Collect keys to modify first to avoid ConcurrentModificationException
        val keysToReplace = mutableListOf<Any>()

        @Suppress("UNCHECKED_CAST")
        val mapObj = o as MutableMap<Any, Any>
        for (key in mapObj.keys) {
            if (key !is String) {
                keysToReplace.add(key)
            }
        }
        // Now modify the map
        var modified = false
        for (key in keysToReplace) {
            val stringKey = key.toString()
            val value = mapObj[key] ?: continue
            mapObj.remove(key)
            mapObj[stringKey] = value
            modified = true
        }
        if (modified) {
            injectDeepKey(path, o)
        }
        return o as Map<String, T>
    }

    fun <T> list(path: String): MutableList<T> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, ArrayList<Any>())
            return ArrayList()
        }
        return (o as List<T>).toMutableList()
    }

    fun <T> list(path: String, def: List<T>): MutableList<T> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def.toMutableList()
        }
        return (o as List<T>).toMutableList()
    }

    fun keys(path: String): List<String> {
        val o = map<Any>(path, emptyMap())
        if (o == null) return ArrayList()
        return ArrayList(o.keys)
    }

    private fun getValueForKeyPath(keyPath: String): Any? {
        val keyParts = keyPath.split("\\.".toRegex())

        var currentLevel = map
        for (i in keyParts.indices) {
            val keyPart = keyParts[i]
            if (currentLevel.containsKey(keyPart)) {
                if (i == keyParts.size - 1) {
                    return currentLevel[keyPart]
                }
                val nextLevel = currentLevel[keyPart]
                if (nextLevel is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    currentLevel = nextLevel as MutableMap<String, Any>
                } else {
                    // Intermediate key exists but is not a map
                    debug("Key path interrupted at: $keyPath (part: $keyPart)")
                    return null
                }
            } else {
                // Key not found or not a mapping; handle accordingly
                debug("Key not found: $keyPath")
                return null
            }
        }
        // This line should never be reached, but kept for safety
        return null
    }

    fun injectDeepKey(keyPath: String, value: Any) {
        try {
            debug("Injecting key: $keyPath with value: $value")
            load()
            val keyParts = keyPath.split("\\.".toRegex())

            var currentLevel = map
            for (i in 0 until keyParts.size - 1) {
                currentLevel.putIfAbsent(keyParts[i], LinkedHashMap<String, Any>())
                @Suppress("UNCHECKED_CAST")
                currentLevel = currentLevel[keyParts[i]] as MutableMap<String, Any>
            }

            // Inject the value at the final level
            currentLevel[keyParts[keyParts.size - 1]] = value
            save()
        } catch (e: Exception) {
            error("Could not inject key: {}", keyPath)
        }
    }

    fun load() {
        val yaml = Yaml()
        val configFile = folder.resolve(filePath).toFile()
        @Suppress("UNCHECKED_CAST")
        map = (yaml.load(FileInputStream(configFile)) as? MutableMap<String, Any>) ?: ConcurrentHashMap()
    }

    fun save() {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            isAllowUnicode = true
        }
        val yaml = Yaml(options)
        val file = folder.resolve(filePath).toFile().path
        try {
            FileWriter(file).use { writer ->
                yaml.dump(map, writer)
            }
        } catch (e: Exception) {
            error("Could not save config: {}", file)
        }
    }

    companion object {
        fun copyDefaultConfig(resource: String, folder: Path, replace: Boolean) {
            try {
                Config::class.java.classLoader.getResourceAsStream(resource).use { stream ->
                    val path = folder.resolve(resource)
                    if (Files.exists(path)) {
                        if (!replace) {
                            return
                        }
                    }
                    Files.createDirectories(path.parent)
                    if (stream == null) {
                        Files.createFile(path)
                    } else {
                        Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            } catch (e: Exception) {
                error("Could not copy default config: {}", resource)
            }
        }
    }

    fun string(s: String): String {
        return string(s, s)
    }

    fun addToList(path: String, value: Any) {
        val list = list<Any>(path)
        (list as MutableList<Any>).add(value)
        injectDeepKey(path, list)
    }

    fun materialSet(s: String, def: Set<Material>): Set<Material> {
        val list = stringList(s, def.map { it.name })
        val materials = HashSet<Material>()
        for (mat in list) {
            try {
                materials.add(Material.valueOf(mat.uppercase()))
            } catch (e: Exception) {
                warn("Could not parse material: {}", mat)
            }
        }
        return materials
    }

    fun particle(s: String, def: Particle): Particle {
        return try {
            Particle.valueOf(string(s, def.name).uppercase())
        } catch (e: Exception) {
            def
        }
    }

    fun material(s: String, material: Material): @NotNull Material {
        return try {
            Material.valueOf(string(s, material.name).uppercase())
        } catch (e: Exception) {
            material
        }
    }

    fun longValue(s: String, def: Long): Long {
        val o = getValueForKeyPath(s)
        if (o == null) {
            injectDeepKey(s, def)
            return def
        }
        if (o is Number) {
            return o.toLong()
        }
        // Try to parse as string
        return try {
            o.toString().toLong()
        } catch (e: NumberFormatException) {
            warn("Could not parse long from path: {} (value: {}), using default: {}", s, o, def)
            injectDeepKey(s, def)
            def
        }
    }

    fun exists(s: String): Boolean {
        return getValueForKeyPath(s) != null
    }
}

