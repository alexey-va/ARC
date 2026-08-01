package ru.arc.ops.luckperms

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.context.Context
import net.luckperms.api.context.ContextManager
import net.luckperms.api.context.ContextSet
import net.luckperms.api.context.ContextSetFactory
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.node.Node
import net.luckperms.api.node.NodeBuilderRegistry
import net.luckperms.api.node.ScopedNode
import net.luckperms.api.node.types.DisplayNameNode
import net.luckperms.api.node.types.InheritanceNode
import net.luckperms.api.node.types.MetaNode
import net.luckperms.api.node.types.PermissionNode
import net.luckperms.api.node.types.PrefixNode
import net.luckperms.api.node.types.SuffixNode
import net.luckperms.api.node.types.WeightNode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Instant

class LuckPermsNodeCodecTest : FreeSpec({
    beforeSpec { TestLuckPermsNodeFactory.install() }
    afterSpec { TestLuckPermsNodeFactory.uninstall() }

    "LuckPerms node codec" - {
        "round-trips permission value contexts and expiry" {
            val spec =
                PermissionNodeSpec(
                    permission = "example.node",
                    value = false,
                    contexts =
                        LpContextSet(
                            mapOf(
                                "server" to listOf("survival", "spawn"),
                                "world" to listOf("world_nether", "world"),
                            ),
                        ),
                    expiresAt = Instant.parse("2026-08-02T12:00:00Z"),
                )

            LuckPermsNodeCodec.toSpec(LuckPermsNodeCodec.toNode(spec)) shouldBe spec
        }

        "round-trips each supported specialized node exactly" {
            val contexts = LpContextSet(mapOf("server" to listOf("survival")))
            val expiry = Instant.parse("2026-08-02T12:00:00Z")
            val specs =
                listOf(
                    PermissionNodeSpec("example.allow", true, contexts, expiry),
                    PermissionNodeSpec("example.deny", false, contexts, expiry),
                    InheritanceNodeSpec("moderator", contexts, expiry),
                    MetaNodeSpec("rank", "moderator", contexts, expiry),
                    PrefixNodeSpec(100, "<gold>Mod", contexts, expiry),
                    SuffixNodeSpec(100, "<gray>[M]", contexts, expiry),
                    WeightNodeSpec(100, contexts, expiry),
                    DisplayNameNodeSpec("Moderator", contexts, expiry),
                )

            specs.forEach { spec ->
                LuckPermsNodeCodec.toSpec(LuckPermsNodeCodec.toNode(spec)) shouldBe spec
            }
        }

        "round-trips false value for every non-permission node" {
            val nodes: List<Node> =
                listOf(
                    InheritanceNode.builder("moderator").value(false).build(),
                    MetaNode.builder("rank", "moderator").value(false).build(),
                    PrefixNode.builder("<gold>Mod", 100).value(false).build(),
                    SuffixNode.builder("<gray>[M]", 100).value(false).build(),
                    WeightNode.builder(100).value(false).build(),
                    DisplayNameNode.builder("Moderator").value(false).build(),
                )

            nodes.forEach { node ->
                val spec = LuckPermsNodeCodec.toSpec(node)
                spec.value shouldBe false
                val restored = LuckPermsNodeCodec.toNode(spec)
                restored.value shouldBe false
                LuckPermsNodeCodec.toSpec(restored) shouldBe spec
            }
        }

        "canonical identity includes value for every non-permission node" {
            val specsByValue =
                listOf(
                    InheritanceNodeSpec(groupName = "moderator", value = false) to
                        InheritanceNodeSpec(groupName = "moderator", value = true),
                    MetaNodeSpec(key = "rank", metaValue = "moderator", value = false) to
                        MetaNodeSpec(key = "rank", metaValue = "moderator", value = true),
                    PrefixNodeSpec(priority = 100, prefix = "<gold>Mod", value = false) to
                        PrefixNodeSpec(priority = 100, prefix = "<gold>Mod", value = true),
                    SuffixNodeSpec(priority = 100, suffix = "<gray>[M]", value = false) to
                        SuffixNodeSpec(priority = 100, suffix = "<gray>[M]", value = true),
                    WeightNodeSpec(weight = 100, value = false) to WeightNodeSpec(weight = 100, value = true),
                    DisplayNameNodeSpec(displayName = "Moderator", value = false) to
                        DisplayNameNodeSpec(displayName = "Moderator", value = true),
                )

            specsByValue.forEach { (negative, positive) ->
                negative.canonicalKey() shouldNotBe positive.canonicalKey()
            }
        }

        "normalizes context map and values into canonical order" {
            val contexts =
                LpContextSet(
                    mapOf(
                        "world" to listOf("world_nether", "world"),
                        "server" to listOf("survival", "spawn"),
                    ),
                )

            contexts.asMap() shouldBe
                mapOf(
                    "server" to listOf("spawn", "survival"),
                    "world" to listOf("world", "world_nether"),
                )
        }

        "rejects unknown LuckPerms node forms" {
            shouldThrow<IllegalArgumentException> {
                LuckPermsNodeCodec.toSpec(Node.builder("example.unknown").build())
            }
        }

        "rejects duplicate context values" {
            shouldThrow<IllegalArgumentException> {
                LpContextSet(mapOf("server" to listOf("survival", "survival")))
            }
        }

        "preserves the mandatory mutation reason" {
            val request =
                LpMutationRequest(
                    subject = LpSubjectRef(LpSubjectType.GROUP, "moderator"),
                    operations =
                        listOf(
                            LpOperation(
                                LpOperationAction.UNSET,
                                PermissionNodeSpec("example.node"),
                            ),
                        ),
                    reason = "remove obsolete direct grant",
                )

            request.reason shouldBe "remove obsolete direct grant"
        }

        "rejects a blank mutation reason" {
            shouldThrow<IllegalArgumentException> {
                LpMutationRequest(
                    subject = LpSubjectRef(LpSubjectType.GROUP, "moderator"),
                    operations =
                        listOf(
                            LpOperation(
                                LpOperationAction.SET,
                                PermissionNodeSpec("example.node"),
                            ),
                        ),
                    reason = "  ",
                )
            }
        }

        "rejects unsafe model identifiers" {
            shouldThrow<IllegalArgumentException> { PermissionNodeSpec(" ") }
            shouldThrow<IllegalArgumentException> { MetaNodeSpec("", "moderator") }
            shouldThrow<IllegalArgumentException> { InheritanceNodeSpec("../moderator") }
        }

        "canonical keys distinguish values containing separators" {
            MetaNodeSpec("rank|name", "moderator").canonicalKey() shouldNotBe
                MetaNodeSpec("rank", "name|moderator").canonicalKey()
        }

        "does not expose mutable normalized contexts" {
            val contexts =
                LpContextSet(
                    mapOf(
                        "server" to listOf("survival", "spawn"),
                        "world" to listOf("world"),
                    ),
                ).asMap()

            shouldThrow<UnsupportedOperationException> {
                (contexts as MutableMap)["world"] = listOf("world")
            }
            shouldThrow<UnsupportedOperationException> {
                (contexts.getValue("server") as MutableList).add("spawn")
            }
        }
    }
})

