package ru.arc.autobuild

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.autobuild.gui.BuildingGui
import ru.arc.autobuild.gui.ConfirmGuiFactory
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import ru.arc.onboarding.OnboardingService
import ru.arc.util.CooldownManager
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private var cleanupTask: ScheduledTask? = null

    // ==================== Initialization ====================

    @JvmStatic
    fun init() {
        BuildBookSettings.validate()
        loadBuildings()
        startCleanupTask()
        cleanupOldNpcs()
    }

    private fun loadBuildings() {
        val schematicsPath = Paths.get(ARC.instance.dataFolder.toString(), "schematics")

        try {
            Files.createDirectories(schematicsPath)
            // Production uses plugins/ARC/schematics as a root symlink to the
            // shared McFine catalog. Resolve that one boundary explicitly;
            // Files.walk does not follow a root symlink by default. Its normal
            // no-follow traversal for nested directory links remains intact.
            val scanRoot = schematicsPath.toRealPath()
            val loaded = HashMap<String, Building>()
            Files.walk(scanRoot, 3)
                .use { stream ->
                    stream.filter { Files.isRegularFile(it) }
                        .map { Building(it.name) }
                        .forEach { loaded[it.fileName] = it }
                }
            buildings.clear()
            buildings.putAll(loaded)
            debug(
                "[autobuild] Loaded {} schematics from {} (resolved root {})",
                buildings.size,
                schematicsPath,
                scanRoot,
            )
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
    fun getBuilding(fileName: String): Building? {
        buildings[fileName]?.let { return it }
        if (!fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}"))) return null
        val candidate = Paths.get(ARC.instance.dataFolder.toString(), "schematics", fileName)
        if (!Files.isRegularFile(candidate)) return null
        return Building(fileName).also { buildings.putIfAbsent(fileName, it) }
    }

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
    fun processPlayerClick(
        player: Player,
        rawLocation: Location,
        buildingId: String,
        rot: String?,
        yOff: String?,
        cooldownSecondsRaw: String?,
    ) {
        val transform = BuildBookTransform.parseLegacy(rot, yOff) ?: BuildBookTransform()
        processPlayerClick(
            player,
            rawLocation,
            BuildBookData(
                buildingId = buildingId,
                title = buildingId,
                transform = transform,
                cooldownSeconds = BuildCooldownPolicy.resolveSeconds(cooldownSecondsRaw, BuildConfig.defaultCooldownSeconds),
            ),
        )
    }

    @JvmStatic
    fun processPlayerClick(
        player: Player,
        rawLocation: Location,
        bookData: BuildBookData,
    ) {
        val checkedBook = runCatching { bookData.validated() }.getOrElse {
            player.sendMessage(BuildConfig.Messages.invalidBook())
            return
        }
        val cooldownSeconds = checkedBook.cooldownSeconds ?: BuildConfig.defaultCooldownSeconds
        debug(
            "[autobuild] processPlayerClick player={} loc={} building={} transform={} cooldownSeconds={} disabled={}",
            player.name,
            rawLocation,
            checkedBook.buildingId,
            checkedBook.transform,
            cooldownSeconds,
            BuildConfig.isDisabled,
        )
        if (BuildConfig.isDisabled && !player.hasPermission("arc.admin")) {
            player.sendMessage(BuildConfig.Messages.disabled())
            return
        }

        // Adjust for grass blocks (build on the block below)
        val location = if (rawLocation.block.type in listOf(Material.SHORT_GRASS, Material.TALL_GRASS)) {
            rawLocation.clone().add(0.0, -1.0, 0.0)
        } else rawLocation

        val building = getBuilding(checkedBook.buildingId) ?: run {
            error("Building with id {} not found for player {} at {}", checkedBook.buildingId, player.name, location)
            player.sendMessage(BuildConfig.Messages.notFound())
            return
        }

        if (CooldownManager.cooldown(player.uniqueId, "clicked_npc") > 0L) return

        val existingSite = getPendingConstruction(player.uniqueId)

        when {
            // No existing site - start new outline
            existingSite == null -> createConstruction(player, location, building, checkedBook, cooldownSeconds)

            // A draft is deliberately preview-only. Repeating the placement
            // click explains the next safe step instead of opening a build
            // confirmation that can never succeed.
            existingSite.state == ConstructionState.DisplayingOutline &&
                    existingSite.same(player, location, building, checkedBook) &&
                    checkedBook.draft -> player.sendMessage(BuildConfig.Messages.draftPreview())

            // Same location clicked while showing outline - advance to confirmation
            existingSite.state == ConstructionState.DisplayingOutline &&
                    existingSite.cooldownSeconds == cooldownSeconds &&
                    existingSite.same(player, location, building, checkedBook) -> existingSite.startConfirmation()

            // Activation and paid copy issuance change the exact book identity.
            // Keep the already inspected placement, refresh it with the new
            // contract, and require one more deliberate click to confirm.
            existingSite.state == ConstructionState.DisplayingOutline &&
                    existingSite.cooldownSeconds == cooldownSeconds &&
                    existingSite.samePlacement(player, location, building, checkedBook) &&
                    existingSite.refreshPreview(checkedBook) -> player.sendMessage(BuildConfig.Messages.startOutline())

            // Already building
            existingSite.state == ConstructionState.Building -> {
                player.sendMessage(BuildConfig.Messages.alreadyBuilding())
            }

            // Different location or building - cancel old and start new
            else -> {
                existingSite.cancelSilently()
                createConstruction(player, location, building, checkedBook, cooldownSeconds)
            }
        }
    }

    /**
     * Handles player clicking on a construction NPC.
     */
    @JvmStatic
    fun processNpcClick(clicker: Player, npcId: Int) {
        val site = findByNpcId(npcId) ?: run {
            debug("[autobuild] processNpcClick: no site for npcId={} clicker={}", npcId, clicker.name)
            return
        }
        debug(
            "[autobuild] processNpcClick player={} npcId={} siteState={} owner={}",
            clicker.name,
            npcId,
            site.state,
            site.player.name,
        )

        if (site.player.uniqueId != clicker.uniqueId && !clicker.hasPermission("arc.admin")) {
            clicker.sendMessage(BuildConfig.Messages.notYourNpc())
            return
        }

        CooldownManager.addCooldown(clicker.uniqueId, "clicked_npc", 20L)

        when (site.state) {
            ConstructionState.Confirmation -> {
                ConfirmGuiFactory.create(clicker, site).show(clicker)
            }

            ConstructionState.Building -> BuildingGui(clicker, site).show(clicker)
            else -> {}
        }
    }

    // ==================== Construction Flow ====================

    @JvmStatic
    fun createConstruction(
        player: Player,
        center: Location,
        building: Building,
        subRotation: Int,
        yOffset: Int,
        cooldownSeconds: Long,
    ) {
        createConstruction(
            player,
            center,
            building,
            BuildBookData(
                buildingId = building.fileName,
                title = building.fileName,
                transform = BuildBookTransform(rotation = BuildBookTransform.normalizeRotation(subRotation), offsetY = yOffset),
                cooldownSeconds = cooldownSeconds,
            ),
            cooldownSeconds,
        )
    }

    private fun createConstruction(
        player: Player,
        center: Location,
        building: Building,
        bookData: BuildBookData,
        cooldownSeconds: Long,
    ) {
        val cooldown = CooldownManager.cooldown(player.uniqueId, "building_cooldown")
        if (cooldownSeconds > 0 && cooldown > 0 && !player.hasPermission("arc.admin")) {
            player.sendMessage(BuildConfig.Messages.cooldown(cooldown))
            return
        }

        val world = center.world ?: run {
            error("Cannot create construction: world is null for location {}", center)
            return
        }

        val rotation = rotationFromYaw(player.yaw)
        val transform = bookData.transform.validated()
        val site = ConstructionSite(
            building,
            center,
            player,
            rotation,
            world,
            transform.rotation,
            transform.offsetY,
            cooldownSeconds,
            bookData,
            transform,
        )

        if (!site.canBuild() && !player.hasPermission("arc.admin")) {
            debug(
                "[autobuild] canBuild denied for {} at {} building={}",
                player.name,
                center,
                building.fileName,
            )
            player.sendMessage(BuildConfig.Messages.cantBuild())
            return
        }

        pendingSites[player.uniqueId] = site
        site.startDisplayingBorder()
        debug(
            "[autobuild] createConstruction player={} building={} center={} rotation={} transform={} cooldownSeconds={} volume={}",
            player.name,
            building.fileName,
            center,
            rotation,
            transform,
            cooldownSeconds,
            building.volume,
        )
        player.sendMessage(BuildConfig.Messages.startOutline())
        OnboardingService.recordBuildBookOpened(player, site.centerBlock)
    }

    @JvmStatic
    fun startConstruction(site: ConstructionSite) {
        if (!site.startBuild()) return
        moveToActive(site)
        OnboardingService.recordAutoBuildStarted(site.player, site.centerBlock)
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
        cleanupTask =
            repeating(
                period = BuildConfig.cleanupIntervalTicks.ticks,
                delay = 20.ticks,
            ) {
                cleanup(force = false)
            }
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
        pendingSites.clear()
        activeSites.clear()
        buildings.clear()
        Display.clearCache()
    }

    @JvmStatic
    fun cancelTasks() {
        cleanupTask?.takeIf { !it.isCancelled }?.cancel()
    }

    @JvmStatic
    fun updatePendingTransform(player: Player, next: BuildBookData): Boolean? {
        val site = pendingSites[player.uniqueId] ?: return null
        return site.refreshPreview(next)
    }

    // ==================== Utilities ====================

    /**
     * Converts player yaw to nearest 90-degree rotation.
     * Returns 0, 90, 180, or 270.
     */
    @JvmStatic
    fun rotationFromYaw(yaw: Float): Int {
        val adjusted = (((yaw + 180f) % 360f) + 360f) % 360f
        return when {
            adjusted > 315 || adjusted <= 45 -> 0
            adjusted <= 135 -> 90
            adjusted <= 225 -> 180
            else -> 270
        }
    }
}
