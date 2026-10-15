package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.PlayerDamagedByEliteMobEvent
import com.magmaguy.elitemobs.config.SkillsConfig
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.WeaponIdentityResolver
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.PlayerInventory
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.staffspells.StaffEliteItems
import ru.arc.staffspells.StaffSpell
import ru.arc.staffspells.StaffSpellCast
import ru.arc.staffspells.StaffSpellsModule
import ru.arc.staffspells.StaffWeaponFamily
import java.util.IdentityHashMap
import java.util.UUID

class DungeonMasteryEffectsTest : FreeSpec({
    "mastery uses whole native ten-level steps with one capped budget and truthful weapon families" {
        listOf(Int.MIN_VALUE, 0, 1, 9, 10, 19, 20, 99, 100, Int.MAX_VALUE)
            .map(::dungeonMasteryPercent) shouldBe listOf(0, 0, 0, 0, 1, 1, 2, 9, 10, 10)
        dungeonMasteryKind(SkillType.ARMOR) shouldBe DungeonMasteryKind.ARMOR_DEFENSE
        listOf(SkillType.SWORDS, SkillType.AXES, SkillType.HOES, SkillType.MACES,
            SkillType.SPEARS, SkillType.TRIDENTS).forEach {
            dungeonMasteryKind(it) shouldBe DungeonMasteryKind.MELEE_ATTACK_SPEED
        }
        listOf(SkillType.WANDS, SkillType.STAVES).forEach {
            dungeonMasteryKind(it) shouldBe DungeonMasteryKind.STAFF_CAST_SPEED
        }
        listOf(SkillType.BOWS, SkillType.CROSSBOWS).forEach { dungeonMasteryKind(it) shouldBe null }
        dungeonMasteryCooldownTicks(20, 100) shouldBe 19L
        dungeonMasteryCooldownTicks(80, 100) shouldBe 73L
        dungeonMasteryCooldownTicks(100, 9) shouldBe 100L
        dungeonMasteryCooldownTicks(1, 100) shouldBe 1L
        dungeonMasteryCooldownTicks(0, 100) shouldBe 0L
    }

    "defense composes with native armor while retaining the native ceiling and full negation" {
        MasteryFixture().use { f ->
            val event = f.damage(100.0)
            f.effects.rememberIncoming(event)
            event.damage = 80.0 // Existing native Battle Hardened.
            f.effects.defend(event)
            event.damage shouldBe 72.0
            f.effects.defend(event)
            event.damage shouldBe 72.0 // An event snapshot is consumed only once.

            dungeonMasteryDefendedDamage(100.0, 15.0, 100) shouldBe (15.0 plusOrMinus 1e-12)
            dungeonMasteryDefendedDamage(100.0, 12.0, 100) shouldBe 12.0
            dungeonMasteryDefendedDamage(100.0, 0.0, 100) shouldBe 0.0
            dungeonMasteryDefendedDamage(Double.NaN, 80.0, 100) shouldBe 80.0
            val cancelled = f.damage(100.0)
            f.effects.rememberIncoming(cancelled)
            cancelled.isCancelled = true
            f.effects.defend(cancelled)
            cancelled.isCancelled = false
            f.effects.defend(cancelled)
            cancelled.damage shouldBe 100.0
        }
    }

    "the transient melee modifier follows the exact held weapon and preserves every foreign modifier" {
        MasteryFixture().use { f ->
            val foreign = AttributeModifier(NamespacedKey("elitemobs", "flurry"), 0.3,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1)
            f.modifiers[foreign.key] = foreign
            f.effects.refresh(f.player)
            f.bonus() shouldBe 0.1
            f.effects.refresh(f.player)
            verify(exactly = 1) { f.attribute.addTransientModifier(any()) }

            val axe = ItemStack(Material.IRON_AXE)
            f.levels[SkillType.AXES] = 20
            f.slots[1] = axe
            val cancelled = PlayerItemHeldEvent(f.player, 0, 1).also { it.isCancelled = true }
            f.effects.held(cancelled)
            f.bonus() shouldBe 0.1
            f.effects.held(PlayerItemHeldEvent(f.player, 0, 1))
            f.bonus() shouldBe 0.02
            f.effects.swapped(PlayerSwapHandItemsEvent(f.player, ItemStack(Material.BOW), f.held))
            f.bonus() shouldBe null

            // An explicit unresolved FMM item cannot inherit its sword carrier's mastery.
            val unresolved = ItemStack(Material.DIAMOND_SWORD)
            f.identities[unresolved] = null
            f.effects.refresh(f.player)
            f.effects.slotChanged(PlayerInventorySlotChangeEvent(f.player, 37, f.held, unresolved))
            f.bonus() shouldBe 0.1 // A different hotbar slot is not the held item.
            f.effects.slotChanged(PlayerInventorySlotChangeEvent(f.player, 36, f.held, unresolved))
            f.bonus() shouldBe null
            f.modifiers shouldBe mapOf(foreign.key to foreign)
            verify(exactly = 0) { f.attribute.baseValue = any() }
        }
    }

    "dungeon membership readiness and native settings are rechecked and remove the owned modifier" {
        val deny: List<(MasteryFixture) -> Unit> = listOf(
            { it.inDungeon = false }, { it.ready = false }, { it.loaded = false },
            { it.enabled = false }, { it.excluded = true }, { it.mode = GameMode.SPECTATOR },
        )
        for (change in deny) MasteryFixture().use { f ->
            f.effects.refresh(f.player)
            f.bonus() shouldBe 0.1
            val event = f.damage(100.0)
            f.effects.rememberIncoming(event)
            change(f)
            f.effects.worldChanged(PlayerChangedWorldEvent(f.player, mockk()))
            f.bonus() shouldBe null
            f.effects.defend(event)
            event.damage shouldBe 100.0
        }
    }

    "reconciliation reads fresh native levels and shutdown leaves no scheduled work or owned modifier" {
        MasteryFixture().use { f ->
            f.effects.start()
            f.effects.start()
            f.bonus() shouldBe 0.1
            f.levels[SkillType.SWORDS] = 30
            f.scheduler.tick(20)
            f.bonus() shouldBe 0.03
            f.effects.quit(PlayerQuitEvent(f.player, null as net.kyori.adventure.text.Component?))
            f.bonus() shouldBe null
            f.effects.refresh(f.player)
            f.effects.close()
            f.bonus() shouldBe null
            f.scheduler.tick(40)
            f.effects.refresh(f.player)
            f.bonus() shouldBe null
        }
    }

    "only an actual migrated native magic weapon grants its captured family cooldown bonus" {
        MasteryFixture().use { f ->
            val wand = ItemStack(Material.BLAZE_ROD)
            f.identities[wand] = SkillType.WANDS
            f.held = wand
            val cast = StaffSpellCast(f.id, UUID.randomUUID(), 100, null, null, true,
                progressionSkill = StaffWeaponFamily.WANDS, nativeEliteWeapon = true)
            dungeonHeldMasterySkill(f.player) shouldBe null // Native FMM, no StaffSpells assignment.
            f.effects.staffCooldownTicks(f.player, cast, 100) shouldBe 100L
            f.spells[wand] = StaffSpell.CHAIN
            dungeonHeldMasterySkill(f.player) shouldBe SkillType.WANDS
            f.effects.refresh(f.player)
            f.bonus() shouldBe null // Magic never receives melee ATTACK_SPEED.
            f.effects.staffCooldownTicks(f.player, cast, 100) shouldBe 91L
            f.effects.staffCooldownTicks(f.player, cast.copy(skillLevel = 20), 100) shouldBe 99L
            f.effects.staffCooldownTicks(f.player, cast.copy(nativeEliteWeapon = false), 100) shouldBe 100L
            f.effects.staffCooldownTicks(f.player, cast.copy(eliteMobsEnabled = false), 100) shouldBe 100L
            f.effects.staffCooldownTicks(f.player, cast.copy(duelAuthorization = mockk()), 100) shouldBe 100L
            f.effects.staffCooldownTicks(f.player, cast.copy(progressionSkill = StaffWeaponFamily.STAVES), 100) shouldBe 100L
            f.staffReady = false
            dungeonHeldMasterySkill(f.player) shouldBe null
            f.effects.staffCooldownTicks(f.player, cast, 100) shouldBe 100L
            f.staffReady = true
            f.inDungeon = false
            f.effects.staffCooldownTicks(f.player, cast, 100) shouldBe 100L
        }
    }
})

