package ru.arc.ops

import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory

object OpsItemHandlers {
    fun playerInventory(playerName: String): Map<String, Any?> =
        OpsBukkitSync.call {
            val player = requireOnline(playerName)
            val inv = player.inventory
            mapOf(
                "player" to player.name,
                "uuid" to player.uniqueId.toString(),
                "heldSlot" to inv.heldItemSlot,
                "slots" to readStorageSlots(inv),
                "armor" to readArmor(inv),
                "offhand" to OpsItemSpec.toMap(inv.itemInOffHand),
            )
        }

    fun playerItem(
        playerName: String,
        slot: Int?,
        hand: Boolean,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val player = requireOnline(playerName)
            val inv = player.inventory
            val resolvedSlot =
                when {
                    hand -> inv.heldItemSlot
                    slot != null -> slot
                    else -> inv.heldItemSlot
                }
            validateSlot(resolvedSlot)

            mapOf(
                "player" to player.name,
                "slot" to resolvedSlot,
                "hand" to hand,
                "item" to OpsItemSpec.toMap(inv.getItem(resolvedSlot)),
            )
        }

    fun previewItem(body: JsonObject): Map<String, Any?> =
        OpsBukkitSync.call {
            val stack = OpsItemSpec.build(body)
            mapOf(
                "preview" to true,
                "item" to OpsItemSpec.toMap(stack),
            )
        }

    fun cmiBlobFromSpec(body: JsonObject): Map<String, Any?> =
        OpsBukkitSync.call {
            val itemJson = extractItemJson(body)
            val stack = OpsItemSpec.build(itemJson)
            val displayAmount =
                body.get("amount")?.takeIf { !it.isJsonNull }?.asInt
                    ?: itemJson.get("amount")?.takeIf { !it.isJsonNull }?.asInt
                    ?: 1
            cmiBlobResponse(stack, displayAmount.coerceIn(1, 64), source = "spec")
        }

    fun cmiBlobFromPreset(
        preset: String,
        amount: Int,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            cmiBlobFromPresetInternal(preset, amount)
        }

    fun cmiBlobBatch(presetNames: List<String>): Map<String, Any?> =
        OpsBukkitSync.call {
            val blobs = linkedMapOf<String, Any?>()
            for (name in presetNames) {
                val key = ItemPresets.normalize(name)
                val entry = cmiBlobFromPresetInternal(name, 1)
                blobs[key] =
                    mapOf(
                        "blob" to entry["blob"],
                        "item" to entry["item"],
                    )
            }
            mapOf(
                "format" to "cmi-gzip-nbt",
                "count" to blobs.size,
                "blobs" to blobs,
            )
        }

    private fun cmiBlobFromPresetInternal(
        preset: String,
        amount: Int,
    ): Map<String, Any?> {
        val specs =
            ItemPresets.resolveSpecs(preset, amount).getOrElse {
                throw IllegalArgumentException(it.message ?: "Unknown preset")
            }
        if (specs.size != 1) {
            throw IllegalArgumentException(
                "Preset '$preset' is a bundle (${specs.size} items). Use cmi-blob/batch or single item presets.",
            )
        }
        val stack = OpsItemSpec.build(specs.first())
        return cmiBlobResponse(stack, stack.amount.coerceIn(1, 64), source = "preset:$preset")
    }

    private fun cmiBlobResponse(
        stack: ItemStack,
        displayAmount: Int,
        source: String,
    ): Map<String, Any?> {
        val blob = CmiItemCodec.encode(stack, displayAmount)
        return mapOf(
            "format" to "cmi-gzip-nbt",
            "source" to source,
            "displayAmount" to displayAmount,
            "blob" to blob,
            "yaml" to CmiItemCodec.yamlBinaryLine(blob),
            "item" to OpsItemSpec.toMap(stack),
        )
    }

    fun giveItem(
        playerName: String,
        body: JsonObject,
        maxStack: Int,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val player = requireOnline(playerName)
            val itemJson = extractItemJson(body)
            val stack = OpsItemSpec.build(itemJson)
            val requestedAmount = body.get("amount")?.takeIf { !it.isJsonNull }?.asInt ?: stack.amount
            stack.amount = requestedAmount.coerceIn(1, maxStack.coerceAtLeast(1))

            val targetSlot = body.get("slot")?.takeIf { !it.isJsonNull }?.asInt ?: -1
            val dropOverflow = body.get("dropOverflow")?.takeIf { !it.isJsonNull }?.asBoolean ?: true

            val result =
                if (targetSlot >= 0) {
                    giveToSlot(player, stack, targetSlot, dropOverflow)
                } else {
                    giveToInventory(player, stack, dropOverflow)
                }

            result +
                mapOf(
                    "player" to player.name,
                    "given" to OpsItemSpec.toMap(stack),
                )
        }

