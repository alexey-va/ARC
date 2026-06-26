package ru.arc.board

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.boss.BarColor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.BoardConfig
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.repository.CachedRepository
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import ru.arc.repository.redisRepo
import ru.arc.util.TextUtil
import ru.arc.util.Logging
import ru.arc.util.Logging.withContext
import ru.arc.xaction.XCondition
import ru.arc.xserver.XMessage
import ru.arc.xserver.announcements.AnnounceManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Board entry entity representing an ad/announcement on the board.
 */
data class BoardEntryData(
    val entryUuid: UUID,
    val playerUuid: UUID,
    val playerName: String,
    var type: BoardEntryType,
    var text: String,
    var title: String,
    var icon: ItemIcon,
    var color: BarColor = BarColor.YELLOW,
    val timestamp: Long = System.currentTimeMillis(),
    var lastShown: Long = 0L,
    val positiveRatings: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    val negativeRatings: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    val reports: MutableSet<String> = ConcurrentHashMap.newKeySet(),
) : Entity,
    Mergeable<BoardEntryData> {
    @Transient
    private var _tagResolver: TagResolver? = null

    override fun id(): String = entryUuid.toString()

    override fun merge(other: BoardEntryData) {
        type = other.type
        text = other.text
        title = other.title
        icon = other.icon
        color = other.color
        lastShown = other.lastShown

        positiveRatings.clear()
        positiveRatings.addAll(other.positiveRatings)

        negativeRatings.clear()
        negativeRatings.addAll(other.negativeRatings)

        reports.clear()
        reports.addAll(other.reports)

        _tagResolver = null
    }

    /**
     * Check if this entry has expired.
     */
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - timestamp > BoardConfig.secondsLifetime * 1000L

    /**
     * Get remaining time until expiration.
     */
    fun tillExpire(nowMillis: Long = System.currentTimeMillis()): Long =
        BoardConfig.secondsLifetime * 1000L + timestamp - nowMillis

    /**
     * Create an ItemStack representation of this entry.
     */
    fun toItemStack(): ItemStack {
        val stack = icon.stack()
        val meta = stack.itemMeta
        val resolver = tagResolver()

        val display =
            TextUtil.strip(
                MiniMessage.miniMessage().deserialize(BoardConfig.getString("item.display"), resolver),
            )
        meta.displayName(display)
        meta.lore(buildLore(resolver))
        meta.addItemFlags(
            ItemFlag.HIDE_ATTRIBUTES,
            ItemFlag.HIDE_ENCHANTS,
            ItemFlag.HIDE_DESTROYS,
            ItemFlag.HIDE_DYE,
            ItemFlag.HIDE_ARMOR_TRIM,
            ItemFlag.HIDE_PLACED_ON,
            ItemFlag.HIDE_UNBREAKABLE,
        )
        stack.itemMeta = meta
        return stack
    }

    /**
     * Build the tag resolver for this entry.
     */
    fun tagResolver(): TagResolver {
        _tagResolver?.let { return it }

        val shortName =
            if (title.isBlank()) {
                TextUtil.strip(Component.text("Нет", NamedTextColor.RED)) ?: Component.empty()
            } else {
                TextUtil.strip(LegacyComponentSerializer.legacyAmpersand().deserialize(title)) ?: Component.empty()
            }

        val bossBarColors = config.map("boss-bar-colors", DEFAULT_BOSS_BAR_COLORS)
        val colorText = bossBarColors[color.toString().lowercase()] ?: "<gray>Неизвестный цвет</gray>"
        val colorComponent = TextUtil.mm(colorText, true)

        val resolver =
            TagResolver
                .builder()
                .resolver(TagResolver.resolver("short_name", Tag.inserting(shortName)))
                .resolver(TagResolver.resolver("player", Tag.inserting(Component.text(playerName))))
                .resolver(TagResolver.resolver("type", Tag.inserting(type.displayName)))
                .resolver(
                    TagResolver.resolver(
                        "expire",
                        Tag.inserting(TextUtil.parseTime(tillExpire(), TimeUnit.MILLISECONDS)),
                    ),
                ).resolver(
                    TagResolver.resolver(
                        "positive",
                        Tag.inserting(
                            if (positiveRatings.isEmpty()) {
                                Component.text("Нет", NamedTextColor.GRAY)
                            } else {
                                Component.text(positiveRatings.size, NamedTextColor.GREEN)
                            },
                        ),
                    ),
                ).resolver(
                    TagResolver.resolver(
                        "negative",
                        Tag.inserting(
                            if (negativeRatings.isEmpty()) {
                                Component.text("Нет", NamedTextColor.GRAY)
                            } else {
                                Component.text(negativeRatings.size, NamedTextColor.RED)
                            },
                        ),
                    ),
                ).resolver(TagResolver.resolver("boss_color", Tag.inserting(colorComponent)))
                .resolver(
                    TagResolver.resolver(
                        "reports",
                        Tag.inserting(
                            if (reports.isEmpty()) {
                                Component.text("Нет", NamedTextColor.GREEN)
                            } else {
                                Component.text(reports.size)
                            },
                        ),
                    ),
                ).build()

        _tagResolver = resolver
        return resolver
    }

    private fun buildLore(resolver: TagResolver): List<Component> {
        val lore = mutableListOf<Component>()

        for (s in BoardConfig.getStringList("item.lore")) {
            if (s.contains("<description>")) {
                lore.addAll(buildDescription(resolver))
                continue
            }
            lore.add(TextUtil.strip(MiniMessage.miniMessage().deserialize(s, resolver)) ?: Component.empty())
        }

        return lore
    }

    private fun buildDescription(resolver: TagResolver): List<Component> {
        if (text.isBlank()) return emptyList()

        val words = text.split(" ")
        val builder = StringBuilder()
        var count = 0
        val components = mutableListOf<Component>()

        for (word in words) {
            builder.append(word).append(" ")
            count += word.length + 1
            if (count >= 40) {
                var component: Component =
                    TextUtil.strip(
                        LegacyComponentSerializer.legacyAmpersand().deserialize(builder.toString()),
                    ) ?: Component.empty()
                if (components.isNotEmpty()) {
                    val children = components.last().children()
                    if (children.isNotEmpty()) {
                        val style = children.last().style()
                        val pre = Component.text("", style)
                        component = pre.append(component)
                    }
                }
                components.add(component)
                count = 0
                builder.clear()
            }
        }

        if (builder.isNotEmpty()) {
            var component: Component =
                TextUtil.strip(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(builder.toString()),
                ) ?: Component.empty()
            if (components.isNotEmpty()) {
                val style = components.last().style()
                val pre = Component.text("", style)
                component = pre.append(component)
            }
            components.add(component)
        }

        val prefix: Component =
            TextUtil.strip(
                MiniMessage.miniMessage().deserialize(BoardConfig.getString("item.description-prefix")),
            ) ?: Component.empty()

        val formattedComponents =
            components.map { c ->
                (TextUtil.strip(prefix.append(c)) ?: Component.empty()).color(NamedTextColor.GRAY)
            }

        val result = mutableListOf<Component>()
        for (s in BoardConfig.getStringList("item.description")) {
            if (s.contains("<description_text>")) {
                result.addAll(formattedComponents)
                continue
            }
            result.add(TextUtil.strip(MiniMessage.miniMessage().deserialize(s, resolver)) ?: Component.empty())
        }

        return result
    }

    /**
     * Check if a player has rated this entry.
     * @return 1 for positive, -1 for negative, 0 for no rating
     */
    fun hasRated(player: Player): Int =
        when {
            positiveRatings.contains(player.name) -> 1
            negativeRatings.contains(player.name) -> -1
            else -> 0
        }

    /**
     * Check if a player has reported this entry.
     */
    fun hasReported(player: Player): Boolean = reports.contains(player.name)

    /**
     * Check if a player can edit this entry.
     */
    fun canEdit(player: Player): Boolean = player.uniqueId == playerUuid || player.hasPermission("arc.board.admin")

    /**
     * Check if a player can rate this entry.
     */
    fun canRate(player: Player): Boolean = player.uniqueId != playerUuid || player.hasPermission("arc.rate-own")

    /**
     * Rate this entry.
     * @param name player name
     * @param rating 1 for positive, -1 for negative
     */
    fun rate(
        name: String,
        rating: Int,
    ) {
        when (rating) {
            1 -> {
                negativeRatings.remove(name)
                positiveRatings.add(name)
            }

            -1 -> {
                positiveRatings.remove(name)
                negativeRatings.add(name)
            }
        }
        _tagResolver = null
    }

    /**
     * Report this entry.
     */
    fun report(name: String) {
        reports.add(name)
        _tagResolver = null
    }

    /**
     * Change the entry text.
     */
    fun changeText(newText: String) {
        if (text != newText) {
            text = newText
            _tagResolver = null
        }
    }

    /**
     * Change the entry title.
     */
    fun changeTitle(newTitle: String) {
        if (title != newTitle) {
            title = newTitle
            _tagResolver = null
        }
    }

    /**
     * Change the entry icon.
     */
    fun changeIcon(newIcon: ItemIcon) {
        if (icon != newIcon) {
            icon = newIcon
            _tagResolver = null
        }
    }

    /**
     * Change the entry type.
     */
    fun changeType(newType: BoardEntryType) {
        if (type != newType) {
            type = newType
            _tagResolver = null
        }
    }

    /**
     * Change the entry color.
     */
    fun changeColor(newColor: BarColor) {
        if (color != newColor) {
            color = newColor
            _tagResolver = null
        }
    }

    /**
     * Change the last shown timestamp.
     */
    fun changeLastShown(time: Long) {
        if (lastShown != time) {
            lastShown = time
        }
    }

    companion object {
        private val config: Config = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "board.yml")

        private val DEFAULT_BOSS_BAR_COLORS =
            mapOf(
                "blue" to "<blue>Синий</blue>",
                "red" to "<red>Красный</red>",
                "green" to "<green>Зелёный</green>",
                "pink" to "<light_purple>Розовый</light_purple>",
                "purple" to "<purple>Фиолетовый</purple>",
                "white" to "<white>Белый</white>",
                "yellow" to "<yellow>Жёлтый</yellow>",
            )

        /**
         * Build description components for a text.
         */
        @JvmStatic
        fun description(
            resolver: TagResolver,
            text: String?,
        ): List<Component> {
            if (text.isNullOrBlank()) return emptyList()

            val words = text.split(" ")
            val builder = StringBuilder()
            var count = 0
            val components = mutableListOf<Component>()

            for (word in words) {
                builder.append(word).append(" ")
                count += word.length + 1
                if (count >= 40) {
                    var component: Component =
                        TextUtil.strip(
                            LegacyComponentSerializer.legacyAmpersand().deserialize(builder.toString()),
                        ) ?: Component.empty()
                    if (components.isNotEmpty()) {
                        val children = components.last().children()
                        if (children.isNotEmpty()) {
                            val style = children.last().style()
                            val pre = Component.text("", style)
                            component = pre.append(component)
                        }
                    }
                    components.add(component)
                    count = 0
                    builder.clear()
                }
            }

            if (builder.isNotEmpty()) {
                var component: Component =
                    TextUtil.strip(
                        LegacyComponentSerializer.legacyAmpersand().deserialize(builder.toString()),
                    ) ?: Component.empty()
                if (components.isNotEmpty()) {
                    val style = components.last().style()
                    val pre = Component.text("", style)
                    component = pre.append(component)
                }
                components.add(component)
            }

            val prefix: Component =
                TextUtil.strip(
                    MiniMessage.miniMessage().deserialize(BoardConfig.getString("item.description-prefix")),
                ) ?: Component.empty()

            val formattedComponents =
                components.map { c ->
                    (TextUtil.strip(prefix.append(c)) ?: Component.empty()).color(NamedTextColor.GRAY)
                }

            val result = mutableListOf<Component>()
            for (s in BoardConfig.getStringList("item.description")) {
                if (s.contains("<description_text>")) {
                    result.addAll(formattedComponents)
                    continue
                }
                result.add(TextUtil.strip(MiniMessage.miniMessage().deserialize(s, resolver)) ?: Component.empty())
            }

            return result
        }
    }
}

