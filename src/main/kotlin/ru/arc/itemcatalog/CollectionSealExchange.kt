package ru.arc.itemcatalog

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.persistence.PersistentDataType

/**
 * Stable identity carried by a physical collection seal.
 *
 * The category is the only bearer value.  The version is deliberately stored
 * separately so a future seal format can be rejected without guessing how to
 * interpret an old stack.
 */
object CollectionSealIdentity {
    const val VERSION = "1"

    val key: NamespacedKey = NamespacedKey("arc", "collection_seal")
    val versionKey: NamespacedKey = NamespacedKey("arc", "collection_seal_version")

    private val CATEGORY = Regex("set_[a-z][a-z0-9_-]{0,91}")

    fun isValidCategoryId(categoryId: String): Boolean = CATEGORY.matches(categoryId)

    fun categoryId(stack: ItemStack?): String? {
        if (stack == null || stack.type.isAir) return null
        val data = stack.itemMeta?.persistentDataContainer ?: return null
        val categoryId = data.get(key, PersistentDataType.STRING) ?: return null
        if (!isValidCategoryId(categoryId)) return null
        if (data.get(versionKey, PersistentDataType.STRING) != VERSION) return null
        return categoryId
    }

    fun mark(stack: ItemStack, categoryId: String): ItemStack {
        require(isValidCategoryId(categoryId)) { "Invalid collection seal category '$categoryId'" }
        stack.editMeta { meta ->
            meta.persistentDataContainer.set(key, PersistentDataType.STRING, categoryId)
            meta.persistentDataContainer.set(versionKey, PersistentDataType.STRING, VERSION)
        }
        return stack
    }
}

/**
 * Main-thread inventory-for-inventory exchange for one collection seal.
 *
 * [plan] is side-effect free: it detaches the storage contents, consumes one
 * matching main-hand seal in the detached copy, and simulates insertion of one
 * cloned reward. [redeem] commits that already validated plan in one main
 * thread operation and restores the original snapshot if the inventory setter
 * fails. No overflow item is ever dropped.
 */
class CollectionSealExchange {
    sealed interface PlanResult {
        data class Ready(val contents: Array<ItemStack?>) : PlanResult

        data object InvalidCategory : PlanResult

        data object InvalidReward : PlanResult

        data object SealMissing : PlanResult

        data object InventoryFull : PlanResult
    }

    sealed interface RedemptionResult {
        data object Success : RedemptionResult

        data object InvalidCategory : RedemptionResult

        data object InvalidReward : RedemptionResult

        data object SealMissing : RedemptionResult

        data object InventoryFull : RedemptionResult

        data class MutationFailed(val cause: Throwable) : RedemptionResult
    }

    /**
     * Builds a detached post-exchange inventory without mutating [storage].
     * Exactly one item is inserted, regardless of [reward.amount].
     */
    fun plan(
        storage: Array<ItemStack?>,
        heldSlot: Int,
        categoryId: String,
        reward: ItemStack,
    ): PlanResult {
        if (!CollectionSealIdentity.isValidCategoryId(categoryId)) return PlanResult.InvalidCategory
        if (reward.type.isAir) return PlanResult.InvalidReward
        if (heldSlot !in storage.indices) return PlanResult.SealMissing

        val detached = storage.map { it?.clone() }.toTypedArray()
        val seal = detached[heldSlot]
        if (seal == null || CollectionSealIdentity.categoryId(seal) != categoryId || seal.amount < 1) {
            return PlanResult.SealMissing
        }

        val oneReward = reward.clone().also { it.amount = 1 }
        detached[heldSlot] =
            if (seal.amount == 1) {
                null
            } else {
                seal.clone().also { it.amount = seal.amount - 1 }
            }

        if (!simulateAdd(detached, oneReward)) return PlanResult.InventoryFull
        return PlanResult.Ready(detached)
    }

    /**
     * Executes [plan] against the player's storage on the calling main thread.
     * Callers must resolve the current reward immediately before this method.
     */
    fun redeem(
        player: Player,
        categoryId: String,
        reward: ItemStack,
    ): RedemptionResult {
        val inventory = player.inventory
        val before = detached(inventory)
        return when (val planned = plan(before, inventory.heldItemSlot, categoryId, reward)) {
            PlanResult.InvalidCategory -> RedemptionResult.InvalidCategory
            PlanResult.InvalidReward -> RedemptionResult.InvalidReward
            PlanResult.SealMissing -> RedemptionResult.SealMissing
            PlanResult.InventoryFull -> RedemptionResult.InventoryFull
            is PlanResult.Ready -> commit(inventory, before, planned.contents)
        }
    }

    private fun commit(
        inventory: PlayerInventory,
        before: Array<ItemStack?>,
        after: Array<ItemStack?>,
    ): RedemptionResult =
        try {
            inventory.storageContents = after.map { it?.clone() }.toTypedArray()
            RedemptionResult.Success
        } catch (failure: Throwable) {
            runCatching { inventory.storageContents = before }
            RedemptionResult.MutationFailed(failure)
        }

    private fun detached(inventory: PlayerInventory): Array<ItemStack?> =
        inventory.storageContents.map { it?.clone() }.toTypedArray()

    /** A small Bukkit-compatible addItem simulation over detached storage. */
    private fun simulateAdd(storage: Array<ItemStack?>, reward: ItemStack): Boolean {
        var remaining = reward.amount
        val maxStackSize = reward.maxStackSize.coerceAtLeast(1)

        for (index in storage.indices) {
            val current = storage[index] ?: continue
            if (current.type.isAir || !current.isSimilar(reward)) continue
            val room = (maxStackSize - current.amount).coerceAtLeast(0)
            if (room == 0) continue
            val added = minOf(room, remaining)
            storage[index] = current.clone().also { it.amount = current.amount + added }
            remaining -= added
            if (remaining == 0) return true
        }

        for (index in storage.indices) {
            val current = storage[index]
            if (current != null && !current.type.isAir) continue
            val added = minOf(maxStackSize, remaining)
            storage[index] = reward.clone().also { it.amount = added }
            remaining -= added
            if (remaining == 0) return true
        }
        return false
    }
}
