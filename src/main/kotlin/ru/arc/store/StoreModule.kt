package ru.arc.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.seconds

/**
 * A player's item store.
 */
class StoreData(
    val uuid: UUID,
    var itemList: ItemList = ItemList(),
    var size: Int = 9,
) : Entity,
    Mergeable<StoreData> {
    @Transient
    private var _lock: Lock? = null

    private val lock: Lock
        get() {
            if (_lock == null) _lock = ReentrantLock()
            return _lock!!
        }

    override fun id(): String = uuid.toString()

    override fun merge(other: StoreData) {
        withLock {
            itemList = other.itemList
            size = other.size
        }
    }

    /**
     * Get items in this store.
     */
    fun getItems(): List<ItemStack> = withLock { itemList.filterNotNull() }

    /**
     * Check if store has space for more items.
     */
    fun hasSpace(): Boolean = withLock { itemList.size < size }

    /**
     * Add an item to the store.
     * @return true if item was added successfully
     */
    fun addItem(item: ItemStack?): Boolean {
        if (item == null) return false
        if (item.type == Material.AIR) return true
        if (FORBIDDEN_MATCHERS.any { it.matches(item) }) return false

        return withLock {
            if (!canFit(item)) return@withLock false
            addAndDistribute(item)
            true
        }
    }

    /**
     * Remove an amount of an item from the store.
     * @return true if items were removed successfully
     */
    fun removeItem(
        item: ItemStack,
        amount: Int,
    ): Boolean =
        withLock {
            var remaining = amount
            val toRemove = mutableListOf<ItemStack>()

            for (stack in itemList.filterNotNull()) {
                if (stack.isSimilar(item)) {
                    when {
                        stack.amount > remaining -> {
                            stack.amount -= remaining
                            compact()
                            return@withLock true
                        }

                        stack.amount == remaining -> {
                            toRemove.add(stack)
                            compact()
                            return@withLock true
                        }

                        else -> {
                            remaining -= stack.amount
                            toRemove.add(stack)
                        }
                    }
                }
            }

            itemList.removeAll(toRemove.toSet())
            compact()
            false
        }

    private fun compact() {
        val current = ArrayList(itemList)
        itemList.clear()
        current.forEach { addAndDistribute(it) }
    }

    private fun addAndDistribute(item: ItemStack?) {
        if (item == null || item.type == Material.AIR) return

        var leftToFit = item.amount

        for (stack in itemList.filterNotNull()) {
            if (stack.isSimilar(item)) {
                val toAdd = minOf(stack.maxStackSize - stack.amount, leftToFit)
                stack.amount += toAdd
                leftToFit -= toAdd
            }
            if (leftToFit <= 0) return
        }

        if (leftToFit > 0) {
            val toAdd = item.clone()
            toAdd.amount = leftToFit
            itemList.add(toAdd)
        }
    }

    private fun canFit(stack: ItemStack?): Boolean {
        if (stack == null) return false
        if (stack.type == Material.AIR) return true
        if (itemList.size < size) return true

        var leftToFit = stack.amount
        for (item in itemList.filterNotNull()) {
            if (item.isSimilar(stack)) {
                leftToFit -= item.maxStackSize - item.amount
            }
            if (leftToFit <= 0) return true
        }
        return false
    }

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
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
    private lateinit var repo: CachedRepository<StoreData>
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var initialized = false

    @JvmStatic
    fun init() {
        if (initialized) return
        if (ru.arc.ARC.redisManager == null) return

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
        Logging.debug("[Store] getStore({})", playerUuid)
        val store =
            repo
                .getOrCreate(playerUuid.toString()) {
                    Logging.debug("[Store] creating new empty StoreData for {}", playerUuid)
                    StoreData(playerUuid)
                }.getOrThrow()

        Logging.debug("[Store] raw itemList size={} for {}", store.itemList.size, playerUuid)
        store.itemList.removeIf { it == null || it.type == Material.AIR }
        store.size = 9
        Logging.debug("[Store] final itemList size={} for {}", store.itemList.size, playerUuid)

        return store
    }

    /**
     * Get a player's store (blocking for Java interop).
     */
    @JvmStatic
    fun getStore(
        playerUuid: UUID,
        callback: java.util.function.Consumer<StoreData>,
    ) {
        scope.launch {
            val store = getStore(playerUuid)
            callback.accept(store)
        }
    }

    /**
     * Get a player's store (blocking CompletableFuture for Java interop).
     */
    @JvmStatic
    fun getStoreAsync(playerUuid: UUID): java.util.concurrent.CompletableFuture<StoreData> {
        val future = java.util.concurrent.CompletableFuture<StoreData>()
        scope.launch {
            try {
                val store = getStore(playerUuid)
                future.complete(store)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
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
        scope.launch { save(store) }
    }

    /**
     * Force save all stores.
     */
    @JvmStatic
    fun saveAll() =
        runBlocking {
            repo.saveDirty()
        }
}
