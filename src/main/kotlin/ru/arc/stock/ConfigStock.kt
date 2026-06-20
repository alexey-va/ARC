package ru.arc.stock

import com.google.gson.Gson
import org.bukkit.Material
import ru.arc.board.ItemIcon
import java.util.UUID

data class ConfigStock(
    var symbol: String,
    var display: String,
    var lore: List<String>,
    var icon: ItemIcon,
    var maxLeverage: Int,
    var type: Stock.Type,
) {
    fun toStock(price: Double, dividend: Double, lastUpdated: Long, lastTimeDividend: Long): Stock =
        Stock(
            symbol = symbol,
            price = price,
            dividend = dividend,
            lastUpdated = lastUpdated,
            display = display,
            lore = lore,
            icon = icon,
            lastTimeDividend = lastTimeDividend,
            maxLeverage = maxLeverage,
            type = type,
        )

    companion object {
        private val gson = Gson()

        @JvmStatic
        fun deserialize(map: Map<String, Any>): ConfigStock {
            val icon = parseIcon(map["icon"])
            val lore = parseLore(map.getOrDefault("lore", listOf("lore")))
            return ConfigStock(
                symbol = map["symbol"] as? String ?: "",
                display = map.getOrDefault("display", "display") as? String ?: "display",
                lore = lore,
                icon = icon,
                maxLeverage = (map.getOrDefault("maxLeverage", 10000) as? Number)?.toInt() ?: 10000,
                type = Stock.Type.valueOf(
                    ((map.getOrDefault("type", "STOCK") as? String) ?: "STOCK").uppercase()
                ),
            )
        }

        private fun parseIcon(o: Any?): ItemIcon = when {
            o is String -> gson.fromJson(o, ItemIcon::class.java)
            o is Map<*, *> -> {
                val uuidString = o["headUuid"] as? String
                val uuid = if (uuidString != null && uuidString.length >= 36) UUID.fromString(uuidString) else null
                val materialStr = (o["material"] as? String) ?: "PAPER"
                val data = (o["data"] as? Number)?.toInt() ?: 0
                ItemIcon(Material.valueOf(materialStr.uppercase()), uuid, data)
            }
            else -> ItemIcon.of(Material.PAPER, 0)
        }

        private fun parseLore(l: Any?): List<String> = when (l) {
            is String -> listOf(l)
            is Collection<*> -> l.filterIsInstance<String>()
            else -> listOf("lore")
        }
    }
}
