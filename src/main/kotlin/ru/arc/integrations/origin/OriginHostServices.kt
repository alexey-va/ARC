package ru.arc.integrations.origin

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.ARC
import ru.arc.BukkitPortalOriginGate
import ru.arc.OriginGateOpeningCurve
import ru.arc.PortalOriginGateController
import ru.arc.PortalOriginGateSettings
import ru.arc.PortalVisualStyle
import ru.arc.core.modules.RedisModule
import ru.arc.origin.api.ArcOriginChunkLeases
import ru.arc.origin.api.ArcOriginConfiguration
import ru.arc.origin.api.ArcOriginPortalVisuals
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult
import java.util.UUID
import java.util.function.Consumer
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.inventory.ItemStack
import net.citizensnpcs.api.CitizensAPI
import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import ru.arc.origin.api.ArcOriginNpcHolograms
import ru.arc.origin.api.ArcOriginEconomy
import ru.arc.origin.api.ArcOriginFurniture
import ru.arc.origin.api.ArcOriginAuctions
import ru.arc.origin.api.ArcOriginCaseFiles
import ru.arc.origin.api.ArcOriginDialogNavigation
import ru.arc.origin.api.ArcOriginTransfers
import ru.arc.origin.api.ArcOriginCommands
import ru.arc.origin.api.ArcOriginViewerGlow
import ru.arc.hooks.citizens.ArcNpcHologramModule
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.zauction.AuctionShowcaseOpenResult
import ru.arc.core.modules.EconomyModule
import ru.arc.worldcontent.ItemsAdderFurnitureRuntime
import ru.arc.worldcontent.RuntimeFurnitureHandle
import ru.arc.worldcontent.FurnitureFamily
import ru.arc.investigation.InvestigationCaseFile
import ru.arc.gui.ArcMenus
import ru.arc.gui.MenuEscapeBehavior

