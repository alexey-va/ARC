package ru.arc.bschests

import com.jeff_media.customblockdata.CustomBlockData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.network.repos.ItemList
import ru.arc.protection.ContainerProtection
import ru.arc.repository.CachedRepository
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import ru.arc.repository.RepoConfig
import ru.arc.repository.redis.RedisStorage
import ru.arc.chestpreview.chestPartnerDirection
import ru.arc.util.ItemUtils.extractInventory
import ru.arc.util.ItemUtils.extractItems
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.Common
import java.util.LinkedHashMap
import java.util.Locale
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

    /** Current generation's gates plus the same memory config read by ChestGenerator. */
    internal fun activePoolLootSettings(): PersonalLootPoolSettings? {
        if (repo == null || !::config.isInitialized || useBsLoot || inventories.isEmpty() || maxPlayers <= 0) return null
        return PersonalLootPoolSettings(
            commonPool = config.string("loot.common-pool", "structures_common").trim().lowercase(Locale.ROOT),
            minRolls = config.integer("loot.min-rolls", 3),
            maxRolls = config.integer("loot.max-rolls", 5),
            bonusPool = config.string("loot.bonus-pool", "structures_special").trim().lowercase(Locale.ROOT),
            bonusChance = config.double("loot.bonus-chance", 0.10),
        )
    }

    private var repo: CachedRepository<CustomLootData>? = null
    private var repositoryScope: CoroutineScope? = null
    private var identities: PersonalLootIdentities? = null
    private var prepared: PreparedRepositories? = null

    private data class PreparedRepositories(
        val loot: CachedRepository<CustomLootData>,
        val identities: PersonalLootIdentities,
        val scope: CoroutineScope,
    ) {
        fun drain() {
            ru.arc.repository.drainRepository(loot, scope)
        }
    }
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

        key = NamespacedKey(ARC.instance, "ploot")
        uuidKey = NamespacedKey(ARC.instance, "ploot_uuid")
        poolKey = NamespacedKey(ARC.instance, "ploot_pool")
        breakKey = NamespacedKey(ARC.instance, "ploot_break")

        reload()

        val candidate = checkNotNull(prepared) { "Personal loot repository preparation is not ready" }
        prepared = null
        clearPreviewReads()
        repo = candidate.loot
        identities = candidate.identities
        repositoryScope = candidate.scope
    }

    internal fun prepareOnWorker(redis: ru.arc.redis.RedisOperations, serverName: String) {
        if (repo != null || prepared != null) return

        val storageKey = "arc.$serverName-ploot"
        val updateChannel = "arc.$serverName-ploot-update"

        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val timerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val newRepository =
            try {
                // This server-scoped hash has one Paper owner. ENTITY pub/sub can echo an older
                // empty snapshot back into a newly filled reward; no cross-server sync is needed.
                CachedRepository(
                    config = RepoConfig<CustomLootData>(
                        id = "$serverName-ploot", storageKey = storageKey, updateChannel = updateChannel,
                        loadAllOnStart = true, saveInterval = 1.seconds,
                    ),
                    storage = RedisStorage<CustomLootData>(redis, storageKey, CustomLootData::class.java, Common.gson),
                    scope = timerScope,
                ).also { runBlocking { it.init().getOrThrow() } }
            } catch (failure: Throwable) {
                newScope.cancel()
                timerScope.cancel()
                throw failure
            }
        val newIdentities = try {
            PersonalLootIdentities(personalLootIdentityStorage(redis, "$storageKey-identities"))
        } catch (failure: Throwable) {
            newScope.cancel()
            runBlocking { newRepository.shutdown() }
            throw failure
        }
        prepared = PreparedRepositories(newRepository, newIdentities, newScope)
    }

    internal fun discardPreparedOnWorker() {
        val candidate = prepared ?: return
        prepared = null
        candidate.identities.tasks.close()
        ru.arc.repository.sealRepositoryOperations(candidate.scope)
        candidate.drain()
    }

    @JvmStatic
    fun shutdown() {
        val currentRepository = repo
        val currentScope = repositoryScope
        val currentIdentities = identities
        clearPreviewReads()
        repo = null
        repositoryScope = null
        identities = null
        currentIdentities?.tasks?.close()
        if (currentRepository != null && currentScope != null && currentIdentities != null) {
            ru.arc.repository.sealRepositoryOperations(currentScope)
            ARC.instance.retireInBackground { PreparedRepositories(currentRepository, currentIdentities, currentScope).drain() }
                .whenComplete { _, failure -> if (failure != null) error("Could not drain personal loot", failure) }
        } else {
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
        if (event.isCancelled) return
        processChestOpen(event, ContainerProtection()::allows)
    }

    private data class ContainerMarker(val uuid: UUID?, val players: Set<UUID>, val pool: String?)
    private enum class OpenDenial { FULL, ALREADY_OPENED }
    private data class PreparedOpen(
        val markers: List<ContainerMarker>,
        val group: PersonalLootGroup,
        val loot: CustomLootData?,
        val denial: OpenDenial? = null,
    )

    internal fun processChestOpen(
        event: InventoryOpenEvent,
        protectionAllows: (Player, Block) -> Boolean,
    ) {
        if (event.isCancelled) return
        val repository = repo ?: return
        val identityStore = identities ?: return
        val activeScope = repositoryScope ?: return
        if (event.inventory.type !in inventories) return

        val player = event.player as? Player ?: return
        val blocks = physicalContainerBlocks(event) ?: return
        if (!isCurrentPhysicalContainer(player, blocks)) return
        val block = event.inventory.location?.block ?: blocks.firstOrNull() ?: return
        if (block !in blocks || blocks.none { CustomBlockData(it, ARC.instance).has(uuidKey) }) return
        event.isCancelled = true
        if (!containerAccessAllowed(player, blocks, protectionAllows)) return
        val markers = readContainerMarkers(blocks) ?: return
        val playerUuid = player.uniqueId
        if (!identityStore.admit(playerUuid)) return
        val location = block.location.toString()
        val useBetterStructuresLoot = useBsLoot
        val currentItems = if (useBetterStructuresLoot) extractItems(block).map { it.clone() } else emptyList()
        val token = identityStore.tasks.token()
        val job = activeScope.launch {
            try {
                identityStore.serialized {
                    val resolved = identityStore.resolve(markerClaims(markers))
                    val linked = identityStore.tasks.callSync(token) {
                        if (!canFinishOpen(player, blocks, markers, repository, identityStore, protectionAllows)) return@callSync null
                        val currentMarkers = readContainerMarkers(blocks) ?: return@callSync null
                        PreparedOpen(currentMarkers, resolved.withPlayers(currentMarkers.flatMap { it.players }), null)
                    }.await() ?: return@serialized
                    // Link every original UUID before making a reward. After a crash or a split,
                    // either half must find the same canonical record even if claim commit was interrupted.
                    identityStore.persist(linked.group)
                    val group = linked.group
                    val records = group.members.associateWith { chestUuid ->
                        val id = "$playerUuid$PERSONAL_LOOT_SEPARATOR$chestUuid"
                        val record = if (chestUuid == group.canonical) {
                            repository.getOrCreate(id) { CustomLootData.create(playerUuid, chestUuid) }.getOrThrow()
                        } else repository.get(id).getOrThrow()
                        require(record == null || (record.playerUuid == playerUuid && record.chestUuid == chestUuid)) {
                            "Personal loot record identity differs from its storage key"
                        }
                        record
                    }
                    val opening = identityStore.tasks.callSync(token) {
                        if (!canFinishOpen(player, blocks, markers, repository, identityStore, protectionAllows)) return@callSync null
                        val currentMarkers = readContainerMarkers(blocks) ?: return@callSync null
                        var currentGroup = group.withPlayers(currentMarkers.flatMap { it.players })
                        val existing = records.values.filterNotNull()
                        // A persisted old record is also evidence of a claim if block metadata was stale.
                        if (existing.any { !it.needsItems() }) currentGroup = currentGroup.withPlayers(listOf(playerUuid))
                        if (currentGroup.players.size >= maxPlayers && playerUuid !in currentGroup.players) {
                            return@callSync PreparedOpen(currentMarkers, currentGroup, null, OpenDenial.FULL)
                        }
                        val remaining = existing.firstOrNull { !it.needsItems() && !it.isExhausted() }
                        if (remaining == null && playerUuid in currentGroup.players) {
                            return@callSync PreparedOpen(currentMarkers, currentGroup, null, OpenDenial.ALREADY_OPENED)
                        }
                        val loot = remaining ?: checkNotNull(records[group.canonical]).also {
                            val poolName = currentMarkers.firstOrNull { marker -> marker.uuid == group.canonical }?.pool
                                ?: currentMarkers.firstOrNull { marker -> marker.uuid != null }?.pool
                            val generated = if (useBetterStructuresLoot) ItemList().apply { addAll(currentItems) }
                                else chestGenerator.generate(player, poolName)
                            if (it.fillIfEmpty(generated)) repository.markDirty(it)
                        }
                        if (loot.addDebrisIfNeeded()) repository.markDirty(loot)
                        PreparedOpen(currentMarkers, currentGroup.withPlayers(listOf(playerUuid)), loot)
                    }.await() ?: return@serialized

                    // Persist the reward first. If the following alias write has an unknown outcome,
                    // retry finds this exact reward rather than rolling another one.
                    if (opening.loot != null) {
                        repository.saveDirty().getOrThrow()
                        // Finish any older background snapshot before marking this stable, unopened
                        // reward again; its mark must not be cleared by that older snapshot.
                        repository.markDirty(opening.loot)
                        repository.saveDirty().getOrThrow()
                    }
                    identityStore.persist(opening.group)
                    identityStore.tasks.callSync(token) {
                        if (!canFinishOpen(player, blocks, opening.markers, repository, identityStore, protectionAllows)) return@callSync
                        if (readContainerMarkers(blocks) != opening.markers) return@callSync
                        applyContainerIdentity(blocks, opening.markers, opening.group)
                        when (opening.denial) {
                            OpenDenial.FULL -> player.sendMessage(config.component(
                                "messages.max-players", "<red>Слишком много игроков уже открыли этот сундук!",
                            ) { tag("amount", opening.group.players.size) })
                            OpenDenial.ALREADY_OPENED -> player.sendMessage(config.component(
                                "messages.already-opened", "<red>Вы уже открывали этот сундук",
                            ))
                            null -> {
                                if (!useBetterStructuresLoot) extractInventory(block)?.clear()
                                LootGuiFactory.open(player, checkNotNull(opening.loot))
                            }
                        }
                    }.await()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error("Failed to prepare personal loot for {} at {}", playerUuid, location, failure)
                identityStore.tasks.runSync(token) {
                    if (repo === repository && identities === identityStore && player.isOnline) {
                        player.sendMessage(config.component(
                            "messages.load-error", "<red>Не удалось загрузить содержимое сундука. Попробуйте ещё раз.",
                        ))
                    }
                }
            }
        }
        job.invokeOnCompletion { identityStore.release(playerUuid) }
    }

    private fun canFinishOpen(
        player: Player,
        blocks: List<Block>,
        markers: List<ContainerMarker>,
        repository: CachedRepository<CustomLootData>,
        identityStore: PersonalLootIdentities,
        protectionAllows: (Player, Block) -> Boolean,
    ): Boolean = repo === repository && identities === identityStore && player.isOnline &&
        isCurrentPhysicalContainer(player, blocks) &&
        readContainerMarkers(blocks)?.map { it.uuid } == markers.map { it.uuid } &&
        containerAccessAllowed(player, blocks, protectionAllows)

    /** Missing or malformed markers fail closed; an unmarked legacy companion is safe to adopt. */
    private fun readContainerMarkers(blocks: List<Block>): List<ContainerMarker>? = try {
        blocks.map { block ->
            val data = CustomBlockData(block, ARC.instance)
            if (!data.has(uuidKey)) ContainerMarker(null, emptySet(), null)
            else {
                val uuid = requireNotNull(parsePersonalLootUuid(data.get(uuidKey, PersistentDataType.STRING)))
                val rawPlayers = requireNotNull(data.get(key, PersistentDataType.STRING))
                val players = if (rawPlayers.isEmpty()) emptySet() else rawPlayers.split(PERSONAL_LOOT_SEPARATOR).map {
                    requireNotNull(parsePersonalLootUuid(it))
                }.toSet()
                ContainerMarker(uuid, players, data.get(poolKey, PersistentDataType.STRING))
            }
        }.also { markers ->
            val commonPool = config.string("loot.common-pool", "structures_common").trim().lowercase(Locale.ROOT)
            val pools = markers.filter { it.uuid != null }.map { marker ->
                when (val pool = marker.pool?.trim()?.lowercase(Locale.ROOT)) {
                    null, "", "default", "generic_bs" -> commonPool
                    else -> pool
                }
            }.distinct()
            require(pools.size <= 1) { "Conflicting personal loot pools" }
        }
    } catch (failure: Exception) {
        warn("Personal loot chest has invalid identity or claims at {}: {}", blocks.firstOrNull()?.location, failure.message)
        null
    }

    private fun markerClaims(markers: List<ContainerMarker>): Map<UUID, Set<UUID>> =
        markers.filter { it.uuid != null }.groupBy { checkNotNull(it.uuid) }
            .mapValues { (_, sameIdentity) -> sameIdentity.flatMap { it.players }.toSet() }

    private fun applyContainerIdentity(blocks: List<Block>, markers: List<ContainerMarker>, group: PersonalLootGroup) {
        val players = group.players.sortedBy(UUID::toString).joinToString(PERSONAL_LOOT_SEPARATOR)
        val sourcePool = markers.firstOrNull { it.uuid == group.canonical }?.pool
            ?: markers.firstOrNull { it.uuid != null }?.pool
        for ((index, block) in blocks.withIndex()) {
            val data = CustomBlockData(block, ARC.instance)
            data.set(key, PersistentDataType.STRING, players)
            data.set(uuidKey, PersistentDataType.STRING, group.canonical.toString())
            if (markers[index].uuid == null && sourcePool != null) data.set(poolKey, PersistentDataType.STRING, sourcePool)
        }
        if (markers.mapNotNull { it.uuid }.distinct().size > 1 || markers.any { it.uuid != group.canonical }) {
            info("Repaired personal loot chest at {}: aliases={}, canonical={}, claims={}",
                blocks.first().location, group.members, group.canonical, group.players.size)
        }
    }

    /** Resolves Bukkit's actual inventory holder and its physical chest halves without reading slots. */
    private fun physicalContainerBlocks(event: InventoryOpenEvent): List<Block>? {
        val holderBlocks = when (val holder = event.inventory.holder) {
            is DoubleChest -> {
                val left = (holder.leftSide as? BlockState)?.block ?: return null
                val right = (holder.rightSide as? BlockState)?.block ?: return null
                listOf(left, right)
            }
            is BlockState -> listOf(holder.block)
            else -> null
        }
        val blocks = holderBlocks ?: event.inventory.location?.let { listOf(it.block) } ?: return null
        if (blocks.size != 1) return blocks.distinct().takeIf { it.size == blocks.size }

        val block = blocks.single()
        val chestData = block.blockData as? ChestData ?: return blocks
        if (chestData.type == ChestData.Type.SINGLE) return blocks
        val direction = chestPartnerDirection(chestData.facing, chestData.type) ?: return null
        return listOf(block, block.getRelative(direction))
    }

    /** Validates that the captured holder still describes the player's current physical container. */
    private fun isCurrentPhysicalContainer(player: Player, blocks: List<Block>): Boolean =
        isCurrentPhysicalContainer(player.world.uid, blocks)

    private fun isCurrentPhysicalContainer(worldUuid: UUID, blocks: List<Block>): Boolean {
        return try {
            if (blocks.isEmpty() || blocks.size > 2 || blocks.distinct().size != blocks.size) return false
            if (blocks.any { it.world.uid != worldUuid || !it.world.isChunkLoaded(it.x shr 4, it.z shr 4) || it.type !in chests }) return false
            if (blocks.size == 1) {
                val block = blocks.single()
                if (block.type == Material.BARREL) true
                else (block.blockData as? ChestData)?.type == ChestData.Type.SINGLE
            } else {
                val first = blocks[0]
                val second = blocks[1]
                if (first.type != second.type || first.type == Material.BARREL || first.y != second.y) return false
                val firstData = first.blockData as? ChestData ?: return false
                val secondData = second.blockData as? ChestData ?: return false
                if (firstData.facing != secondData.facing || firstData.type == ChestData.Type.SINGLE ||
                    secondData.type == ChestData.Type.SINGLE || firstData.type == secondData.type) return false
                val direction = chestPartnerDirection(firstData.facing, firstData.type) ?: return false
                first.getRelative(direction) == second
            }
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun containerAccessAllowed(
        player: Player,
        blocks: List<Block>,
        protectionAllows: (Player, Block) -> Boolean,
    ): Boolean = try {
        blocks.all { protectionAllows(player, it) }
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    @JvmStatic
    fun processChestGen(block: Block) {
        if (repo == null) return
        if (block.type !in chests) return
        val chestData = block.blockData as? ChestData
        val partner = chestData?.takeIf { it.type != ChestData.Type.SINGLE }
            ?.let { chestPartnerDirection(it.facing, it.type) }?.let(block::getRelative)
        val blocks = if (partner != null && partner.type == block.type) listOf(block, partner) else listOf(block)
        // BetterStructures may refill a container. Its identity and previous claims remain authoritative.
        if (blocks.any { CustomBlockData(it, ARC.instance).has(uuidKey) }) {
            repairGeneratedContainer(blocks)
            return
        }
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

    /** Generation can encounter halves marked independently before Bukkit joined the chest. */
    private fun repairGeneratedContainer(blocks: List<Block>) {
        val identityStore = identities ?: return
        val repository = repo ?: return
        val scope = repositoryScope ?: return
        val markers = readContainerMarkers(blocks) ?: return
        val location = blocks.first().location.toString()
        val token = identityStore.tasks.token()
        scope.launch {
            try {
                identityStore.serialized {
                    val group = identityStore.resolve(markerClaims(markers))
                    val valid = identityStore.tasks.callSync(token) {
                        repo === repository && identities === identityStore &&
                            isCurrentPhysicalContainer(blocks.first().world.uid, blocks) && readContainerMarkers(blocks) == markers
                    }.await()
                    if (!valid) return@serialized
                    identityStore.persist(group)
                    identityStore.tasks.callSync(token) {
                        if (repo !== repository || identities !== identityStore || readContainerMarkers(blocks) != markers) return@callSync
                        if (!isCurrentPhysicalContainer(blocks.first().world.uid, blocks)) return@callSync
                        applyContainerIdentity(blocks, markers, group)
                    }.await()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error("Failed to repair generated personal loot chest at {}", location, failure)
            }
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

        val markers = readContainerMarkers(blocks) ?: return PersonalLootPreview.Unavailable
        if (markers.any { it.uuid != chestUuid }) return PersonalLootPreview.Unavailable
        val playerSets = markers.map { it.players }
        if (playerSets.distinct().size != 1) return PersonalLootPreview.Unavailable
        val identityStore = identities ?: return PersonalLootPreview.Unavailable
        val group = try {
            identityStore.cached(chestUuid) ?: run {
                previewReadState("identity:$chestUuid", repository, scope) {
                    identityStore.readForPreview(chestUuid)
                    false
                }
                return PersonalLootPreview.Unavailable
            }
        } catch (_: Exception) {
            return PersonalLootPreview.Unavailable
        }
        val openedPlayers = playerSets.first() + group.players
        if (openedPlayers.size >= maxPlayers && viewer !in openedPlayers) {
            return PersonalLootPreview.Unavailable
        }

        val useBetterStructuresLoot = useBsLoot
        val stored = mutableListOf<CustomLootData>()
        for (member in group.members) {
            val id = "$viewer$PERSONAL_LOOT_SEPARATOR$member"
            val record = repository.getNow(id)
            if (record != null) stored += record
            else if (previewReadState(id, repository, scope) != PreviewReadState.ABSENT) return PersonalLootPreview.Unavailable
        }
        stored.firstOrNull { !it.needsItems() && !it.isExhausted() }?.let {
            return previewStoredLoot(it, useBetterStructuresLoot)
        }
        if (viewer in openedPlayers || stored.any { !it.needsItems() }) return PersonalLootPreview.Contents(emptyList())
        return previewAbsent(useBetterStructuresLoot)
    }

    private fun previewReadState(
        id: String,
        repository: CachedRepository<CustomLootData>,
        scope: CoroutineScope,
        readAbsent: (suspend () -> Boolean)? = null,
    ): PreviewReadState {
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
        if (startRead) startPreviewRead(id, repository, scope, generation, readAbsent)

        return readState
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
        readAbsent: (suspend () -> Boolean)?,
    ) {
        val job = scope.launch {
            val state = try {
                if (readAbsent?.invoke() ?: (repository.get(id).getOrThrow() == null)) {
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

internal data class PersonalLootPoolSettings(
    val commonPool: String,
    val minRolls: Int,
    val maxRolls: Int,
    val bonusPool: String,
    val bonusChance: Double,
)

internal fun parsePersonalLootUuid(raw: String?): UUID? =
    raw?.let { value -> runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString().equals(value, ignoreCase = true) } }

internal fun parsePersonalLootPlayers(raw: String): Set<UUID> =
    raw
        .split(PERSONAL_LOOT_SEPARATOR)
        .mapNotNull(::parsePersonalLootUuid)
        .toSet()
