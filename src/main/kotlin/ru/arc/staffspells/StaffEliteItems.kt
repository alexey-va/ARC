package ru.arc.staffspells

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.WeaponIdentityResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import ru.arc.util.Logging.warn
import java.util.concurrent.atomic.AtomicBoolean

/** Bridges only EliteMobs weapons whose FMM identity resolves to a supported staff family. */
internal object StaffEliteItems {
    val itemKey = NamespacedKey("arc", "elite_staff_spell")

    private val loreKey = NamespacedKey("arc", "elite_staff_lore")
    private val fmmItemKey = NamespacedKey("freeminecraftmodels", "fmm_item_id")
    private const val loreMarker = "arc-elite-staff-lore-v1"
    private val componentSerializer = GsonComponentSerializer.gson()
    private val missingProviderLogged = AtomicBoolean()

    private val wandSpells = listOf(
        StaffSpell.CHAIN,
        StaffSpell.MARK,
        StaffSpell.FROST,
        StaffSpell.LANCE,
        StaffSpell.NOVA,
        StaffSpell.LUNAR,
        StaffSpell.REQUIEM,
        StaffSpell.PRISM,
    )
    private val staffSpells = listOf(
        StaffSpell.EMBER,
        StaffSpell.ICICLE,
        StaffSpell.FAULT,
        StaffSpell.GALE,
        StaffSpell.TIDE,
        StaffSpell.NECROMANCER,
        StaffSpell.INFERNO,
        StaffSpell.GRAVITY,
        StaffSpell.OSSUARY,
    )

    /** Resolve a previously assigned spell without changing the stack. */
    fun resolve(item: ItemStack?): StaffSpell? {
        if (item == null || item.type.isAir) return null
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        if (pdc.keys.contains(StaffSpell.itemKey)) return null
        val id = pdc.get(itemKey, PersistentDataType.STRING) ?: return null
        val eligible = family(item) ?: return null
        return eligible.firstOrNull { it.id == id }
    }

    /** Assign one stable spell to an eligible legacy item; true means this call wrote the marker. */
    fun migrate(
        item: ItemStack?,
        choose: (List<StaffSpell>) -> StaffSpell = { it.random() },
    ): Boolean {
        if (item == null || item.type.isAir) return false
        val pdc = item.itemMeta.persistentDataContainer
        if (pdc.keys.contains(StaffSpell.itemKey) || pdc.keys.contains(itemKey)) return false
        val eligible = family(item) ?: return false
        val meta = item.itemMeta

        val selected = choose(eligible)
        if (selected !in eligible) return false
        meta.persistentDataContainer.set(itemKey, PersistentDataType.STRING, selected.id)
        item.itemMeta = meta
        return true
    }

    /** Apply the configured spell appearance and append its controls while retaining native item data. */
    fun present(item: ItemStack, config: StaffSpellConfig): Boolean {
        migrate(item)
        val spell = resolve(item) ?: return false
        config.applyEliteStaffAppearance(item, spell)
        item.editMeta { meta ->
            val existing = withoutOwnedLore(meta)
            val block = config.eliteStaffLore(spell)
                .map { it.decoration(TextDecoration.ITALIC, false) }
            val storedBlock = listOf(loreMarker) + block.map(componentSerializer::serialize)
            meta.lore(existing + block)
            meta.persistentDataContainer.set(loreKey, PersistentDataType.LIST.strings(), storedBlock)
        }
        return true
    }

    /** Remove ARC's recorded ability rows from a rendering clone before EliteMobs rebuilds its lore. */
    fun removePresentationLore(item: ItemStack) {
        item.editMeta { withoutOwnedLore(it) }
    }

    private fun family(item: ItemStack?): List<StaffSpell>? {
        if (item == null || item.type.isAir) return null
        if (!item.itemMeta.persistentDataContainer.keys.contains(fmmItemKey)) return null
        return try {
            if (!Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) return null
            if (!Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels")) return null
            if (!EliteItemManager.isEliteMobsItem(item)) return null
            when (WeaponIdentityResolver.progressionSkill(item)) {
                SkillType.WANDS -> wandSpells
                SkillType.STAVES -> staffSpells
                else -> null
            }
        } catch (failure: LinkageError) {
            if (missingProviderLogged.compareAndSet(false, true)) {
                warn("StaffSpells EliteMobs/FMM integration is unavailable; migrated staff recognition is disabled", failure)
            }
            null
        }
    }

    /** Returns lore with only the exact block recorded by this helper removed. */
    private fun withoutOwnedLore(meta: ItemMeta): List<Component> {
        val pdc = meta.persistentDataContainer
        val marker = pdc.get(loreKey, PersistentDataType.LIST.strings()).orEmpty()
        val lore = meta.lore().orEmpty()
        if (marker.firstOrNull() == loreMarker && marker.size > 1) {
            val stored = marker.drop(1)
            val serialized = lore.map(componentSerializer::serialize)
            val candidates = if (lore.size < stored.size) emptyList() else
                (0..lore.size - stored.size).filter { index ->
                    serialized.subList(index, index + stored.size) == stored
                }
            val suffix = lore.size - stored.size
            val start = when {
                suffix >= 0 && candidates.contains(suffix) -> suffix
                else -> candidates.singleOrNull()
            }
            if (start != null) {
                val updated = lore.toMutableList()
                repeat(stored.size) { updated.removeAt(start) }
                meta.lore(updated)
                pdc.remove(loreKey)
                return updated
            }
        }
        pdc.remove(loreKey)
        return lore
    }
}
