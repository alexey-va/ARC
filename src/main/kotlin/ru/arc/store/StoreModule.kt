package ru.arc.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.network.repos.ItemList
import ru.arc.repository.CachedRepository
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import ru.arc.repository.redisRepo
import ru.arc.util.Logging
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.seconds

/**
 * A player's item store.
 */
class StoreData(
    val uuid: UUID,
    itemList: ItemList = ItemList(),
    var size: Int = 9,
) : Entity,
    Mergeable<StoreData> {
    var itemList: ItemList = itemList
        private set

    @Transient
    @Volatile
    private var lockRef: ReentrantLock? = null

    private val lock: ReentrantLock
        get() =
            lockRef ?: synchronized(this) {
                lockRef ?: ReentrantLock().also { lockRef = it }
            }

    override fun id(): String = uuid.toString()

    override fun merge(other: StoreData) {
        val (otherItems, otherSize) = other.snapshot()
        withLock {
            itemList = otherItems
            size = otherSize
        }
    }

    /**
     * Get a detached snapshot of the items in this store.
     */
    fun getItems(): List<ItemStack> = withLock { itemList.filterNotNull().map(ItemStack::clone) }

    /**
     * Get a detached, fixed-size snapshot that preserves every store slot.
     */
    fun getSlots(): List<ItemStack?> =
        withLock {
            List(size.coerceAtLeast(0)) { slot ->
                itemList.getOrNull(slot)?.takeUnless { it.type == Material.AIR }?.clone()
            }
        }

    /**
     * Get a detached snapshot of one explicit store slot.
     */
    fun getItemAt(slot: Int): ItemStack? =
        withLock {
            if (slot !in 0 until size) return@withLock null
            itemList.getOrNull(slot)?.takeUnless { it.type == Material.AIR }?.clone()
        }

    /**
     * Remove invalid entries left by legacy serialized data.
     */
    fun sanitize(): Int =
        withLock {
            itemList.indices.forEach { slot ->
                if (itemList[slot]?.type == Material.AIR) itemList[slot] = null
            }
            itemList.count { it != null }
        }

    /**
     * Check if store has space for more items.
     */
    fun hasSpace(): Boolean = withLock { occupiedSlots() < size }

    /**
     * Check whether the complete stack can be stored without moving existing stacks.
     */
    fun canAddItem(item: ItemStack?): Boolean {
        if (!isAllowed(item)) return false
        val validItem = item ?: return false
        if (validItem.type == Material.AIR) return true
        return withLock { canFit(validItem) }
    }

    /**
     * Add an item to the store.
     * @return true if item was added successfully
     */
    fun addItem(item: ItemStack?): Boolean {
        if (!isAllowed(item)) return false
        val validItem = item ?: return false
        if (validItem.type == Material.AIR) return true

        return withLock {
            if (!canFit(validItem)) return@withLock false
            addAndDistribute(validItem)
            true
        }
    }

    /**
     * Add an item to one explicit store slot without compacting neighbouring slots.
     */
    fun addItemAt(
        slot: Int,
        item: ItemStack?,
    ): Boolean {
        if (!isAllowed(item)) return false
        val validItem = item ?: return false
        if (validItem.type == Material.AIR) return true

        return withLock {
            if (slot !in 0 until size) return@withLock false

            val existing = itemList.getOrNull(slot)?.takeUnless { it.type == Material.AIR }
            when {
                existing == null -> {
                    if (validItem.amount > validItem.maxStackSize) return@withLock false
                    setSlot(slot, validItem.clone())
                    true
                }

                existing.isSimilar(validItem) && existing.amount + validItem.amount <= existing.maxStackSize -> {
                    existing.amount += validItem.amount
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Remove an amount of an item from the store.
     * @return true if items were removed successfully
     */
    fun removeItem(
        item: ItemStack,
        amount: Int,
    ): Boolean {
        if (amount <= 0) return false

        return withLock {
            val matchingSlots =
                itemList.indices.filter { slot ->
                    slot < size && itemList[slot]?.isSimilar(item) == true
                }
            if (matchingSlots.sumOf { slot -> itemList[slot]?.amount ?: 0 } < amount) return@withLock false

            var remaining = amount
            for (slot in matchingSlots) {
                if (remaining == 0) break
                val stack = itemList[slot] ?: continue
                val removed = minOf(stack.amount, remaining)
                stack.amount -= removed
                remaining -= removed
                if (stack.amount == 0) itemList[slot] = null
            }
            true
        }
    }

    /**
     * Remove an amount from one explicit slot without affecting any other slot.
     */
    fun removeItemAt(
        slot: Int,
        expected: ItemStack,
        amount: Int,
    ): Boolean {
        if (amount <= 0) return false

        return withLock {
            if (slot !in 0 until size) return@withLock false
            val stored = itemList.getOrNull(slot) ?: return@withLock false
            if (!stored.isSimilar(expected) || stored.amount < amount) return@withLock false

            stored.amount -= amount
            if (stored.amount == 0) itemList[slot] = null
            true
        }
    }

    private fun addAndDistribute(item: ItemStack) {
        if (item.type == Material.AIR) return

        var leftToFit = item.amount

        for (slot in 0 until minOf(size, itemList.size)) {
            val stack = itemList[slot] ?: continue
            if (stack.isSimilar(item)) {
                val toAdd = minOf((stack.maxStackSize - stack.amount).coerceAtLeast(0), leftToFit)
                stack.amount += toAdd
                leftToFit -= toAdd
            }
            if (leftToFit <= 0) return
        }

        for (slot in 0 until size) {
            if (leftToFit <= 0) return
            if (itemList.getOrNull(slot)?.type?.let { it != Material.AIR } == true) continue

            val toAdd = item.clone()
            toAdd.amount = minOf(item.maxStackSize, leftToFit)
            setSlot(slot, toAdd)
            leftToFit -= toAdd.amount
        }
    }

    private fun canFit(stack: ItemStack): Boolean {
        if (stack.type == Material.AIR) return true

        var leftToFit = stack.amount
        for (slot in 0 until size) {
            val item = itemList.getOrNull(slot)
            leftToFit -=
                when {
                    item == null || item.type == Material.AIR -> stack.maxStackSize
                    item.isSimilar(stack) -> (item.maxStackSize - item.amount).coerceAtLeast(0)
                    else -> 0
                }
            if (leftToFit <= 0) return true
        }
        return false
    }

    private fun setSlot(
        slot: Int,
        item: ItemStack?,
    ) {
        while (itemList.size <= slot) itemList.add(null)
        itemList[slot] = item
    }

    private fun isAllowed(item: ItemStack?): Boolean {
        if (item == null) return false
        if (item.type == Material.AIR) return true
        if (item.amount <= 0) return false
        return FORBIDDEN_MATCHERS.none { it.matches(item) }
    }

    private fun occupiedSlots(): Int {
        var occupied = 0
        for (slot in 0 until size) {
            if (itemList.getOrNull(slot)?.type?.let { it != Material.AIR } == true) {
                occupied++
            }
        }
        return occupied
    }

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun snapshot(): Pair<ItemList, Int> =
        withLock {
            ItemList().apply {
                itemList.forEach { add(it?.clone()) }
            } to size
        }

    companion object {
        private val FORBIDDEN_MATCHERS =
            listOf(
                ItemMatcher.ofRegex(".*shulker.*"),
                ItemMatcher.ofRegex(".*dragon.*"),
                ItemMatcher.sfItem(true),
                ItemMatcher.of(Material.BARRIER),
                ItemMatcher.of(Material.COMMAND_BLOCK),
                ItemMatcher.of(Material.COMMAND_BLOCK_MINECART),
                ItemMatcher.of(Material.STRUCTURE_BLOCK),
                ItemMatcher.of(Material.STRUCTURE_VOID),
                ItemMatcher.of(Material.JIGSAW),
                ItemMatcher.of(Material.DEBUG_STICK),
            )
    }
}

/**
 * Manager for player stores.
 */
object StoreManager {
    private const val UNAVAILABLE_MESSAGE = "Player store is unavailable because Redis is not initialized"
    private lateinit var repo: CachedRepository<StoreData>
    private lateinit var scope: CoroutineScope
    private var initialized = false

    @JvmStatic
    fun init() {
        if (initialized) return
        if (ru.arc.ARC.redisManager == null) return

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        repo =
            redisRepo<StoreData>(
                id = "store",
                storageKey = "arc.store",
                updateChannel = "arc.store_update",
                scope = scope,
            ) {
                loadAllOnStart(true)
                saveInterval(1.seconds)
            }
        initialized = true
    }

    @JvmStatic
    fun shutdown() {
        if (!initialized) return
        runBlocking { repo.shutdown() }
        initialized = false
    }

    /**
     * Get a player's store.
     */
    suspend fun getStore(playerUuid: UUID): StoreData {
        check(initialized) { UNAVAILABLE_MESSAGE }
        Logging.debug("[Store] getStore({})", playerUuid)
        val store =
            repo
                .getOrCreate(playerUuid.toString()) {
                    Logging.debug("[Store] creating new empty StoreData for {}", playerUuid)
                    StoreData(playerUuid)
                }.getOrThrow()

        Logging.debug("[Store] loaded item count={} for {}", store.sanitize(), playerUuid)

        return store
    }

    @JvmStatic
    fun getStoreAsync(playerUuid: UUID): java.util.concurrent.CompletableFuture<StoreData> =
        if (initialized) {
            scope.future {
                getStore(playerUuid)
            }
        } else {
            unavailableFuture()
        }

    /**
     * Save a store.
     */
    suspend fun save(store: StoreData) {
        repo.save(store)
    }

    /**
     * Schedule a store save from any thread (fire-and-forget).
     * Marks the entity dirty so the background sync picks it up.
     */
    @JvmStatic
    fun saveLater(store: StoreData) {
        if (!initialized) return
        scope.launch { save(store) }
    }

    @JvmStatic
    fun saveAllAsync(): java.util.concurrent.CompletableFuture<Unit> =
        if (initialized) {
            scope.future {
                repo.saveDirty().getOrThrow()
            }
        } else {
            unavailableFuture()
        }

    private fun <T> unavailableFuture(): java.util.concurrent.CompletableFuture<T> =
        java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException(UNAVAILABLE_MESSAGE))
}