private object TestLuckPermsNodeFactory {
    private val providerField =
        LuckPermsProvider::class.java.getDeclaredField("instance").apply { isAccessible = true }

    fun install() {
        providerField.set(null, proxy(LuckPerms::class.java) { method, _ ->
            when (method.name) {
                "getNodeBuilderRegistry" -> nodeBuilderRegistry()
                "getContextManager" -> contextManager()
                else -> defaultValue(method.returnType)
            }
        })
    }

    fun uninstall() {
        providerField.set(null, null)
    }

    private fun nodeBuilderRegistry(): NodeBuilderRegistry =
        proxy(NodeBuilderRegistry::class.java) { method, _ ->
            val nodeType =
                when (method.name) {
                    "forKey" -> Node::class.java
                    "forPermission" -> PermissionNode::class.java
                    "forInheritance" -> InheritanceNode::class.java
                    "forMeta" -> MetaNode::class.java
                    "forPrefix" -> PrefixNode::class.java
                    "forSuffix" -> SuffixNode::class.java
                    "forWeight" -> WeightNode::class.java
                    "forDisplayName" -> DisplayNameNode::class.java
                    else -> error("Unsupported test node builder: ${method.name}")
                }
            nodeBuilder(nodeType, method.returnType)
        }

    private fun contextManager(): ContextManager =
        proxy(ContextManager::class.java) { method, _ ->
            when (method.name) {
                "getContextSetFactory" -> contextSetFactory()
                else -> defaultValue(method.returnType)
            }
        }

