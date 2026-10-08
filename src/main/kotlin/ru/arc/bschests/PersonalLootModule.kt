package ru.arc.bschests

import com.jeff_media.customblockdata.CustomBlockData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.sync
import ru.arc.network.repos.ItemList
import ru.arc.repository.CachedRepository
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import ru.arc.repository.redisRepo
import ru.arc.util.ItemUtils.connectedChests
import ru.arc.util.ItemUtils.extractInventory
import ru.arc.util.ItemUtils.extractItems
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private const val PERSONAL_LOOT_SEPARATOR = ":::"

/**
 * Custom loot data for personal chest loot.
 */
class CustomLootData(
    var playerUuid: UUID = UUID.randomUUID(),
    var chestUuid: UUID = UUID.randomUUID(),
    var timestamp: Long = System.currentTimeMillis(),
    var items: ItemList = ItemList(),
    var filled: Boolean = false,
    var debrisAdded: Boolean = false,
) : Entity,
    Mergeable<CustomLootData> {
    override fun id(): String = "$playerUuid$PERSONAL_LOOT_SEPARATOR$chestUuid"

    override fun merge(other: CustomLootData) {
        val (otherItems, otherFilled, otherTimestamp, otherDebrisAdded) = other.persistedSnapshot()
        synchronized(this) {
            items.clear()
            items.addAll(otherItems)
            filled = otherFilled
            timestamp = otherTimestamp
            debrisAdded = otherDebrisAdded
        }
    }

    /**
     * Check if this entry should be removed.
     */
    fun shouldRemove(): Boolean =
        synchronized(this) {
            val ttl = 1000L * 60 * 60 * 24 * 7 // 7 days
            System.currentTimeMillis() - timestamp > ttl || (filled && items.all { it == null })
        }

    /**
     * Check if all items have been taken.
     */
    fun isExhausted(): Boolean = synchronized(this) { filled && items.all { it == null } }

    fun needsItems(): Boolean = synchronized(this) { !filled && items.isEmpty() }

    fun fillIfEmpty(generated: Iterable<ItemStack?>): Boolean =
        synchronized(this) {
            if (filled || items.isNotEmpty()) return@synchronized false
            generated.forEach { items.add(it?.clone()) }
            filled = true
            true
        }

    fun snapshotItems(): List<ItemStack?> =
        synchronized(this) {
            items.map { it?.takeUnless { item -> item.type.isAir }?.clone() }
        }

    /** A preview must not grant items or change the player's persisted loot. */
    fun previewItems(): List<ItemStack?> = synchronized(this) {
        val snapshot = snapshotItems()
        if (debrisAdded || isExhausted()) snapshot
        else personalLootWithDebris(snapshot, playerUuid, chestUuid)
    }

    /** Debris is ordinary loot, recorded once so reopening cannot replenish it. */
    fun addDebrisIfNeeded(): Boolean = synchronized(this) {
        if (debrisAdded || isExhausted()) return@synchronized false
        val withDebris = personalLootWithDebris(snapshotItems(), playerUuid, chestUuid)
        items.clear()
        items.addAll(withDebris)
        debrisAdded = true
        true
    }

    /** Cloud loot may only remove items from their exact persisted slots. */
    fun compareAndSetItems(expected: List<ItemStack?>, replacement: List<ItemStack?>): Boolean = synchronized(this) {
        if (expected.size != items.size || replacement.size != items.size || snapshotItems() != expected) return@synchronized false
        if (replacement.indices.any { slot ->
                val next = replacement[slot]
                val previous = expected[slot]
                next != null && (previous == null || !next.isSimilar(previous) || next.amount !in 1..previous.amount)
            }) return@synchronized false
        items.clear()
        items.addAll(replacement.map { it?.clone() })
        true
    }

    /**
     * Remove an item from the loot.
     */
    fun removeItem(
        item: ItemStack,
        slot: Int,
    ): Boolean = synchronized(this) {
        if (item.amount <= 0) return false

        val candidateSlots =
            buildList {
                if (slot in items.indices) add(slot)
                items.indices.filterTo(this) { it != slot }
            }
        val matchingSlots =
            candidateSlots.filter { index ->
                items[index]?.isSimilar(item) == true
            }
        val available = matchingSlots.sumOf { index -> items[index]?.amount ?: 0 }
        if (available < item.amount) {
            warn(
                "Unable to remove personal loot item: requested={}, available={}, preferredSlot={}",
                item.amount,
                available,
                slot,
            )
            return false
        }

        var remaining = item.amount
        for (index in matchingSlots) {
            if (remaining == 0) break
            val stored = items[index] ?: continue
            val removed = minOf(stored.amount, remaining)
            if (removed == stored.amount) {
                items[index] = null
            } else {
                stored.amount -= removed
            }
            remaining -= removed
        }

        check(remaining == 0) { "Personal loot changed while removing an item" }
        return true
    }

    private data class PersistedSnapshot(
        val items: List<ItemStack?>,
        val filled: Boolean,
        val timestamp: Long,
        val debrisAdded: Boolean,
    )

    private fun persistedSnapshot(): PersistedSnapshot =
        synchronized(this) {
            PersistedSnapshot(
                items.map { it?.clone() },
                filled,
                timestamp,
                debrisAdded,
            )
        }

    companion object {
        fun create(
            playerUuid: UUID,
            chestUuid: UUID,
        ): CustomLootData =
            CustomLootData(
                playerUuid = playerUuid,
                chestUuid = chestUuid,
                timestamp = System.currentTimeMillis(),
            )
    }
}

