package ru.arc.treasure.core

import org.bukkit.Material
import org.bukkit.Registry
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * Sealed class representing all types of treasures.
 * Immutable by design - all modifications create new instances.
 */
sealed class Treasure {
    abstract val id: String
    abstract val weight: Int
    abstract val messages: List<TreasureMessage>
    abstract val type: String

    /**
     * Serializes this treasure to a map for YAML storage.
     */
    abstract fun toMap(): Map<String, Any?>

    /**
     * Creates a copy of this treasure with a new random ID.
     */
    abstract fun newId(): Treasure

    /**
     * Creates a copy with updated messages.
     */
    abstract fun withMessages(messages: List<TreasureMessage>): Treasure

    /**
     * Creates a copy with updated weight.
     */
    abstract fun withWeight(weight: Int): Treasure

    // ==================== Helper Methods ====================

    /**
     * Adds a message to this treasure.
     */
    fun addMessage(message: TreasureMessage): Treasure = withMessages(messages + message)

    /**
     * Removes all messages from this treasure.
     */
    fun clearMessages(): Treasure = withMessages(emptyList())

    /**
     * Gets the display name for this treasure (used in GUIs).
     */
    abstract val displayName: String

    /**
     * Gets the display icon material for this treasure.
     */
    abstract val displayMaterial: Material

    // ==================== Treasure Types ====================

    /**
     * Item treasure - gives item stacks to players.
     */
    data class Item(
        val stack: ItemStack,
        val min: Int = 1,
        val max: Int = 1,
        override val weight: Int = 1,
        override val messages: List<TreasureMessage> = emptyList(),
        override val id: String = UUID.randomUUID().toString(),
    ) : Treasure() {
        override val type: String = "item"
        override val displayName: String get() = stack.type.name
        override val displayMaterial: Material get() = stack.type

        val amount: Int get() = if (min == max) min else ThreadLocalRandom.current().nextInt(min, max + 1)

        override fun toMap(): Map<String, Any?> =
            treasureMap {
                put("stack", stack.serialize())
                put("amount", formatRange(min, max))
            }

        override fun newId(): Item = copy(id = UUID.randomUUID().toString())

        override fun withMessages(messages: List<TreasureMessage>): Item = copy(messages = messages)

        override fun withWeight(weight: Int): Item = copy(weight = weight)

        fun withAmount(
            min: Int,
            max: Int = min,
        ): Item = copy(min = min, max = max)
    }

    /**
     * Money treasure - deposits money to player's economy account.
     */
    data class Money(
        val min: Double = 1.0,
        val max: Double = 1.0,
        override val weight: Int = 1,
        override val messages: List<TreasureMessage> = emptyList(),
        override val id: String = UUID.randomUUID().toString(),
    ) : Treasure() {
        override val type: String = "money"
        override val displayName: String get() = "Money: ${formatRange(min, max)}"
        override val displayMaterial: Material get() = Material.GOLD_INGOT

        val amount: Double get() = if (min == max) min else ThreadLocalRandom.current().nextDouble(min, max)

        override fun toMap(): Map<String, Any?> =
            treasureMap {
                put("amount", formatRange(min, max))
            }

        override fun newId(): Money = copy(id = UUID.randomUUID().toString())

        override fun withMessages(messages: List<TreasureMessage>): Money = copy(messages = messages)

        override fun withWeight(weight: Int): Money = copy(weight = weight)

        fun withAmount(
            min: Double,
            max: Double = min,
        ): Money = copy(min = min, max = max)
    }

    /**
     * Command treasure - executes console commands with player placeholders.
     */
    data class Command(
        val commands: List<String>,
        override val weight: Int = 1,
        override val messages: List<TreasureMessage> = emptyList(),
        override val id: String = UUID.randomUUID().toString(),
    ) : Treasure() {
        override val type: String = "command"
        override val displayName: String get() = commands.firstOrNull() ?: "Command"
        override val displayMaterial: Material get() = Material.COMMAND_BLOCK

        override fun toMap(): Map<String, Any?> =
            treasureMap {
                put("commands", commands)
            }

        override fun newId(): Command = copy(id = UUID.randomUUID().toString())

        override fun withMessages(messages: List<TreasureMessage>): Command = copy(messages = messages)

        override fun withWeight(weight: Int): Command = copy(weight = weight)

        fun withCommands(commands: List<String>): Command = copy(commands = commands)
    }

