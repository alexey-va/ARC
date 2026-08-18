package ru.arc.ops

import com.google.gson.JsonObject
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.persistence.PersistenceLoader
import net.citizensnpcs.api.trait.trait.Equipment
import net.citizensnpcs.api.trait.trait.MobType
import net.citizensnpcs.api.util.MemoryDataKey
import net.citizensnpcs.trait.CommandTrait
import net.citizensnpcs.trait.CurrentLocation
import net.citizensnpcs.trait.HologramTrait
import net.citizensnpcs.trait.LookClose
import net.citizensnpcs.trait.SkinTrait
import net.citizensnpcs.trait.text.Text
import net.citizensnpcs.trait.waypoint.LinearWaypointProvider
import net.citizensnpcs.trait.waypoint.Waypoint
import net.citizensnpcs.trait.waypoint.Waypoints
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import java.security.MessageDigest
import java.time.Duration
import kotlin.math.floor

/**
 * Native Citizens API operations for content management.
 *
 * All Bukkit and Citizens calls are executed on the main thread. Preview never
 * loads or generates a chunk; a content manager must inspect an already loaded
 * area before creating or moving an NPC there.
 */
object OpsNpcHandlers {
    private const val MAX_LIST_LIMIT = 500

    fun list(
        id: Int? = null,
        worldName: String? = null,
        limit: Int = 200,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val registry = registry()
            if (id != null) {
                val npc = registry.getById(id) ?: throw NoSuchElementException("NPC not found: $id")
                return@call mapOf(
                    "provider" to "Citizens",
                    "npc" to summary(npc),
                )
            }

            val normalizedWorld = worldName?.trim()?.takeIf { it.isNotEmpty() }
            val npcs =
                registry
                    .asSequence()
                    .filter { npc ->
                        normalizedWorld == null || npc.getStoredLocation()?.world?.name == normalizedWorld
                    }.take(limit.coerceIn(1, MAX_LIST_LIMIT))
                    .map(::summary)
                    .toList()
            mapOf(
                "provider" to "Citizens",
                "count" to npcs.size,
                "npcs" to npcs,
                "blueMapMarkers" to BlueMapNpcMarkers.available(),
            )
        }

    fun preview(body: JsonObject): Map<String, Any?> =
        OpsBukkitSync.call {
            val placement = placement(body)
            val world = placement.world
            val blockX = floor(placement.location.x).toInt()
            val blockY = floor(placement.location.y).toInt()
            val blockZ = floor(placement.location.z).toInt()
            val chunkLoaded = world.isChunkLoaded(blockX shr 4, blockZ shr 4)

            if (!chunkLoaded) {
                return@call mapOf(
                    "valid" to false,
                    "world" to world.name,
                    "x" to placement.location.x,
                    "y" to placement.location.y,
                    "z" to placement.location.z,
                    "chunkLoaded" to false,
                    "issues" to listOf("chunk_not_loaded"),
                )
            }

            val feet = world.getBlockAt(blockX, blockY, blockZ)
            val head = world.getBlockAt(blockX, blockY + 1, blockZ)
            val ground = world.getBlockAt(blockX, blockY - 1, blockZ)
            val issues = mutableListOf<String>()
            if (!world.worldBorder.isInside(placement.location)) issues += "outside_world_border"
            if (!feet.isPassable) issues += "feet_blocked"
            if (!head.isPassable) issues += "head_blocked"
            if (ground.isPassable || ground.type.isAir) issues += "no_solid_ground"

            val nearest =
                registry()
                    .asSequence()
                    .mapNotNull { npc ->
                        npc.getStoredLocation()
                            ?.takeIf { it.world?.uid == world.uid }
                            ?.let { location -> npc to location.distance(placement.location) }
                    }.minByOrNull { it.second }

            mapOf(
                "valid" to issues.isEmpty(),
                "world" to world.name,
                "x" to placement.location.x,
                "y" to placement.location.y,
                "z" to placement.location.z,
                "yaw" to placement.location.yaw,
                "pitch" to placement.location.pitch,
                "chunkLoaded" to true,
                "surfaceY" to surfaceY(world, blockX, blockZ),
                "ground" to ground.type.key.asString(),
                "feet" to feet.type.key.asString(),
                "head" to head.type.key.asString(),
                "biome" to world.getBiome(blockX, blockY, blockZ).key.asString(),
                "light" to feet.lightLevel,
                "nearestNpc" to
                    nearest?.let { (npc, distance) ->
                        mapOf(
                            "id" to npc.id,
                            "name" to npc.name,
                            "distance" to "%.2f".format(distance),
                        )
                    },
                "issues" to issues,
            )
        }

