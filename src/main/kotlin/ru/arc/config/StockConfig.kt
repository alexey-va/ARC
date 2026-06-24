package ru.arc.config

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.stock.StockClient
import ru.arc.stock.StockMarket
import ru.arc.util.ItemStackDslBuilder
import ru.arc.util.fromConfig
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.TreeMap

/**
 * Stock module configuration.
 *
 * Backed by the project's [Config] system. Simple scalar values use get()-accessors
 * for hot-reload support. Complex derived values (iconMaterials, permissionMap,
 * stockMarketLocation) are cached and refreshed on [load].
 *
 * Public `var` properties allow direct assignment in unit tests without needing
 * a full Bukkit server (MockBukkit).
 */
object StockConfig {

    private lateinit var config: Config

    // ── Simple scalar values (hot-reload via get()) ──────────────────────────

    @JvmField var mainServer: Boolean = false
    @JvmField var commission: Double = 0.01
    @JvmField var leveragePower: Double = 0.5
    @JvmField var defaultStockMaxAmount: Int = 10
    @JvmField var mainMenuBackCommand: String = "menu"
    @JvmField var stockRefreshRate: Long = 300L
    @JvmField var maxBuyPrice: Double = 1_000_000.0
    @JvmField var maxLeveragedPrice: Double = 10_000_000.0
    @JvmField var historyLifetime: Long = 60L * 60 * 24 * 3
    @JvmField var dividendPeriod: Long = 60L * 60 * 4
    @JvmField var dividendPercentFromPrice: Double = 0.02
    @JvmField var updateImagesRadius: Double = 50.0

    // ── Complex derived values (parsed on load) ──────────────────────────────

    @JvmField var stockMarketLocation: Location? = null
    @JvmField var iconMaterials: MutableList<Material> = mutableListOf(Material.PAPER)
    @JvmField var permissionMap: TreeMap<Int, String> = TreeMap()

    // ── Initialization ───────────────────────────────────────────────────────

    /**
     * Called from [ru.arc.core.modules.CoreModules.StockModule].
     * Reads all values from [cfg] and caches complex ones.
     */
    @JvmStatic
    fun load(cfg: Config) {
        config = cfg

        if (!cfg.bool("enabled", false)) {
            stockMarketLocation = null
            info("Stocks disabled, skipping stock market location and definitions")
            return
        }

        mainServer = cfg.bool("main-server", false)
        commission = cfg.double("commission", 0.01)
        leveragePower = cfg.double("leverage-power", 0.5)
        mainMenuBackCommand = cfg.string("main-menu-back-command", "menu")
        stockRefreshRate = cfg.long("stock-refresh-rate", 300L)
        defaultStockMaxAmount = cfg.integer("default-max-stock-amount", 10)
        maxBuyPrice = cfg.double("max-buy-price", 1_000_000.0)
        maxLeveragedPrice = cfg.double("max-leveraged-price", 10_000_000.0)
        historyLifetime = cfg.long("history-lifetime", 60L * 60 * 24 * 3)
        dividendPeriod = cfg.long("dividend-period", 60L * 60 * 4)
        dividendPercentFromPrice = cfg.double("dividend-percent-from-price", 0.02)
        updateImagesRadius = cfg.double("update-images-radius", 50.0)

        stockMarketLocation = parseLocation(cfg.string("stock-market-location", ""))

        iconMaterials = parseIconMaterials(cfg.stringList("icon-materials"))
        permissionMap = parsePermissionMap(cfg.stringList("max-stock-permissions"))

        for (map in cfg.list<Map<String, Any>>("stocks")) {
            try {
                StockMarket.loadStockFromMap(map)
            } catch (e: Exception) {
                warn("Error parsing stock entry {}: {}", map, e.message)
            }
        }

        val finnKey = cfg.stringOrNull("finn-api-key")
        val polyKey = cfg.stringOrNull("poly-api-key")
        StockMarket.setClient(StockClient(finnKey, polyKey))
    }

    // ── Locale string accessors ──────────────────────────────────────────────

    /**
     * Returns locale string for [key], injecting the key as default if missing.
     */
    @JvmStatic
    fun string(key: String): String {
        val path = "locale.$key"
        return config.stringOrNull(path) ?: run {
            config.injectDeepKey(path, key)
            key
        }
    }

    /**
     * Returns locale string list for [key], injecting a single-element default if missing.
     */
    @JvmStatic
    fun stringList(key: String): List<String> {
        val path = "locale.$key"
        return config.stringListOrNull(path) ?: run {
            config.injectDeepKey(path, listOf(key))
            listOf(key)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun parseLocation(raw: String): Location? {
        if (raw.isBlank()) return null
        return try {
            val (x, y, z, worldName) = raw.split(",")
            val world = Bukkit.getWorld(worldName)
            if (world == null) {
                warn("Stock market world not found: {} (check stock-market-location)", worldName)
                null
            } else {
                Location(world, x.toDouble(), y.toDouble(), z.toDouble())
            }
        } catch (e: Exception) {
            warn("Could not parse stock-market-location '{}': {}", raw, e.message)
            null
        }
    }

    private fun parseIconMaterials(list: List<String>): MutableList<Material> {
        val result = list.mapNotNull { Material.matchMaterial(it.uppercase()) }
        return result.ifEmpty { listOf(Material.PAPER) }.toMutableList()
    }

    private fun parsePermissionMap(list: List<String>): TreeMap<Int, String> =
        TreeMap<Int, String>().also { map ->
            for (s in list) {
                try {
                    val parts = s.split(":")
                    require(parts.size == 2)
                    val permission = parts[0]
                    val amount = parts[1].toInt()
                    if (map.containsKey(amount)) {
                        warn("Permission map already has amount: {}", amount)
                    } else {
                        map[amount] = permission
                    }
                } catch (e: Exception) {
                    warn("Could not parse max-stock-permission entry '{}': {}", s, e.message)
                }
            }
        }

    /** Loaded stock YAML — use [ru.arc.util.fromConfig] with paths under `locale.*`. */
    @JvmStatic
    fun config(): Config = config
}