    private fun contextSetFactory(): ContextSetFactory =
        proxy(ContextSetFactory::class.java) { method, _ ->
            when (method.name) {
                "immutableBuilder" -> immutableContextSetBuilder()
                else -> defaultValue(method.returnType)
            }
        }

    private fun immutableContextSetBuilder(): ImmutableContextSet.Builder {
        val contexts = mutableListOf<Pair<String, String>>()
        lateinit var builder: ImmutableContextSet.Builder
        builder =
            proxy(ImmutableContextSet.Builder::class.java) { method, args ->
                when (method.name) {
                    "add" -> {
                        contexts += args[0] as String to args[1] as String
                        builder
                    }
                    "build" -> contextSet(contexts)
                    else -> defaultValue(method.returnType)
                }
            }
        return builder
    }

    private fun contextSet(contexts: List<Pair<String, String>>): ImmutableContextSet {
        val entries = contexts.map { (key, value) -> context(key, value) }
        return proxy(ImmutableContextSet::class.java) { method, _ ->
            when (method.name) {
                "iterator" -> entries.iterator()
                "size" -> entries.size
                "isEmpty" -> entries.isEmpty()
                "contains" -> false
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun context(key: String, value: String): Context =
        proxy(Context::class.java) { method, _ ->
            when (method.name) {
                "getKey" -> key
                "getValue" -> value
                else -> defaultValue(method.returnType)
            }
    }

    private fun nodeBuilder(nodeType: Class<out Node>, builderType: Class<*>): Any {
        val state = mutableMapOf<String, Any?>("contexts" to contextSet(emptyList()))
        lateinit var builder: Any
        builder =
            proxy(builderType) { method, args ->
                when (method.name) {
                    "build" -> node(nodeType, state)
                    "permission", "group", "key", "prefix", "suffix", "priority", "weight", "displayName" -> {
                        state[method.name] = args.single()
                        builder
                    }
                    "value" -> {
                        state[if (args.single() is Boolean) "value" else "metaValue"] = args.single()
                        builder
                    }
                    "withContext", "context" -> {
                        state["contexts"] = args.single()
                        builder
                    }
                    "expiry" -> {
                        state["expiry"] = args.single()
                        builder
                    }
                    "clearExpiry" -> {
                        state.remove("expiry")
                        builder
                    }
                    else -> defaultValue(method.returnType)
                }
            }
        return builder
    }

    private fun node(nodeType: Class<out Node>, state: Map<String, Any?>): Node =
        proxy(
            nodeType,
            *(if (nodeType == Node::class.java) arrayOf(ScopedNode::class.java) else emptyArray()),
        ) { method, _ ->
            when (method.name) {
                "getPermission" -> state["permission"]
                "getGroupName" -> state["group"]
                "getMetaKey" -> state["key"]
                "getMetaValue" -> state["metaValue"] ?: state["prefix"] ?: state["suffix"]
                "getPriority" -> state["priority"]
                "getWeight" -> state["weight"]
                "getDisplayName" -> state["displayName"]
                "getValue" -> state["value"] ?: true
                "getContexts" -> state["contexts"] ?: error("Node contexts were not set")
                "getExpiry" -> state["expiry"]
                else -> defaultValue(method.returnType)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(
        type: Class<T>,
        vararg additionalTypes: Class<*>,
        body: (Method, Array<out Any?>) -> Any?,
    ): T =
        Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type, *additionalTypes),
            InvocationHandler { _, method, args ->
                if (method.name == "toString") return@InvocationHandler "TestLuckPermsProxy(${type.simpleName})"
                body(method, args ?: emptyArray())
            },
        ) as T

    private fun defaultValue(type: Class<*>): Any? =
        when {
            !type.isPrimitive -> null
            type == Boolean::class.javaPrimitiveType -> false
            type == Int::class.javaPrimitiveType -> 0
            type == Long::class.javaPrimitiveType -> 0L
            type == Double::class.javaPrimitiveType -> 0.0
            type == Float::class.javaPrimitiveType -> 0f
            type == Short::class.javaPrimitiveType -> 0.toShort()
            type == Byte::class.javaPrimitiveType -> 0.toByte()
            type == Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }
}