    fun upsert(
        id: Int?,
        body: JsonObject,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val spec = OpsNpcSpec.parse(body)
            if (id == null) spec.requireCreateFields()
            val registry = registry()
            val created = id == null
            val existing =
                if (created) {
                    null
                } else {
                    registry.getById(id) ?: throw NoSuchElementException("NPC not found: $id")
                }
            val prepared = prepare(spec, existing)
            val npc =
                existing ?: run {
                    val name = (spec.name as NpcPatch.Set).value
                    val type = (spec.type as? NpcPatch.Set)?.value ?: EntityType.PLAYER
                    registry.createNPC(type, name)
                }
            try {
                validateAgainst(npc, spec)
                applySpec(npc, spec, prepared, created)
                registry.saveToStore()
            } catch (failure: Throwable) {
                if (created) npc.destroy()
                throw failure
            }
            BlueMapNpcMarkers.refresh()
            mapOf(
                "operation" to "upsert",
                "created" to created,
                "changedFields" to spec.changedFields,
                // Citizens may complete entity teleports asynchronously.
                "npc" to summary(npc, prepared.location),
            )
        }

    /**
     * Citizens updates CurrentLocation from the spawned entity on a later
     * trait tick. Keep the persistent trait in sync before saveToStore(), or a
     * move can revert after the chunk unloads.
     */
    internal fun teleportAndStore(
        npc: NPC,
        location: Location,
    ) {
        npc.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
        npc.getOrAddTrait(CurrentLocation::class.java).setLocation(location)
    }

    fun delete(id: Int): Map<String, Any?> =
        OpsBukkitSync.call {
            val registry = registry()
            val npc = registry.getById(id) ?: throw NoSuchElementException("NPC not found: $id")
            val before = summary(npc)
            npc.destroy()
            registry.saveToStore()
            BlueMapNpcMarkers.refresh()
            mapOf(
                "operation" to "delete",
                "npc" to before,
            )
        }

