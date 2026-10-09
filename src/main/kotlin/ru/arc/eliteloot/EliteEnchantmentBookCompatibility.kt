package ru.arc.eliteloot

import com.magmaguy.elitemobs.items.itemconstructor.EnchantmentGenerator
import com.magmaguy.elitemobs.items.upgradesystem.EliteEnchantmentItems
import com.magmaguy.elitemobs.skills.WeaponIdentityResolver
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** Shared native-enchantment admission used before preview and when describing a book. */
internal fun <T> nativeBookCompatible(
    enchantments: Collection<T>,
    isMagicWeapon: Boolean,
    supportedMagicEnchantments: Collection<T>,
    ordinaryCompatibility: (T) -> Boolean,
): Boolean {
    if (enchantments.isEmpty()) return true
    return if (isMagicWeapon) {
        enchantments.all(supportedMagicEnchantments::contains)
    } else {
        enchantments.all(ordinaryCompatibility)
    }
}

/** EM's magic-weapon exception is intentional; ordinary gear must follow Bukkit applicability. */
internal fun isEliteBookNativeCompatible(target: ItemStack, book: ItemStack): Boolean {
    val enchantments = EliteEnchantmentItems.nativeLevels(book).keys
    val magicWeapon = WeaponIdentityResolver.isMagicWeapon(target)
    val supportedMagic = if (magicWeapon) EnchantmentGenerator.supportedMagicEnchantments() else emptyList()
    return nativeBookCompatible(enchantments, magicWeapon, supportedMagic) { enchantment ->
        runCatching { enchantment.canEnchantItem(target) }.getOrDefault(false)
    }
}

private val ordinaryCompatibilityByEnchantment = ConcurrentHashMap<Enchantment, Set<Material>>()

private fun ordinaryCompatibleMaterials(enchantment: Enchantment): Set<Material> =
    ordinaryCompatibilityByEnchantment.computeIfAbsent(enchantment) {
        Material.entries.asSequence()
            .filter { !it.isLegacy && it.isItem && !it.isAir && it != Material.ENCHANTED_BOOK }
            .filter { material -> runCatching { enchantment.canEnchantItem(ItemStack(material)) }.getOrDefault(false) }
            .toSet()
    }

private data class MaterialFamily(val label: String, val matches: (Material) -> Boolean)

private val materialFamilies = listOf(
    MaterialFamily("луков") { it == Material.BOW },
    MaterialFamily("арбалетов") { it == Material.CROSSBOW },
    MaterialFamily("трезубцев") { it.name == "TRIDENT" || it.name.endsWith("_TRIDENT") },
    MaterialFamily("мечей") { it.name.endsWith("_SWORD") },
    MaterialFamily("кирк") { it.name.endsWith("_PICKAXE") },
    MaterialFamily("лопат") { it.name.endsWith("_SHOVEL") },
    MaterialFamily("мотыг") { it.name.endsWith("_HOE") },
    MaterialFamily("топоров") { it.name.endsWith("_AXE") },
    MaterialFamily("копий") { it.name == "SPEAR" || it.name.endsWith("_SPEAR") },
    MaterialFamily("шлемов") { it.name.endsWith("_HELMET") },
    MaterialFamily("нагрудников") { it.name.endsWith("_CHESTPLATE") },
    MaterialFamily("поножей") { it.name.endsWith("_LEGGINGS") },
    MaterialFamily("ботинок") { it.name.endsWith("_BOOTS") },
    MaterialFamily("удочек") { it == Material.FISHING_ROD },
    MaterialFamily("ножниц") { it == Material.SHEARS },
    MaterialFamily("щитов") { it == Material.SHIELD },
    MaterialFamily("элитр") { it == Material.ELYTRA },
    MaterialFamily("булав") { it.name == "MACE" },
)

