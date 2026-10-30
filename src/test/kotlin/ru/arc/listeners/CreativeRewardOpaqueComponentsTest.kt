package ru.arc.listeners

import de.tr7zw.changeme.nbtapi.NBTType
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBTCompoundList
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBTList
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.inventory.ItemStack

class CreativeRewardOpaqueComponentsTest : StringSpec({
    "an item without metadata skips the native snapshot" {
        val stack = mockk<ItemStack>()
        every { stack.hasItemMeta() } returns false
        CreativeRewardOpaqueComponents.isProtected(stack) shouldBe false
    }

    "actual reward tags are found through nested entity compounds and item lists" {
        listOf("arc:treasure_key", "arc:pouch_id", "money_token_mean").forEach { key ->
            val item = nbt("id" to "minecraft:stick", "components" to nbt(
                "minecraft:custom_data" to nbt(key to "any-value"),
            ))
            val data = nbt("minecraft:entity_data" to nbt("Passengers" to listOf(
                nbt("HandItems" to listOf(item)),
            )))
            CreativeRewardOpaqueComponents.containsProtectedData(data) shouldBe true
        }
    }

    "bucket and non-container block payloads use the same protected traversal" {
        listOf("minecraft:bucket_entity_data", "minecraft:block_entity_data").forEach { component ->
            val data = nbt(component to nbt("SpawnData" to nbt("entity" to nbt(
                "equipment" to nbt("mainhand" to nbt("tag" to nbt("arc:pouch_id" to "royal"))),
            ))))
            CreativeRewardOpaqueComponents.containsProtectedData(data) shouldBe true
        }
    }

    "physical reward voucher PDC keys cannot be hidden inside entity equipment" {
        listOf(
            "arc:physical_reward_voucher", "arc:physical_reward_voucher_version",
            "arc:physical_reward_voucher_source", "arc:physical_reward_voucher_fingerprint",
        ).forEach { key ->
            CreativeRewardOpaqueComponents.containsProtectedData(nbt("minecraft:entity_data" to nbt(
                "HandItems" to listOf(nbt("components" to nbt(
                    "minecraft:custom_data" to nbt("PublicBukkitValues" to nbt(key to "forged")),
                ))),
            ))) shouldBe true
        }
    }

    "markerless legacy money is detected in current and historical serialized item formats" {
        for (model in 11135..11138) {
            val formats = listOf(
                nbt("id" to "minecraft:stick", "components" to nbt("minecraft:custom_model_data" to nbt("floats" to listOf(model.toFloat())))),
                nbt("id" to "minecraft:stick", "components" to nbt("minecraft:custom_model_data" to model)),
                nbt("id" to "minecraft:stick", "tag" to nbt("CustomModelData" to model)),
            )
            formats.forEach { payload ->
                CreativeRewardOpaqueComponents.containsProtectedData(
                    nbt("minecraft:entity_data" to nbt("Items" to listOf(payload))),
                ) shouldBe true
            }
        }
    }

    "ordinary custom carriers and marker-like prose remain allowed" {
        val ordinary = listOf(
            nbt("minecraft:custom_name" to "Named spawn egg"),
            nbt("minecraft:custom_name" to "Named block", "minecraft:block_entity_data" to nbt("CustomName" to "Display")),
            nbt("minecraft:entity_data" to nbt(
                "id" to "minecraft:armor_stand", "CustomName" to "arc:treasure_key and money_token_mean",
                "Tags" to listOf("arc:pouch_id"),
                "HandItems" to listOf(nbt("id" to "minecraft:stick", "components" to nbt(
                    "minecraft:custom_model_data" to nbt("floats" to listOf(11139f)),
                ))),
            )),
        )
        ordinary.forEach { CreativeRewardOpaqueComponents.containsProtectedData(it) shouldBe false }
    }

    "depth and node budgets fail closed and are shared across opaque components" {
        fun chain(depth: Int): ReadWriteNBT {
            var result = nbt()
            repeat(depth) { result = nbt("child" to result) }
            return result
        }
        CreativeRewardOpaqueComponents.containsProtectedData(nbt(
            "minecraft:entity_data" to chain(CreativeRewardOpaqueComponents.MAX_DEPTH),
        )) shouldBe false
        CreativeRewardOpaqueComponents.containsProtectedData(nbt(
            "minecraft:entity_data" to chain(CreativeRewardOpaqueComponents.MAX_DEPTH + 1),
        )) shouldBe true
        val halfBudget = CreativeRewardOpaqueComponents.MAX_NODES / 2
        val wide = nbt(*(0 until halfBudget).map { "field$it" to 1 }.toTypedArray())
        CreativeRewardOpaqueComponents.containsProtectedData(nbt("minecraft:entity_data" to wide)) shouldBe false
        CreativeRewardOpaqueComponents.containsProtectedData(nbt(
            "minecraft:entity_data" to wide, "minecraft:bucket_entity_data" to wide,
        )) shouldBe true
        val empty = nbt()
        CreativeRewardOpaqueComponents.containsProtectedData(nbt("minecraft:entity_data" to nbt(
            "Items" to List(CreativeRewardOpaqueComponents.MAX_NODES + 1) { empty },
        ))) shouldBe true
    }

    "malformed opaque values unsupported nested lists and read failures fail closed" {
        CreativeRewardOpaqueComponents.containsProtectedData(nbt("minecraft:entity_data" to "invalid")) shouldBe true
        CreativeRewardOpaqueComponents.containsProtectedData(nbt("minecraft:entity_data" to nbt(
            "nested" to listOf(listOf(nbt())),
        ))) shouldBe true
        val unreadable = nbt("minecraft:entity_data" to nbt())
        every { unreadable.getCompound("minecraft:entity_data") } throws IllegalStateException("unreadable")
        CreativeRewardOpaqueComponents.containsProtectedData(unreadable) shouldBe true
    }
})

