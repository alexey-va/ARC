package ru.arc.staffspells

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.WeaponIdentityResolver
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import ru.arc.config.Config
import ru.arc.eliteloot.EliteLootProcessor
import ru.arc.paper.testing.MockBukkitTestRuntime

class StaffEliteItemsTest : FreeSpec({
    "migration offers exactly the configured 8 wand spells and 8 staff spells" {
        MockBukkitTestRuntime.open().use { paper ->
            enableEliteMobs(paper)
            mockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                val wand = fmmStaff("fmm_default_arcane_wand")
                every { WeaponIdentityResolver.progressionSkill(wand) } returns SkillType.WANDS
                var wandOptions: List<StaffSpell>? = null
                StaffEliteItems.migrate(wand) { options -> options.also { wandOptions = it }.last() } shouldBe true
                wandOptions shouldBe listOf(
                    StaffSpell.CHAIN, StaffSpell.MARK, StaffSpell.FROST, StaffSpell.LANCE,
                    StaffSpell.NOVA, StaffSpell.LUNAR, StaffSpell.REQUIEM, StaffSpell.PRISM,
                )
                StaffEliteItems.resolve(wand) shouldBe StaffSpell.PRISM

                val staff = fmmStaff("fmm_default_arcane_staff")
                every { WeaponIdentityResolver.progressionSkill(staff) } returns SkillType.STAVES
                var staffOptions: List<StaffSpell>? = null
                StaffEliteItems.migrate(staff) { options -> options.also { staffOptions = it }.last() } shouldBe true
                staffOptions shouldBe listOf(
                    StaffSpell.EMBER, StaffSpell.ICICLE, StaffSpell.FAULT, StaffSpell.GALE,
                    StaffSpell.TIDE, StaffSpell.NECROMANCER,
                    StaffSpell.GRAVITY, StaffSpell.OSSUARY,
                )
                StaffEliteItems.resolve(staff) shouldBe StaffSpell.OSSUARY
            } finally {
                unmockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            }
        }
    }

    "migration adds one persistent marker in place and preserves EliteMobs, FMM, and weapon data" {
        MockBukkitTestRuntime.open().use { paper ->
            enableEliteMobs(paper)
            mockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                every { WeaponIdentityResolver.progressionSkill(any()) } returns SkillType.STAVES
                val fmmKey = NamespacedKey("freeminecraftmodels", "fmm_item_id")
                val soulbindKey = NamespacedKey("elitemobs", "soulbind")
                val itemValueKey = NamespacedKey("elitemobs", "itemvalue")
                val nativeKey = NamespacedKey("elitemobs", "itemsource")
                val arbitraryKey = NamespacedKey("test", "unrelated")
                val item = ItemStack(Material.WOODEN_SPEAR, 3)
                item.editMeta { meta ->
                    meta.setCustomModelData(12345)
                    meta.persistentDataContainer.set(fmmKey, PersistentDataType.STRING, "fmm_default_arcane_staff")
                    meta.persistentDataContainer.set(soulbindKey, PersistentDataType.STRING, "owner-id")
                    meta.persistentDataContainer.set(itemValueKey, PersistentDataType.DOUBLE, 37.5)
                    meta.persistentDataContainer.set(nativeKey, PersistentDataType.STRING, "elite-source")
                    meta.persistentDataContainer.set(arbitraryKey, PersistentDataType.STRING, "keep-me")
                    meta.addAttributeModifier(
                        Attribute.ATTACK_DAMAGE,
                        AttributeModifier(
                            NamespacedKey("test", "staff-damage"), 13.0,
                            AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND,
                        ),
                    )
                    (meta as Damageable).damage = 7
                }
                item.addUnsafeEnchantment(Enchantment.SHARPNESS, 4)
                val originalIdentity = item.itemMeta.persistentDataContainer.get(fmmKey, PersistentDataType.STRING)
                var selectionCalls = 0

                StaffEliteItems.migrate(item) { choices -> selectionCalls++; choices[6] } shouldBe true
                StaffEliteItems.migrate(item) { error("An assigned spell must not be rerolled") } shouldBe false

                selectionCalls shouldBe 1
                item.type shouldBe Material.WOODEN_SPEAR
                item.amount shouldBe 3
                item.itemMeta.customModelData shouldBe 12345
                item.itemMeta.getAttributeModifiers(Attribute.ATTACK_DAMAGE)!!.single().amount shouldBe 13.0
                (item.itemMeta as Damageable).damage shouldBe 7
                item.itemMeta.enchants[Enchantment.SHARPNESS] shouldBe 4
                val pdc = item.itemMeta.persistentDataContainer
                pdc.get(fmmKey, PersistentDataType.STRING) shouldBe originalIdentity
                pdc.get(soulbindKey, PersistentDataType.STRING) shouldBe "owner-id"
                pdc.get(itemValueKey, PersistentDataType.DOUBLE) shouldBe 37.5
                pdc.get(nativeKey, PersistentDataType.STRING) shouldBe "elite-source"
                pdc.get(arbitraryKey, PersistentDataType.STRING) shouldBe "keep-me"
                pdc.get(StaffEliteItems.itemKey, PersistentDataType.STRING) shouldBe StaffSpell.GRAVITY.id
                StaffSpell.from(item) shouldBe StaffSpell.GRAVITY
            } finally {
                unmockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            }
        }
    }

    "retired Elite inferno becomes Ember once without rerolling other assignments" {
        MockBukkitTestRuntime.open().use { paper ->
            enableEliteMobs(paper)
            mockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                every { WeaponIdentityResolver.progressionSkill(any()) } returns SkillType.STAVES
                val item = fmmStaff("fmm_default_arcane_staff")
                item.editMeta {
                    it.persistentDataContainer.set(StaffEliteItems.itemKey, PersistentDataType.STRING, "inferno")
                }
                StaffEliteItems.resolve(item) shouldBe StaffSpell.EMBER
                StaffEliteItems.migrate(item) { error("Retirement must not reroll") } shouldBe true
                item.itemMeta.persistentDataContainer.get(StaffEliteItems.itemKey, PersistentDataType.STRING) shouldBe "ember"
                StaffEliteItems.migrate(item) { error("Existing assignments are stable") } shouldBe false
                StaffEliteItems.resolve(item) shouldBe StaffSpell.EMBER
            } finally {
                unmockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            }
        }
    }

    "ordinary items, wrong-family markers, and ARC prototypes never become Elite spells" {
        MockBukkitTestRuntime.open().use { paper ->
            enableEliteMobs(paper)
            mockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns false
                every { WeaponIdentityResolver.progressionSkill(any()) } returns SkillType.WANDS
                val ordinary = fmmStaff("fmm_default_arcane_wand")
                StaffEliteItems.migrate(ordinary) shouldBe false
                StaffEliteItems.resolve(ordinary) shouldBe null
                ordinary.itemMeta.persistentDataContainer.keys.contains(StaffEliteItems.itemKey) shouldBe false

                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                every { WeaponIdentityResolver.progressionSkill(any()) } returns SkillType.STAVES
                val wrongFamily = fmmStaff("fmm_default_arcane_staff")
                wrongFamily.editMeta {
                    it.persistentDataContainer.set(StaffEliteItems.itemKey, PersistentDataType.STRING, StaffSpell.CHAIN.id)
                }
                StaffEliteItems.resolve(wrongFamily) shouldBe null
                StaffEliteItems.migrate(wrongFamily) shouldBe false
                wrongFamily.itemMeta.persistentDataContainer.get(StaffEliteItems.itemKey, PersistentDataType.STRING) shouldBe StaffSpell.CHAIN.id

                val prototype = ItemStack(Material.BLAZE_ROD)
                prototype.editMeta { it.persistentDataContainer.set(StaffSpell.itemKey, PersistentDataType.STRING, StaffSpell.CHAIN.id) }
                StaffEliteItems.migrate(prototype) shouldBe false
                StaffEliteItems.resolve(prototype) shouldBe null
                StaffSpell.from(prototype) shouldBe StaffSpell.CHAIN
                prototype.itemMeta.persistentDataContainer.keys.contains(StaffEliteItems.itemKey) shouldBe false
            } finally {
                unmockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            }
        }
    }

    "FMM staff stays untouched when the optional EliteMobs provider is absent" {
        MockBukkitTestRuntime.open().use { paper ->
            enableFmm(paper)
            mockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                every { WeaponIdentityResolver.progressionSkill(any()) } returns SkillType.STAVES
                val item = fmmStaff("fmm_default_arcane_staff")

                StaffEliteItems.migrate(item) shouldBe false
                StaffEliteItems.resolve(item) shouldBe null
                item.itemMeta.persistentDataContainer.keys.contains(StaffEliteItems.itemKey) shouldBe false
                verify(exactly = 0) { EliteItemManager.isEliteMobsItem(any()) }
                verify(exactly = 0) { WeaponIdentityResolver.progressionSkill(any()) }
            } finally {
                unmockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            }
        }
    }

    "FMM staff stays untouched when the optional FMM provider is absent" {
        MockBukkitTestRuntime.open().use { paper ->
            enableEliteMobsOnly(paper)
            mockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                every { WeaponIdentityResolver.progressionSkill(any()) } returns SkillType.STAVES
                val item = fmmStaff("fmm_default_arcane_staff")

                StaffEliteItems.migrate(item) shouldBe false
                StaffEliteItems.resolve(item) shouldBe null
                item.itemMeta.persistentDataContainer.keys.contains(StaffEliteItems.itemKey) shouldBe false
                verify(exactly = 0) { EliteItemManager.isEliteMobsItem(any()) }
                verify(exactly = 0) { WeaponIdentityResolver.progressionSkill(any()) }
            } finally {
                unmockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            }
        }
    }

    "Elite spell presentation is idempotent and leaves native lore and value data intact" {
        MockBukkitTestRuntime.open().use { paper ->
            enableEliteMobs(paper)
            mockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                every { WeaponIdentityResolver.progressionSkill(any()) } returns SkillType.WANDS
                val item = fmmStaff("fmm_default_arcane_wand")
                item.editMeta { meta ->
                    meta.persistentDataContainer.set(StaffEliteItems.itemKey, PersistentDataType.STRING, StaffSpell.CHAIN.id)
                    meta.persistentDataContainer.set(NamespacedKey("elitemobs", "itemvalue"), PersistentDataType.DOUBLE, 12.25)
                    meta.lore(listOf(Component.text("Native item level"), Component.text("Sharpness V")))
                }
                val config = StaffSpellConfig(StaffTestConfig(mapOf(
                    "chain.name" to "Цепная магия",
                    "chain.description" to "Описание цепи",
                    "chain.aim" to "Прицельтесь",
                    "chain.secondary" to "Вторичное умение",
                    "chain.quick" to "Быстрое умение",
                    "chain.quick-secondary" to "Быстрое вторичное умение",
                    "messages.use" to "ПКМ: удерживать и отпустить",
                )))

                StaffEliteItems.present(item, config) shouldBe true
                val firstLore = item.itemMeta.lore().orEmpty()
                StaffEliteItems.present(item, config) shouldBe true
                item.itemMeta.lore() shouldBe firstLore

                val plain = PlainTextComponentSerializer.plainText()
                val loreText = item.itemMeta.lore().orEmpty().map(plain::serialize)
                loreText.first() shouldBe "Native item level"
                loreText shouldContain "Sharpness V"
                loreText shouldContain "Описание цепи"
                loreText shouldContain "ПКМ: удерживать и отпустить"
                loreText.count { it == "Описание цепи" } shouldBe 1
                loreText.any { it.contains("prototype", ignoreCase = true) || it.contains("duel-only", ignoreCase = true) } shouldBe false
                plain.serialize(item.itemMeta.displayName()!!) shouldBe "Цепная магия"
                item.itemMeta.persistentDataContainer.get(
                    NamespacedKey("elitemobs", "itemvalue"), PersistentDataType.DOUBLE,
                ) shouldBe 12.25
            } finally {
                unmockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            }
        }
    }

    "Elite loot processing does not replace a migrated staff's configured spell skin" {
        MockBukkitTestRuntime.open().use { paper ->
            enableEliteMobs(paper)
            mockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            try {
                every { EliteItemManager.isEliteMobsItem(any()) } returns true
                every { WeaponIdentityResolver.progressionSkill(any()) } returns SkillType.STAVES
                val item = fmmStaff("fmm_default_arcane_staff")
                val config = mockk<Config>(relaxed = true)
                val processor = EliteLootProcessor(config, selectDecor = { error("Staff presentation owns spell skin") })

                processor.processEliteLoot(item) shouldBe item
                val firstSpell = requireNotNull(StaffEliteItems.resolve(item))
                processor.processEliteLoot(item) shouldBe item
                StaffEliteItems.resolve(item) shouldBe firstSpell
            } finally {
                unmockkStatic(EliteItemManager::class, WeaponIdentityResolver::class)
            }
        }
    }
})

private fun enableEliteMobs(paper: MockBukkitTestRuntime) {
    enableEliteMobsOnly(paper)
    enableFmm(paper)
}

private fun enableEliteMobsOnly(paper: MockBukkitTestRuntime) {
    paper.createSimplePlugin("EliteMobs")
}

private fun enableFmm(paper: MockBukkitTestRuntime) {
    paper.createSimplePlugin("FreeMinecraftModels")
}

private fun fmmStaff(id: String) = ItemStack(Material.WOODEN_SPEAR).apply {
    editMeta {
        it.persistentDataContainer.set(
            NamespacedKey("freeminecraftmodels", "fmm_item_id"), PersistentDataType.STRING, id,
        )
    }
}