/** Compress only complete material families; partial matches retain exact client-localized names. */
internal fun compatibleNativeMaterialLabels(materials: Set<Material>): List<Component> {
    val remaining = materials.toMutableSet()
    val labels = mutableListOf<Component>()
    for (family in materialFamilies) {
        val members = Material.entries.filter { !it.isLegacy && family.matches(it) }
        if (members.isNotEmpty() && remaining.containsAll(members)) {
            labels += Component.text(family.label)
            remaining.removeAll(members.toSet())
        }
    }
    if (remaining.any { vanillaBookTargetProfile(it).type == "OTHER" }) {
        val otherMaterials = Material.entries.filter {
            !it.isLegacy && it.isItem && !it.isAir && it != Material.ENCHANTED_BOOK && vanillaBookTargetProfile(it).type == "OTHER"
        }
        if (otherMaterials.isNotEmpty() && remaining.containsAll(otherMaterials)) {
            labels += Component.text("прочих предметов")
            remaining.removeAll(otherMaterials.toSet())
        }
    }
    labels += remaining.sortedBy { it.key.toString() }.map { Component.translatable(it.translationKey()) }
    return labels
}

/** Compute exact ordinary targets through Bukkit's native API, intersecting every book enchant. */
internal fun compatibleOrdinaryBookMaterials(enchantments: Collection<Enchantment>): Set<Material> {
    if (enchantments.isEmpty()) return emptySet()
    return enchantments.drop(1).fold(ordinaryCompatibleMaterials(enchantments.first())) { common, enchantment ->
        common intersect ordinaryCompatibleMaterials(enchantment)
    }
}

internal data class EliteBookApplicabilityRule(
    val itemTypes: Set<String>,
    val validSlots: Set<String>,
    val attackKinds: Set<String>,
)

internal data class EliteBookTargetProfile(
    val type: String,
    val slots: Set<String>,
    val attackKinds: Set<String> = emptySet(),
)

internal fun eliteBookCustomRuleAllows(rule: EliteBookApplicabilityRule, target: EliteBookTargetProfile): Boolean =
    (rule.itemTypes.isEmpty() || target.type in rule.itemTypes) &&
        (rule.validSlots.isEmpty() || rule.validSlots.any(target.slots::contains)) &&
        (rule.attackKinds.isEmpty() || rule.attackKinds.any(target.attackKinds::contains))

internal fun eliteBookCustomRulesAllow(rules: Collection<EliteBookApplicabilityRule>, target: EliteBookTargetProfile): Boolean =
    rules.all { eliteBookCustomRuleAllows(it, target) }

private val vanillaBookItemTypes = listOf(
    "SWORD", "AXE", "PICKAXE", "SHOVEL", "HOE", "BOW", "CROSSBOW", "TRIDENT", "MACE", "SPEAR",
    "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "SHIELD", "FISHING_ROD", "SHEARS", "ELYTRA",
)
private val vanillaBookArmorSlots = mapOf(
    "HELMET" to "HEAD", "CHESTPLATE" to "CHEST", "LEGGINGS" to "LEGS", "BOOTS" to "FEET",
)

internal fun vanillaBookTargetProfile(material: Material): EliteBookTargetProfile {
    val type = vanillaBookItemTypes.firstOrNull { material.name == it || material.name.endsWith("_$it") } ?: "OTHER"
    val slots = vanillaBookArmorSlots[type]?.let { setOf(it) } ?: setOf("MAINHAND", "OFFHAND")
    return EliteBookTargetProfile(type, slots)
}

private data class EliteEnchantmentCatalogMethods(
    val definition: Method,
    val itemTypes: Method,
    val validSlots: Method,
    val attackKinds: Method,
)

@Volatile private var eliteEnchantmentCatalogMethods: EliteEnchantmentCatalogMethods? = null
@Volatile private var eliteEnchantmentCatalogWarningLogged = false

private fun disableEliteEnchantmentCatalogProjection(context: String, failure: Throwable) {
    eliteEnchantmentCatalogMethods = null
    if (!eliteEnchantmentCatalogWarningLogged) {
        org.bukkit.Bukkit.getLogger().log(
            Level.WARNING,
            "ARC disabled EliteMobs custom applicability projection $context; book lore will use its generic label",
            failure,
        )
        eliteEnchantmentCatalogWarningLogged = true
    }
}