    internal fun summariesByWorld(): Map<World, List<NPC>> =
        registry()
            .asSequence()
            .mapNotNull { npc -> persistentLocation(npc)?.world?.let { it to npc } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

    private fun registry() =
        runCatching {
            CitizensAPI.getNPCRegistry()
                ?: throw IllegalStateException("Citizens NPC registry unavailable")
        }
            .getOrElse { throw IllegalStateException("Citizens API unavailable", it) }

    internal fun summary(
        npc: NPC,
        locationOverride: Location? = null,
    ): Map<String, Any?> {
        val location = locationOverride ?: persistentLocation(npc)
        val type = npc.getTraitNullable(MobType::class.java)?.type ?: npc.entity?.type
        val spec = linkedMapOf<String, Any?>(
            "name" to npc.name,
            "type" to type?.name,
            "protected" to npc.isProtected,
            "location" to locationMap(location),
            "useMinecraftAi" to npc.useMinecraftAI(),
            "navigation" to navigationSummary(npc),
        )
        npc.getTraitNullable(SkinTrait::class.java)?.let { trait ->
            val texture = trait.texture
            spec["skin"] =
                mapOf(
                    "name" to trait.skinName,
                    "update" to trait.shouldUpdateSkins(),
                    "persistent" to (texture != null && trait.signature != null),
                    "textureSha256" to texture?.sha256(),
                )
        }
        npc.getTraitNullable(LookClose::class.java)?.let { spec["lookClose"] = lookCloseSummary(it) }
        npc.getTraitNullable(CommandTrait::class.java)?.let { spec["commands"] = commandSummary(it) }
        npc.getTraitNullable(HologramTrait::class.java)?.let { spec["hologram"] = hologramSummary(it) }
        npc.getTraitNullable(Equipment::class.java)?.let { spec["equipment"] = equipmentSummary(it) }
        npc.getTraitNullable(Waypoints::class.java)?.let { spec["path"] = pathSummary(it) }
        npc.getTraitNullable(Text::class.java)?.let { spec["text"] = textSummary(it) }
        return mapOf(
            "id" to npc.id,
            "uuid" to npc.uniqueId.toString(),
            "minecraftUuid" to npc.minecraftUniqueId.toString(),
            "name" to npc.name,
            "entityType" to type?.name,
            "spawned" to npc.isSpawned,
            "protected" to npc.isProtected,
            "location" to locationMap(location),
            "spec" to spec,
        )
    }

    private data class PreparedNpcSpec(
        val location: Location?,
        val equipment: Map<Equipment.EquipmentSlot, ItemStack?>?,
        val pathPoints: List<Location>?,
    )

    private fun prepare(
        spec: OpsNpcSpec,
        existing: NPC?,
    ): PreparedNpcSpec {
        val location =
            (spec.location as? NpcPatch.Set)?.value?.let {
                resolveLocation(it, existing?.let(::persistentLocation), allowSurfaceY = true)
                    .also(::requireLoadedSafePlacement)
            }
        val equipment =
            (spec.equipment as? NpcPatch.Set)?.value?.slots?.mapValues { (_, item) ->
                item?.let(OpsItemSpec::build)
            }
        val pathPoints =
            (spec.path as? NpcPatch.Set)
                ?.value
                ?.points
                ?.let { it as? NpcPatch.Set }
                ?.value
                ?.map(::resolvePathPoint)
        return PreparedNpcSpec(location, equipment, pathPoints)
    }

    private fun validateAgainst(
        npc: NPC,
        spec: OpsNpcSpec,
    ) {
        (spec.skin as? NpcPatch.Set)?.let { skin ->
            val type =
                (spec.type as? NpcPatch.Set)?.value
                    ?: npc.getTraitNullable(MobType::class.java)?.type
                    ?: npc.entity?.type
            require(type == EntityType.PLAYER) { "skin is only supported for PLAYER NPCs" }
            if (skin.value.name !is NpcPatch.Set) {
                require(npc.getTraitNullable(SkinTrait::class.java)?.skinName != null) {
                    "skin.name required when adding a skin"
                }
            }
        }
        (spec.path as? NpcPatch.Set)?.let { path ->
            val currentProvider = npc.getTraitNullable(Waypoints::class.java)?.currentProviderName
            val requestedProvider = (path.value.provider as? NpcPatch.Set)?.value
            require(requestedProvider == "linear" || currentProvider == null || currentProvider == "linear") {
                "NpcSpec can only patch the linear path provider; set provider='linear' to replace it"
            }
        }
    }

    private fun applySpec(
        npc: NPC,
        spec: OpsNpcSpec,
        prepared: PreparedNpcSpec,
        created: Boolean,
    ) {
        (spec.name as? NpcPatch.Set)?.let { npc.name = it.value }
        (spec.type as? NpcPatch.Set)?.let { npc.setBukkitEntityType(it.value) }
        when (val value = spec.protectedState) {
            is NpcPatch.Set -> npc.isProtected = value.value
            NpcPatch.Absent -> if (created) npc.isProtected = true
            NpcPatch.Clear -> error("unreachable")
        }
        (spec.useMinecraftAi as? NpcPatch.Set)?.let { npc.setUseMinecraftAI(it.value) }
        prepared.location?.let { location ->
            if (created) {
                if (!npc.spawn(location)) {
                    throw IllegalStateException("Citizens failed to spawn NPC at requested location")
                }
                npc.getOrAddTrait(CurrentLocation::class.java).setLocation(location)
            } else {
                teleportAndStore(npc, location)
            }
        }
        applySkin(npc, spec.skin)
        applyLookClose(npc, spec.lookClose)
        applyCommands(npc, spec.commands)
        applyHologram(npc, spec.hologram)
        applyEquipment(npc, spec.equipment, prepared.equipment)
        applyPath(npc, spec.path, prepared.pathPoints)
        applyText(npc, spec.text)
        applyNavigation(npc, spec.navigation)
    }

    internal fun applySkin(
        npc: NPC,
        patch: NpcPatch<SkinSpec>,
    ) {
        when (patch) {
            NpcPatch.Absent -> Unit
            NpcPatch.Clear -> npc.removeTrait(SkinTrait::class.java)
            is NpcPatch.Set -> {
                val type = npc.getTraitNullable(MobType::class.java)?.type ?: npc.entity?.type
                require(type == EntityType.PLAYER) { "skin is only supported for PLAYER NPCs" }
                val existing = npc.getTraitNullable(SkinTrait::class.java)
                val name =
                    (patch.value.name as? NpcPatch.Set)?.value
                        ?: existing?.skinName
                        ?: throw IllegalArgumentException("skin.name required when adding a skin")
                val update =
                    (patch.value.update as? NpcPatch.Set)?.value
                        ?: existing?.shouldUpdateSkins()
                        ?: false
                val texture = (patch.value.texture as? NpcPatch.Set)?.value
                val signature = (patch.value.signature as? NpcPatch.Set)?.value
                val trait = npc.getOrAddTrait(SkinTrait::class.java)
                if (texture != null && signature != null) {
                    trait.setSkinPersistent(name, signature, texture)
                } else {
                    trait.setSkinName(name, update)
                }
            }
        }
    }

    private fun applyLookClose(
        npc: NPC,
        patch: NpcPatch<LookCloseSpec>,
    ) {
        when (patch) {
            NpcPatch.Absent -> Unit
            NpcPatch.Clear -> npc.removeTrait(LookClose::class.java)
            is NpcPatch.Set -> {
                val trait = npc.getOrAddTrait(LookClose::class.java)
                (patch.value.enabled as? NpcPatch.Set)?.let { trait.lookClose(it.value) }
                (patch.value.range as? NpcPatch.Set)?.let { trait.setRange(it.value) }
                (patch.value.realisticLooking as? NpcPatch.Set)?.let { trait.setRealisticLooking(it.value) }
                (patch.value.randomLook as? NpcPatch.Set)?.let { trait.setRandomLook(it.value) }
                (patch.value.disableWhileNavigating as? NpcPatch.Set)?.let {
                    trait.setDisableWhileNavigating(it.value)
                }
                (patch.value.targetNpcs as? NpcPatch.Set)?.let { trait.setTargetNPCs(it.value) }
                (patch.value.perPlayer as? NpcPatch.Set)?.let { trait.setPerPlayer(it.value) }
            }
        }
    }

    internal fun applyCommands(
        npc: NPC,
        patch: NpcPatch<CommandsSpec>,
    ) {
        when (patch) {
            NpcPatch.Absent -> Unit
            NpcPatch.Clear -> npc.removeTrait(CommandTrait::class.java)
            is NpcPatch.Set -> {
                val trait = npc.getOrAddTrait(CommandTrait::class.java)
                (patch.value.entries as? NpcPatch.Set)?.let { entries ->
                    trait.clear()
                    entries.value.forEach { command ->
                        val builder = CommandTrait.NPCCommandBuilder(command.command, command.hand)
                        when (command.runAs) {
                            NpcCommandRunAs.SERVER -> Unit
                            NpcCommandRunAs.PLAYER -> builder.player(true)
                            NpcCommandRunAs.NPC -> builder.npc(true)
                        }
                        command.cooldownSeconds?.let { builder.cooldown(Duration.ofSeconds(it)) }
                        command.globalCooldownSeconds?.let { builder.globalCooldown(Duration.ofSeconds(it)) }
                        command.delayTicks?.let { builder.delay(Duration.ofMillis(it * 50L)) }
                        command.maxUses?.let(builder::n)
                        if (command.permissions.isNotEmpty()) builder.addPerms(command.permissions)
                        trait.addCommand(builder)
                    }
                }
                (patch.value.mode as? NpcPatch.Set)?.let { trait.executionMode = it.value }
                (patch.value.persistSequence as? NpcPatch.Set)?.let { trait.setRememberLastUsed(it.value) }
                (patch.value.hideErrors as? NpcPatch.Set)?.let { trait.setHideErrorMessages(it.value) }
            }
        }
    }

    private fun applyHologram(
        npc: NPC,
        patch: NpcPatch<HologramSpec>,
    ) {
        when (patch) {
            NpcPatch.Absent -> Unit
            NpcPatch.Clear -> npc.removeTrait(HologramTrait::class.java)
            is NpcPatch.Set -> {
                val trait = npc.getOrAddTrait(HologramTrait::class.java)
                (patch.value.lines as? NpcPatch.Set)?.let {
                    trait.clear()
                    it.value.forEach(trait::addLine)
                }
                (patch.value.lineHeight as? NpcPatch.Set)?.let { trait.lineHeight = it.value }
                (patch.value.viewRange as? NpcPatch.Set)?.let { trait.viewRange = it.value }
            }
        }
    }

    private fun applyEquipment(
        npc: NPC,
        patch: NpcPatch<EquipmentSpec>,
        prepared: Map<Equipment.EquipmentSlot, ItemStack?>?,
    ) {
        when (patch) {
            NpcPatch.Absent -> Unit
            NpcPatch.Clear -> npc.removeTrait(Equipment::class.java)
            is NpcPatch.Set -> {
                val trait = npc.getOrAddTrait(Equipment::class.java)
                requireNotNull(prepared) { "Prepared equipment missing" }
                    .forEach { (slot, item) -> trait.set(slot, item) }
            }
        }
    }

    internal fun applyPath(
        npc: NPC,
        patch: NpcPatch<PathSpec>,
        preparedPoints: List<Location>?,
    ) {
        when (patch) {
            NpcPatch.Absent -> Unit
            NpcPatch.Clear -> npc.removeTrait(Waypoints::class.java)
            is NpcPatch.Set -> {
                val existing = npc.getTraitNullable(Waypoints::class.java)
                val existingLinear = existing?.currentProvider as? LinearWaypointProvider
                val providerName =
                    (patch.value.provider as? NpcPatch.Set)?.value
                        ?: existing?.currentProviderName
                        ?: "linear"
                require(providerName == "linear") {
                    "NpcSpec can only patch the linear path provider; set provider='linear' to replace it"
                }
                val desiredCycle =
                    (patch.value.cycle as? NpcPatch.Set)?.value
                        ?: existingLinear?.cycleWaypoints()
                        ?: false
                val desiredCache =
                    (patch.value.cachePaths as? NpcPatch.Set)?.value
                        ?: existingLinear?.cachePaths()
                        ?: true
                val pointsPatch = patch.value.points
                val provider =
                    if (pointsPatch is NpcPatch.Set) {
                        npc.removeTrait(Waypoints::class.java)
                        val trait = npc.getOrAddTrait(Waypoints::class.java)
                        require(trait.setWaypointProvider("linear")) {
                            "Citizens linear waypoint provider unavailable"
                        }
                        trait.currentProvider as? LinearWaypointProvider
                            ?: throw IllegalStateException(
                                "Citizens did not activate the linear waypoint provider",
                            )
                    } else if (existingLinear != null) {
                        existingLinear
                    } else {
                        val trait = npc.getOrAddTrait(Waypoints::class.java)
                        require(trait.setWaypointProvider("linear")) {
                            "Citizens linear waypoint provider unavailable"
                        }
                        trait.currentProvider as? LinearWaypointProvider
                            ?: throw IllegalStateException(
                                "Citizens did not activate the linear waypoint provider",
                            )
                    }
                provider.setCycle(desiredCycle)
                provider.setCachePaths(desiredCache)
                if (pointsPatch is NpcPatch.Set) {
                    requireNotNull(preparedPoints) { "Prepared path points missing" }
                        .forEach { provider.addWaypoint(Waypoint(it)) }
                }
            }
        }
    }

    private fun applyText(
        npc: NPC,
        patch: NpcPatch<TextSpec>,
    ) {
        when (patch) {
            NpcPatch.Absent -> Unit
            NpcPatch.Clear -> npc.removeTrait(Text::class.java)
            is NpcPatch.Set -> {
                val trait = npc.getOrAddTrait(Text::class.java)
                (patch.value.lines as? NpcPatch.Set)?.let { lines ->
                    trait.texts.indices.reversed().forEach(trait::remove)
                    lines.value.forEach(trait::add)
                }
                (patch.value.talkClose as? NpcPatch.Set)?.let {
                    if (trait.shouldTalkClose() != it.value) trait.toggleTalkClose()
                }
                (patch.value.randomTalker as? NpcPatch.Set)?.let {
                    if (trait.isRandomTalker != it.value) trait.toggleRandomTalker()
                }
                (patch.value.realisticLooking as? NpcPatch.Set)?.let {
                    if (trait.useRealisticLooking() != it.value) trait.toggleRealisticLooking()
                }
                (patch.value.speechBubbles as? NpcPatch.Set)?.let {
                    if (trait.useSpeechBubbles() != it.value) trait.toggleSpeechBubbles()
                }
                (patch.value.delayTicks as? NpcPatch.Set)?.let { trait.setDelay(it.value) }
                (patch.value.range as? NpcPatch.Set)?.let { trait.setRange(it.value) }
            }
        }
    }

    private fun applyNavigation(
        npc: NPC,
        patch: NpcPatch<NavigationSpec>,
    ) {
        if (patch !is NpcPatch.Set) return
        val parameters = npc.navigator.localParameters
        (patch.value.speedModifier as? NpcPatch.Set)?.let { parameters.speedModifier(it.value.toFloat()) }
        (patch.value.range as? NpcPatch.Set)?.let { parameters.range(it.value.toFloat()) }
        (patch.value.avoidWater as? NpcPatch.Set)?.let { parameters.avoidWater(it.value) }
        (patch.value.distanceMargin as? NpcPatch.Set)?.let { parameters.distanceMargin(it.value) }
        (patch.value.pathDistanceMargin as? NpcPatch.Set)?.let { parameters.pathDistanceMargin(it.value) }
    }

    private fun persistentLocation(npc: NPC): Location? =
        npc.getTraitNullable(CurrentLocation::class.java)?.location ?: npc.getStoredLocation()

    private fun locationMap(location: Location?): Map<String, Any?>? =
        location?.let {
            linkedMapOf(
                "world" to it.world?.name,
                "x" to it.x,
                "y" to it.y,
                "z" to it.z,
                "yaw" to it.yaw,
                "pitch" to it.pitch,
            )
        }

    private fun navigationSummary(npc: NPC): Map<String, Any?> {
        val parameters = npc.navigator.localParameters
        return linkedMapOf(
            "speedModifier" to parameters.speedModifier(),
            "range" to parameters.range(),
            "avoidWater" to parameters.avoidWater(),
            "distanceMargin" to parameters.distanceMargin(),
            "pathDistanceMargin" to parameters.pathDistanceMargin(),
        )
    }

    private fun lookCloseSummary(trait: LookClose): Map<String, Any?> =
        linkedMapOf(
            "enabled" to trait.isEnabled,
            "range" to trait.range,
            "realisticLooking" to trait.useRealisticLooking(),
            "randomLook" to trait.isRandomLook,
            "disableWhileNavigating" to trait.disableWhileNavigating(),
            "targetNpcs" to trait.targetNPCs(),
        )

    /**
     * CommandTrait intentionally exposes no command collection. Its public
     * save contract is the stable native API for inspecting entries without
     * touching Citizens saves.yml.
     */
    internal fun commandSummary(trait: CommandTrait): Map<String, Any?> {
        val key = MemoryDataKey()
        PersistenceLoader.save(trait, key)
        trait.save(key)
        return commandSummary(trait, key)
    }

    internal fun commandSummary(
        trait: CommandTrait,
        key: MemoryDataKey,
    ): Map<String, Any?> {
        val entries =
            key
                .getRelative("commands")
                .getIntegerSubKeys()
                .map { command ->
                    linkedMapOf<String, Any?>(
                        "command" to command.getString("command"),
                        "hand" to command.getString("hand", CommandTrait.Hand.BOTH.name).lowercase(),
                        "runAs" to
                            when {
                                command.getBoolean("player", false) -> "player"
                                command.getBoolean("npc", false) -> "npc"
                                command.getBoolean("op", false) -> "op"
                                else -> "server"
                            },
                    ).apply {
                        command.getInt("cooldown", 0).takeIf { it > 0 }?.let {
                            this["cooldownSeconds"] = it
                        }
                        command.getInt("globalcooldown", 0).takeIf { it > 0 }?.let {
                            this["globalCooldownSeconds"] = it
                        }
                        command.getInt("delay", 0).takeIf { it > 0 }?.let {
                            this["delayTicks"] = it
                        }
                        command.getInt("n", 0).takeIf { it > 0 }?.let {
                            this["maxUses"] = it
                        }
                        command
                            .getRelative("permissions")
                            .getIntegerSubKeys()
                            .map { it.getString("") }
                            .filter(String::isNotBlank)
                            .toList()
                            .takeIf { it.isNotEmpty() }
                            ?.let { this["permissions"] = it }
                    }
                }.toList()
        return linkedMapOf(
            "entries" to entries,
            "mode" to trait.executionMode.name.lowercase(),
            "persistSequence" to trait.rememberLastUsed(),
            "hideErrors" to trait.isHideErrorMessages,
        )
    }

    private fun hologramSummary(trait: HologramTrait): Map<String, Any?> =
        linkedMapOf(
            "lines" to trait.lines,
            "lineHeight" to trait.lineHeight,
            "viewRange" to trait.viewRange,
        )

    private fun equipmentSummary(trait: Equipment): Map<String, Any?> =
        Equipment.EquipmentSlot.entries.mapNotNull { slot ->
            trait
                .get(slot)
                ?.takeUnless { it.type.isAir }
                ?.let { slot.name.lowercase() to OpsItemSpec.toMap(it) }
        }.toMap()

    private fun pathSummary(trait: Waypoints): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>("provider" to trait.currentProviderName)
        (trait.currentProvider as? LinearWaypointProvider)?.let { provider ->
            result["points"] = provider.waypoints().map { locationMap(it.location) }
            result["cycle"] = provider.cycleWaypoints()
            result["cachePaths"] = provider.cachePaths()
        }
        return result
    }