    /**
     * SubPool treasure - gives a random treasure from another pool.
     */
    data class SubPool(
        val poolId: String,
        override val weight: Int = 1,
        override val messages: List<TreasureMessage> = emptyList(),
        override val id: String = UUID.randomUUID().toString(),
    ) : Treasure() {
        override val type: String = "sub-pool"
        override val displayName: String get() = "Pool: $poolId"
        override val displayMaterial: Material get() = Material.CHEST_MINECART

        override fun toMap(): Map<String, Any?> =
            treasureMap {
                put("poolId", poolId)
            }

        override fun newId(): SubPool = copy(id = UUID.randomUUID().toString())

        override fun withMessages(messages: List<TreasureMessage>): SubPool = copy(messages = messages)

        override fun withWeight(weight: Int): SubPool = copy(weight = weight)
    }

    /**
     * Enchant treasure - gives random enchanted books.
     */
    data class Enchant(
        val min: Int = 1,
        val max: Int = 1,
        val exclude: Set<String> = emptySet(),
        override val weight: Int = 1,
        override val messages: List<TreasureMessage> = emptyList(),
        override val id: String = UUID.randomUUID().toString(),
    ) : Treasure() {
        override val type: String = "enchant"
        override val displayName: String get() = "Enchant: ${formatRange(min, max)}"
        override val displayMaterial: Material get() = Material.ENCHANTED_BOOK

        val amount: Int get() = if (min == max) min else ThreadLocalRandom.current().nextInt(min, max + 1)

        fun randomBook(): ItemStack {
            @Suppress("DEPRECATION")
            val enchants =
                Registry.ENCHANTMENT
                    .filter { it.key.key !in exclude.map { e -> e.lowercase() } }
                    .toList()

            return ItemStack(Material.ENCHANTED_BOOK).apply {
                val meta = itemMeta as? EnchantmentStorageMeta ?: return@apply
                val enchant = enchants.randomOrNull() ?: return@apply
                val level = ThreadLocalRandom.current().nextInt(1, enchant.maxLevel + 1)
                meta.addStoredEnchant(enchant, level, true)
                itemMeta = meta
            }
        }

        override fun toMap(): Map<String, Any?> =
            treasureMap {
                put("amount", formatRange(min, max))
                if (exclude.isNotEmpty()) put("exclude", exclude.toList())
            }

        override fun newId(): Enchant = copy(id = UUID.randomUUID().toString())

        override fun withMessages(messages: List<TreasureMessage>): Enchant = copy(messages = messages)

        override fun withWeight(weight: Int): Enchant = copy(weight = weight)

        fun withAmount(
            min: Int,
            max: Int = min,
        ): Enchant = copy(min = min, max = max)
    }

    /**
     * Potion treasure - gives random potions.
     */
    data class Potion(
        val min: Int = 1,
        val max: Int = 1,
        override val weight: Int = 1,
        override val messages: List<TreasureMessage> = emptyList(),
        override val id: String = UUID.randomUUID().toString(),
    ) : Treasure() {
        override val type: String = "potion"
        override val displayName: String get() = "Potion: ${formatRange(min, max)}"
        override val displayMaterial: Material get() = Material.POTION

        val amount: Int get() = if (min == max) min else ThreadLocalRandom.current().nextInt(min, max + 1)

        override fun toMap(): Map<String, Any?> =
            treasureMap {
                put("amount", formatRange(min, max))
            }

        override fun newId(): Potion = copy(id = UUID.randomUUID().toString())

        override fun withMessages(messages: List<TreasureMessage>): Potion = copy(messages = messages)

        override fun withWeight(weight: Int): Potion = copy(weight = weight)

        fun withAmount(
            min: Int,
            max: Int = min,
        ): Potion = copy(min = min, max = max)

        companion object {
            private val POTION_TYPES = listOf(Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION)

            fun randomPotion(): ItemStack {
                val material = POTION_TYPES.random()
                val types = PotionType.entries.filter { it != PotionType.WATER && it != PotionType.MUNDANE }
                val type = types.randomOrNull() ?: PotionType.HEALING

                return ItemStack(material).apply {
                    val meta = itemMeta as? PotionMeta ?: return@apply
                    meta.basePotionType = type
                    itemMeta = meta
                }
            }
        }
    }