/** Typed NBT API fixtures; no native NBT runtime or alternate production parser. */
private fun nbt(vararg values: Pair<String, Any>): ReadWriteNBT {
    val data = values.toMap()
    return mockk<ReadWriteNBT>().also { node ->
        every { node.keys } returns data.keys
        every { node.hasTag(any()) } answers { data.containsKey(firstArg<String>()) }
        every { node.getType(any()) } answers { nbtType(data[firstArg<String>()]) }
        every { node.getCompound(any()) } answers { data[firstArg<String>()] as? ReadWriteNBT }
        every { node.getString(any()) } answers { data[firstArg<String>()] as? String ?: "" }
        every { node.getInteger(any()) } answers { data[firstArg<String>()] as? Int ?: 0 }
        every { node.getListType(any()) } answers { nbtType((data[firstArg<String>()] as? List<*>)?.firstOrNull()) }
        every { node.getCompoundList(any()) } answers {
            nbtCompoundList((data[firstArg<String>()] as List<*>).map { it as ReadWriteNBT })
        }
        every { node.getFloatList(any()) } answers {
            nbtList((data[firstArg<String>()] as List<*>).map { it as Float })
        }
        every { node.getStringList(any()) } answers {
            nbtList((data[firstArg<String>()] as List<*>).map { it as String })
        }
    }
}

private fun nbtType(value: Any?): NBTType = when (value) {
    null -> NBTType.NBTTagEnd
    is ReadWriteNBT -> NBTType.NBTTagCompound
    is List<*> -> NBTType.NBTTagList
    is String -> NBTType.NBTTagString
    is Int -> NBTType.NBTTagInt
    is Float -> NBTType.NBTTagFloat
    else -> error("Unsupported fixture type")
}

private fun <T> nbtList(values: List<T>): ReadWriteNBTList<T> = mockk<ReadWriteNBTList<T>>().also { list ->
    every { list.size() } returns values.size
    every { list[any()] } answers { values[firstArg<Int>()] }
}

private fun nbtCompoundList(values: List<ReadWriteNBT>): ReadWriteNBTCompoundList =
    mockk<ReadWriteNBTCompoundList>().also { list ->
        every { list.size() } returns values.size
        every { list[any()] } answers { values[firstArg<Int>()] }
    }