/**
 * Manager for personal chest loot.
 */
object PersonalLootModule {
    private val chests = setOf(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL)
    private lateinit var key: NamespacedKey
    private lateinit var uuidKey: NamespacedKey
    private lateinit var poolKey: NamespacedKey
    private lateinit var breakKey: NamespacedKey

    private lateinit var config: Config
    private var inventories: Set<InventoryType> = emptySet()
    private var maxPlayers: Int = 5
    private var useBsLoot: Boolean = false

    private var repo: CachedRepository<CustomLootData>? = null
    private var repositoryScope: CoroutineScope? = null
    private lateinit var chestGenerator: ChestGenerator
    private val previewReadLock = Any()
    private val previewGeneration = AtomicLong()
    private val pendingPreviewReads = linkedSetOf<String>()
    private val previewReadCache = object : LinkedHashMap<String, CachedPreviewRead>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPreviewRead>?): Boolean =
            size > MAX_PREVIEW_READS
    }

    private enum class PreviewReadState { PENDING, ABSENT, FAILED }

    private data class CachedPreviewRead(
        val state: PreviewReadState,
        val expiresAtNanos: Long,
    )

    @JvmStatic
    fun init() {
        if (repo != null) return
        if (ARC.redisManager == null) return
        val serverName = checkNotNull(ARC.serverName) { "ARC server name is not initialized" }

        key = NamespacedKey(ARC.instance, "ploot")
        uuidKey = NamespacedKey(ARC.instance, "ploot_uuid")
        poolKey = NamespacedKey(ARC.instance, "ploot_pool")
        breakKey = NamespacedKey(ARC.instance, "ploot_break")

        reload()

        val storageKey = "arc.$serverName-ploot"
        val updateChannel = "arc.$serverName-ploot-update"

        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val newRepository =
            try {
                redisRepo<CustomLootData>(
                    id = "$serverName-ploot",
                    storageKey = storageKey,
                    updateChannel = updateChannel,
                    scope = newScope,
                ) {
                    loadAllOnStart(true)
                    saveInterval(1.seconds)
                }
            } catch (failure: Throwable) {
                newScope.cancel()
                throw failure
            }
        clearPreviewReads()
        repo = newRepository
        repositoryScope = newScope
    }

    @JvmStatic
    fun shutdown() {
        val currentRepository = repo
        val currentScope = repositoryScope
        clearPreviewReads()
        repo = null
        repositoryScope = null
        try {
            if (currentRepository != null) {
                runBlocking { currentRepository.shutdown() }
            }
        } finally {
            currentScope?.cancel()
        }
    }

    @JvmStatic
    fun reload() {
        config = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "personalloot.yml")
        maxPlayers = config.integer("max-players", 5)
        inventories =
            config
                .stringList("inventories")
                .map { it.uppercase() }
                .mapNotNull {
                    try {
                        InventoryType.valueOf(it)
                    } catch (_: Exception) {
                        null
                    }
                }.toSet()
        chestGenerator = ChestGenerator(config)
        useBsLoot = config.bool("use-bs-loot", false)
    }

    @JvmStatic
    fun processChestBreak(event: BlockBreakEvent) {
        if (repo == null) return
        val block = event.block
        if (block.type !in chests) return

        val data = CustomBlockData(block, ARC.instance)
        if (!data.has(uuidKey)) return

        var breaks = data.get(breakKey, PersistentDataType.INTEGER) ?: 0
        breaks++

        if (breaks >= config.integer("max-breaks", 3)) {
            val inventory = extractInventory(block)
            inventory?.clear()
            data.clear()
        } else {
            data.set(breakKey, PersistentDataType.INTEGER, breaks)
            event.isCancelled = true
            event.player.sendMessage(
                config.component(
                    "messages.break",
                    "<red>Этот сундук нужно сломать еще <amount> раз",
                ) { tag("amount", config.integer("max-breaks", 3) - breaks) },
            )
        }
    }

    @JvmStatic
    fun processChestOpen(event: InventoryOpenEvent) {
        val repository = repo ?: return
        val activeScope = repositoryScope ?: return
        if (event.inventory.type !in inventories) return

        val player = event.player as? Player ?: return
        val location = event.inventory.location ?: return
        val block = location.block
        val blocks = connectedChests(block)

        val data = CustomBlockData(block, ARC.instance)
        if (!data.has(uuidKey)) return
        event.isCancelled = true

        val chestUuid =
            parsePersonalLootUuid(data.get(uuidKey, PersistentDataType.STRING))
                ?: run {
                    warn("Personal loot chest has an invalid UUID at {}", block.location)
                    return
                }
        val playerListString = data.get(key, PersistentDataType.STRING)
        if (playerListString == null) {
            warn("Player list string is null")
            return
        }

        val players = parsePersonalLootPlayers(playerListString).toMutableSet()

        if (players.size >= maxPlayers && player.uniqueId !in players) {
            player.sendMessage(
                config.component(
                    "messages.max-players",
                    "<red>Слишком много игроков уже открыли этот сундук!",
                ) { tag("amount", players.size) },
            )
            return
        }

        val poolName = data.get(poolKey, PersistentDataType.STRING)
        val currentItems = blocks.flatMap { extractItems(it) }.map { it.clone() }

        val playerUuid = player.uniqueId
        val lootId = "$playerUuid$PERSONAL_LOOT_SEPARATOR$chestUuid"
        activeScope.launch {
            try {
                val lootData =
                    repository
                        .getOrCreate(lootId) {
                            CustomLootData.create(playerUuid, chestUuid)
                        }.getOrThrow()
                sync {
                    finishChestOpen(
                        player = player,
                        block = block,
                        expectedChestUuid = chestUuid,
                        poolName = poolName,
                        currentItems = currentItems,
                        lootData = lootData,
                        repository = repository,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error("Failed to load personal loot {}", lootId, failure)
                sync {
                    if (player.isOnline) {
                        player.sendMessage(
                            config.component(
                                "messages.load-error",
                                "<red>Не удалось загрузить содержимое сундука. Попробуйте ещё раз.",
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun finishChestOpen(
        player: Player,
        block: Block,
        expectedChestUuid: UUID,
        poolName: String?,
        currentItems: List<ItemStack>,
        lootData: CustomLootData,
        repository: CachedRepository<CustomLootData>,
    ) {
        if (!player.isOnline || block.type !in chests) return

        val currentData = CustomBlockData(block, ARC.instance)
        val currentChestUuid =
            parsePersonalLootUuid(currentData.get(uuidKey, PersistentDataType.STRING))
        if (currentChestUuid != expectedChestUuid) return

        val playerListString = currentData.get(key, PersistentDataType.STRING) ?: return
        val players = parsePersonalLootPlayers(playerListString).toMutableSet()
        if (players.size >= maxPlayers && player.uniqueId !in players) {
            player.sendMessage(
                config.component(
                    "messages.max-players",
                    "<red>Слишком много игроков уже открыли этот сундук!",
                ) { tag("amount", players.size) },
            )
            return
        }

        if (lootData.isExhausted()) {
            player.sendMessage(
                config.component("messages.already-opened", "<red>Вы уже открывали этот сундук"),
            )
            return
        }

        val blocks = connectedChests(block)
        players.add(player.uniqueId)
        for (connectedBlock in blocks) {
            CustomBlockData(connectedBlock, ARC.instance).set(
                key,
                PersistentDataType.STRING,
                players.joinToString(PERSONAL_LOOT_SEPARATOR) { it.toString() },
            )
        }

        if (!useBsLoot) {
            extractInventory(block)?.clear()
        }

        if (lootData.needsItems()) {
            val generated =
                if (useBsLoot) {
                    ItemList().apply { addAll(currentItems) }
                } else {
                    chestGenerator.generate(poolName ?: "default", 5, 27)
                }
            if (lootData.fillIfEmpty(generated)) {
                repository.markDirty(lootData)
            }
        }

        LootGuiFactory.open(player, lootData)
    }

    @JvmStatic
    fun processChestGen(block: Block) {
        if (repo == null) return
        val blocks = connectedChests(block)
        val uuid = UUID.randomUUID().toString()

        for (b in blocks) {
            val data = CustomBlockData(b, ARC.instance)
            data.set(key, PersistentDataType.STRING, "")
            data.set(uuidKey, PersistentDataType.STRING, uuid)
            val treasurePool = if (useBsLoot) "default" else "generic_bs"
            data.set(poolKey, PersistentDataType.STRING, treasurePool)
            data.set(breakKey, PersistentDataType.INTEGER, 0)
        }
    }

    /**
     * Returns only the viewer's existing personal loot or an explicitly safe
     * BetterStructures template projection. Called on Paper's main thread after
     * the physical chest and every half have passed access checks. Redis reads
     * run on this module's IO scope; misses and failures are bounded and retried.
     */
    internal fun preview(
        viewer: UUID,
        chestUuid: UUID?,
        blocks: List<Block>,
    ): PersonalLootPreview {
        if (chestUuid == null) return PersonalLootPreview.NotPersonal
        val repository = repo ?: return PersonalLootPreview.Unavailable
        val scope = repositoryScope ?: return PersonalLootPreview.Unavailable
        if (!::key.isInitialized || !::uuidKey.isInitialized || !::config.isInitialized) {
            return PersonalLootPreview.Unavailable
        }
        if (blocks.size !in 1..2) return PersonalLootPreview.Unavailable

        val expectedInventory = when (blocks.first().type) {
            Material.BARREL -> InventoryType.BARREL
            Material.CHEST, Material.TRAPPED_CHEST -> InventoryType.CHEST
            else -> return PersonalLootPreview.Unavailable
        }
        if (expectedInventory !in inventories || blocks.any { block ->
                block.type !in chests || when (block.type) {
                    Material.BARREL -> InventoryType.BARREL
                    else -> InventoryType.CHEST
                } != expectedInventory
            }) return PersonalLootPreview.Unavailable

        val playerSets = try {
            val resolvedPlayers = mutableListOf<Set<UUID>>()
            for (block in blocks) {
                val data = CustomBlockData(block, ARC.instance)
                if (!data.has(uuidKey) ||
                    parsePersonalLootUuid(data.get(uuidKey, PersistentDataType.STRING)) != chestUuid
                ) return PersonalLootPreview.Unavailable
                val playerList = data.get(key, PersistentDataType.STRING)
                    ?: return PersonalLootPreview.Unavailable
                resolvedPlayers += parsePersonalLootPlayers(playerList)
            }
            resolvedPlayers
        } catch (_: Exception) {
            return PersonalLootPreview.Unavailable
        } catch (_: LinkageError) {
            return PersonalLootPreview.Unavailable
        }
        if (playerSets.distinct().size != 1) return PersonalLootPreview.Unavailable
        val openedPlayers = playerSets.first()
        if (openedPlayers.size >= maxPlayers && viewer !in openedPlayers) {
            return PersonalLootPreview.Unavailable
        }

        val useBetterStructuresLoot = useBsLoot
        val id = "$viewer$PERSONAL_LOOT_SEPARATOR$chestUuid"
        repository.getNow(id)?.let { return previewStoredLoot(it, useBetterStructuresLoot) }

        val generation = previewGeneration.get()
        var startRead = false
        val readState = synchronized(previewReadLock) {
            val now = System.nanoTime()
            val cached = previewReadCache[id]
            if (cached != null && cached.expiresAtNanos > now) {
                cached.state
            } else {
                if (cached != null) previewReadCache.remove(id)
                when {
                    id in pendingPreviewReads -> PreviewReadState.PENDING
                    pendingPreviewReads.size >= MAX_PREVIEW_READS -> PreviewReadState.FAILED
                    else -> {
                        pendingPreviewReads += id
                        startRead = true
                        PreviewReadState.PENDING
                    }
                }
            }
        }
        if (startRead) startPreviewRead(id, repository, scope, generation)

        return when (readState) {
            PreviewReadState.ABSENT -> previewAbsent(useBetterStructuresLoot)
            PreviewReadState.PENDING, PreviewReadState.FAILED -> PersonalLootPreview.Unavailable
        }
    }

    private fun previewStoredLoot(data: CustomLootData, useBetterStructuresLoot: Boolean): PersonalLootPreview {
        if (data.isExhausted()) return PersonalLootPreview.Contents(emptyList())
        if (data.needsItems()) {
            return if (useBetterStructuresLoot) {
                PersonalLootPreview.Contents(emptyList(), usePhysicalTemplate = true)
            } else {
                PersonalLootPreview.Unavailable
            }
        }
        return PersonalLootPreview.Contents(data.previewItems())
    }

    private fun previewAbsent(useBetterStructuresLoot: Boolean): PersonalLootPreview =
        if (useBetterStructuresLoot) {
            PersonalLootPreview.Contents(emptyList(), usePhysicalTemplate = true)
        } else {
            PersonalLootPreview.Unavailable
        }

    private fun startPreviewRead(
        id: String,
        repository: CachedRepository<CustomLootData>,
        scope: CoroutineScope,
        generation: Long,
    ) {
        val job = scope.launch {
            val state = try {
                if (repository.get(id).getOrThrow() == null) {
                    PreviewReadState.ABSENT
                } else {
                    PreviewReadState.PENDING
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error("Failed to read personal loot for chest preview {}", id, failure)
                PreviewReadState.FAILED
            }
            finishPreviewRead(id, repository, generation, state)
        }
        job.invokeOnCompletion { failure ->
            if (failure != null) finishPreviewRead(id, repository, generation, PreviewReadState.FAILED)
        }
    }

    private fun finishPreviewRead(
        id: String,
        repository: CachedRepository<CustomLootData>,
        generation: Long,
        state: PreviewReadState,
    ) = synchronized(previewReadLock) {
        if (generation != previewGeneration.get() || repo !== repository || !pendingPreviewReads.remove(id)) {
            return@synchronized
        }
        if (state == PreviewReadState.ABSENT || state == PreviewReadState.FAILED) {
            val ttlNanos = if (state == PreviewReadState.ABSENT) MISSING_PREVIEW_TTL_NANOS else FAILED_PREVIEW_TTL_NANOS
            previewReadCache[id] = CachedPreviewRead(state, System.nanoTime() + ttlNanos)
        }
    }

    private fun clearPreviewReads() = synchronized(previewReadLock) {
        previewGeneration.incrementAndGet()
        pendingPreviewReads.clear()
        previewReadCache.clear()
    }

    /**
     * Save a loot data entry.
     */
    @JvmStatic
    fun save(lootData: CustomLootData) {
        repo?.markDirty(lootData)
    }

    private const val MAX_PREVIEW_READS = 256
    private const val MISSING_PREVIEW_TTL_NANOS = 30_000_000_000L
    private const val FAILED_PREVIEW_TTL_NANOS = 60_000_000_000L
}

internal sealed interface PersonalLootPreview {
    data object NotPersonal : PersonalLootPreview
    data object Unavailable : PersonalLootPreview
    data class Contents(
        val items: List<ItemStack?>,
        val usePhysicalTemplate: Boolean = false,
    ) : PersonalLootPreview
}

internal fun parsePersonalLootUuid(raw: String?): UUID? =
    raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

internal fun parsePersonalLootPlayers(raw: String): Set<UUID> =
    raw
        .split(PERSONAL_LOOT_SEPARATOR)
        .mapNotNull(::parsePersonalLootUuid)
        .toSet()