    /**
     * AdvancedEnchantments loot — runs `ae giveitem` / `ae giverandombook` via console.
     */
    data class Ae(
        val kind: AeKind,
        val itemName: String? = null,
        val amount: Int = 1,
        val args: List<AeArg> = emptyList(),
        override val weight: Int = 1,
        override val messages: List<TreasureMessage> = emptyList(),
        override val id: String = UUID.randomUUID().toString(),
    ) : Treasure() {
        override val type: String = "ae"
        override val displayName: String
            get() =
                when (kind) {
                    AeKind.ITEM -> "AE: ${itemName ?: "?"}"
                    AeKind.RANDOM_BOOK -> "AE: random book"
                }
        override val displayMaterial: Material get() = Material.ENCHANTED_BOOK

        override fun toMap(): Map<String, Any?> =
            treasureMap {
                put("kind", kind.name.lowercase())
                itemName?.let { put("name", it) }
                if (amount != 1) put("amount", amount)
                if (args.isNotEmpty()) put("args", AeArg.toMapList(args))
            }

        override fun newId(): Ae = copy(id = UUID.randomUUID().toString())

        override fun withMessages(messages: List<TreasureMessage>): Ae = copy(messages = messages)

        override fun withWeight(weight: Int): Ae = copy(weight = weight)
    }

    /**
     * Slimefun item — runs `sf give <player> <item-id> [amount]`.
     */
    data class Slimefun(
        val itemId: String,
        val min: Int = 1,
        val max: Int = 1,
        override val weight: Int = 1,
        override val messages: List<TreasureMessage> = emptyList(),
        override val id: String = UUID.randomUUID().toString(),
    ) : Treasure() {
        override val type: String = "slimefun"
        override val displayName: String get() = "SF: $itemId (${formatRange(min, max)})"
        override val displayMaterial: Material get() = Material.IRON_INGOT

        val rolledAmount: Int get() = if (min == max) min else ThreadLocalRandom.current().nextInt(min, max + 1)

        override fun toMap(): Map<String, Any?> =
            treasureMap {
                put("item-id", itemId)
                put("amount", formatRange(min, max))
            }

        override fun newId(): Slimefun = copy(id = UUID.randomUUID().toString())

        override fun withMessages(messages: List<TreasureMessage>): Slimefun = copy(messages = messages)

        override fun withWeight(weight: Int): Slimefun = copy(weight = weight)
    }

    // ==================== Serialization Helpers ====================

    /**
     * Helper to build the common treasure map structure.
     */
    protected inline fun treasureMap(block: MutableMap<String, Any?>.() -> Unit): Map<String, Any?> =
        buildMap {
            put("type", type)
            put("id", id)
            put("weight", weight)
            block()
            if (messages.isNotEmpty()) {
                put("messages", messages.map { it.toMap() })
            }
        }