    private fun textSummary(trait: Text): Map<String, Any?> =
        linkedMapOf(
            "lines" to trait.texts,
            "talkClose" to trait.shouldTalkClose(),
            "randomTalker" to trait.isRandomTalker,
            "realisticLooking" to trait.useRealisticLooking(),
            "speechBubbles" to trait.useSpeechBubbles(),
        )

    private fun resolveLocation(
        spec: LocationSpec,
        existing: Location?,
        allowSurfaceY: Boolean,
    ): Location {
        val worldName =
            (spec.world as? NpcPatch.Set)?.value
                ?: existing?.world?.name
                ?: throw IllegalArgumentException("location.world required")
        val world =
            Bukkit.getWorld(worldName)
                ?: throw IllegalArgumentException("World is not loaded: $worldName")
        val x =
            (spec.x as? NpcPatch.Set)?.value
                ?: existing?.x
                ?: throw IllegalArgumentException("location.x required")
        val z =
            (spec.z as? NpcPatch.Set)?.value
                ?: existing?.z
                ?: throw IllegalArgumentException("location.z required")
        val blockX = floor(x).toInt()
        val blockZ = floor(z).toInt()
        val chunkLoaded = world.isChunkLoaded(blockX shr 4, blockZ shr 4)
        val y =
            (spec.y as? NpcPatch.Set)?.value
                ?: existing?.y
                ?: when {
                    allowSurfaceY && chunkLoaded -> surfaceY(world, blockX, blockZ).toDouble()
                    allowSurfaceY -> world.minHeight.toDouble()
                    else -> throw IllegalArgumentException("location.y required")
                }
        require(y >= world.minHeight && y < world.maxHeight) {
            "location.y must be within world height ${world.minHeight}..${world.maxHeight - 1}"
        }
        val yaw = (spec.yaw as? NpcPatch.Set)?.value ?: existing?.yaw ?: 0f
        val pitch = (spec.pitch as? NpcPatch.Set)?.value ?: existing?.pitch ?: 0f
        return Location(world, x, y, z, yaw, pitch)
    }