/** Host-owned shared resources; Origin retains its scenes and gameplay lifecycle. */
object OriginHostServices {
    fun register(plugin: Plugin) {
        val services = plugin.server.servicesManager
        fun <T : Any> register(type: Class<T>, provider: T) =
            services.register(type, provider, plugin, ServicePriority.Normal)
        register(ArcOriginConfiguration::class.java, object : ArcOriginConfiguration {
            override fun legacyDataPath() = ARC.instance.dataPath
            override fun serverName() = requireNotNull(ARC.serverName)
            override fun sharedRedisAvailable() = ARC.redisManager != null
            override fun sharedRedisGeneration() = RedisModule.bindingGeneration
        })
        register(ArcOriginChunkLeases::class.java, object : ArcOriginChunkLeases {
            override fun acquire(worldId: UUID, chunkX: Int, chunkZ: Int): ArcOriginChunkLeases.AcquireResult {
                check(Bukkit.isPrimaryThread()) { "Origin chunk leases require the server thread" }
                return when (val result = ARC.instance.chunkTicketRegistry.acquire(PaperChunkKey(worldId, chunkX, chunkZ))) {
                    is PaperChunkTicketAcquireResult.Acquired -> ArcOriginChunkLeases.AcquireResult.acquired(result.lease)
                    PaperChunkTicketAcquireResult.WorldUnavailable -> ArcOriginChunkLeases.AcquireResult.unavailable(ArcOriginChunkLeases.AcquireResult.Status.WORLD_UNAVAILABLE)
                    PaperChunkTicketAcquireResult.RegistryClosed -> ArcOriginChunkLeases.AcquireResult.unavailable(ArcOriginChunkLeases.AcquireResult.Status.REGISTRY_CLOSED)
                    is PaperChunkTicketAcquireResult.Failed -> {
                        plugin.logger.log(java.util.logging.Level.WARNING, "Origin chunk ticket failed for $worldId:$chunkX,$chunkZ", result.failure)
                        ArcOriginChunkLeases.AcquireResult.unavailable(ArcOriginChunkLeases.AcquireResult.Status.FAILED)
                    }
                }
            }
        })
        register(ArcOriginPortalVisuals::class.java, OriginPortalVisualProvider())
        register(ArcOriginNpcHolograms::class.java, OriginNpcHolograms())
        register(ArcOriginEconomy::class.java, object : ArcOriginEconomy {
            override fun isAvailable() = EconomyModule.getEconomy() != null
            override fun has(player: Player, amount: Double) = EconomyModule.getEconomy()?.has(player, amount) == true
            override fun withdraw(player: Player, amount: Double) =
                EconomyModule.getEconomy()?.withdrawPlayer(player, amount)?.transactionSuccess() == true
            override fun depositDefaultCurrency(playerId: UUID, amount: Double, reason: String): Boolean {
                val currency = RedisEconomyAPI.getAPI()?.defaultCurrency ?: return false
                return runCatching { currency.depositPlayer(playerId, currency.currencyName, amount, reason).transactionSuccess() }
                    .getOrDefault(false)
            }
        })
        register(ArcOriginFurniture::class.java, object : ArcOriginFurniture {
            override fun isAvailable() = ItemsAdderFurnitureRuntime.available
            override fun inspect(entity: Entity) = ItemsAdderFurnitureRuntime.inspect(entity)?.toApi()
            override fun inspect(block: Block) = ItemsAdderFurnitureRuntime.inspect(block)?.toApi()
            override fun remove(entity: Entity, family: ArcOriginFurniture.FurnitureFamily) =
                ItemsAdderFurnitureRuntime.remove(entity, FurnitureFamily.valueOf(family.name))
        })
        register(ArcOriginAuctions::class.java, object : ArcOriginAuctions {
            override fun showcaseListings() = HookRegistry.auctionHook?.showcaseListings().orEmpty().map {
                ArcOriginAuctions.Listing(it.id, it.item, it.itemName, it.sellerName, it.price)
            }
            override fun openShowcaseListing(player: Player, listingId: Int, callback: Consumer<ArcOriginAuctions.OpenResult>) {
                val auction = HookRegistry.auctionHook
                if (auction == null) callback.accept(ArcOriginAuctions.OpenResult.UNAVAILABLE)
                else auction.openShowcaseListing(player, listingId) { result ->
                    callback.accept(when (result) {
                        AuctionShowcaseOpenResult.ConfirmationOpened -> ArcOriginAuctions.OpenResult.CONFIRMATION_OPENED
                        AuctionShowcaseOpenResult.InsufficientFunds -> ArcOriginAuctions.OpenResult.INSUFFICIENT_FUNDS
                        AuctionShowcaseOpenResult.Stale -> ArcOriginAuctions.OpenResult.STALE
                        AuctionShowcaseOpenResult.Unavailable -> ArcOriginAuctions.OpenResult.UNAVAILABLE
                        AuctionShowcaseOpenResult.Busy -> ArcOriginAuctions.OpenResult.BUSY
                        AuctionShowcaseOpenResult.Failed -> ArcOriginAuctions.OpenResult.FAILED
                    })
                }
            }
        })
        register(ArcOriginCaseFiles::class.java, ArcOriginCaseFiles(InvestigationCaseFile::isCaseFile))
        register(ArcOriginTransfers::class.java, ArcOriginTransfers { player, destination ->
            ARC.pluginMessenger?.sendPlayerToServer(player, destination) == true
        })
        register(ArcOriginDialogNavigation::class.java, object : ArcOriginDialogNavigation {
            override fun beginDialogFlow(player: Player) = ArcMenus.beginDialogFlow(player)
            override fun escapeGoesBack(playerId: UUID) =
                HookRegistry.luckPermsHook?.getCachedMeta(playerId, MenuEscapeBehavior.META_KEY) != "close"
        })
        register(ArcOriginViewerGlow::class.java, object : ArcOriginViewerGlow {
            override fun setDisplayGlowingFor(display: Display, viewer: Player, glowing: Boolean) {
                val packets = HookRegistry.packetEventsHook
                if (packets == null) display.isGlowing = glowing
                else packets.setDisplayGlowingFor(display, viewer, glowing)
            }
            override fun setEntityGlowingFor(entity: LivingEntity, viewer: Player?, glowing: Boolean) {
                val packets = HookRegistry.packetEventsHook
                if (packets == null) entity.isGlowing = glowing
                else viewer?.takeIf(Player::isOnline)?.let { packets.setEntityGlowingFor(entity, it, glowing) }
            }
        })
    }
}