    companion object {
        /**
         * Deserializes a treasure from a map.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): Treasure? {
            val type = map["type"] as? String ?: return null
            val id = map["id"] as? String ?: UUID.randomUUID().toString()
            val weight = (map["weight"] as? Number)?.toInt() ?: 1

            // Parse new message format
            val messages =
                (map["messages"] as? List<*>)
                    ?.mapNotNull { (it as? Map<String, Any?>)?.let { m -> TreasureMessage.fromMap(m) } }
                    ?: emptyList()

            // Legacy migration: if old format exists, convert to new
            val legacyMessages =
                if (messages.isEmpty()) {
                    val oldMessage = map["message"] as? String
                    val oldGlobalMessage = map["globalMessage"] as? String
                    val oldAnnounce = map["announce"] as? Boolean ?: false
                    TreasureMessage.fromLegacy(oldMessage, oldGlobalMessage, oldAnnounce)
                } else {
                    messages
                }

            return when (type.lowercase()) {
                "item" -> {
                    val stackMap = map["stack"] as? Map<String, Any> ?: return null
                    val stack = ItemStack.deserialize(stackMap)
                    val (min, max) = parseAmountInt(map["amount"])
                    Item(stack, min, max, weight, legacyMessages, id)
                }

                "money" -> {
                    val (min, max) = parseAmountDouble(map["amount"])
                    Money(min, max, weight, legacyMessages, id)
                }

                "command" -> {
                    val commands = (map["commands"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    Command(commands, weight, legacyMessages, id)
                }

                "sub-pool" -> {
                    val poolId =
                        map["poolId"] as? String
                            ?: map["sub-pool-id"] as? String
                            ?: return null
                    SubPool(poolId, weight, legacyMessages, id)
                }

                "enchant" -> {
                    val (min, max) = parseAmountInt(map["amount"])
                    val exclude =
                        (map["exclude"] as? List<*>)
                            ?.filterIsInstance<String>()
                            ?.map { it.lowercase() }
                            ?.toSet()
                            ?: emptySet()
                    Enchant(min, max, exclude, weight, legacyMessages, id)
                }

                "potion" -> {
                    val (min, max) = parseAmountInt(map["amount"])
                    Potion(min, max, weight, legacyMessages, id)
                }

                "ae" -> {
                    val kind =
                        when ((map["kind"] as? String)?.lowercase()) {
                            "book", "random_book", "randombook" -> AeKind.RANDOM_BOOK
                            else -> AeKind.ITEM
                        }
                    val itemName = map["name"] as? String
                    val amount = (map["amount"] as? Number)?.toInt() ?: 1
                    val args = AeArg.parseList(map["args"])
                    Ae(kind, itemName, amount, args, weight, legacyMessages, id)
                }

                "slimefun" -> {
                    val itemId =
                        map["item-id"] as? String
                            ?: map["itemId"] as? String
                            ?: map["item"] as? String
                            ?: return null
                    val (min, max) = parseAmountInt(map["amount"])
                    Slimefun(itemId, min, max, weight, legacyMessages, id)
                }

                else -> {
                    null
                }
            }
        }

        private fun parseAmountInt(value: Any?): Pair<Int, Int> {
            val (min, max) = parseAmount(value)
            return min.toInt() to max.toInt()
        }

        private fun parseAmountDouble(value: Any?): Pair<Double, Double> {
            val (min, max) = parseAmount(value)
            return min.toDouble() to max.toDouble()
        }

        private fun parseAmount(value: Any?): Pair<Number, Number> =
            when (value) {
                is Number -> {
                    value to value
                }

                is String -> {
                    val parts = value.split("-")
                    if (parts.size == 2) {
                        val min = parts[0].trim().toDoubleOrNull() ?: 1.0
                        val max = parts[1].trim().toDoubleOrNull() ?: min
                        min to max
                    } else {
                        val single = value.toDoubleOrNull() ?: 1.0
                        single to single
                    }
                }

                else -> {
                    1 to 1
                }
            }

        private fun formatRange(
            min: Number,
            max: Number,
        ): Any = if (min == max) min else "$min-$max"
    }
}

/**
 * Configuration for giving treasures.
 */
data class GiveConfig(
    val sendMessages: Boolean = true,
    val sendPoolMessages: Boolean = true,
) {
    companion object {
        val DEFAULT = GiveConfig()
        val SILENT = GiveConfig(sendMessages = false, sendPoolMessages = false)
    }
}
