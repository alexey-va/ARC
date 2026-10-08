package ru.arc.chestpreview

import com.jeff_media.customblockdata.CustomBlockData
import com.magmaguy.elitemobs.treasurechest.TreasureChest
import dev.lone.itemsadder.api.CustomBlock
import dev.lone.itemsadder.api.CustomFurniture
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.plugin.Plugin
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.hooks.slimefun.SlimefunItemAccess
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.UUID

/** Prevents previews from reading blocks whose physical inventories have another owner. */
internal class ManagedChestExclusions(
    private val managedCrate: (Block) -> Boolean = ChestPreviewCrates::contains,
) {
    private val personalLootKey by lazy { NamespacedKey(ARC.instance, PERSONAL_LOOT_UUID_KEY) }

    @Volatile
    private var quickShopCache: BindingCache<QuickShopBinding>? = null

    @Volatile
    private var autoSellCache: BindingCache<AutoSellBinding>? = null

    fun isManaged(block: Block): Boolean = failClosed {
        managedCrate(block) ||
            pluginManages(ITEMS_ADDER_PLUGIN, block, ::isItemsAdderBlock) ||
            pluginManages(SLIMEFUN_PLUGIN, block, ::isSlimefunBlock) ||
            pluginManages(ELITE_MOBS_PLUGIN, block, ::isEliteMobsTreasureChest) ||
            pluginManages(QUICK_SHOP_PLUGIN, block, ::isQuickShopInventory) ||
            pluginManages(AUTO_SELL_PLUGIN, block, ::isAutoSellChest)
    }

    fun marker(block: Block): PersonalLootChestMarker {
        val data = CustomBlockData(block, ARC.instance)
        if (!data.has(personalLootKey)) return PersonalLootChestMarker.Unmarked
        val uuid = data.get(personalLootKey, PersistentDataType.STRING)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return PersonalLootChestMarker.Invalid
        return PersonalLootChestMarker.Marked(uuid)
    }

    private fun isItemsAdderBlock(
        _plugin: Plugin,
        block: Block,
    ): Boolean {
        return CustomBlock.byAlreadyPlaced(block) != null || CustomFurniture.byAlreadySpawned(block) != null
    }

    private fun isSlimefunBlock(
        _plugin: Plugin,
        block: Block,
    ): Boolean {
        return SlimefunItemAccess.blockId(block) != null
    }

    private fun isEliteMobsTreasureChest(
        _plugin: Plugin,
        block: Block,
    ): Boolean {
        return TreasureChest.getTreasureChest(block.location) != null
    }

    private fun isQuickShopInventory(
        plugin: Plugin,
        block: Block,
    ): Boolean {
        val binding = quickShopBinding(plugin) ?: error("QuickShop API is unavailable")
        val api = binding.getInstance.invoke(null) ?: error("QuickShop API instance is unavailable")
        val manager = binding.getShopManager.invoke(api) ?: error("QuickShop shop manager is unavailable")
        return binding.getShopIncludeAttached.invoke(manager, block.location) != null
    }

    private fun isAutoSellChest(
        plugin: Plugin,
        block: Block,
    ): Boolean {
        val binding = autoSellBinding(plugin) ?: error("AutoSellChests API is unavailable")
        return binding.isSellChest.invoke(null, block) as? Boolean
            ?: error("AutoSellChests returned an invalid result")
    }

    private fun pluginManages(
        pluginName: String,
        block: Block,
        probe: (Plugin, Block) -> Boolean,
    ): Boolean = failClosed {
        val plugin = Bukkit.getPluginManager().getPlugin(pluginName) ?: return@failClosed false
        if (!plugin.isEnabled) return@failClosed true
        probe(plugin, block)
    }

    private fun quickShopBinding(plugin: Plugin): QuickShopBinding? {
        quickShopCache?.takeIf { it.plugin === plugin }?.let { return it.binding }
        return synchronized(this) {
            quickShopCache?.takeIf { it.plugin === plugin }?.let { return@synchronized it.binding }
            val binding = failClosedOrNull { bindQuickShop(plugin) }
            quickShopCache = BindingCache(plugin, binding)
            binding
        }
    }

    private fun bindQuickShop(plugin: Plugin): QuickShopBinding {
        val apiClass = loadProviderClass(plugin, QUICK_SHOP_API_CLASS)
        val getInstance = apiClass.getMethod("getInstance").requireStatic()
        val getShopManager = apiClass.getMethod("getShopManager")
        val getShopIncludeAttached =
            getShopManager.returnType.getMethod("getShopIncludeAttached", Location::class.java)
        return QuickShopBinding(getInstance, getShopManager, getShopIncludeAttached)
    }

    private fun autoSellBinding(plugin: Plugin): AutoSellBinding? {
        autoSellCache?.takeIf { it.plugin === plugin }?.let { return it.binding }
        return synchronized(this) {
            autoSellCache?.takeIf { it.plugin === plugin }?.let { return@synchronized it.binding }
            val binding = failClosedOrNull {
                val apiClass = loadProviderClass(plugin, AUTO_SELL_API_CLASS)
                AutoSellBinding(apiClass.getMethod("isSellChest", Block::class.java).requireStatic())
            }
            autoSellCache = BindingCache(plugin, binding)
            binding
        }
    }

    private fun loadProviderClass(
        plugin: Plugin,
        className: String,
    ): Class<*> = Class.forName(className, false, plugin.javaClass.classLoader)

    private fun Method.requireStatic(): Method = apply {
        check(Modifier.isStatic(modifiers)) { "Expected static provider API method: $name" }
    }

    private inline fun <T> failClosedOrNull(block: () -> T): T? =
        try {
            block()
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }

    private inline fun failClosed(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (_: Exception) {
            true
        } catch (_: LinkageError) {
            true
        }

    private data class BindingCache<T>(
        val plugin: Plugin,
        val binding: T?,
    )

    private data class QuickShopBinding(
        val getInstance: Method,
        val getShopManager: Method,
        val getShopIncludeAttached: Method,
    )

    private data class AutoSellBinding(val isSellChest: Method)

    private companion object {
        const val PERSONAL_LOOT_UUID_KEY = "ploot_uuid"
        const val ITEMS_ADDER_PLUGIN = "ItemsAdder"
        const val SLIMEFUN_PLUGIN = "Slimefun"
        const val ELITE_MOBS_PLUGIN = "EliteMobs"
        const val QUICK_SHOP_PLUGIN = "QuickShop-Hikari"
        const val AUTO_SELL_PLUGIN = "AutoSellChests"

        const val QUICK_SHOP_API_CLASS = "com.ghostchu.quickshop.api.QuickShopAPI"
        const val AUTO_SELL_API_CLASS = "me.gypopo.autosellchests.api.AutoSellChestsAPI"
    }
}

internal sealed interface PersonalLootChestMarker {
    data object Unmarked : PersonalLootChestMarker
    data object Invalid : PersonalLootChestMarker
    data class Marked(val chestUuid: UUID) : PersonalLootChestMarker
}

internal fun combinePersonalLootMarkers(markers: List<PersonalLootChestMarker>): PersonalLootChestMarker {
    if (markers.isEmpty() || markers.all { it == PersonalLootChestMarker.Unmarked }) {
        return PersonalLootChestMarker.Unmarked
    }
    val chestUuids = markers.mapNotNull { (it as? PersonalLootChestMarker.Marked)?.chestUuid }.distinct()
    return if (chestUuids.size == 1 && markers.all { it is PersonalLootChestMarker.Marked }) {
        PersonalLootChestMarker.Marked(chestUuids.first())
    } else {
        PersonalLootChestMarker.Invalid
    }
}
