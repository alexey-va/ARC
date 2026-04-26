package ru.arc.bschests

import com.jeff_media.customblockdata.CustomBlockData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.network.repos.ItemList
import ru.arc.repository.CachedRepository
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import ru.arc.repository.redisRepo
import ru.arc.util.GuiUtils
import ru.arc.util.ItemUtils.connectedChests
import ru.arc.util.ItemUtils.extractInventory
import ru.arc.util.ItemUtils.extractItems
import ru.arc.util.Logging.warn
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

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
    @Transient
    var isDirty: Boolean = false

    override fun id(): String = "$playerUuid:::$chestUuid"

    override fun merge(other: CustomLootData) {
        items.clear()
        items.addAll(other.items)
    }

    /**
     * Check if this entry should be removed.
     */
    fun shouldRemove(): Boolean {
        val ttl = 1000L * 60 * 60 * 24 * 7 // 7 days
        return System.currentTimeMillis() - timestamp > ttl || (filled && items.isEmpty())
    }

    /**
     * Check if all items have been taken.
     */
    fun isExhausted(): Boolean = filled && items.all { it == null }

    /**
     * Remove an item from the loot.
     */
    fun removeItem(
        item: ItemStack,
        slot: Int,
    ) {
        var amountLeft = tryRemoveSlotItem(item, slot)
        if (amountLeft == 0) {
            isDirty = true
            return
        }
        for (i in 0 until items.size) {
            amountLeft = tryRemoveSlotItem(item, i)
            if (amountLeft == 0) {
                isDirty = true
                return
            }
        }
        ru.arc.util.Logging
            .error("Item not found in chest: {} {}", item, slot)
    }

    private fun tryRemoveSlotItem(
        item: ItemStack,
        slot: Int,
    ): Int {
        if (items.size <= slot) return item.amount
        val itemStack = items[slot] ?: return item.amount

        if (itemStack.isSimilar(item)) {
            val amount = itemStack.amount
            if (amount > item.amount) {
                itemStack.amount = amount - item.amount
                items[slot] = itemStack
                return 0
            } else {
                items[slot] = null
                return item.amount - amount
            }
        }
        return item.amount
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
    private const val SEPARATOR = ":::"

    private lateinit var repo: CachedRepository<CustomLootData>
    private lateinit var chestGenerator: ChestGenerator
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var initialized = false

    @JvmStatic
    fun init() {
        if (initialized) return

        key = NamespacedKey(ARC.instance, "ploot")
        uuidKey = NamespacedKey(ARC.instance, "ploot_uuid")
        poolKey = NamespacedKey(ARC.instance, "ploot_pool")
        breakKey = NamespacedKey(ARC.instance, "ploot_break")

        reload()

        val storageKey = "arc.${ARC.serverName}-ploot"
        val updateChannel = "arc.${ARC.serverName}-ploot-update"

        repo =
            redisRepo<CustomLootData>(
                id = "${ARC.serverName}-ploot",
                storageKey = storageKey,
                updateChannel = updateChannel,
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
                config.componentDef(
                    "messages.break",
                    "<red>Этот сундук нужно сломать еще <amount> раз",
                    "<amount>",
                    (config.integer("max-breaks", 3) - breaks).toString(),
                ),
            )
        }
    }

    @JvmStatic
    fun processChestOpen(event: InventoryOpenEvent) {
        if (event.inventory.type !in inventories) return

        val player = event.player as? Player ?: return
        val location = event.inventory.location ?: return
        val block = location.block
        val blocks = connectedChests(block)

        val data = CustomBlockData(block, ARC.instance)
        if (!data.has(uuidKey)) return

        val chestUuid = UUID.fromString(data.get(uuidKey, PersistentDataType.STRING))
        val playerListString = data.get(key, PersistentDataType.STRING)
        if (playerListString == null) {
            warn("Player list string is null")
            return
        }

        val players =
            playerListString
                .split(SEPARATOR)
                .filter { it.isNotEmpty() && it.length == 36 }
                .map { UUID.fromString(it) }
                .toMutableSet()

        event.isCancelled = true

        if (players.size >= maxPlayers && player.uniqueId !in players) {
            player.sendMessage(
                config.componentDef(
                    "messages.max-players",
                    "<red>Слишком много игроков уже открыли этот сундук!",
                    "%amount%",
                    players.size.toString(),
                ),
            )
            return
        }

        players.add(player.uniqueId)
        for (b in blocks) {
            val bData = CustomBlockData(b, ARC.instance)
            bData.set(key, PersistentDataType.STRING, players.joinToString(SEPARATOR) { it.toString() })
        }

        val poolName = data.get(poolKey, PersistentDataType.STRING)
        val currentItems = blocks.flatMap { extractItems(it) }.map { it.clone() }

        if (!useBsLoot) {
            extractInventory(block)?.clear()
        }

        scope.launch {
            val lootId = "${player.uniqueId}$SEPARATOR$chestUuid"
            val cl =
                repo
                    .getOrCreate(lootId) {
                        CustomLootData.create(player.uniqueId, chestUuid)
                    }.getOrNull()

            if (cl == null || cl.isExhausted()) {
                player.sendMessage(
                    config.componentDef("messages.already-opened", "<red>Вы уже открывали этот сундук"),
                )
                return@launch
            }

            if (!cl.filled && cl.items.isEmpty()) {
                val generated =
                    if (useBsLoot) {
                        ItemList().apply { addAll(currentItems) }
                    } else {
                        chestGenerator.generate(poolName ?: "default", 5, 27)
                    }
                cl.items.addAll(generated)
                cl.filled = true
                cl.isDirty = true
                repo.save(cl)
            }

            GuiUtils.constructAndShowAsync({ LootGuiFactory.create(player, cl) }, player)
        }
    }

    @JvmStatic
    fun processChestGen(block: Block) {
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
        scope.launch {
            repo.save(lootData)
        }
    }
}