    private fun resolvePathPoint(spec: PathPointSpec): Location {
        val world =
            Bukkit.getWorld(spec.world)
                ?: throw IllegalArgumentException("World is not loaded: ${spec.world}")
        require(spec.y >= world.minHeight && spec.y < world.maxHeight) {
            "path point y must be within world height ${world.minHeight}..${world.maxHeight - 1}"
        }
        return Location(world, spec.x, spec.y, spec.z, spec.yaw, spec.pitch).also {
            require(world.worldBorder.isInside(it)) { "Path point is outside world border" }
        }
    }

    private data class Placement(
        val world: World,
        val location: Location,
    )

    private fun placement(body: JsonObject): Placement {
        val worldName = optionalString(body, "world")?.trim().orEmpty()
        require(worldName.isNotEmpty()) { "world required" }
        val world = Bukkit.getWorld(worldName) ?: throw IllegalArgumentException("World is not loaded: $worldName")
        val x = finiteDouble(body, "x")
        val z = finiteDouble(body, "z")
        val blockX = floor(x).toInt()
        val blockZ = floor(z).toInt()
        val chunkLoaded = world.isChunkLoaded(blockX shr 4, blockZ shr 4)
        val y =
            optionalFiniteDouble(body, "y") ?: if (chunkLoaded) {
                surfaceY(world, blockX, blockZ).toDouble()
            } else {
                world.minHeight.toDouble()
            }
        require(y >= world.minHeight && y < world.maxHeight) {
            "y must be within world height ${world.minHeight}..${world.maxHeight - 1}"
        }
        val yaw = optionalFiniteDouble(body, "yaw")?.toFloat() ?: 0f
        val pitch = optionalFiniteDouble(body, "pitch")?.toFloat() ?: 0f
        require(yaw.isFinite() && pitch.isFinite()) { "yaw and pitch must be finite" }
        return Placement(world, Location(world, x, y, z, yaw, pitch))
    }