/** Direct AttributeInstance double verifies exact ownership; it does not model client attack timing. */
private class MasteryFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    val id: UUID = UUID.randomUUID()
    val scheduler = TestTaskScheduler()
    val player = mockk<Player>()
    val attribute = mockk<AttributeInstance>()
    private val inventory = mockk<PlayerInventory>()
    private val view = mockk<InventoryView>()
    val modifiers = linkedMapOf<Key, AttributeModifier>()
    val levels = SkillType.entries.associateWith { 100 }.toMutableMap()
    val identities = IdentityHashMap<ItemStack, SkillType?>()
    val spells = IdentityHashMap<ItemStack, StaffSpell>()
    val slots = mutableMapOf<Int, ItemStack>()
    var held = ItemStack(Material.IRON_SWORD)
    var inDungeon = true
    var ready = true
    var loaded = true
    var enabled = true
    var excluded = false
    var staffReady = true
    var mode = GameMode.SURVIVAL
    val effects: DungeonMasteryEffects

    init {
        mockkStatic(Bukkit::class, PlayerData::class, SkillsConfig::class, WeaponIdentityResolver::class)
        mockkObject(StaffEliteItems, StaffSpellsModule)
        every { Bukkit.getOnlinePlayers() } returns listOf(player)
        every { player.uniqueId } returns id
        every { player.isOnline } returns true
        every { player.isValid } returns true
        every { player.isDead } returns false
        every { player.gameMode } answers { mode }
        every { player.inventory } returns inventory
        every { player.openInventory } returns view
        // Paper converts the raw default crafting-view hotbar slots into PlayerInventory slots.
        every { view.convertSlot(36) } returns 0
        every { view.convertSlot(37) } returns 1
        every { inventory.itemInMainHand } answers { held }
        every { inventory.heldItemSlot } returns 0
        every { inventory.getItem(any<Int>()) } answers { slots[firstArg()] }
        every { player.getAttribute(Attribute.ATTACK_SPEED) } returns attribute
        every { attribute.getModifier(any<Key>()) } answers { modifiers[firstArg<Key>()] }
        every { attribute.removeModifier(any<AttributeModifier>()) } answers {
            modifiers.remove(firstArg<AttributeModifier>().key); Unit
        }
        every { attribute.addTransientModifier(any()) } answers {
            val modifier = firstArg<AttributeModifier>()
            modifiers[modifier.key] = modifier
        }
        every { PlayerData.isDataLoaded(id) } answers { loaded }
        every { PlayerData.getSkillLevel(id, any()) } answers { levels.getValue(secondArg()) }
        every { SkillsConfig.isSkillSystemEnabled() } answers { enabled }
        every { SkillsConfig.isWorldExcludedFromSkills(player) } answers { excluded }
        every { WeaponIdentityResolver.progressionSkill(any()) } answers {
            val item = firstArg<ItemStack?>()
            if (item == null) null else if (identities.containsKey(item)) identities[item]
            else SkillType.fromMaterial(item.type)
        }
        every { StaffEliteItems.resolve(any()) } answers { spells[firstArg()] }
        every { StaffSpellsModule.ready() } answers { staffReady }
        effects = Tasks.withScheduler(scheduler) { DungeonMasteryEffects({ inDungeon }, { ready }) }
    }

    fun bonus(): Double? = modifiers[DungeonMasteryEffects.ATTACK_SPEED_KEY]?.amount

    fun damage(amount: Double): PlayerDamagedByEliteMobEvent {
        val source = mockk<EntityDamageByEntityEvent>(relaxed = true)
        every { source.entity } returns player
        return PlayerDamagedByEliteMobEvent(mockk(), player, source, null, amount)
    }

    override fun close() {
        effects.close()
        unmockkObject(StaffEliteItems, StaffSpellsModule)
        unmockkStatic(Bukkit::class, PlayerData::class, SkillsConfig::class, WeaponIdentityResolver::class)
        paper.close()
    }
}
