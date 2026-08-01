package ru.arc.ops.luckperms

import net.luckperms.api.context.ContextSet
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.node.Node
import net.luckperms.api.node.NodeBuilder
import net.luckperms.api.node.types.DisplayNameNode
import net.luckperms.api.node.types.InheritanceNode
import net.luckperms.api.node.types.MetaNode
import net.luckperms.api.node.types.PermissionNode
import net.luckperms.api.node.types.PrefixNode
import net.luckperms.api.node.types.SuffixNode
import net.luckperms.api.node.types.WeightNode

object LuckPermsNodeCodec {
    fun toSpec(node: Node): LpNodeSpec =
        when (node) {
            is PermissionNode -> PermissionNodeSpec(node.permission, node.value, node.contexts.toLpContextSet(), node.expiry)
            is InheritanceNode -> InheritanceNodeSpec(node.groupName, node.contexts.toLpContextSet(), node.expiry)
            is MetaNode -> MetaNodeSpec(node.metaKey, node.metaValue, node.contexts.toLpContextSet(), node.expiry)
            is PrefixNode -> PrefixNodeSpec(node.priority, node.metaValue, node.contexts.toLpContextSet(), node.expiry)
            is SuffixNode -> SuffixNodeSpec(node.priority, node.metaValue, node.contexts.toLpContextSet(), node.expiry)
            is WeightNode -> WeightNodeSpec(node.weight, node.contexts.toLpContextSet(), node.expiry)
            is DisplayNameNode -> DisplayNameNodeSpec(node.displayName, node.contexts.toLpContextSet(), node.expiry)
            else -> throw IllegalArgumentException("Unsupported LuckPerms node type: ${node::class.qualifiedName}")
        }

    fun toNode(spec: LpNodeSpec): Node =
        when (spec) {
            is PermissionNodeSpec ->
                PermissionNode.builder(spec.permission).value(spec.value).withAttributes(spec).build()
            is InheritanceNodeSpec -> InheritanceNode.builder(spec.groupName).withAttributes(spec).build()
            is MetaNodeSpec -> MetaNode.builder().key(spec.key).value(spec.value).withAttributes(spec).build()
            is PrefixNodeSpec -> PrefixNode.builder(spec.value, spec.priority).withAttributes(spec).build()
            is SuffixNodeSpec -> SuffixNode.builder(spec.value, spec.priority).withAttributes(spec).build()
            is WeightNodeSpec -> WeightNode.builder(spec.weight).withAttributes(spec).build()
            is DisplayNameNodeSpec -> DisplayNameNode.builder(spec.displayName).withAttributes(spec).build()
        }
}

private fun NodeBuilder<*, *>.withAttributes(spec: LpNodeSpec): NodeBuilder<*, *> =
    withContext(spec.contexts.toLuckPermsContextSet()).also { builder ->
        spec.expiresAt?.let(builder::expiry)
    }

private fun ContextSet.toLpContextSet(): LpContextSet =
    LpContextSet(groupBy({ it.key }, { it.value }))

private fun LpContextSet.toLuckPermsContextSet(): ContextSet =
    ImmutableContextSet.builder().also { builder ->
        asMap().forEach { (key, values) -> values.forEach { value -> builder.add(key, value) } }
    }.build()
