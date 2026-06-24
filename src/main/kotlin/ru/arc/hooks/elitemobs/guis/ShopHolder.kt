package ru.arc.hooks.elitemobs.guis

import com.magmaguy.elitemobs.items.ItemTagger
import com.magmaguy.elitemobs.items.ItemWorthCalculator
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.hooks.elitemobs.EMHook
import java.util.Random
import java.util.UUID

class ShopHolder {

    private val config = ConfigManager.of(ARC.instance.dataPath, "elitemobs.yml")
    private val items = HashMap<UUID, Shop>()

    fun getShop(player: Player, emHook: EMHook): Shop {
        val gearSize = config.integer("shop.gear-size", 36)
        val trinketSize = config.integer("shop.trinket-size", 36)
        return items.getOrPut(player.uniqueId) {
            Shop(gearSize, trinketSize, emHook.tier(player), player, emHook)
        }
    }

    fun deleteAll() {
        items.clear()
    }

    class Shop(gearSize: Int, trinketSize: Int, tier: Int, player: Player, emHook: EMHook) {
        private val emHook: EMHook = emHook
        private val player: Player = player
        val timestamp: Long = System.currentTimeMillis()
        val gear: MutableList<ShopItem> = ArrayList()
        val trinkets: MutableList<ShopItem> = ArrayList()

        init {
            generateGear(gearSize, tier, player)
            generateTrinkets(trinketSize, tier, player)
        }

        fun generateGear(size: Int, tier: Int, player: Player) {
            gear.clear()
            val random = Random()
            val titles = HashSet<String>()
            repeat((size * 1.5).toInt()) {
                val gauss = random.nextGaussian() * (tier * 0.15) + (tier * 0.8)
                val rTier = maxOf(1, minOf(tier + 3, Math.round(gauss).toInt()))
                val stack = emHook.generateDrop(rTier, player, false, 0.05) ?: return@repeat
                val display = stack.itemMeta?.displayName()
                val text = if (display == null) "" else PlainTextComponentSerializer.plainText().serialize(display)
                if (titles.contains(text)) return@repeat
                titles.add(text)
                var price = ItemTagger.getItemValue(stack)
                if (price <= 0) price = ItemWorthCalculator.determineItemWorth(stack, player)
                gear.add(ShopItem(stack, price * 2))
            }
            gear.sortWith(Comparator.comparingInt { it.stack.type.ordinal })
        }

        fun generateTrinkets(size: Int, tier: Int, player: Player) {
            trinkets.clear()
            val random = Random()
            val food = setOf(
                Material.COOKED_BEEF, Material.COOKED_CHICKEN, Material.COOKED_COD,
                Material.COOKED_MUTTON, Material.COOKED_PORKCHOP, Material.COOKED_RABBIT, Material.COOKED_SALMON,
                Material.BREAD, Material.APPLE, Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE,
                Material.CARROT, Material.GOLDEN_CARROT, Material.POTATO, Material.BAKED_POTATO,
            )
            val titles = HashSet<String>()
            repeat((size * 1.5).toInt()) {
                val gauss = random.nextGaussian() * (tier * 0.15) + (tier * 0.75)
                val rTier = maxOf(1, minOf(tier - 5, Math.round(gauss).toInt()))
                val stack = emHook.generateDrop(rTier, player, true, 0.1) ?: return@repeat
                val display = stack.itemMeta?.displayName()
                val text = if (display == null) "" else PlainTextComponentSerializer.plainText().serialize(display)
                if (titles.contains(text)) return@repeat
                titles.add(text)
                var price = ItemTagger.getItemValue(stack)
                if (price <= 0) price = ItemWorthCalculator.determineItemWorth(stack, player)
                if (food.contains(stack.type)) {
                    stack.amount = 16
                    price *= 16
                }
                trinkets.add(ShopItem(stack, price * 2))
            }
            trinkets.sortWith(Comparator.comparingInt { it.stack.type.ordinal })
        }
    }

    data class ShopItem(val stack: ItemStack, val price: Double)
}