/**
 * Board entry types.
 */
enum class BoardEntryType(
    val displayName: Component,
    val icon: Material,
    val defaultColor: BarColor,
) {
    BUY(
        TextUtil.strip(LegacyComponentSerializer.legacyAmpersand().deserialize("&aПокупаю")) ?: Component.empty(),
        Material.GOLD_INGOT,
        BarColor.RED,
    ),
    SELL(
        TextUtil.strip(LegacyComponentSerializer.legacyAmpersand().deserialize("&eПродаю")) ?: Component.empty(),
        Material.CHEST,
        BarColor.BLUE,
    ),
    LOOKING_FOR(
        TextUtil.strip(LegacyComponentSerializer.legacyAmpersand().deserialize("&6Ищу человека")) ?: Component.empty(),
        Material.PLAYER_HEAD,
        BarColor.YELLOW,
    ),
    INFO(
        TextUtil.strip(LegacyComponentSerializer.legacyAmpersand().deserialize("&3Сообщаю")) ?: Component.empty(),
        Material.FLOWER_BANNER_PATTERN,
        BarColor.WHITE,
    ),
}

/**
 * Cached item representation of a board entry.
 */
data class BoardItem(
    val entry: BoardEntryData,
    val stack: ItemStack,
)

/**
 * Cache for board item representations.
 */
