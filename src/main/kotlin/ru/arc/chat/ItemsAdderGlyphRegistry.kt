package ru.arc.chat

import dev.lone.itemsadder.api.FontImages.FontImageWrapper
import java.lang.reflect.Method

internal fun interface ChatGlyphRegistry {
    fun snapshot(): List<ChatGlyphDefinition>
}

/**
 * ItemsAdder 4.0.18 metadata boundary. Its published 3.6.1 API does not expose
 * the registry or permissions, and getInternal even has a different JVM return
 * descriptor. Bind those exact methods once; never inspect plugin internals on
 * the chat path. No dependency on obfuscated class or field names is retained.
 */
internal class ItemsAdderGlyphRegistry(
    private val apiClass: Class<*> = FontImageWrapper::class.java,
) : ChatGlyphRegistry {
    // Resolve inside snapshot so an incompatible API enters the guard's closed
    // state instead of preventing registration and silently leaving chat open.
    private val registry by lazy { apiClass.getMethod("getNamespacedIdsAndValueInRegistry") }
    private val constructor by lazy { apiClass.getConstructor(String::class.java) }
    private val internal by lazy { apiClass.getMethod("getInternal") }
    private val height by lazy { apiClass.getMethod("getHeight") }
    private val width by lazy { apiClass.getMethod("getWidth") }
    private var permission: Method? = null

    override fun snapshot(): List<ChatGlyphDefinition> {
        val entries = checkNotNull(registry.invoke(null) as? Map<*, *>) {
            "ItemsAdder font registry is not a map"
        }.toMap()
        return entries.map { (rawId, rawUnicode) ->
            val id = checkNotNull(rawId as? String)
            val unicode = checkNotNull(rawUnicode as? String)
            require(unicode.isNotEmpty()) { "ItemsAdder font image has no Unicode: $id" }
            val wrapper = constructor.newInstance(id)
            val metadata = checkNotNull(internal.invoke(wrapper)) { "Missing ItemsAdder font image: $id" }
            val accessor = permission ?: metadata.javaClass.getMethod("getPermission").also { permission = it }
            val node = accessor.invoke(metadata)
            check(node == null || node is String) { "Invalid ItemsAdder font permission: $id" }
            val name = id.substringAfter(':')
            ChatGlyphDefinition(
                id = id,
                unicode = unicode,
                permission = (node as? String)?.takeIf { it.isNotBlank() },
                technical = id.startsWith("_iainternal:") || name.startsWith("offset_") ||
                    (height.invoke(wrapper) as Number).toInt() > 16 ||
                    (width.invoke(wrapper) as Number).toInt() > 96,
            )
        }
    }
}