/** Bind the exact optional EliteMobs API once; the active jar shades MagmaCore's record type. */
internal fun bindEliteEnchantmentCatalog(classLoader: ClassLoader): Boolean = try {
    val catalog = classLoader.loadClass("com.magmaguy.elitemobs.items.EliteEnchantmentCatalog")
    val definition = catalog.getMethod("definition", String::class.java)
    require(Modifier.isStatic(definition.modifiers))
    val record = definition.returnType
    fun setGetter(name: String) = record.getMethod(name).also {
        require(java.util.Set::class.java.isAssignableFrom(it.returnType))
    }
    eliteEnchantmentCatalogMethods = EliteEnchantmentCatalogMethods(
        definition,
        setGetter("itemTypes"),
        setGetter("validSlots"),
        setGetter("attackKinds"),
    )
    eliteEnchantmentCatalogWarningLogged = false
    true
} catch (failure: Exception) {
    disableEliteEnchantmentCatalogProjection("while binding its catalog", failure)
    false
} catch (failure: LinkageError) {
    disableEliteEnchantmentCatalogProjection("while binding its catalog", failure)
    false
}

internal fun clearEliteEnchantmentCatalog() {
    eliteEnchantmentCatalogMethods = null
}

private fun reflectedStringSet(value: Any?): Set<String> {
    val values = value as? Set<*> ?: error("EliteMobs applicability field is not a set")
    return values.mapTo(linkedSetOf()) {
        when (it) {
            is Enum<*> -> it.name
            is String -> it
            else -> error("Unknown EliteMobs applicability value")
        }
    }
}

private fun eliteBookCustomRules(ids: Collection<String>): List<EliteBookApplicabilityRule>? {
    if (ids.isEmpty()) return emptyList()
    val methods = eliteEnchantmentCatalogMethods ?: return null
    var currentId = "unknown"
    return try {
        ids.map { id ->
            currentId = id
            val definition = methods.definition.invoke(null, id) ?: error("Unknown EliteMobs enchantment $id")
            EliteBookApplicabilityRule(
                reflectedStringSet(methods.itemTypes.invoke(definition)),
                reflectedStringSet(methods.validSlots.invoke(definition)),
                reflectedStringSet(methods.attackKinds.invoke(definition)),
            )
        }
    } catch (failure: Exception) {
        disableEliteEnchantmentCatalogProjection("while reading custom enchantment $currentId", failure)
        null
    } catch (failure: LinkageError) {
        disableEliteEnchantmentCatalogProjection("while reading custom enchantment $currentId", failure)
        null
    }
}

/** Intersect native-approved materials with every custom rule; magic profiles are native-approved upstream. */
internal fun eliteBookTargetLabels(
    ordinaryMaterials: Collection<Material>,
    customRules: Collection<EliteBookApplicabilityRule>,
    magicTargets: Collection<EliteBookTargetProfile>,
): List<Component>? {
    val labels = compatibleNativeMaterialLabels(ordinaryMaterials.filterTo(linkedSetOf()) { material ->
        eliteBookCustomRulesAllow(customRules, vanillaBookTargetProfile(material))
    }).toMutableList()
    for (target in magicTargets) {
        if (!eliteBookCustomRulesAllow(customRules, target)) continue
        when (target.type) {
            "WAND" -> labels += Component.text("жезлов")
            "STAFF" -> labels += Component.text("посохов")
        }
    }
    return labels.takeIf { it.isNotEmpty() }
}

/** Native Bukkit admission and exact EM custom-profile restrictions share the lore calculation. */
internal fun eliteBookCompatibleTargetLabels(
    enchantments: Collection<Enchantment>,
    customEnchantmentIds: Collection<String>,
): List<Component>? {
    if (enchantments.isEmpty() && customEnchantmentIds.isEmpty()) return null
    val rules = eliteBookCustomRules(customEnchantmentIds) ?: return null
    val nativeTargets = if (enchantments.isEmpty()) {
        Material.entries.filterTo(linkedSetOf()) { !it.isLegacy && it.isItem && !it.isAir && it != Material.ENCHANTED_BOOK }
    } else compatibleOrdinaryBookMaterials(enchantments)
    val supportedMagic = EnchantmentGenerator.supportedMagicEnchantments()
    val magicTargets = if (nativeBookCompatible(enchantments, true, supportedMagic) { false }) listOf(
        EliteBookTargetProfile("WAND", setOf("MAINHAND"), setOf("WAND_MISSILE")),
        EliteBookTargetProfile("STAFF", setOf("MAINHAND"), setOf("STAFF_FIREBALL", "STAFF_MELEE")),
    ) else emptyList()
    return eliteBookTargetLabels(nativeTargets, rules, magicTargets)
}