internal class OriginPortalVisualProvider : ArcOriginPortalVisuals {
    override fun open(displayCenter: Location, request: ArcOriginPortalVisuals.Settings): ArcOriginPortalVisuals.Gate? {
        check(Bukkit.isPrimaryThread()) { "Origin portal visuals require the server thread" }
        val settings = originPortalSettings(request) ?: return null
        val center = displayCenter.clone()
        val style = settings.defaultStyle
        var spawned = false
        val controller = PortalOriginGateController(settings) {
            BukkitPortalOriginGate.spawn(center, settings, style).also { spawned = it != null }
        }
        return object : ArcOriginPortalVisuals.Gate {
            override fun isActive() = controller.isActive
            override fun gateSpawned() = spawned
            override fun tick(elapsedTicks: Int) = controller.tickOpening(settings.entryTick + elapsedTicks)
            override fun updateScale(scale: Float) = controller.updateScale(scale)
            override fun renderSuction(tick: Int, full: List<Player>, reduced: List<Player>) =
                BukkitPortalOriginGate.renderSuction(center, tick, settings, style, full, reduced)
            override fun close() = controller.remove()
        }
    }
}

/** Preserve the permanent Origin portal's existing validated renderer settings. */
internal fun originPortalSettings(request: ArcOriginPortalVisuals.Settings): PortalOriginGateSettings? =
    PortalOriginGateSettings.validated(
        defaultStyle = request.styleId,
        itemIds = request.itemIds.mapNotNull { (key, value) -> PortalVisualStyle.parse(key)?.let { it to value } }.toMap(),
        openingStartTick = 0,
        openingDurationTicks = 1,
        openingCurve = OriginGateOpeningCurve.SMOOTH.name,
        closingDurationTicks = 1,
        width = request.width.toFloat(),
        height = request.height.toFloat(),
        verticalOffset = request.verticalOffset,
        yawOffsetDegrees = 0f,
        viewRange = 2.0f,
        openingSoundEnabled = false,
        openingSoundDelayTicks = 0,
        openingSoundId = "minecraft:block.end_portal.spawn",
        openingSoundVolume = 1.0f,
        openingSoundPitch = 1.0f,
        suctionEnabled = request.particlesEnabled,
        suctionStreams = request.streams.coerceAtMost(8),
        reducedSuctionStreams = request.reducedStreams.coerceAtMost(request.streams),
        suctionPointsPerStream = request.pointsPerStream.coerceAtMost(4),
        reducedSuctionPointsPerStream = request.reducedPointsPerStream.coerceAtMost(request.pointsPerStream),
        suctionRadius = request.radius,
        suctionHeight = request.suctionHeight,
        suctionTurns = request.turns,
        suctionParticleSize = request.particleSize.toFloat(),
        suctionCoreCount = request.coreCount,
        maxHeight = 20.0f,
    )


internal class OriginNpcHolograms : ArcOriginNpcHolograms {
    override fun showTemporaryBubble(npcId: Int, lines: List<String>, durationTicks: Int) =
        ArcNpcHologramModule.showTemporaryBubble(npcId, lines, durationTicks)
    override fun showTemporaryBubble(npcId: Int, lines: List<String>, durationTicks: Int, owner: String) =
        ArcNpcHologramModule.showTemporaryBubble(npcId, lines, durationTicks, owner)
    override fun clearTemporaryBubble(npcId: Int, owner: String) { ArcNpcHologramModule.clearTemporaryBubble(npcId, owner) }
    override fun hasTemporaryBubble(npcId: Int) = ArcNpcHologramModule.hasTemporaryBubble(npcId)
    override fun setNameHiddenTemporarily(npcId: Int, owner: String, hidden: Boolean) {
        ArcNpcHologramModule.setNameHiddenTemporarily(npcId, owner, hidden)
    }
    override fun patchName(npcId: Int, name: String) { npc(npcId)?.let { ArcNpcHologramModule.patchName(it, name) } }
    override fun patchNameplate(npcId: Int, enabled: String) { npc(npcId)?.let { ArcNpcHologramModule.patchNameplate(it, enabled) } }
    override fun patchHologram(npcId: Int, lines: List<String>, viewRange: Int) {
        npc(npcId)?.let { ArcNpcHologramModule.patchHologram(it, lines, viewRange = viewRange) }
    }
    private fun npc(id: Int) = if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) CitizensAPI.getNPCRegistry().getById(id) else null
}

private fun RuntimeFurnitureHandle.toApi() =
    ArcOriginFurniture.FurnitureHandle(root, ArcOriginFurniture.FurnitureFamily.valueOf(family.name), namespacedId)

fun originService(): ArcOriginCommands? = Bukkit.getServicesManager().load(ArcOriginCommands::class.java)