    private fun requireLoadedSafePlacement(location: Location) {
        val world = location.world ?: throw IllegalArgumentException("world required")
        val blockX = floor(location.x).toInt()
        val blockY = floor(location.y).toInt()
        val blockZ = floor(location.z).toInt()
        require(world.isChunkLoaded(blockX shr 4, blockZ shr 4)) { "Target chunk is not loaded" }
        require(world.worldBorder.isInside(location)) { "Target is outside world border" }
        require(world.getBlockAt(blockX, blockY, blockZ).isPassable) { "NPC feet position is blocked" }
        require(world.getBlockAt(blockX, blockY + 1, blockZ).isPassable) { "NPC head position is blocked" }
        val ground = world.getBlockAt(blockX, blockY - 1, blockZ)
        require(!ground.isPassable && !ground.type.isAir) { "NPC has no solid ground" }
    }

    private fun surfaceY(
        world: World,
        x: Int,
        z: Int,
    ): Int = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1

    private fun finiteDouble(
        body: JsonObject,
        key: String,
    ): Double = optionalFiniteDouble(body, key) ?: throw IllegalArgumentException("$key required")

    private fun optionalFiniteDouble(
        body: JsonObject,
        key: String,
    ): Double? {
        val value = body.get(key) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$key must be a number" }
        return value.asDouble.also { require(it.isFinite()) { "$key must be finite" } }
    }

    private fun optionalString(
        body: JsonObject,
        key: String,
    ): String? {
        val value = body.get(key) ?: return null
        require(!value.isJsonNull && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "$key must be a string"
        }
        return value.asString
    }

}

private fun String.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