class BoardEntryCache {
    private val cache = ConcurrentHashMap<UUID, BoardItem>()

    fun get(entry: BoardEntryData): BoardItem = cache.getOrPut(entry.entryUuid) { generate(entry) }

    fun refresh(entry: BoardEntryData) {
        cache[entry.entryUuid] = generate(entry)
    }

    fun clear() {
        cache.clear()
    }

    fun remove(uuid: UUID) {
        cache.remove(uuid)
    }

    private fun generate(entry: BoardEntryData): BoardItem = BoardItem(entry, entry.toItemStack())
}

/**
 * Pick the next live entry to announce (oldest lastShown). Expired entries are ignored.
 */
internal fun selectNextAnnounceEntry(entries: Collection<BoardEntryData>): BoardEntryData? =
    entries.filter { !it.isExpired() }.minByOrNull { it.lastShown }

/**
 * Manager for the board system.
 */
object BoardManager {
    private lateinit var repo: CachedRepository<BoardEntryData>
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var initialized = false

    private val cache = BoardEntryCache()
    private var updateCacheJob: kotlinx.coroutines.Job? = null
    private var announceJob: kotlinx.coroutines.Job? = null
    private var purgeJob: kotlinx.coroutines.Job? = null

    private val config: Config get() = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "board.yml")

    @JvmStatic
    fun init() {
        if (initialized) return
        if (ARC.redisManager == null) {
            Logging.info("Redis not available — Board feature disabled")
            return
        }

        repo =
            redisRepo<BoardEntryData>(
                id = "board",
                storageKey = "arc.board",
                updateChannel = "arc.board_update",
                scope = scope,
            ) {
                loadAllOnStart(true)
                saveInterval(1.seconds)
            }
        setupTasks()
        initialized = true
    }

    @JvmStatic
    fun shutdown() {
        if (!initialized) return
        cancelTasks()
        runBlocking { repo.shutdown() }
        initialized = false
    }

    private fun setupTasks() {
        cancelTasks()

        // Update cache periodically
        updateCacheJob =
            scope.launch {
                while (isActive) {
                    delay(60_000)
                    updateAllCache()
                }
            }

        // Announce next entry periodically
        announceJob =
            scope.launch {
                val interval = config.integer("seconds-announce", 600) * 1000L
                delay(6_000) // Initial delay
                while (isActive) {
                    if (BoardConfig.mainServer) {
                        purgeExpiredEntries()
                        announceNext()
                    }
                    delay(interval)
                }
            }

        purgeJob =
            scope.launch {
                while (isActive) {
                    delay(300_000)
                    purgeExpiredEntries()
                }
            }
    }

    private fun cancelTasks() {
        updateCacheJob?.cancel()
        announceJob?.cancel()
        purgeJob?.cancel()
    }

    /**
     * Remove expired entries from Redis and local cache.
     */
    suspend fun purgeExpiredEntries(): Int {
        val all = repo.all().getOrNull() ?: return 0
        val expired = all.filter { it.isExpired() }
        expired.forEach { entry ->
            repo.delete(entry.id())
            cache.remove(entry.entryUuid)
        }
        return expired.size
    }

    /**
     * Announce the next board entry.
     */
    @JvmStatic
    fun announceNext() {
        scope.launch {
            val entries = repo.all().getOrNull() ?: return@launch
            val entry = selectNextAnnounceEntry(entries) ?: return@launch

            entry.changeLastShown(System.currentTimeMillis())
            repo.save(entry)

            runCatching { BarColor.valueOf(config.string("color", "YELLOW").uppercase()) }
                .getOrDefault(BarColor.YELLOW)
            val finalColor = entry.color
            val message =
                XMessage(
                    type = XMessage.Type.BOSS_BAR,
                    serializedMessage = "&7[&6${entry.playerName}&7]&r ${entry.title}",
                    serializationType = XMessage.SerializationType.LEGACY,
                    bossBarData =
                        XMessage.BossBarData(
                            color = finalColor,
                            name = "board",
                            keepFor = config.integer("keep-for", 10),
                            seconds = config.integer("seconds-boss-bar", 10),
                        ),
                    conditions = listOf(XCondition.ofPermission(BoardConfig.receivePermission)),
                )
            withContext(module = "board", player = entry.playerName, action = "announce") {
                Logging.debug(
                    "Board announce entry={} title=\"{}\" {}",
                    entry.id(),
                    entry.title.take(80),
                    message.logSummary(),
                )
                AnnounceManager.announce(message)
            }
        }
    }

    /**
     * Get all board items.
     */
    @JvmStatic
    fun items(): List<BoardItem> =
        runBlocking {
            repo
                .all()
                .getOrNull()
                ?.filter { !it.isExpired() }
                ?.map { cache.get(it) }
                ?: emptyList()
        }

    /**
     * Get all board entries.
     */
    suspend fun allEntries(): List<BoardEntryData> =
        repo
            .all()
            .getOrNull()
            ?.filter { !it.isExpired() }
            ?: emptyList()

    /**
     * Add a new board entry.
     */
    @JvmStatic
    fun addEntry(entry: BoardEntryData) {
        scope.launch {
            repo.save(entry)
            updateCache(entry.entryUuid)
        }
    }

    /**
     * Delete a board entry.
     */
    @JvmStatic
    fun deleteEntry(entry: BoardEntryData) {
        scope.launch {
            repo.delete(entry.id())
            cache.remove(entry.entryUuid)
        }
    }

    /**
     * Get an entry by UUID.
     */
    @JvmStatic
    fun getEntry(uuid: UUID): BoardEntryData? =
        runBlocking {
            repo.get(uuid.toString()).getOrNull()
        }

    /**
     * Save an entry after modification.
     */
    @JvmStatic
    fun saveEntry(entry: BoardEntryData) {
        scope.launch {
            repo.save(entry)
            updateCache(entry.entryUuid)
        }
    }

    /**
     * Update the cache for a specific entry.
     */
    fun updateCache(uuid: UUID) {
        scope.launch {
            val entry = repo.get(uuid.toString()).getOrNull()
            if (entry != null) {
                cache.refresh(entry)
            } else {
                cache.remove(uuid)
            }
        }
    }

    /**
     * Update all cached entries.
     */
    fun updateAllCache() {
        cache.clear()
        scope.launch {
            purgeExpiredEntries()
            repo.all().getOrNull()?.filter { !it.isExpired() }?.forEach { cache.refresh(it) }
        }
    }
}
