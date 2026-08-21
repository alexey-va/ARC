package ru.arc.ops

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.MetadataStore
import net.citizensnpcs.api.ai.Navigator
import net.citizensnpcs.api.ai.NavigatorParameters
import net.citizensnpcs.api.trait.trait.MobType
import net.citizensnpcs.api.trait.Trait
import net.citizensnpcs.api.util.MemoryDataKey
import net.citizensnpcs.trait.CurrentLocation
import net.citizensnpcs.trait.CommandTrait
import net.citizensnpcs.trait.LookClose
import net.citizensnpcs.trait.SkinTrait
import net.citizensnpcs.trait.text.Text
import net.citizensnpcs.trait.waypoint.LinearWaypointProvider
import net.citizensnpcs.trait.waypoint.Waypoints
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.UUID

class OpsNpcHandlersTest : FreeSpec({

    "presence-aware NpcSpec patch" - {
        "should preserve every setting omitted by a name-only edit" {
            val spec = OpsNpcSpec.parse(JsonParser.parseString("""{"name":"Новое имя"}""").asJsonObject)

            (spec.name as NpcPatch.Set).value shouldBe "Новое имя"
            spec.nameplate shouldBe NpcPatch.Absent
            spec.location shouldBe NpcPatch.Absent
            spec.skin shouldBe NpcPatch.Absent
            spec.lookClose shouldBe NpcPatch.Absent
            spec.commands shouldBe NpcPatch.Absent
            spec.hologram shouldBe NpcPatch.Absent
            spec.equipment shouldBe NpcPatch.Absent
            spec.path shouldBe NpcPatch.Absent
            spec.text shouldBe NpcPatch.Absent
            spec.navigation shouldBe NpcPatch.Absent
            spec.changedFields shouldBe listOf("name")
        }

        "should parse an explicit nameplate mode" {
            val spec =
                OpsNpcSpec.parse(
                    JsonParser.parseString("""{"nameplate":"hidden"}""").asJsonObject,
                )

            (spec.nameplate as NpcPatch.Set).value shouldBe NameplateMode.HIDDEN
            spec.changedFields shouldBe listOf("nameplate")
        }

        "should reject booleans and unknown nameplate modes" {
            shouldThrow<IllegalArgumentException> {
                OpsNpcSpec.parse(JsonParser.parseString("""{"nameplate":false}""").asJsonObject)
            }
            shouldThrow<IllegalArgumentException> {
                OpsNpcSpec.parse(JsonParser.parseString("""{"nameplate":"sometimes"}""").asJsonObject)
            }
        }

        "should preserve command entries when only their mode is patched" {
            val spec =
                OpsNpcSpec.parse(
                    JsonParser
                        .parseString("""{"commands":{"mode":"sequential"}}""")
                        .asJsonObject,
                )
            val commands = (spec.commands as NpcPatch.Set).value

            commands.entries shouldBe NpcPatch.Absent
            (commands.mode as NpcPatch.Set).value.name shouldBe "SEQUENTIAL"
        }

        "should preserve a skin name when only its refresh setting is patched" {
            val spec =
                OpsNpcSpec.parse(
                    JsonParser.parseString("""{"skin":{"update":true}}""").asJsonObject,
                )
            val skin = (spec.skin as NpcPatch.Set).value

            skin.name shouldBe NpcPatch.Absent
            (skin.update as NpcPatch.Set).value shouldBe true
        }

        "should accept a complete persistent MineSkin payload" {
            val texture =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(
                        """{"textures":{"SKIN":{"url":"https://textures.minecraft.net/texture/example"}}}"""
                            .toByteArray(),
                    )
            val spec =
                OpsNpcSpec.parse(
                    JsonParser
                        .parseString(
                            """{"skin":{"name":"rc-smith","texture":"$texture","signature":"c2lnbmF0dXJl"}}""",
                        ).asJsonObject,
                )
            val skin = (spec.skin as NpcPatch.Set).value

            (skin.name as NpcPatch.Set).value shouldBe "rc-smith"
            (skin.texture as NpcPatch.Set).value shouldBe texture
            (skin.signature as NpcPatch.Set).value shouldBe "c2lnbmF0dXJl"
        }

        "should reject incomplete or refreshable persistent MineSkin payloads" {
            val texture =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(
                        """{"textures":{"SKIN":{"url":"https://textures.minecraft.net/texture/example"}}}"""
                            .toByteArray(),
                    )

            shouldThrow<IllegalArgumentException> {
                OpsNpcSpec.parse(
                    JsonParser
                        .parseString("""{"skin":{"name":"rc-smith","texture":"$texture"}}""")
                        .asJsonObject,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OpsNpcSpec.parse(
                    JsonParser
                        .parseString(
                            """{"skin":{"name":"rc-smith","texture":"$texture","signature":"c2lnbmF0dXJl","update":true}}""",
                        ).asJsonObject,
                )
            }
        }

        "should preserve omitted coordinates in a location patch" {
            val spec =
                OpsNpcSpec.parse(
                    JsonParser.parseString("""{"location":{"yaw":180}}""").asJsonObject,
                )
            val location = (spec.location as NpcPatch.Set).value

            location.world shouldBe NpcPatch.Absent
            location.x shouldBe NpcPatch.Absent
            location.y shouldBe NpcPatch.Absent
            location.z shouldBe NpcPatch.Absent
            (location.yaw as NpcPatch.Set).value shouldBe 180f
            location.pitch shouldBe NpcPatch.Absent
        }

        "should use explicit null only to clear a supported trait" {
            val spec = OpsNpcSpec.parse(JsonParser.parseString("""{"hologram":null}""").asJsonObject)

            spec.hologram shouldBe NpcPatch.Clear
            shouldThrow<IllegalArgumentException> {
                OpsNpcSpec.parse(JsonParser.parseString("""{"name":null}""").asJsonObject)
            }
        }

        "should reject unknown fields instead of silently dropping settings" {
            shouldThrow<IllegalArgumentException> {
                OpsNpcSpec.parse(JsonParser.parseString("""{"displayName":"Guide"}""").asJsonObject)
            }
            shouldThrow<IllegalArgumentException> {
                OpsNpcSpec.parse(
                    JsonParser
                        .parseString("""{"commands":{"entries":[],"mdoe":"sequential"}}""")
                        .asJsonObject,
                )
            }
        }

        "should require identity and base coordinates only for creation" {
            val patch =
                OpsNpcSpec.parse(
                    JsonParser.parseString("""{"location":{"yaw":180}}""").asJsonObject,
                )
            shouldThrow<IllegalArgumentException> { patch.requireCreateFields() }

            val create =
                OpsNpcSpec.parse(
                    JsonParser
                        .parseString(
                            """{"name":"Guide","location":{"world":"spawn","x":1,"z":2}}""",
                        ).asJsonObject,
                )
            create.requireCreateFields()
        }
    }

    "nameplate persistence" - {
        "should store the Citizens metadata and schedule a packet refresh" {
            val metadata = mockk<MetadataStore>()
            val npc = mockk<NPC>()
            every { npc.data() } returns metadata
            every { metadata.setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false) } just runs
            every { npc.scheduleUpdate(NPC.NPCUpdate.PACKET) } just runs

            OpsNpcHandlers.applyNameplate(npc, NpcPatch.Set(NameplateMode.HIDDEN))

            verifyOrder {
                metadata.setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false)
                npc.scheduleUpdate(NPC.NPCUpdate.PACKET)
            }
        }

        "should normalize Citizens boolean and hover storage values" {
            val metadata = mockk<MetadataStore>()
            val npc = mockk<NPC>()
            every { npc.data() } returns metadata
            every { metadata.get<Any>(NPC.Metadata.NAMEPLATE_VISIBLE, true) } returns false
            OpsNpcHandlers.nameplateSummary(npc) shouldBe "hidden"

            every { metadata.get<Any>(NPC.Metadata.NAMEPLATE_VISIBLE, true) } returns "hover"
            OpsNpcHandlers.nameplateSummary(npc) shouldBe "hover"

            every { metadata.get<Any>(NPC.Metadata.NAMEPLATE_VISIBLE, true) } returns true
            OpsNpcHandlers.nameplateSummary(npc) shouldBe "visible"
        }
    }

    "move persistence" - {
        "should update CurrentLocation before the registry is saved" {
            val world = mockk<World>()
            val target = Location(world, 379.0, 119.0, 272.0, 180f, 0f)
            val npc = mockk<NPC>()
            val currentLocation = mockk<CurrentLocation>()

            every {
                npc.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
            } just runs
            every { npc.getOrAddTrait(CurrentLocation::class.java) } returns currentLocation
            every { currentLocation.setLocation(target) } just runs

            OpsNpcHandlers.teleportAndStore(npc, target)

            verifyOrder {
                npc.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                currentLocation.setLocation(target)
            }
        }

        "should report the validated target while Citizens finishes teleporting the entity" {
            val world = mockk<World>()
            every { world.name } returns "spawn"
            val oldLocation = Location(world, 376.0, 119.0, 272.0, 180f, 0f)
            val target = Location(world, 379.0, 119.0, 272.0, 180f, 0f)
            val entity = mockk<Entity>()
            every { entity.type } returns EntityType.VILLAGER
            val navigatorParameters = mockk<NavigatorParameters>(relaxed = true)
            val navigator = mockk<Navigator>()
            every { navigator.localParameters } returns navigatorParameters
            val npc = mockk<NPC>()
            every { npc.id } returns 198
            every { npc.uniqueId } returns UUID.fromString("40c163b0-491f-41d4-8135-41b3950da6e4")
            every { npc.minecraftUniqueId } returns UUID.fromString("40c163b0-491f-41d4-8135-41b3950da6e4")
            every { npc.name } returns "Оружейница Ида"
            every { npc.entity } returns entity
            every { npc.isSpawned } returns true
            every { npc.isProtected } returns true
            every { npc.getStoredLocation() } returns oldLocation
            every { npc.navigator } returns navigator
            every { npc.useMinecraftAI() } returns false
            val metadata = mockk<MetadataStore>()
            every { metadata.get<Any>(NPC.Metadata.NAMEPLATE_VISIBLE, true) } returns true
            every { npc.data() } returns metadata
            every { npc.getTraitNullable(any<Class<out Trait>>()) } returns null

            val response = OpsNpcHandlers.summary(npc, target)
            val location = response["location"] as Map<*, *>

            location["x"] shouldBe 379.0
            location["z"] shouldBe 272.0
        }
    }

    "persistent MineSkin application" - {
        "should call the Citizens direct signed-texture API" {
            val npc = mockk<NPC>()
            val entity = mockk<Entity>()
            val trait = mockk<SkinTrait>()
            every { entity.type } returns EntityType.PLAYER
            every { npc.entity } returns entity
            every { npc.getTraitNullable(MobType::class.java) } returns null
            every { npc.getTraitNullable(SkinTrait::class.java) } returns null
            every { npc.getOrAddTrait(SkinTrait::class.java) } returns trait
            every { trait.setSkinPersistent("rc-smith", "signature", "texture") } just runs
            val patch =
                NpcPatch.Set(
                    SkinSpec(
                        name = NpcPatch.Set("rc-smith"),
                        update = NpcPatch.Absent,
                        texture = NpcPatch.Set("texture"),
                        signature = NpcPatch.Set("signature"),
                    ),
                )

            OpsNpcHandlers.applySkin(npc, patch)

            verify(exactly = 1) { trait.setSkinPersistent("rc-smith", "signature", "texture") }
            verify(exactly = 0) { trait.setSkinName(any(), any()) }
        }
    }

    "linear path patch" - {
        "should not recreate the provider or lose points for a cycle-only edit" {
            val npc = mockk<NPC>()
            val waypoints = mockk<Waypoints>()
            val provider = mockk<LinearWaypointProvider>()
            every { npc.getTraitNullable(Waypoints::class.java) } returns waypoints
            every { waypoints.currentProvider } returns provider
            every { waypoints.currentProviderName } returns "linear"
            every { provider.cycleWaypoints() } returns false
            every { provider.cachePaths() } returns true
            every { provider.setCycle(true) } just runs
            every { provider.setCachePaths(true) } just runs

            OpsNpcHandlers.applyPath(
                npc,
                NpcPatch.Set(
                    PathSpec(
                        provider = NpcPatch.Absent,
                        points = NpcPatch.Absent,
                        cycle = NpcPatch.Set(true),
                        cachePaths = NpcPatch.Absent,
                    ),
                ),
                preparedPoints = null,
            )

            verify(exactly = 0) { waypoints.setWaypointProvider(any()) }
            verify(exactly = 0) { npc.removeTrait(Waypoints::class.java) }
            verify { provider.setCycle(true) }
            verify { provider.setCachePaths(true) }
        }
    }

    "command summary" - {
        "should map NpcSpec sequence persistence to Citizens remember-last-used API" {
            val npc = mockk<NPC>()
            val trait = mockk<CommandTrait>()
            every { npc.getOrAddTrait(CommandTrait::class.java) } returns trait
            every { trait.setRememberLastUsed(true) } just runs
            val patch =
                OpsNpcSpec
                    .parse(
                        JsonParser
                            .parseString("""{"commands":{"persistSequence":true}}""")
                            .asJsonObject,
                    ).commands

            OpsNpcHandlers.applyCommands(npc, patch)

            verify(exactly = 1) { trait.setRememberLastUsed(true) }
            verify(exactly = 0) { trait.clear() }
        }

        "should map native persisted command entries for safe read-before-patch" {
            val trait = mockk<CommandTrait>()
            every { trait.executionMode } returns CommandTrait.ExecutionMode.LINEAR
            every { trait.rememberLastUsed() } returns false
            every { trait.isHideErrorMessages } returns true
            val key = MemoryDataKey()
            key.setString("commands.0.command", "cmi warp spawn")
            key.setString("commands.0.hand", "RIGHT")
            key.setBoolean("commands.0.player", true)
            key.setInt("commands.0.cooldown", 3)
            key.setString("commands.0.permissions.0", "ruscrafting.guide")

            val summary = OpsNpcHandlers.commandSummary(trait, key)
            val entries = summary["entries"] as List<*>
            val command = entries.single() as Map<*, *>

            command["command"] shouldBe "cmi warp spawn"
            command["hand"] shouldBe "right"
            command["runAs"] shouldBe "player"
            command["cooldownSeconds"] shouldBe 3
            command["permissions"] shouldBe listOf("ruscrafting.guide")
        }
    }

    "desired-state trait summary" - {
        "should expose per-player look-close persistence" {
            val trait = mockk<LookClose>()
            every { trait.isEnabled } returns true
            every { trait.range } returns 7.5
            every { trait.useRealisticLooking() } returns true
            every { trait.isRandomLook } returns false
            every { trait.disableWhileNavigating() } returns true
            every { trait.targetNPCs() } returns false
            val key = MemoryDataKey()
            key.setBoolean("perplayer", true)

            val summary = OpsNpcHandlers.lookCloseSummary(trait, key)

            summary["perPlayer"] shouldBe true
        }

        "should expose text delay and range persistence" {
            val trait = mockk<Text>()
            every { trait.texts } returns listOf("Привет")
            every { trait.shouldTalkClose() } returns true
            every { trait.isRandomTalker } returns false
            every { trait.useRealisticLooking() } returns true
            every { trait.useSpeechBubbles() } returns false
            val key = MemoryDataKey()
            key.setInt("delay", 80)
            key.setDouble("range", 4.5)

            val summary = OpsNpcHandlers.textSummary(trait, key)

            summary["delayTicks"] shouldBe 80
            summary["range"] shouldBe 4.5
        }
    }
})
