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
import ru.arc.util.GuiUtils
import ru.arc.util.ItemUtils.connectedChests
import ru.arc.util.ItemUtils.extractInventory
import ru.arc.util.ItemUtils.extractItems
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import java.util.UUID
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
) : Entity,
    Mergeable<CustomLootData> {
    override fun id(): String = "$playerUuid$PERSONAL_LOOT_SEPARATOR$chestUuid"

    override fun merge(other: CustomLootData) {
        val (otherItems, otherFilled, otherTimestamp) = other.persistedSnapshot()
        synchronized(this) {
            items.clear()
            items.addAll(otherItems)
            filled = otherFilled
            timestamp = otherTimestamp
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
            items.map { it?.clone() }
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

    private fun persistedSnapshot(): Triple<List<ItemStack?>, Boolean, Long> =
        synchronized(this) {
            Triple(
                items.map { it?.clone() },
                filled,
                timestamp,
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
        repo = newRepository
        repositoryScope = newScope
    }

    @JvmStatic
    fun shutdown() {
        val currentRepository = repo
        val currentScope = repositoryScope
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

        GuiUtils.constructAndShowAsync({ LootGuiFactory.create(player, lootData) }, player)
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
     * Save a loot data entry.
     */
    @JvmStatic
    fun save(lootData: CustomLootData) {
        repo?.markDirty(lootData)
    }
}

internal fun parsePersonalLootUuid(raw: String?): UUID? =
    raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

internal fun parsePersonalLootPlayers(raw: String): Set<UUID> =
    raw
        .split(PERSONAL_LOOT_SEPARATOR)
        .mapNotNull(::parsePersonalLootUuid)
        .toSet()
