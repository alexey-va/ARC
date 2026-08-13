package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.economy.EconomyHandler
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfig
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfigFields
import com.magmaguy.elitemobs.instanced.dungeons.DynamicDungeonInstance
import com.magmaguy.elitemobs.items.ScalableItemConstructor
import com.magmaguy.elitemobs.items.customitems.CustomItem
import com.magmaguy.elitemobs.items.itemconstructor.ItemConstructor
import com.magmaguy.elitemobs.playerdata.ElitePlayerInventory
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.hooks.elitemobs.guis.EmShop
import ru.arc.hooks.elitemobs.guis.ShopHolder
import ru.arc.util.GuiUtils
import ru.arc.util.Logging.error

class EMHook internal constructor(
    private val config: Config,
    private val wormholesFactory: () -> EMWormholes,
    private val shopHolderFactory: () -> ShopHolder,
    private val scheduleShopReset: (intervalTicks: Long, reset: () -> Unit) -> ScheduledTask,
) : AutoCloseable {
    constructor() : this(
        config = ConfigManager.of(ARC.instance.dataPath, "elitemobs.yml"),
        wormholesFactory = ::EMWormholes,
        shopHolderFactory = ::ShopHolder,
        scheduleShopReset = { intervalTicks, reset ->
            repeating(intervalTicks.ticks, delay = intervalTicks.ticks) {
                reset()
            }
        },
    )

    private var emWormholes: EMWormholes? = null
    private var shopHolder: ShopHolder? = null
    private var resetShopTask: ScheduledTask? = null
    private var closed = false

    @Volatile
    var lastShopReset: Long = System.currentTimeMillis()
        private set

    private fun scheduleReset(holder: ShopHolder): ScheduledTask {
        val resetTime = config.integer("shop.reset-ticks", 20 * 60 * 5).toLong()
        require(resetTime > 0) { "shop.reset-ticks must be positive, got $resetTime" }
        return scheduleShopReset(resetTime, holder::deleteAll)
    }

    fun generateDrop(tier: Int, player: Player, trinket: Boolean, customChance: Double): ItemStack {
        if (customChance > 0 && Math.random() < customChance) {
            val forbidden = setOf(
                Material.DIAMOND_SWORD, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS, Material.DIAMOND_HELMET,
                Material.IRON_SWORD, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS, Material.IRON_HELMET,
                Material.GOLDEN_SWORD, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS, Material.GOLDEN_HELMET,
                Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS, Material.LEATHER_HELMET,
                Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS, Material.CHAINMAIL_HELMET,
                Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, Material.NETHERITE_HELMET,
                Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE, Material.TOTEM_OF_UNDYING,
            )
            val list = CustomItem.getCustomItems().values
                .filter { ci -> if (trinket) ci.scalability == CustomItem.Scalability.SCALABLE else true }
                .filter { ci -> !forbidden.contains(ci.customItemsConfigFields.material) }
            list.randomOrNull()?.let { customItem ->
                return customItem.generateItemStack(tier, player, null)
            }
        }
        return if (trinket) ScalableItemConstructor.randomizeScalableItem(tier, player, null)
        else ItemConstructor.constructItem(tier.toDouble(), null, player, true)
    }

    fun tier(player: Player): Int =
        ElitePlayerInventory.playerInventories
            ?.get(player.uniqueId)
            ?.getFullPlayerTier(false)
            ?: 1

    fun canLaunchSeasonDungeon(player: Player, blueprintWorld: String): Boolean {
        val packageEntry = seasonDungeonPackage(blueprintWorld) ?: return false
        val fields = packageEntry.value
        return fields.contentType == ContentPackagesConfigFields.ContentType.DYNAMIC_DUNGEON &&
            fields.difficulties.any { difficulty -> difficulty["name"]?.toString() == "normal" } &&
            (fields.permission.isNullOrBlank() || player.hasPermission(fields.permission))
    }

    fun launchSeasonDungeon(player: Player, blueprintWorld: String) {
        val packageEntry = requireNotNull(seasonDungeonPackage(blueprintWorld)) {
            "EliteMobs season dungeon package is unavailable"
        }
        val fields = packageEntry.value
        require(fields.contentType == ContentPackagesConfigFields.ContentType.DYNAMIC_DUNGEON) {
            "Season dungeon package is not dynamic"
        }
        require(fields.difficulties.any { difficulty -> difficulty["name"]?.toString() == "normal" }) {
            "Season dungeon has no exact normal difficulty"
        }
        require(fields.permission.isNullOrBlank() || player.hasPermission(fields.permission)) {
            "Player lacks the season dungeon permission"
        }
        DynamicDungeonInstance.setupDynamicDungeon(player, packageEntry.key, "normal", tier(player).coerceAtLeast(1))
    }

    private fun seasonDungeonPackage(blueprintWorld: String) =
        ContentPackagesConfig.getDungeonPackages().entries.singleOrNull { (_, fields) ->
            fields.worldName?.equals(blueprintWorld.trim(), ignoreCase = true) == true
        }

    @Synchronized
    fun reload() {
        check(!closed) { "EMHook is closed" }

        val holder = shopHolder ?: shopHolderFactory()
        val replacementWormholes = wormholesFactory()
        val replacementTask =
            try {
                replacementWormholes.init()
                scheduleReset(holder)
            } catch (failure: Throwable) {
                try {
                    replacementWormholes.close()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }

        val previousWormholes = emWormholes
        val previousTask = resetShopTask
        emWormholes = replacementWormholes
        shopHolder = holder
        resetShopTask = replacementTask

        val cleanupFailures = mutableListOf<Throwable>()
        cleanup(cleanupFailures) { previousTask?.takeUnless { it.isCancelled }?.cancel() }
        cleanup(cleanupFailures) { previousWormholes?.close() }
        if (cleanupFailures.isNotEmpty()) {
            val first = cleanupFailures.first()
            cleanupFailures.drop(1).forEach(first::addSuppressed)
            error("Error cleaning previous EliteMobs runtime after reload", first)
        }
        resetShop()
    }

    @Synchronized
    fun resetShop() {
        check(!closed) { "EMHook is closed" }
        lastShopReset = System.currentTimeMillis()
        checkNotNull(shopHolder) { "EMHook is not started" }.deleteAll()
    }

    @Deprecated("Use close()", ReplaceWith("close()"))
    fun cancel() {
        close()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true

        val task = resetShopTask
        val wormholes = emWormholes
        val holder = shopHolder
        resetShopTask = null
        emWormholes = null
        shopHolder = null

        val failures = mutableListOf<Throwable>()
        cleanup(failures) { task?.takeUnless { it.isCancelled }?.cancel() }
        cleanup(failures) { wormholes?.close() }
        cleanup(failures) { holder?.deleteAll() }
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    fun openShopGui(player: Player, isGear: Boolean) {
        val holder =
            synchronized(this) {
                check(!closed) { "EMHook is closed" }
                checkNotNull(shopHolder) { "EMHook is not started" }
            }
        GuiUtils.constructAndShowAsync({ EmShop(config, player, holder, isGear, this) }, player)
    }

    fun balance(player: Player): Double = EconomyHandler.checkCurrency(player.uniqueId)

    fun addBalance(player: Player, amount: Double) = EconomyHandler.addCurrency(player.uniqueId, amount)

    fun removeBalance(player: Player, amount: Double) = EconomyHandler.subtractCurrency(player.uniqueId, amount)

    private inline fun cleanup(
        failures: MutableList<Throwable>,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (failure: Throwable) {
            failures += failure
        }
    }
}
