package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.economy.EconomyHandler
import com.magmaguy.elitemobs.items.ScalableItemConstructor
import com.magmaguy.elitemobs.items.customitems.CustomItem
import com.magmaguy.elitemobs.items.itemconstructor.ItemConstructor
import com.magmaguy.elitemobs.playerdata.ElitePlayerInventory
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.hooks.elitemobs.guis.EmShop
import ru.arc.hooks.elitemobs.guis.ShopHolder
import ru.arc.util.GuiUtils

class EMHook : Listener {

    private val config = ConfigManager.of(ARC.instance.dataFolder.toPath(), "elitemobs.yml")
    private var resetShopTask: BukkitTask? = null
    var lastShopReset: Long = System.currentTimeMillis()

    init {
        if (emWormholes == null) {
            emWormholes = EMWormholes()
            emWormholes!!.init()
        }
        if (shopHolder == null) {
            shopHolder = ShopHolder()
        }
        startTasks()
    }

    private fun cancelTasks() {
        resetShopTask?.takeUnless { it.isCancelled }?.cancel()
    }

    private fun startTasks() {
        cancelTasks()
        val resetTime = config.integer("shop.reset-ticks", 20 * 60 * 5).toLong()
        resetShopTask = Bukkit.getScheduler().runTaskTimer(ARC.instance, Runnable { shopHolder!!.deleteAll() }, resetTime, resetTime)
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
            val customItem = list[(Math.random() * list.size).toInt()]
            return customItem.generateItemStack(tier, player, null)
        }
        return if (trinket) ScalableItemConstructor.randomizeScalableItem(tier, player, null)
        else ItemConstructor.constructItem(tier.toDouble(), null, player, true)
    }

    fun tier(player: Player): Int =
        ElitePlayerInventory.playerInventories[player.uniqueId]!!.getFullPlayerTier(false)

    fun reload() {
        emWormholes?.cancel()
        emWormholes = EMWormholes()
        emWormholes!!.init()
        resetShop()
        startTasks()
    }

    fun resetShop() {
        lastShopReset = System.currentTimeMillis()
        shopHolder?.deleteAll()
    }

    fun cancel() {
        emWormholes?.cancel()
    }

    fun openShopGui(player: Player, isGear: Boolean) {
        GuiUtils.constructAndShowAsync({ EmShop(config, player, shopHolder!!, isGear, this) }, player)
    }

    fun balance(player: Player): Double = EconomyHandler.checkCurrency(player.uniqueId)

    fun addBalance(player: Player, amount: Double) = EconomyHandler.addCurrency(player.uniqueId, amount)

    fun removeBalance(player: Player, amount: Double) = EconomyHandler.subtractCurrency(player.uniqueId, amount)

    companion object {
        private var emWormholes: EMWormholes? = null
        private var shopHolder: ShopHolder? = null
    }
}
