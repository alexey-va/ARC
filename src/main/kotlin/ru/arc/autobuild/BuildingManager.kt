package ru.arc.autobuild

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.autobuild.gui.BuildingGui
import ru.arc.autobuild.gui.ConfirmGui
import ru.arc.hooks.HookRegistry
import ru.arc.util.CooldownManager
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Central manager for building schematics and construction sites.
 *
 * Handles:
 * - Loading building schematics from disk
 * - Managing pending (outline/confirmation) and active (building) construction sites
 * - Processing player interactions with construction NPCs
 * - Cleanup of stale construction sites
 */
object BuildingManager {

    private val buildings = ConcurrentHashMap<String, Building>()
    private val pendingSites = ConcurrentHashMap<UUID, ConstructionSite>()
    private val activeSites = ConcurrentHashMap<UUID, MutableList<ConstructionSite>>()
    private var cleanupTask: BukkitTask? = null

    // ==================== Initialization ====================

    @JvmStatic
    fun init() {
        loadBuildings()
        startCleanupTask()
        cleanupOldNpcs()
    }

    private fun loadBuildings() {
        val schematicsPath = Paths.get(ARC.plugin.dataFolder.toString(), "schematics")
        Files.createDirectories(schematicsPath)

        try {
            Files.walk(schematicsPath, 3).use { stream ->
                stream.filter { !it.isDirectory() }
                    .map { Building(it.name) }
                    .forEach { buildings[it.fileName] = it }
            }
        } catch (e: Exception) {
            error("Error loading buildings", e)
        }
    }

    private fun cleanupOldNpcs() {
        val npcNames = BuildConfig.npcSkins.keys
        HookRegistry.citizensHook?.deleteWithNames(npcNames)
    }

    // ==================== Building Registry ====================

    @JvmStatic
    fun addBuilding(building: Building) {
        buildings[building.fileName] = building
    }

    @JvmStatic
    fun getBuilding(fileName: String): Building? = buildings[fileName]

    @JvmStatic
    fun getBuildings(): Collection<Building> = buildings.values

    // ==================== Construction Site Access ====================

    @JvmStatic
    fun getPendingConstruction(playerId: UUID): ConstructionSite? = pendingSites[playerId]

    @JvmStatic
    fun findByNpcId(npcId: Int): ConstructionSite? =
        pendingSites.values.find { it.npcId == npcId }
            ?: activeSites.values.flatten().find { it.npcId == npcId }

    @JvmStatic
    fun removeConstruction(site: ConstructionSite) {
        val playerId = site.player.uniqueId
        activeSites[playerId]?.let { sites ->
            sites.remove(site)
            if (sites.isEmpty()) activeSites.remove(playerId)
        }
        pendingSites.remove(playerId)
    }

    private fun moveToActive(site: ConstructionSite) {
        val playerId = site.player.uniqueId
        activeSites.computeIfAbsent(playerId) { mutableListOf() }.add(site)
        pendingSites.remove(playerId)
    }

    // ==================== Player Interaction ====================

    /**
     * Handles player clicking on a block with a building item.
     * Creates outline -> confirmation -> building flow.
     */
    @JvmStatic
    fun processPlayerClick(player: Player, rawLocation: Location, buildingId: String, rot: String?, yOff: String?) {
        if (BuildConfig.isDisabled && !player.hasPermission("arc.admin")) {
            player.sendMessage(BuildConfig.Messages.disabled())
            return
        }

        // Adjust for grass blocks (build on the block below)
        val location = if (rawLocation.block.type in listOf(Material.SHORT_GRASS, Material.TALL_GRASS)) {
            rawLocation.clone().add(0.0, -1.0, 0.0)
        } else rawLocation

        val yOffset = yOff?.toDoubleOrNull()?.toInt() ?: 0
        val subRotation = rot?.toDoubleOrNull()?.toInt() ?: 0

        val building = getBuilding(buildingId) ?: run {
            error("Building with id {} not found!", buildingId)
            player.sendMessage(BuildConfig.Messages.notFound())
            return
        }

        if (CooldownManager.cooldown(player.uniqueId, "clicked_npc") > 0L) return

        val existingSite = getPendingConstruction(player.uniqueId)

        when {
            // No existing site - start new outline
            existingSite == null -> createConstruction(player, location, building, subRotation, yOffset)

            // Same location clicked while showing outline - advance to confirmation
            existingSite.state == ConstructionState.DisplayingOutline &&
                existingSite.same(player, location, building) -> existingSite.startConfirmation()

            // Already building
            existingSite.state == ConstructionState.Building -> {
                player.sendMessage(BuildConfig.Messages.alreadyBuilding())
            }

            // Different location or building - cancel old and start new
            else -> {
                existingSite.cancel()
                createConstruction(player, location, building, subRotation, yOffset)
            }
        }
    }

