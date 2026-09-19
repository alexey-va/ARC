package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.treasurechest.TreasureChest
import com.magmaguy.elitemobs.quests.CustomQuest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import com.magmaguy.elitemobs.config.npcs.NPCsConfigFields
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.npcs.NPCEntity
import com.magmaguy.elitemobs.npcs.NPCInteractions
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.quests.DynamicQuest
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier
import java.util.UUID

class DungeonCompassPointsTest : FreeSpec({
    "questless player sees only nearby live NPCs with an available offer" {
        mockkStatic(TreasureChest::class, EntityTracker::class, PlayerData::class, DynamicQuest::class)
        try {
            val world = mockk<World> { every { uid } returns UUID.randomUUID() }
            val otherWorld = mockk<World> { every { uid } returns UUID.randomUUID() }
            val player = mockk<Player> {
                every { uniqueId } returns UUID.randomUUID()
                every { location } returns Location(world, 0.0, 64.0, 0.0)
                every { hasPermission("elitemobs.quest.npc") } returns true
            }
            fun npc(location: Location, valid: Boolean = true): NPCEntity {
                val body = mockk<Villager> {
                    every { isValid } returns valid
                    every { getLocation() } returns location
                }
                val fields = mockk<NPCsConfigFields> {
                    every { getInteractionType() } returns NPCInteractions.NPCInteractionType.QUEST_GIVER
                    every { isEnabled() } returns false // Do not spawn a native NPC in the fixture.
                }
                return object : NPCEntity(fields, null as String?) {
                    override fun isValid() = valid
                    override fun getVillager() = body
                    override fun getNPCsConfigFields() = fields
                }
            }
            every { TreasureChest.getTreasureChestHashMap() } returns hashMapOf()
            every { PlayerData.isInMemory(player) } returns true
            every { PlayerData.getQuests(player.uniqueId) } returns arrayListOf()
            every { DynamicQuest.hasAvailableQuests(player) } returns true
            val npcs = hashMapOf(
                UUID.randomUUID() to npc(Location(world, 10.0, 64.0, 0.0)),
                UUID.randomUUID() to npc(Location(world, 65.0, 64.0, 0.0)),
                UUID.randomUUID() to npc(Location(otherWorld, 10.0, 64.0, 0.0)),
                UUID.randomUUID() to npc(Location(world, 12.0, 64.0, 0.0), false),
            )
            every { EntityTracker.getNpcEntities() } returns npcs
            val provider = DungeonCompassPoints()
            provider.nearby(player) shouldBe listOf(
                DungeonCompassPoint(world.uid, 10.0, 64.0, 0.0, DungeonCompassPointKind.AVAILABLE_QUEST),
            )
            every { DynamicQuest.hasAvailableQuests(player) } returns false
            provider.nearby(player) shouldBe emptyList()
        } finally {
            unmockkStatic(TreasureChest::class, EntityTracker::class, PlayerData::class, DynamicQuest::class)
        }
    }

    "only accepted unfinished quests suppress another offer" {
        fun quest(filename: String, accepted: Boolean, turnedIn: Boolean): CustomQuest = mockk {
            every { getConfigurationFilename() } returns filename
            every { isAccepted() } returns accepted
            every { getQuestObjectives().isTurnedIn() } returns turnedIn
        }
        val active = activeCustomQuestFilenames(listOf(
            quest("active.yml", true, false),
            quest("pending.yml", false, false),
            quest("repeat.yml", true, true),
        ))
        active shouldBe setOf("active.yml")
        isFreshQuestOffer("repeat.yml", active, nativePermission = true) shouldBe true
        isFreshQuestOffer("repeat.yml", active, nativePermission = false) shouldBe false
    }

    "the pinned EliteMobs chest state contract has the exact private fields" {
        val restockTime = TreasureChest::class.java.getDeclaredField("restockTime")
        val blacklistedPlayers = TreasureChest::class.java.getDeclaredField("blacklistedPlayersInstance")

        restockTime.type shouldBe Long::class.javaPrimitiveType
        blacklistedPlayers.type shouldBe HashSet::class.java
        Modifier.isPrivate(restockTime.modifiers) shouldBe true
        Modifier.isPrivate(blacklistedPlayers.modifiers) shouldBe true
    }

    "quest offer gate rejects an existing or permission-denied offer" {
        val filename = "story.yml"

        isFreshQuestOffer(filename, emptySet(), nativePermission = true) shouldBe true
        isFreshQuestOffer(filename, setOf(filename), nativePermission = true) shouldBe false
        isFreshQuestOffer(filename, emptySet(), nativePermission = false) shouldBe false
    }

    "group chest cooldown is read-only and player scoped" {
        val player = UUID.randomUUID()
        val other = UUID.randomUUID()
        val now = 1_000L

        isChestAvailableFor(
            player,
            instanced = false,
            dropStyle = TreasureChest.DropStyle.GROUP,
            restockTime = now,
            restockTimers = listOf("$player:${now + 30}", "$other:${now + 30}"),
            blacklistedPlayers = emptySet(),
            nowEpochSeconds = now,
        ) shouldBe false

        isChestAvailableFor(
            player,
            instanced = false,
            dropStyle = TreasureChest.DropStyle.GROUP,
            restockTime = now,
            restockTimers = listOf("$other:${now + 30}"),
            blacklistedPlayers = emptySet(),
            nowEpochSeconds = now,
        ) shouldBe true
    }

    "instanced group chest cooldown uses the player blacklist" {
        val player = UUID.randomUUID()

        isChestAvailableFor(
            player,
            instanced = true,
            dropStyle = TreasureChest.DropStyle.GROUP,
            restockTime = 0,
            restockTimers = emptyList(),
            blacklistedPlayers = setOf(player),
            nowEpochSeconds = 1_000,
        ) shouldBe false
    }

    "expired and malformed timers stay unchanged and do not hide a chest" {
        val player = UUID.randomUUID()
        val timers = mutableListOf("$player:999", "$player:1000", "$player:invalid", "invalid")
        val original = timers.toList()
        isChestAvailableFor(player, false, TreasureChest.DropStyle.GROUP, 1000,
            timers, emptySet(), 1000) shouldBe true
        timers shouldBe original
        isChestAvailableFor(player, false, TreasureChest.DropStyle.GROUP, 1001,
            timers, emptySet(), 1000) shouldBe false
        isChestAvailableFor(player, true, TreasureChest.DropStyle.GROUP, 0,
            timers, setOf(UUID.randomUUID()), 1000) shouldBe true
    }
})
