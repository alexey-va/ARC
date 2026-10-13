package ru.arc.enchanting

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.dungeons.api.ArcBookApplicationOutcome
import java.util.concurrent.ThreadLocalRandom

internal data class EliteBookApplicationChances(val success: Int, val destroyOnFailure: Int) {
    init { require(success in 0..100 && destroyOnFailure in 0..1) }
}

private val successKey = NamespacedKey("arc", "elite_book_success")
private val destroyKey = NamespacedKey("arc", "elite_book_destroy")

internal fun readEliteBookChances(book: ItemStack): EliteBookApplicationChances? {
    val pdc = book.itemMeta?.persistentDataContainer ?: return null
    val success = pdc.get(successKey, PersistentDataType.INTEGER) ?: return null
    val destroy = pdc.get(destroyKey, PersistentDataType.INTEGER) ?: return null
    if (success !in 0..100 || destroy !in 0..100) return null
    return EliteBookApplicationChances(success, destroy.coerceAtMost(1))
}

/** Roll once on acquisition/migration, then carry the percentages with the physical book. */
internal fun prepareEliteBookChances(book: ItemStack): EliteBookApplicationChances {
    val chances = readEliteBookChances(book)
        ?: EliteBookApplicationChances(ThreadLocalRandom.current().nextInt(40, 81), 1)
    writeEliteBookChances(book, chances)
    return chances
}

internal fun writeEliteBookChances(book: ItemStack, chances: EliteBookApplicationChances) {
    book.editMeta { meta ->
        meta.persistentDataContainer.set(successKey, PersistentDataType.INTEGER, chances.success)
        meta.persistentDataContainer.set(destroyKey, PersistentDataType.INTEGER, chances.destroyOnFailure)
    }
}

internal fun boostedBookChances(chances: EliteBookApplicationChances, boost: Int, lowerDestroy: Boolean): EliteBookApplicationChances {
    require(boost in 1..100)
    return EliteBookApplicationChances(
        (chances.success + boost).coerceAtMost(100),
        if (lowerDestroy) (chances.destroyOnFailure - boost).coerceAtLeast(0) else chances.destroyOnFailure,
    )
}

internal fun bookChanceLore(chances: EliteBookApplicationChances): List<Component> = listOf(
    chanceRow("Шанс успеха: ", chances.success, 0x70E0A0),
    chanceRow("Разрушение при неудаче: ", chances.destroyOnFailure, 0xFF6B72),
)

private fun chanceRow(label: String, value: Int, color: Int): Component =
    Component.text(label, TextColor.color(0xB8B8B8))
        .append(Component.text("$value%", TextColor.color(color)))
        .decoration(TextDecoration.ITALIC, false)

internal typealias EliteEnchantmentOutcome = ArcBookApplicationOutcome

/** A separate destruction roll happens only after a failed application, matching AE's meaning. */
internal fun bookApplicationOutcome(
    chances: EliteBookApplicationChances,
    successRoll: Double,
    destroyRoll: Double,
): EliteEnchantmentOutcome = ArcBookApplicationOutcome.roll(
    chances.success,
    chances.destroyOnFailure,
    successRoll,
    destroyRoll,
)