    /**
     * Handles player clicking on a construction NPC.
     */
    @JvmStatic
    fun processNpcClick(clicker: Player, npcId: Int) {
        val site = findByNpcId(npcId) ?: return

        if (site.player.uniqueId != clicker.uniqueId && !clicker.hasPermission("arc.admin")) {
            clicker.sendMessage(BuildConfig.Messages.notYourNpc())
            return
        }

        CooldownManager.addCooldown(clicker.uniqueId, "clicked_npc", 20L)

        when (site.state) {
            ConstructionState.Confirmation -> ConfirmGui(clicker, site).show(clicker)
            ConstructionState.Building -> BuildingGui(clicker, site).show(clicker)
            else -> {}
        }
    }

    // ==================== Construction Flow ====================

    @JvmStatic
    fun createConstruction(player: Player, center: Location, building: Building, subRotation: Int, yOffset: Int) {
        val cooldown = CooldownManager.cooldown(player.uniqueId, "building_cooldown")
        if (cooldown > 0 && !player.hasPermission("arc.admin")) {
            player.sendMessage(BuildConfig.Messages.cooldown(cooldown))
            return
        }

        val world = center.world ?: run {
            error("Cannot create construction: world is null for location {}", center)
            return
        }

        val rotation = rotationFromYaw(player.yaw)
        val site = ConstructionSite(building, center, player, rotation, world, subRotation, yOffset)

        if (!site.canBuild() && !player.hasPermission("arc.admin")) {
            player.sendMessage(BuildConfig.Messages.cantBuild())
            return
        }

        pendingSites[player.uniqueId] = site
        site.startDisplayingBorder()
        player.sendMessage(BuildConfig.Messages.startOutline())
    }

    @JvmStatic
    fun startConstruction(site: ConstructionSite) {
        site.startBuild()
        moveToActive(site)
    }

    @JvmStatic
    fun cancelConstruction(site: ConstructionSite) {
        site.cancel()
    }

    @JvmStatic
    fun confirmConstruction(player: Player, confirm: Boolean) {
        val site = getPendingConstruction(player.uniqueId) ?: run {
            info("Player {} tried to confirm construction but no site found", player.name)
            return
        }

        if (site.state == ConstructionState.Confirmation) {
            if (confirm) startConstruction(site) else cancelConstruction(site)
        }
    }

    // ==================== Cleanup ====================

    private fun startCleanupTask() {
        cleanupTask?.cancel()
        cleanupTask = ARC.plugin.server.scheduler.runTaskTimer(ARC.plugin, Runnable {
            cleanup(force = false)
        }, 20L, BuildConfig.cleanupIntervalTicks)
    }

    private fun cleanup(force: Boolean) {
        val allSites = pendingSites.values + activeSites.values.flatten()

        for (site in allSites) {
            try {
                val isStale = System.currentTimeMillis() - site.timestamp > 180_000
                if (!isStale && !force) continue

                info("Cleaning up construction site for player {} {}", site.player.name, site)

                when (site.state) {
                    ConstructionState.DisplayingOutline,
                    ConstructionState.Confirmation -> site.cancel()

                    ConstructionState.Building -> if (force) site.finishInstantly()
                    ConstructionState.Done,
                    ConstructionState.Created,
                    ConstructionState.Cancelled -> site.cleanup(0)
                }

                removeConstruction(site)
            } catch (e: Exception) {
                error("Error while cleaning up site for player {}", site.player.name, e)
            }
        }
    }

    @JvmStatic
    fun stopAll() {
        cleanup(force = true)
        cleanupTask?.cancel()
        cleanupTask = null
        Display.clearCache()
    }

    @JvmStatic
    fun cancelTasks() {
        cleanupTask?.takeIf { !it.isCancelled }?.cancel()
    }

    // ==================== Utilities ====================

    /**
     * Converts player yaw to nearest 90-degree rotation.
     * Returns 0, 90, 180, or 270.
     */
    @JvmStatic
    fun rotationFromYaw(yaw: Float): Int {
        val adjusted = yaw + 180
        return when {
            adjusted > 315 || adjusted <= 45 -> 0
            adjusted <= 135 -> 90
            adjusted <= 225 -> 180
            else -> 270
        }
    }
}