    fun handCmiBlob(
        playerName: String,
        amount: Int,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val player = requireOnline(playerName)
            val stack = player.inventory.itemInMainHand
            require(!stack.type.isAir) { "Player $playerName is not holding any item" }
            cmiBlobResponse(stack, amount.coerceIn(1, 64), source = "hand:$playerName")
        }

    fun validatePresets(presetNames: List<String>): Map<String, Any?> {
        val results = linkedMapOf<String, Any?>()
        var valid = 0
        var invalid = 0
        for (name in presetNames) {
            val resolved = ItemPresets.resolveSpecs(name, 1)
            if (resolved.isSuccess) {
                results[name] = mapOf("ok" to true)
                valid++
            } else {
                results[name] = mapOf("ok" to false, "error" to (resolved.exceptionOrNull()?.message ?: "unknown"))
                invalid++
            }
        }
        return mapOf(
            "ok" to (invalid == 0),
            "valid" to valid,
            "invalid" to invalid,
            "total" to presetNames.size,
            "results" to results,
        )
    }

    fun giveStacks(
        player: Player,
        stacks: List<ItemStack>,
        dropOverflow: Boolean = true,
    ): Int {
        var total = 0
        for (stack in stacks) {
            val leftover = player.inventory.addItem(stack.clone())
            val overflow = leftover.values.sumOf { it.amount }
            total += stack.amount - overflow
            if (dropOverflow && overflow > 0) {
                leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            }
        }
        return total
    }

    private fun extractItemJson(body: JsonObject): JsonObject {
        body.get("item")?.takeIf { it.isJsonObject }?.asJsonObject?.let { return it }
        if (body.has("material") || body.has("itemsadder") || body.has("ia")) {
            return body
        }
        throw IllegalArgumentException("JSON body must contain \"item\" object or item fields (material/itemsadder)")
    }

    private fun giveToInventory(
        player: Player,
        stack: ItemStack,
        dropOverflow: Boolean,
    ): Map<String, Any?> {
        val leftover = player.inventory.addItem(stack.clone())
        if (leftover.isEmpty()) {
            return mapOf("mode" to "inventory", "overflow" to 0)
        }

        val overflowCount = leftover.values.sumOf { it.amount }
        if (dropOverflow) {
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            return mapOf("mode" to "inventory", "overflow" to overflowCount, "dropped" to true)
        }
        return mapOf(
            "mode" to "inventory",
            "overflow" to overflowCount,
            "dropped" to false,
            "error" to "inventory full",
        )
    }

    private fun giveToSlot(
        player: Player,
        stack: ItemStack,
        slot: Int,
        dropOverflow: Boolean,
    ): Map<String, Any?> {
        validateSlot(slot)
        val inv = player.inventory
        val existing = inv.getItem(slot)
        if (existing != null && !existing.type.isAir) {
            if (!dropOverflow) {
                return mapOf(
                    "mode" to "slot",
                    "slot" to slot,
                    "error" to "slot occupied",
                )
            }
            player.world.dropItemNaturally(player.location, existing.clone())
        }
        inv.setItem(slot, stack.clone())
        return mapOf(
            "mode" to "slot",
            "slot" to slot,
            "replaced" to (existing != null && !existing.type.isAir),
        )
    }

    private fun readStorageSlots(inv: PlayerInventory): Map<String, Any?> {
        val slots = linkedMapOf<String, Any?>()
        for (slot in 0..35) {
            slots[slot.toString()] = OpsItemSpec.toMap(inv.getItem(slot))
        }
        return slots
    }

    private fun readArmor(inv: PlayerInventory): Map<String, Any?> =
        mapOf(
            "boots" to OpsItemSpec.toMap(inv.boots),
            "leggings" to OpsItemSpec.toMap(inv.leggings),
            "chestplate" to OpsItemSpec.toMap(inv.chestplate),
            "helmet" to OpsItemSpec.toMap(inv.helmet),
        )

    private fun validateSlot(slot: Int) {
        require(slot in 0..40) { "slot must be 0-40 (36-39 armor, 40 offhand)" }
    }

    private fun requireOnline(name: String): Player =
        Bukkit.getPlayerExact(name) ?: throw IllegalArgumentException("Player not online: $name")
}
