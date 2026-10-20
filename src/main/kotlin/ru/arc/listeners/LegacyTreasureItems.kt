package ru.arc.listeners

import de.tr7zw.changeme.nbtapi.NBT
import de.tr7zw.changeme.nbtapi.NBTType
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.ops.ItemPresets
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.customModelDataOrNull

/** Restores only the old Denizen dungeon rewards, using the currently loaded preset. */
internal object LegacyTreasureItems {
    private const val TREASURE_KEY = "arc:treasure_key"
    private const val USE_LORE = "Нажмите ПКМ для использования"
    private const val SF_LANTERN_NAME = "Сундук Слаймфана"
    private val plain = PlainTextComponentSerializer.plainText()
    private val whitespace = Regex("\\s+")
    private val failures = mutableMapOf<String, Long>()
    private val shapes = listOf(
        LegacyTreasureTarget(Material.IRON_INGOT, 12033, "ae_lootbox", "ae",
            setOf("Шкатулка с чарами", "Токен элитных чар"), mapOf("ae_token" to "true")),
        LegacyTreasureTarget(Material.IRON_INGOT, 73026, "sf_lootbox", "sf",
            setOf("Лутбокс слаймфана"), mapOf("slimefun_token" to "1")),
        LegacyTreasureTarget(Material.SEA_LANTERN, 10000, "sf_lootbox", "sf",
            setOf(SF_LANTERN_NAME), mapOf("slimefun_token" to "1")),
        money(11135, "money_handful", "smallmoney", "Горсть монет", 2000, 500, 500),
        money(11136, "money_pile", "mediummoney", "Кучка монет", 6500, 2000, 3000),
        money(11137, "money_heap", "bigmoney", "Куча монет", 10000, 3000, 5000),
        money(11138, "money_bag", "hugemoney", "Мешок монет", 15000, 5000, 7500),
    )

    fun replacement(original: ItemStack): ItemStack? {
        val fingerprint = fingerprint(original, emptyMap()) ?: return null
        if (fingerprint.foreignMetadata) return null
        val operation = "read:${original.type}:${fingerprint.model}"
        val data = try {
            NBT.get<Map<String, String?>>(original) { nbt ->
                readCustomData(fingerprint, nbt)
            }
        } catch (failure: Exception) {
            failed(operation, failure)
            return null
        }
        recovered(operation)
        val target = target(fingerprint.copy(customData = data)) ?: return null
        return replacement(original, target)
    }

    internal fun readCustomData(item: LegacyTreasureFingerprint, nbt: ReadableNBT): Map<String, String?> =
        nbt.keys.filterNot { key ->
            // BetterStructures' historical SF chest carried an empty IA compound, not an IA identity.
            key == "itemsadder" && item.material == Material.SEA_LANTERN && item.model == 10000 &&
                item.name == SF_LANTERN_NAME && item.lore == listOf(USE_LORE) &&
                nbt.getType(key) == NBTType.NBTTagCompound && nbt.getCompound(key)?.keys?.isEmpty() == true
        }.associateWith { key ->
            when (nbt.getType(key)) {
                NBTType.NBTTagString -> nbt.getString(key)
                NBTType.NBTTagInt -> nbt.getInteger(key).toString()
                NBTType.NBTTagByte -> nbt.getByte(key).toString()
                else -> null
            }
        }

    internal fun fingerprint(item: ItemStack, customData: Map<String, String?>): LegacyTreasureFingerprint? {
        val model = item.customModelDataOrNull ?: return null
        if (shapes.none { it.material == item.type && it.model == model }) return null
        val meta = item.itemMeta ?: return null
        return LegacyTreasureFingerprint(
            item.type,
            model,
            meta.displayName()?.let(plain::serialize)?.let(::normalize),
            meta.lore().orEmpty().map { normalize(plain.serialize(it)) }.filter(String::isNotEmpty),
            customData,
            meta.persistentDataContainer.keys.isNotEmpty() || meta.hasEnchants() ||
                meta.hasAttributeModifiers() || meta.isUnbreakable,
        )
    }

    internal fun target(item: LegacyTreasureFingerprint): LegacyTreasureTarget? {
        val target = shapes.firstOrNull { it.material == item.material && it.model == item.model } ?: return null
        if (item.foreignMetadata) return null
        val data = item.customData
        // Other reward/voucher/IA identities and explicit roll counts must retain their own contract.
        if (data.keys.any { it != TREASURE_KEY && it !in target.tokens }) return null
        if (TREASURE_KEY in data && data[TREASURE_KEY] != target.treasureKey) return null
        if (target.tokens.any { (key, value) -> key in data && !compatibleToken(key, data[key], value) }) return null

        val nativeMarker = data[TREASURE_KEY] == target.treasureKey ||
            target.tokens.all { (key, value) -> compatibleToken(key, data[key], value) }
        val oldPresentation = item.name in target.names && item.lore == listOf(USE_LORE)
        return target.takeIf { nativeMarker || oldPresentation }
    }

    internal fun replacement(
        original: ItemStack,
        target: LegacyTreasureTarget,
        resolve: (String, Int) -> Result<List<ItemStack>> = ItemPresets::resolveStacks,
        treasureKey: (ItemStack) -> String? = { item -> NBT.get<String>(item) { it.getString(TREASURE_KEY) } },
    ): ItemStack? {
        if (original.amount !in 1..64) return null
        val operation = "preset:${target.preset}"
        val replacement = try {
            val stacks = resolve(target.preset, original.amount).getOrThrow()
            check(stacks.size == 1) { "Preset must produce exactly one stack, got ${stacks.size}" }
            stacks.single().also { item ->
                check(!item.type.isAir && item.amount == original.amount && item.amount <= item.maxStackSize) {
                    "Preset must preserve the complete stack amount ${original.amount}, got ${item.type} x${item.amount}"
                }
                check(treasureKey(item) == target.treasureKey) { "Preset has an unexpected treasure key" }
            }
        } catch (failure: Exception) {
            failed(operation, failure)
            return null
        }
        recovered(operation)
        return replacement.takeUnless { it.isSimilar(original) }
    }

    private fun money(model: Int, preset: String, key: String, name: String, mean: Int, sigma: Int, minimum: Int) =
        LegacyTreasureTarget(Material.STICK, model, preset, key, setOf(name), mapOf(
            "money_token_mean" to mean.toString(),
            "money_token_sigma" to sigma.toString(),
            "money_token_minimum" to minimum.toString(),
        ))

    private fun compatibleToken(key: String, actual: String?, expected: String): Boolean =
        actual == expected || key == "ae_token" && actual == "1"

    private fun normalize(text: String): String = text.trim().replace(whitespace, " ")

    private fun failed(operation: String, failure: Exception) {
        val count = failures.getOrDefault(operation, 0) + 1
        failures[operation] = count
        if (count == 1L) warn("Legacy treasure migration failed at {}; original stack preserved", operation, failure)
    }

    private fun recovered(operation: String) {
        failures.remove(operation)?.let { count ->
            info("Legacy treasure migration recovered at {} after {} failed attempts", operation, count)
        }
    }
}

internal data class LegacyTreasureFingerprint(
    val material: Material,
    val model: Int,
    val name: String?,
    val lore: List<String>,
    val customData: Map<String, String?> = emptyMap(),
    val foreignMetadata: Boolean = false,
)

internal data class LegacyTreasureTarget(
    val material: Material,
    val model: Int,
    val preset: String,
    val treasureKey: String,
    val names: Set<String>,
    val tokens: Map<String, String>,
)
