package ru.arc.buildertools

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.Leaves
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import ru.arc.autobuild.BuildBookSettings
import ru.arc.autobuild.BuildBookTransform
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.PlayerBuildBookLimitException
import ru.arc.autobuild.PlayerBuildBookStore
import ru.arc.core.LifecycleTaskScope
import ru.arc.hooks.HookRegistry
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.sql.SqlRuntime
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.BlockUtils.rotateBlockData
import com.sk89q.worldedit.bukkit.BukkitAdapter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal class BuilderToolsRuntime(
    private val plugin: ARC,
    private val config: BuilderToolsConfig,
) : Listener, CommandExecutor, TabCompleter, AutoCloseable {
    private data class SelectionDraft(var first: BuilderBlockPos? = null, var second: BuilderBlockPos? = null)

    private data class ActiveOperation(
        var record: BuilderJournalRecord,
        val gameMode: GameMode,
        var appliedChanges: Int = 0,
        var inventoryMutated: Boolean = false,
        var cancelled: Boolean = false,
        var uncertainCommit: Boolean = false,
    )

    private data class PendingBookMint(
        val kind: BuilderBookMintKind,
        val sourceBlueprintId: UUID,
        val sourceInstanceId: UUID?,
        val blueprint: BuilderBookBlueprint,
        val outputInstanceId: UUID,
        val expiresAtMillis: Long,
    )

    private class UserFailure(val path: String, val values: Map<String, Component> = emptyMap()) : RuntimeException(path)

    private val messages: LocalizedMiniMessage = config.messages()
    private val shop = BuilderShopCoordinator(config, messages)
    private val bookPricing = BuilderBookPricing(config)
    private val safety = BuilderBlockSafety(plugin, config.replaceableMaterials)
    private val coreProtect = BuilderCoreProtectBridge.resolve()
    private val journal = BuilderJournalStore(plugin.dataPath, config.maxChanges)
    private val stateService = PaperPlayerStateService()
    private val stateCodec = PaperPlayerStateCodec()
    private val taskScope = LifecycleTaskScope()
    private val bookRegistry: BuilderBookRegistry? = if (config.bookContractsEnabled) {
        runCatching {
            BuilderBookSqlRegistry(
                SqlRuntime.create(config.bookSqlConfig().connection(), "arc-builder-books"),
            )
        }.onFailure { failure -> error("Builder-book MySQL runtime could not be created", failure) }.getOrNull()
    } else {
        null
    }
    private val bookMintCoordinator: BuilderBookMintCoordinator? = bookRegistry?.let { registry ->
        BuilderBookMintCoordinator(
            registry = registry,
            wallet = RedisEconomyBuilderBookWallet(),
            runSync = { action -> taskScope.runSync(action) },
            onManualReview = { mint ->
                error(
                    "Builder-book mint requires manual review: transaction=${mint.transactionId} player=${mint.playerId} status=${mint.status}",
                )
            },
        )
    }
    private val storageExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "arc-builder-tools-storage").apply { isDaemon = true }
    }
    private val debugLine = StructuredDebugLine("ARC_BUILDER_TOOLS")
    private val wandKey = org.bukkit.NamespacedKey(plugin, "builder_selector")
    private val crownBrushKey = org.bukkit.NamespacedKey(plugin, "crown_brush")
    private val selections = mutableMapOf<UUID, SelectionDraft>()
    private val clipboards = mutableMapOf<UUID, BuilderClipboard>()
    private val crownSessions = BuilderCrownSessions()
    private val crownBrushAnchors = mutableMapOf<UUID, BuilderBlockPos>()
    private val pendingPlans = mutableMapOf<UUID, BuilderPendingPlan>()
    private val pendingBookMints = mutableMapOf<UUID, PendingBookMint>()
    private val bookLockedPlayers = mutableSetOf<UUID>()
    private val bookDeliveryWaitingForSpace = mutableSetOf<UUID>()
    private val bookDeliveryRecoveries = mutableSetOf<UUID>()
    private val activeOperations = mutableMapOf<UUID, ActiveOperation>()
    private val lockedBlocks = mutableMapOf<BuilderBlockPos, UUID>()
    private val committedRecords = mutableMapOf<UUID, BuilderJournalRecord>()
    private val recoveryByPlayer = mutableMapOf<UUID, BuilderJournalRecord>()
    private val consumedUndoSources = mutableSetOf<UUID>()
    private val previewFailurePlayers = mutableSetOf<UUID>()
    private var recovering = true
    private var recoveryBlocked = false
    private var bookRegistryReady = !config.bookContractsEnabled
    private var bookRegistryFailed = config.bookContractsEnabled && bookRegistry == null
    private var closed = false

    init {
        require(!config.requireLands || HookRegistry.landsHook != null) {
            "Builder-tools requires the active Lands integration"
        }
        require(!config.requireCoreProtect || coreProtect != null) {
            "Builder-tools requires the active CoreProtect API"
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
        BuilderPreviewLoop(taskScope, config.previewPeriodTicks, ::renderPreviews)
        if (config.bookContractsEnabled) {
            checkNotNull(
                taskScope.runTimer(100L, 100L) {
                    (bookLockedPlayers + bookDeliveryWaitingForSpace).toList()
                        .mapNotNull(Bukkit::getPlayer)
                        .filter(Player::isOnline)
                        .forEach(::recoverBookDeliveries)
                },
            ) { "Builder-book delivery retry task was not scheduled" }
        }
        loadRecoveryState()
        initializeBookContracts()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage("Builder tools are player-only.")
            return true
        }
        try {
            handleBuilder(player, args)
        } catch (failure: UserFailure) {
            send(player, failure.path, failure.values)
        } catch (failure: IllegalArgumentException) {
            warn("Builder-tools rejected command for {}: {}", player.name, failure.message)
            send(player, "errors.plan-failed", mapOf("reason" to messages.literal(failure.message ?: "invalid plan")))
        } catch (failure: Throwable) {
            error("Builder-tools command failed for ${player.name}", failure)
            send(player, "errors.plan-failed", mapOf("reason" to messages.literal("internal safety check")))
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (sender !is Player) return emptyList()
        if (args.size == 1) {
            return filterPrefix(
                listOf("help", "wand", "pos1", "pos2", "fill", "copy", "book", "paste", "deconstruct", "crown", "confirm", "cancel", "undo", "status"),
                args[0],
            )
        }
        if (args.size == 2 && args[0].equals("confirm", true)) return filterPrefix(listOf("buy"), args[1])
        if (args.size == 2 && args[0].equals("book", true)) {
            return filterPrefix(listOf("draft", "activate", "copy", "confirm", "cancel"), args[1])
        }
        if (args.firstOrNull().equals("crown", true)) {
            if (args.size == 2) {
                return filterPrefix(
                    listOf("help", "wand", "palette", "shape", "radius", "density", "noise", "reroll", "place", "undo", "cancel", "status") + leafNames(),
                    args.lastOrNull(),
                )
            }
            if (args.size != 3) return emptyList()
            return when (args[1].lowercase(Locale.ROOT)) {
                "shape" -> filterPrefix(BuilderCrownShape.entries.map { it.name.lowercase(Locale.ROOT) }, args.last())
                "radius" -> filterPrefix((3..10).map(Int::toString), args.last())
                "density" -> filterPrefix(BuilderCrownDensity.entries.map { it.name.lowercase(Locale.ROOT) }, args.last())
                "noise" -> filterPrefix(BuilderCrownNoise.entries.map { it.name.lowercase(Locale.ROOT) }, args.last())
                "palette" -> filterPrefix(leafNames(), args.last())
                else -> if (args[1].lowercase(Locale.ROOT) in leafNames()) {
                    filterPrefix((3..10).map(Int::toString), args.last())
                } else {
                    emptyList()
                }
            }
        }
        if (args.size == 2 && args[0].equals("fill", true)) return filterPrefix(safeMaterialNames(), args[1])
        return emptyList()
    }

    private fun handleBuilder(player: Player, args: Array<out String>) {
        ensureAvailable(player)
        when (args.firstOrNull()?.lowercase(Locale.ROOT) ?: "help") {
            "help" -> messages.renderLines("help", locale(player)).forEach(player::sendMessage)
            "wand" -> giveWand(player)
            "pos1" -> setCommandPosition(player, first = true)
            "pos2" -> setCommandPosition(player, first = false)
            "fill" -> preparePlan(player, planFill(player, materialArgument(player, args.getOrNull(1))))
            "copy" -> copySelection(player)
            "book" -> handleBuildBookCommand(player, args.drop(1))
            "paste" -> preparePlan(player, planPaste(player))
            "deconstruct" -> preparePlan(player, planDeconstruct(player))
            "crown" -> handleBuilderCrown(player, args.drop(1))
            "confirm" -> when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
                null -> confirm(player)
                "buy" -> confirm(player, buyMissing = true)
                else -> messages.renderLines("help", locale(player)).forEach(player::sendMessage)
            }
            "cancel", "stop" -> cancelPlan(player)
            "undo" -> prepareUndo(player)
            "status" -> showStatus(player)
            else -> messages.renderLines("help", locale(player)).forEach(player::sendMessage)
        }
    }

    private fun handleBuilderCrown(player: Player, args: List<String>) {
        ensureFeaturePermission(player, BuilderFeature.CROWN)
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "confirm", "place" -> return confirm(player)
            "undo" -> return prepareUndo(player)
            "cancel", "stop" -> return cancelPlan(player)
            "status" -> return showCrownStatus(player)
            "brush", "wand" -> return giveCrownBrush(player)
            "palette" -> return updateCrownPalette(player, args.getOrNull(1))
            "shape" -> return updateCrownEnum<BuilderCrownShape>(player, "shape", args.getOrNull(1)) { settings, value -> settings.copy(shape = value) }
            "radius" -> {
                val radius = crownRadius(args.getOrNull(1))
                return updateCrownSettings(player, "radius", radius.toString(), crownSettings(player).copy(radius = radius))
            }
            "density" -> return updateCrownEnum<BuilderCrownDensity>(player, "density", args.getOrNull(1)) { settings, value -> settings.copy(density = value) }
            "noise" -> return updateCrownEnum<BuilderCrownNoise>(player, "noise", args.getOrNull(1)) { settings, value -> settings.copy(noise = value) }
            "reroll" -> return prepareCrownPlan(player, crownSettings(player), reroll = true)
            "help", "settings" -> {
                messages.renderLines("crown.help", locale(player)).forEach(player::sendMessage)
                return
            }
        }
        val current = crownSettings(player)
        val requested = if (args.isEmpty()) {
            current
        } else {
            val material = materialArgument(player, args[0])
            if (!safety.isLeaf(material)) throw UserFailure("errors.material")
            current.copy(
                palette = listOf(BuilderCrownPaletteEntry(material.name.lowercase(Locale.ROOT), 1)),
                radius = args.getOrNull(1)?.let(::crownRadius) ?: current.radius,
            )
        }
        prepareCrownPlan(player, requested)
    }

    private fun crownRadius(raw: String?): Int {
        val radius = raw?.toIntOrNull() ?: throw UserFailure("errors.crown-setting")
        if (radius !in 3..10) throw UserFailure("errors.crown-setting")
        return radius
    }

    private fun crownSettings(player: Player): BuilderCrownSettings =
        crownSessions.settings(player.uniqueId)

    private fun updateCrownPalette(player: Player, raw: String?) {
        val parsed = try {
            BuilderCrownPaletteParser.parse(raw ?: throw IllegalArgumentException("missing palette"))
        } catch (_: IllegalArgumentException) {
            throw UserFailure("errors.crown-setting")
        }
        parsed.forEach { entry ->
            val material = Material.matchMaterial(entry.materialName) ?: throw UserFailure("errors.material")
            if (!safety.isLeaf(material)) throw UserFailure("errors.material")
        }
        storeCrownSettings(player, crownSettings(player).copy(palette = parsed))
        send(player, "crown.palette-updated", mapOf("count" to messages.literal(parsed.size)))
    }

    private inline fun <reified T : Enum<T>> updateCrownEnum(
        player: Player,
        key: String,
        raw: String?,
        update: (BuilderCrownSettings, T) -> BuilderCrownSettings,
    ) {
        val value = enumValues<T>().firstOrNull { it.name.equals(raw, true) } ?: throw UserFailure("errors.crown-setting")
        updateCrownSettings(player, key, value.name.lowercase(Locale.ROOT), update(crownSettings(player), value))
    }

    private fun updateCrownSettings(player: Player, key: String, value: String, updated: BuilderCrownSettings) {
        storeCrownSettings(player, updated)
        send(player, "crown.settings-updated", mapOf("setting" to messages.literal(key), "value" to messages.literal(value)))
    }

    private fun storeCrownSettings(player: Player, updated: BuilderCrownSettings) {
        crownSessions.update(player.uniqueId, updated)
        pendingPlans[player.uniqueId]?.plan?.takeIf { it.kind == BuilderPlanKind.CROWN }?.let {
            discardPendingPlan(player.uniqueId)
        }
    }

    private fun showCrownStatus(player: Player) {
        val settings = crownSettings(player)
        val values = mapOf(
            "shape" to messages.literal(settings.shape.name.lowercase(Locale.ROOT)),
            "radius" to messages.literal(settings.radius),
            "density" to messages.literal(settings.density.name.lowercase(Locale.ROOT)),
            "noise" to messages.literal(settings.noise.name.lowercase(Locale.ROOT)),
        )
        messages.renderLines("crown.status", locale(player), values).forEach(player::sendMessage)
        settings.palette.forEach { entry ->
            send(
                player,
                "crown.palette-row",
                mapOf("material" to messages.literal(entry.materialName), "weight" to messages.literal(entry.weight)),
            )
        }
        showStatus(player)
    }

    private fun ensureAvailable(player: Player) {
        if (!hasUsePermission(player)) throw UserFailure("errors.no-permission")
        ensureOperationalContext(player)
    }

    private fun ensureBuildBookAvailable(player: Player) {
        if (!player.hasPermission("arc.build.book.use")) throw UserFailure("errors.no-permission")
        ensureOperationalContext(player)
    }

    private fun ensureOperationalContext(player: Player) {
        if (recovering || recoveryBlocked || player.uniqueId in recoveryByPlayer) throw UserFailure("errors.recovering")
        if (!BuilderGameModePolicy.allows(player.gameMode)) throw UserFailure("errors.game-mode")
        if (!config.allowsWorld(player.world.name)) throw UserFailure("errors.world-not-allowed")
    }

    private fun ensureFeaturePermission(player: Player, feature: BuilderFeature) {
        if (!BuilderPermissionPolicy.canUse(feature, player::hasPermission)) {
            throw UserFailure("errors.no-permission")
        }
    }

    private fun hasUsePermission(player: Player): Boolean =
        BuilderPermissionPolicy.canUseAny(player::hasPermission)

    private fun giveWand(player: Player) {
        if (isSelector(player.inventory.itemInMainHand)) {
            player.inventory.setItemInMainHand(styleWand(player.inventory.itemInMainHand.clone(), player))
            send(player, "wand.received")
            return
        }
        val wand = styleWand(ItemStack(Material.ECHO_SHARD), player)
        when (BuilderOwnedToolExchange.replaceOnePlainHeld(player, Material.ECHO_SHARD, wand)) {
            BuilderOwnedToolExchangeResult.REPLACED -> Unit
            BuilderOwnedToolExchangeResult.WRONG_ITEM -> throw UserFailure("wand.material-required")
            BuilderOwnedToolExchangeResult.INVENTORY_FULL -> throw UserFailure("wand.inventory-full")
        }
        send(player, "wand.received")
    }

    private fun giveCrownBrush(player: Player) {
        if (isCrownBrush(player.inventory.itemInMainHand)) {
            player.inventory.setItemInMainHand(styleCrownBrush(player.inventory.itemInMainHand.clone(), player))
            send(player, "crown-brush.received")
            return
        }
        val brush = styleCrownBrush(ItemStack(Material.BRUSH), player)
        when (BuilderOwnedToolExchange.replaceOnePlainHeld(player, Material.BRUSH, brush)) {
            BuilderOwnedToolExchangeResult.REPLACED -> Unit
            BuilderOwnedToolExchangeResult.WRONG_ITEM -> throw UserFailure("crown-brush.material-required")
            BuilderOwnedToolExchangeResult.INVENTORY_FULL -> throw UserFailure("crown-brush.inventory-full")
        }
        send(player, "crown-brush.received")
    }

    private fun styleWand(item: ItemStack, player: Player): ItemStack = item.apply {
        editMeta { meta ->
            BuilderItemPresentation.apply(
                meta,
                messages.render("wand.name", locale(player)),
                messages.renderLines("wand.lore", locale(player)),
            )
            meta.persistentDataContainer.set(wandKey, PersistentDataType.BYTE, 1)
        }
    }

    private fun styleCrownBrush(item: ItemStack, player: Player): ItemStack = item.apply {
        editMeta { meta ->
            BuilderItemPresentation.apply(
                meta,
                messages.render("crown-brush.name", locale(player)),
                messages.renderLines("crown-brush.lore", locale(player)),
            )
            meta.persistentDataContainer.set(crownBrushKey, PersistentDataType.BYTE, 1)
        }
    }

    private fun setCommandPosition(player: Player, first: Boolean) {
        val target = player.getTargetBlockExact(48) ?: player.location.block
        setPosition(player, target.location, first)
    }

    private fun setPosition(player: Player, location: Location, first: Boolean) {
        ensureAvailable(player)
        require(location.world == player.world) { "Selection world mismatch" }
        val position = BuilderBlockPos(player.world.uid, location.blockX, location.blockY, location.blockZ).validated()
        val draft = selections.getOrPut(player.uniqueId, ::SelectionDraft)
        if (first) draft.first = position else draft.second = position
        send(
            player,
            if (first) "selection.first" else "selection.second",
            mapOf("x" to messages.literal(position.x), "y" to messages.literal(position.y), "z" to messages.literal(position.z)),
        )
        val selection = selectionOrNull(player)
        if (selection != null) {
            send(
                player,
                "selection.complete",
                mapOf(
                    "x" to messages.literal(selection.sizeX),
                    "y" to messages.literal(selection.sizeY),
                    "z" to messages.literal(selection.sizeZ),
                    "volume" to messages.literal(selection.volume),
                ),
            )
            showSelectionOutline(player, selection, Particle.HAPPY_VILLAGER)
        } else {
            player.spawnParticle(Particle.HAPPY_VILLAGER, location.clone().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.0)
        }
    }

    private fun requiredSelection(player: Player): BuilderSelection {
        val selection = selectionOrNull(player) ?: throw UserFailure("errors.selection-missing")
        return try {
            selection.validated(maxAxis(player), config.maxScanVolume)
        } catch (_: IllegalArgumentException) {
            throw UserFailure("errors.selection-too-large")
        }
    }

    private fun selectionOrNull(player: Player): BuilderSelection? {
        val draft = selections[player.uniqueId] ?: return null
        return if (draft.first != null && draft.second != null) BuilderSelection(draft.first!!, draft.second!!) else null
    }

    private fun maxAxis(player: Player): Int {
        return BuilderPermissionPolicy.maximumAxis(player::hasPermission, config.absoluteMaxAxis)
    }

    private fun planFill(player: Player, material: Material): BuilderPlan {
        ensureFeaturePermission(player, BuilderFeature.FILL)
        val data = placementData(material)
        val selection = requiredSelection(player)
        val world = requireWorld(selection.worldId)
        val changes = selection.positionsBottomUp().mapNotNull { position ->
            val block = block(world, position)
            if (block.blockData.asString == data.asString) return@mapNotNull null
            if (!safety.isReplaceable(block)) return@mapNotNull null
            ensureMutable(player, block)
            BuilderBlockChange(position, block.blockData.asString, data.asString)
        }.take(config.maxChanges + 1).toList()
        requireChanges(changes)
        val cost = if (BuilderGameModePolicy.usesInventory(player.gameMode)) {
            BuilderItemCodec.aggregate(listOf(ItemStack(material, changes.size)))
        } else {
            emptyList()
        }
        return newPlan(player, BuilderPlanKind.FILL, changes, cost, emptyList())
    }

    private fun copySelection(player: Player) {
        ensureFeaturePermission(player, BuilderFeature.COPY)
        val selection = requiredSelection(player)
        val world = requireWorld(selection.worldId)
        val blocks = mutableListOf<BuilderClipboardBlock>()
        selection.positionsBottomUp().forEach { position ->
            val block = block(world, position)
            ensureInRangeAndLoaded(player, block)
            if (block.type.isAir) return@forEach
            if (safety.isReplaceable(block)) return@forEach
            if (!safety.isSafeExisting(block)) throw unsafeBlock(block)
            ensureProtected(player, block)
            val copiedData = block.blockData.clone().also { data ->
                if (data is Leaves) data.isPersistent = true
            }
            blocks += BuilderClipboardBlock(position.x - selection.minX, position.y - selection.minY, position.z - selection.minZ, copiedData.asString)
            if (blocks.size > config.maxClipboardBlocks) throw UserFailure("errors.selection-too-large")
        }
        if (blocks.isEmpty()) throw UserFailure("errors.plan-failed", mapOf("reason" to messages.literal("empty copy")))
        val now = System.currentTimeMillis()
        clipboards[player.uniqueId] = BuilderClipboard(
            blocks = blocks,
            sizeX = selection.sizeX,
            sizeY = selection.sizeY,
            sizeZ = selection.sizeZ,
            createdAtMillis = now,
            expiresAtMillis = now + config.clipboardTtl.toMillis(),
        ).validated(config.maxClipboardBlocks)
        send(player, "clipboard.saved", mapOf("count" to messages.literal(blocks.size)))
    }

    private fun handleBuildBookCommand(player: Player, args: List<String>) {
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "draft" -> createBuildBookDraft(player, args.drop(1))
            "activate" -> prepareBuildBookActivation(player)
            "copy" -> prepareBuildBookCopy(player)
            "confirm" -> confirmBuildBookMint(player)
            "cancel" -> cancelBuildBookMint(player)
            else -> messages.renderLines("help", locale(player)).forEach(player::sendMessage)
        }
    }

    private fun createBuildBookDraft(player: Player, rawTitle: List<String>) {
        ensureFeaturePermission(player, BuilderFeature.COPY)
        if (!player.hasPermission("arc.build.book.create")) throw UserFailure("errors.no-permission")
        val clipboard = clipboards[player.uniqueId]?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }
            ?: throw UserFailure("errors.expired")
        val held = player.inventory.itemInMainHand
        if (!isPlainBook(held)) throw UserFailure("book.material-required")
        if (held.amount > 1 && player.inventory.firstEmpty() == -1) throw UserFailure("book.inventory-full")
        val title = rawTitle.joinToString(" ").trim().ifEmpty { BuildBookSettings.defaultTitle }
        if (title.length > 48 || title.any(Char::isISOControl)) throw UserFailure("book.invalid-name")
        val template = try {
            PlayerBuildBookStore.create(player.uniqueId, clipboard)
        } catch (_: PlayerBuildBookLimitException) {
            throw UserFailure("book.limit")
        } catch (failure: Throwable) {
            error("Could not persist player build book for ${player.name}", failure)
            throw UserFailure("book.failed")
        }
        val data = BuildBookData(
            buildingId = template.buildingId,
            title = title,
            playerCreated = true,
            creatorId = player.uniqueId,
            creatorName = player.name,
            blueprintId = UUID.randomUUID(),
            contentSha256 = template.contentSha256,
            schematicSha256 = template.schematicSha256,
            blockCount = template.blockCount,
            cooldownSeconds = 0,
        ).validated()
        val book = BuildBookItems.create(data)
        replaceOneHeldBook(player, held, book)
        send(
            player,
            "book.draft-created",
            mapOf("name" to messages.literal(title), "count" to messages.literal(clipboard.blocks.size)),
        )
    }

    private fun prepareBuildBookActivation(player: Player) {
        if (!player.hasPermission("arc.build.book.create")) throw UserFailure("errors.no-permission")
        val registry = requireBookRegistry()
        val held = player.inventory.itemInMainHand
        val data = BuildBookCodec.read(held)?.takeIf { it.draft } ?: throw UserFailure("book.draft-required")
        if (held.amount != 1) throw UserFailure("book.duplicate")
        if (data.creatorId != player.uniqueId) throw UserFailure("book.creator-only")
        verifyBookSchematic(data)
        val blueprintId = checkNotNull(data.blueprintId)
        registry.loadBlueprint(blueprintId).whenComplete { existing, failure ->
            taskScope.runSync {
                if (!player.isOnline) return@runSync
                if (failure != null) {
                    warn("Builder-book blueprint lookup failed for {}: {}", player.name, failure.message)
                    send(player, "book.registry-unavailable")
                    return@runSync
                }
                try {
                    val blueprint = if (existing != null) {
                        if (!matchesBlueprint(data, existing)) throw UserFailure("book.invalid")
                        existing
                    } else {
                        val building = BuildingManager.getBuilding(data.buildingId) ?: throw UserFailure("book.invalid")
                        when (val quoted = bookPricing.quote(player, building)) {
                            is BuilderBookQuoteResult.Ready -> BuilderBookBlueprint(
                                blueprintId = blueprintId,
                                creatorId = checkNotNull(data.creatorId),
                                creatorName = data.creatorName ?: player.name,
                                title = data.title,
                                buildingId = data.buildingId,
                                contentSha256 = checkNotNull(data.contentSha256),
                                schematicSha256 = checkNotNull(data.schematicSha256),
                                blockCount = data.blockCount ?: quoted.quote.materialItems,
                                materialTypes = quoted.quote.materialTypes,
                                materialItems = quoted.quote.materialItems,
                                materialCostMinor = quoted.quote.cost.materialCostMinor,
                                constructionFeeMinor = quoted.quote.cost.constructionFeeMinor,
                                issuePriceMinor = quoted.quote.cost.issuePriceMinor,
                                createdAtMillis = System.currentTimeMillis(),
                            ).validated()
                            BuilderBookQuoteResult.ShopUnavailable -> throw UserFailure("book.shop-unavailable")
                            is BuilderBookQuoteResult.MaterialsUnavailable -> throw UserFailure(
                                "book.material-unavailable",
                                mapOf("materials" to messages.literal(quoted.materials.take(5).joinToString { it.key.key })),
                            )
                            BuilderBookQuoteResult.LimitExceeded -> throw UserFailure("book.price-limit")
                        }
                    }
                    pendingBookMints[player.uniqueId] = PendingBookMint(
                        kind = BuilderBookMintKind.CREATE,
                        sourceBlueprintId = blueprintId,
                        sourceInstanceId = null,
                        blueprint = blueprint,
                        outputInstanceId = UUID.randomUUID(),
                        expiresAtMillis = System.currentTimeMillis() + config.planTtl.toMillis(),
                    )
                    sendBookMintQuote(player, blueprint, "activation")
                } catch (userFailure: UserFailure) {
                    send(player, userFailure.path, userFailure.values)
                } catch (unexpected: Throwable) {
                    error("Builder-book activation quote failed for ${player.name}", unexpected)
                    send(player, "book.failed")
                }
            }
        }
    }

    private fun prepareBuildBookCopy(player: Player) {
        if (!player.hasPermission("arc.build.book.create")) throw UserFailure("errors.no-permission")
        val registry = requireBookRegistry()
        val held = player.inventory.itemInMainHand
        val data = BuildBookCodec.read(held)?.takeIf { it.available } ?: throw UserFailure("book.active-required")
        if (held.amount != 1) throw UserFailure("book.duplicate")
        if (player.inventory.firstEmpty() == -1) throw UserFailure("book.inventory-full")
        verifyBookSchematic(data)
        val instanceId = checkNotNull(data.instanceId)
        registry.loadInstance(instanceId).whenComplete { instance, instanceFailure ->
            taskScope.runSync instanceLookup@{
                if (!player.isOnline) return@instanceLookup
                if (instanceFailure != null || instance == null) {
                    send(player, if (instanceFailure == null) "book.duplicate" else "book.registry-unavailable")
                    return@instanceLookup
                }
                if (instance.status != BuilderBookInstanceStatus.AVAILABLE || instance.blueprintId != data.blueprintId) {
                    send(player, "book.duplicate")
                    return@instanceLookup
                }
                registry.loadBlueprint(instance.blueprintId).whenComplete { blueprint, blueprintFailure ->
                    taskScope.runSync blueprintLookup@{
                        if (!player.isOnline) return@blueprintLookup
                        if (blueprintFailure != null || blueprint == null) {
                            send(player, if (blueprintFailure == null) "book.invalid" else "book.registry-unavailable")
                            return@blueprintLookup
                        }
                        if (!matchesBlueprint(data, blueprint)) {
                            send(player, "book.invalid")
                            return@blueprintLookup
                        }
                        pendingBookMints[player.uniqueId] = PendingBookMint(
                            kind = BuilderBookMintKind.COPY,
                            sourceBlueprintId = blueprint.blueprintId,
                            sourceInstanceId = instanceId,
                            blueprint = blueprint,
                            outputInstanceId = UUID.randomUUID(),
                            expiresAtMillis = System.currentTimeMillis() + config.planTtl.toMillis(),
                        )
                        sendBookMintQuote(player, blueprint, "copy")
                    }
                }
            }
        }
    }

    private fun confirmBuildBookMint(player: Player) {
        val registry = requireBookRegistry()
        val coordinator = bookMintCoordinator ?: throw UserFailure("book.registry-unavailable")
        if (player.uniqueId in bookLockedPlayers || player.uniqueId in activeOperations) throw UserFailure("errors.busy")
        val pending = pendingBookMints[player.uniqueId] ?: throw UserFailure("book.quote-expired")
        if (pending.expiresAtMillis <= System.currentTimeMillis()) {
            pendingBookMints.remove(player.uniqueId)
            throw UserFailure("book.quote-expired")
        }
        val held = player.inventory.itemInMainHand
        val data = BuildBookCodec.read(held) ?: throw UserFailure("book.source-changed")
        if (!matchesPendingSource(data, pending) || held.amount != 1) {
            throw UserFailure("book.source-changed")
        }
        if (pending.kind == BuilderBookMintKind.COPY && player.inventory.firstEmpty() == -1) {
            throw UserFailure("book.inventory-full")
        }
        verifyBookSchematic(data)
        pendingBookMints.remove(player.uniqueId)
        bookLockedPlayers += player.uniqueId
        val now = System.currentTimeMillis()
        val transactionId = UUID.randomUUID()
        val transform = data.transform.validated()
        val intent = BuilderBookMint(
            transactionId = transactionId,
            kind = pending.kind,
            playerId = player.uniqueId,
            blueprint = pending.blueprint,
            instanceId = pending.outputInstanceId,
            sourceInstanceId = pending.sourceInstanceId,
            placement = BuilderBookPlacement(transform.rotation, transform.offsetX, transform.offsetY, transform.offsetZ),
            createdAtMillis = now,
        ).validated()
        val sourceId = pending.sourceInstanceId
        if (sourceId == null) {
            startBookMint(player, intent, coordinator)
            return
        }
        val serverName = ARC.serverName ?: run {
            bookLockedPlayers -= player.uniqueId
            throw UserFailure("book.registry-unavailable")
        }
        registry.reserve(
            instanceId = sourceId,
            expectedBlueprintId = pending.blueprint.blueprintId,
            expectedBuildingId = pending.blueprint.buildingId,
            expectedSchematicSha256 = pending.blueprint.schematicSha256,
            operationId = transactionId,
            playerId = player.uniqueId,
            serverName = serverName,
            now = now,
        ).whenComplete { reservation, failure ->
            taskScope.runSync {
                if (failure != null || reservation == null) {
                    bookLockedPlayers -= player.uniqueId
                    send(player, "book.registry-unavailable")
                } else if (reservation is BuilderBookReservationResult.Reserved) {
                    startBookMint(player, intent, coordinator)
                } else {
                    bookLockedPlayers -= player.uniqueId
                    send(player, "book.duplicate")
                }
            }
        }
    }

    private fun startBookMint(player: Player, intent: BuilderBookMint, coordinator: BuilderBookMintCoordinator) {
        coordinator.mint(intent) { result ->
            when (result) {
                is BuilderBookMintResult.Issued -> deliverIssuedBook(player, result.mint)
                BuilderBookMintResult.Busy -> finishFailedBookMint(player, intent, "errors.busy", releaseSource = true)
                BuilderBookMintResult.EconomyUnavailable -> finishFailedBookMint(player, intent, "book.economy-unavailable", releaseSource = true)
                BuilderBookMintResult.InsufficientFunds -> finishFailedBookMint(player, intent, "book.insufficient-funds", releaseSource = true)
                BuilderBookMintResult.PaymentRejected -> finishFailedBookMint(player, intent, "book.payment-failed", releaseSource = true)
                BuilderBookMintResult.RegistryUnavailable -> finishFailedBookMint(player, intent, "book.registry-unavailable", releaseSource = true)
                BuilderBookMintResult.Refunded -> finishFailedBookMint(player, intent, "book.refunded", releaseSource = true)
                BuilderBookMintResult.ManualReview -> finishFailedBookMint(player, intent, "book.manual-review", releaseSource = false)
            }
        }
    }

    private fun deliverIssuedBook(player: Player, mint: BuilderBookMint) {
        if (!player.isOnline) return
        val data = registeredBookData(mint.blueprint, mint.instanceId, mint.placement)
        val existing = inventoryBooksWithInstance(player, mint.instanceId)
        if (existing.size > 1 || (existing.size == 1 && existing.single().second != data)) {
            error("Builder-book issued item conflicts with local inventory: transaction=${mint.transactionId} instance=${mint.instanceId}")
            send(player, "book.manual-review")
            return
        }
        if (existing.isEmpty()) {
            val held = player.inventory.itemInMainHand
            val heldData = BuildBookCodec.read(held)
            if (mint.kind == BuilderBookMintKind.CREATE && heldData?.draft == true && heldData.blueprintId == mint.blueprint.blueprintId) {
                replaceOneHeldBook(player, held, BuildBookItems.create(data))
            } else {
                if (player.inventory.firstEmpty() == -1) {
                    waitForBookDeliverySpace(player)
                    return
                }
                check(player.inventory.addItem(BuildBookItems.create(data)).isEmpty()) { "Paid builder book did not fit after preflight" }
            }
            player.updateInventory()
        }
        markBookDelivered(player, mint.instanceId, mint.transactionId, mint.sourceInstanceId, recovered = false)
    }

    private fun markBookDelivered(
        player: Player,
        instanceId: UUID,
        transactionId: UUID,
        sourceInstanceId: UUID?,
        recovered: Boolean,
        finished: () -> Unit = {},
    ) {
        val registry = bookRegistry ?: return finished()
        registry.markDelivered(instanceId, transactionId, System.currentTimeMillis()).whenComplete { delivered, failure ->
            taskScope.runSync {
                if (failure != null || delivered != true) {
                    error(
                        "Builder-book delivery requires retry: transaction=$transactionId instance=$instanceId",
                        failure ?: IllegalStateException("delivery transition rejected"),
                    )
                    if (player.isOnline) send(player, "book.delivery-pending")
                    finished()
                    return@runSync
                }
                if (!completeLocalBookDelivery(player, instanceId)) {
                    error("Builder-book delivery item disappeared while locked: transaction=$transactionId instance=$instanceId")
                    if (player.isOnline) send(player, "book.manual-review")
                    bookDeliveryRecoveries += player.uniqueId
                    // The paid output remains quarantined, but a completed copy
                    // must never leave its legitimate source reserved forever.
                    releaseBookSource(sourceInstanceId, transactionId)
                    return@runSync
                }
                releaseBookSource(sourceInstanceId, transactionId) {
                    bookLockedPlayers -= player.uniqueId
                    bookDeliveryWaitingForSpace -= player.uniqueId
                    if (player.isOnline) {
                        send(
                            player,
                            when {
                                recovered -> "book.delivery-recovered"
                                sourceInstanceId != null -> "book.copied"
                                else -> "book.activated"
                            },
                        )
                    }
                    finished()
                }
            }
        }
    }

    private fun finishFailedBookMint(player: Player, mint: BuilderBookMint, messagePath: String, releaseSource: Boolean) {
        val finish = {
            bookLockedPlayers -= player.uniqueId
            bookDeliveryWaitingForSpace -= player.uniqueId
            if (player.isOnline) send(player, messagePath)
        }
        if (releaseSource) releaseBookSource(mint.sourceInstanceId, mint.transactionId, finish) else finish()
    }

    private fun releaseBookSource(sourceInstanceId: UUID?, operationId: UUID, done: () -> Unit = {}) {
        if (sourceInstanceId == null) return done()
        val registry = bookRegistry ?: return done()
        registry.release(sourceInstanceId, operationId).whenComplete { released, failure ->
            taskScope.runSync {
                if (failure != null || released != true) {
                    warn("Builder-book copy source release requires recovery: source={} operation={}", sourceInstanceId, operationId)
                }
                done()
            }
        }
    }

    private fun cancelBuildBookMint(player: Player) {
        if (pendingBookMints.remove(player.uniqueId) != null) send(player, "book.quote-cancelled")
        else throw UserFailure("book.quote-expired")
    }

    private fun waitForBookDeliverySpace(player: Player) {
        bookLockedPlayers -= player.uniqueId
        if (bookDeliveryWaitingForSpace.add(player.uniqueId)) send(player, "book.delivery-space")
    }

    private fun sendBookMintQuote(player: Player, blueprint: BuilderBookBlueprint, kind: String) {
        send(
            player,
            "book.quote",
            mapOf(
                "kind" to messages.render("book.quote-kind.$kind", locale(player)),
                "name" to messages.literal(blueprint.title),
                "blocks" to messages.literal(blueprint.blockCount),
                "items" to messages.literal(blueprint.materialItems),
                "types" to messages.literal(blueprint.materialTypes),
                "materials" to messages.literal(formatMinor(blueprint.materialCostMinor)),
                "labor" to messages.literal(formatMinor(blueprint.constructionFeeMinor)),
                "price" to messages.literal(formatMinor(blueprint.issuePriceMinor)),
                "seconds" to messages.literal(config.planTtl.seconds),
            ),
        )
    }

    private fun matchesPendingSource(data: BuildBookData, pending: PendingBookMint): Boolean =
        data.blueprintId == pending.sourceBlueprintId &&
            data.instanceId == pending.sourceInstanceId &&
            ((pending.kind == BuilderBookMintKind.CREATE && data.draft) ||
                (pending.kind == BuilderBookMintKind.COPY && data.available)) &&
            matchesBlueprint(data, pending.blueprint)

    private fun matchesBlueprint(data: BuildBookData, blueprint: BuilderBookBlueprint): Boolean =
        data.blueprintId == blueprint.blueprintId &&
            data.creatorId == blueprint.creatorId &&
            data.creatorName == blueprint.creatorName &&
            data.title == blueprint.title &&
            data.buildingId == blueprint.buildingId &&
            data.contentSha256 == blueprint.contentSha256 &&
            data.schematicSha256 == blueprint.schematicSha256 &&
            data.blockCount == blueprint.blockCount &&
            (data.issuePriceMinor == null || data.issuePriceMinor == blueprint.issuePriceMinor)

    private fun verifyBookSchematic(data: BuildBookData) {
        val expectedFile = data.schematicSha256 ?: throw UserFailure("book.invalid")
        val expectedContent = data.contentSha256 ?: throw UserFailure("book.invalid")
        val actualFile = PlayerBuildBookStore.schematicSha256(data.buildingId) ?: throw UserFailure("book.invalid")
        val actualContent = PlayerBuildBookStore.contentSha256(data.buildingId) ?: throw UserFailure("book.invalid")
        if (actualFile != expectedFile || actualContent != expectedContent) throw UserFailure("book.invalid")
    }

    private fun registeredBookData(
        blueprint: BuilderBookBlueprint,
        instanceId: UUID,
        placement: BuilderBookPlacement,
        deliveryPending: Boolean = true,
    ): BuildBookData = BuildBookData(
        buildingId = blueprint.buildingId,
        title = blueprint.title,
        transform = BuildBookTransform(placement.rotation, placement.offsetX, placement.offsetY, placement.offsetZ),
        playerCreated = true,
        creatorId = blueprint.creatorId,
        creatorName = blueprint.creatorName,
        blueprintId = blueprint.blueprintId,
        instanceId = instanceId,
        issuePriceMinor = blueprint.issuePriceMinor,
        contentSha256 = blueprint.contentSha256,
        schematicSha256 = blueprint.schematicSha256,
        deliveryPending = deliveryPending,
        blockCount = blueprint.blockCount,
        cooldownSeconds = 0,
    ).validated()

    private fun inventoryBooksWithInstance(player: Player, instanceId: UUID): List<Pair<Int, BuildBookData>> =
        (0 until player.inventory.size).mapNotNull { slot ->
            val data = player.inventory.getItem(slot)?.let(BuildBookCodec::read) ?: return@mapNotNull null
            if (data.instanceId == instanceId) slot to data else null
        }

    private fun completeLocalBookDelivery(player: Player, instanceId: UUID): Boolean {
        val matches = inventoryBooksWithInstance(player, instanceId)
        if (matches.size != 1) return false
        val (slot, data) = matches.single()
        if (!data.deliveryPending) return true
        val item = player.inventory.getItem(slot) ?: return false
        player.inventory.setItem(slot, BuildBookCodec.update(item, data.copy(deliveryPending = false).validated()))
        player.updateInventory()
        return true
    }

    private fun localPendingBookInstances(player: Player): List<UUID> = player.inventory.contents
        .filterNotNull()
        .mapNotNull { BuildBookCodec.read(it)?.takeIf(BuildBookData::deliveryPending)?.instanceId }

    private fun requireBookRegistry(): BuilderBookRegistry {
        if (!config.bookContractsEnabled) throw UserFailure("book.contracts-disabled")
        if (bookRegistryFailed) throw UserFailure("book.registry-unavailable")
        if (!bookRegistryReady) throw UserFailure("book.registry-starting")
        return bookRegistry ?: throw UserFailure("book.registry-unavailable")
    }

    private fun formatMinor(amount: Long): String = String.format(Locale.US, "%,.2f", amount / 100.0)

    private fun isPlainBook(item: ItemStack): Boolean {
        if (item.type != Material.BOOK || item.amount <= 0) return false
        return item.clone().also { it.amount = 1 }.isSimilar(ItemStack(Material.BOOK))
    }

    private fun replaceOneHeldBook(player: Player, held: ItemStack, replacement: ItemStack) {
        if (held.amount == 1) {
            player.inventory.setItemInMainHand(replacement)
            return
        }
        if (player.inventory.firstEmpty() == -1) throw UserFailure("book.inventory-full")
        player.inventory.setItemInMainHand(held.clone().also { it.amount = held.amount - 1 })
        check(player.inventory.addItem(replacement).isEmpty()) { "Owned build book did not fit after preflight" }
    }

    private fun planPaste(player: Player): BuilderPlan {
        ensureFeaturePermission(player, BuilderFeature.PASTE)
        val clipboard = clipboards[player.uniqueId]?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }
            ?: throw UserFailure("errors.expired")
        val anchor = selections[player.uniqueId]?.first ?: throw UserFailure("errors.selection-missing")
        val world = requireWorld(anchor.worldId)
        val costs = mutableListOf<ItemStack>()
        val changes = clipboard.blocks.mapNotNull { copied ->
            val position = BuilderBlockPos(anchor.worldId, anchor.x + copied.dx, anchor.y + copied.dy, anchor.z + copied.dz).validated()
            val block = block(world, position)
            val after = Bukkit.createBlockData(copied.blockData)
            if (!safety.isSafePlacement(after)) throw unsafeBlock(block)
            if (block.blockData.asString == after.asString) return@mapNotNull null
            if (!safety.isReplaceable(block)) throw unsafeBlock(block)
            ensureMutable(player, block)
            if (BuilderGameModePolicy.usesInventory(player.gameMode)) costs += BuilderPlacementCost.item(after)
            BuilderBlockChange(position, block.blockData.asString, after.asString)
        }
        requireChanges(changes)
        return newPlan(player, BuilderPlanKind.PASTE, changes, BuilderItemCodec.aggregate(costs), emptyList())
    }

    private fun planBuildBook(player: Player, site: ConstructionSite, book: ItemStack): BuilderPlan {
        if (!player.hasPermission("arc.build.book.use")) throw UserFailure("errors.no-permission")
        val data = site.bookData?.takeIf { it.playerCreated } ?: throw UserFailure("book.invalid")
        if (data.draft) throw UserFailure("book.unactivated")
        if (data.deliveryPending) throw UserFailure("book.delivery-pending")
        if (!data.available) throw UserFailure("book.invalid")
        if (!BuildBookCodec.matches(book, data)) throw UserFailure("book.missing")
        verifyBookSchematic(data)
        if (site.building.volume > config.maxScanVolume) throw UserFailure("errors.selection-too-large")

        val changes = site.relativePositionsBottomUp().mapNotNull { relative ->
            val after = BukkitAdapter.adapt(site.building.getBlock(relative, site.fullRotation)).also { blockData ->
                rotateBlockData(blockData, site.fullRotation)
            }
            if (after.material.isAir) return@mapNotNull null
            val location = site.worldLocation(relative)
            val block = location.block
            if (!safety.isSafePlacement(after)) throw unsafeBlock(block)
            if (block.blockData.asString == after.asString) return@mapNotNull null
            if (!safety.isReplaceable(block)) throw unsafeBlock(block)
            ensureMutable(player, block)
            BuilderBlockChange(
                BuilderBlockPos(site.world.uid, block.x, block.y, block.z).validated(),
                block.blockData.asString,
                after.asString,
            )
        }.take(config.maxChanges + 1).toList()
        requireChanges(changes)
        val exactBook = book.clone().also { it.amount = 1 }
        return newPlan(
            player = player,
            kind = BuilderPlanKind.BUILD_BOOK,
            changes = changes,
            costs = BuilderItemCodec.aggregate(listOf(exactBook)),
            rewards = emptyList(),
            bookBlueprintId = checkNotNull(data.blueprintId),
            bookInstanceId = checkNotNull(data.instanceId),
            bookBuildingId = data.buildingId,
            bookSchematicSha256 = checkNotNull(data.schematicSha256),
        )
    }

    private fun planDeconstruct(player: Player): BuilderPlan {
        ensureFeaturePermission(player, BuilderFeature.DECONSTRUCT)
        val selection = requiredSelection(player)
        val world = requireWorld(selection.worldId)
        val usesInventory = BuilderGameModePolicy.usesInventory(player.gameMode)
        val tool = player.inventory.itemInMainHand.clone().takeIf { usesInventory }
        if (usesInventory && (tool == null || tool.type.isAir || tool.type.maxDurability <= 0)) throw UserFailure("errors.tool")
        val drops = mutableListOf<ItemStack>()
        val air = Material.AIR.createBlockData().asString
        val changes = selection.positionsTopDown().mapNotNull { position ->
            val block = block(world, position)
            if (block.type.isAir) return@mapNotNull null
            if (safety.isReplaceable(block)) return@mapNotNull null
            if (!safety.isSafeExisting(block)) throw unsafeBlock(block)
            if (usesInventory && !block.isPreferredTool(checkNotNull(tool))) throw UserFailure("errors.tool")
            ensureMutable(player, block)
            if (usesInventory) drops += block.getDrops(checkNotNull(tool), player).map(ItemStack::clone)
            BuilderBlockChange(position, block.blockData.asString, air)
        }.take(config.maxChanges + 1).toList()
        requireChanges(changes)
        val fingerprint = tool?.let(BuilderItemCodec::encodePrototype)
        val rewards = BuilderItemCodec.aggregate(drops)
        val toolDamage = if (usesInventory) changes.size else 0
        if (!BuilderInventory.canApply(player, emptyList(), rewards, fingerprint, toolDamage)) throw UserFailure("errors.inventory")
        return newPlan(
            player = player,
            kind = BuilderPlanKind.DECONSTRUCT,
            changes = changes,
            costs = emptyList(),
            rewards = rewards,
            toolFingerprint = fingerprint,
            toolDamage = toolDamage,
        )
    }

    private fun prepareCrownPlan(player: Player, rawSettings: BuilderCrownSettings, reroll: Boolean = false) {
        val settings = try {
            rawSettings.validated()
        } catch (_: IllegalArgumentException) {
            throw UserFailure("errors.crown-setting")
        }
        val center = selections[player.uniqueId]?.first ?: throw UserFailure("errors.selection-missing")
        val seed = crownSessions.seed(player.uniqueId, center, settings, reroll)
        preparePlan(player, planCrown(player, settings, seed))
    }

    private fun planCrown(player: Player, settings: BuilderCrownSettings, seed: Long): BuilderPlan {
        val center = selections[player.uniqueId]?.first ?: throw UserFailure("errors.selection-missing")
        val world = requireWorld(center.worldId)
        val materialByName = settings.palette.associate { entry ->
            val material = Material.matchMaterial(entry.materialName) ?: throw UserFailure("errors.material")
            if (!safety.isLeaf(material)) throw UserFailure("errors.material")
            entry.materialName to material
        }
        val dataByMaterial = materialByName.values.associateWith(::placementData)
        val costs = mutableListOf<ItemStack>()
        val changes = BuilderCrownGeometry.offsets(settings, seed).mapNotNull { (dx, dy, dz) ->
            val position = BuilderBlockPos(center.worldId, center.x + dx, center.y + dy, center.z + dz).validated()
            val block = block(world, position)
            val material = materialByName.getValue(settings.materialAt(dx, dy, dz, seed))
            val data = dataByMaterial.getValue(material)
            if (block.blockData.asString == data.asString) return@mapNotNull null
            if (!safety.isReplaceable(block)) return@mapNotNull null
            ensureMutable(player, block)
            if (BuilderGameModePolicy.usesInventory(player.gameMode)) costs += ItemStack(material)
            BuilderBlockChange(position, block.blockData.asString, data.asString)
        }.take(config.maxChanges + 1).toList()
        requireChanges(changes)
        return newPlan(player, BuilderPlanKind.CROWN, changes, BuilderItemCodec.aggregate(costs), emptyList())
    }

    private fun placementData(material: Material) = material.createBlockData().also { data ->
        if (!safety.isSafePlacement(data)) throw UserFailure("errors.material")
        if (data is Leaves) data.isPersistent = true
    }

    private fun materialArgument(player: Player, raw: String?): Material {
        if (raw == null) return player.inventory.itemInMainHand.type.takeUnless(Material::isAir) ?: throw UserFailure("errors.material")
        return Material.matchMaterial(raw) ?: Material.matchMaterial(raw.uppercase(Locale.ROOT)) ?: throw UserFailure("errors.material")
    }

    private fun newPlan(
        player: Player,
        kind: BuilderPlanKind,
        changes: List<BuilderBlockChange>,
        costs: List<BuilderItemAmount>,
        rewards: List<BuilderItemAmount>,
        toolFingerprint: String? = null,
        toolDamage: Int = 0,
        sourceRecordId: UUID? = null,
        bookBlueprintId: UUID? = null,
        bookInstanceId: UUID? = null,
        bookBuildingId: String? = null,
        bookSchematicSha256: String? = null,
    ): BuilderPlan {
        val now = System.currentTimeMillis()
        return BuilderPlan(
            id = UUID.randomUUID(),
            playerId = player.uniqueId,
            kind = kind,
            changes = changes,
            costs = costs,
            rewards = rewards,
            toolFingerprintBase64 = toolFingerprint,
            toolDamage = toolDamage,
            sourceRecordId = sourceRecordId,
            bookBlueprintId = bookBlueprintId,
            bookInstanceId = bookInstanceId,
            bookBuildingId = bookBuildingId,
            bookSchematicSha256 = bookSchematicSha256,
            createdAtMillis = now,
            expiresAtMillis = now + config.planTtl.toMillis(),
        ).validated(config.maxChanges)
    }

    private fun preparePlan(player: Player, plan: BuilderPlan) {
        preflightPlan(player, plan)
        crownBrushAnchors.remove(player.uniqueId)
        pendingPlans[player.uniqueId] = BuilderPendingPlan(plan, player.gameMode)
        showPlanParticles(player, plan)
        send(
            player,
            "plan.ready",
            mapOf(
                "kind" to kindLabel(player, plan.kind),
                "count" to messages.literal(plan.changes.size),
                "cost" to messages.literal(itemsSummary(plan.costs)),
                "reward" to messages.literal(itemsSummary(plan.rewards)),
                "seconds" to messages.literal(config.planTtl.seconds),
            ),
        )
        shop.preview(player, plan)
        val planId = plan.id
        taskScope.runLater(config.planTtl.toTicks()) {
            if (pendingPlans[player.uniqueId]?.plan?.id == planId) {
                pendingPlans.remove(player.uniqueId)
                shop.clear(player.uniqueId)
                crownBrushAnchors.remove(player.uniqueId)
            }
        }
    }

    private fun preflightPlan(player: Player, plan: BuilderPlan) {
        if (isPlayerLocked(player.uniqueId)) throw UserFailure("errors.busy")
        val used = hourlyUsage(player.uniqueId, System.currentTimeMillis())
        if (plan.kind != BuilderPlanKind.UNDO && used + plan.changes.size > hourlyLimit(player)) {
            throw UserFailure("errors.plan-failed", mapOf("reason" to messages.literal("hourly change limit")))
        }
        val canApplyNow = BuilderInventory.canApply(player, plan.costs, plan.rewards, plan.toolFingerprintBase64, plan.toolDamage)
        if (!canApplyNow) {
            if (!BuilderShopEstimateRules.supportsAutoBuy(plan.kind)) throw UserFailure("errors.inventory")
            val missing = BuilderInventory.missingCosts(player, plan.costs)
            if (
                missing.isEmpty() ||
                !BuilderInventory.canApplyAfterReceiving(
                    player,
                    missing,
                    plan.costs,
                    plan.rewards,
                    plan.toolFingerprintBase64,
                    plan.toolDamage,
                )
            ) {
                throw UserFailure("errors.inventory")
            }
        }
    }

    fun startPlayerBuildBook(player: Player, site: ConstructionSite, book: ItemStack): Boolean = try {
        ensureBuildBookAvailable(player)
        val plan = planBuildBook(player, site, book)
        preflightPlan(player, plan)
        pendingPlans[player.uniqueId] = BuilderPendingPlan(plan, player.gameMode)
        confirm(player, buildBook = true)
        site.cancelSilently()
        true
    } catch (failure: UserFailure) {
        send(player, failure.path, failure.values)
        false
    } catch (failure: IllegalArgumentException) {
        warn("Player build-book plan was rejected for {}: {}", player.name, failure.message)
        send(player, "book.failed")
        false
    } catch (failure: Throwable) {
        error("Player build-book start failed for ${player.name}", failure)
        send(player, "book.failed")
        false
    }

    private fun confirm(player: Player, buyMissing: Boolean = false, buildBook: Boolean = false) {
        if (buildBook) ensureBuildBookAvailable(player) else ensureAvailable(player)
        if (isPlayerLocked(player.uniqueId)) throw UserFailure("errors.busy")
        val pending = pendingPlans[player.uniqueId] ?: throw UserFailure("errors.expired")
        val plan = pending.plan
        if (plan.expiresAtMillis <= System.currentTimeMillis()) {
            discardPendingPlan(player.uniqueId)
            throw UserFailure("errors.expired")
        }
        val plannedMode = pending.gameMode
        if (player.gameMode != plannedMode) {
            discardPendingPlan(player.uniqueId)
            throw UserFailure("errors.game-mode-changed")
        }
        revalidatePlan(player, plan)
        if (buyMissing) {
            when (val result = shop.procure(player, plan)) {
                BuilderShopConfirmation.Ready -> Unit
                is BuilderShopConfirmation.Rejected -> throw UserFailure(
                    result.messagePath,
                    result.values.mapValues { (_, value) -> messages.literal(value) },
                )
            }
        }
        if (!BuilderInventory.canApply(player, plan.costs, plan.rewards, plan.toolFingerprintBase64, plan.toolDamage)) {
            throw UserFailure("errors.inventory")
        }
        if (!lock(plan)) throw UserFailure("errors.busy")
        pendingPlans.remove(player.uniqueId, pending)
        shop.clear(player.uniqueId)
        crownBrushAnchors.remove(player.uniqueId)
        val instanceId = plan.bookInstanceId
        if (instanceId != null) {
            reserveBookForBuild(player, plan, plannedMode)
        } else {
            startJournaledOperation(player, plan, plannedMode)
        }
    }

    private fun reserveBookForBuild(player: Player, plan: BuilderPlan, plannedMode: GameMode) {
        val registry = requireBookRegistry()
        val serverName = ARC.serverName ?: run {
            unlock(plan)
            throw UserFailure("book.registry-unavailable")
        }
        bookLockedPlayers += player.uniqueId
        registry.reserve(
            instanceId = checkNotNull(plan.bookInstanceId),
            expectedBlueprintId = checkNotNull(plan.bookBlueprintId),
            expectedBuildingId = checkNotNull(plan.bookBuildingId),
            expectedSchematicSha256 = checkNotNull(plan.bookSchematicSha256),
            operationId = plan.id,
            playerId = player.uniqueId,
            serverName = serverName,
            now = System.currentTimeMillis(),
        ).whenComplete { result, failure ->
            taskScope.runSync {
                if (failure != null || result == null) {
                    bookLockedPlayers -= player.uniqueId
                    unlock(plan)
                    send(player, "book.registry-unavailable")
                    return@runSync
                }
                if (result !is BuilderBookReservationResult.Reserved) {
                    bookLockedPlayers -= player.uniqueId
                    unlock(plan)
                    send(player, "book.duplicate")
                    return@runSync
                }
                if (!player.isOnline) {
                    releasePlanBookReservation(plan)
                    bookLockedPlayers -= player.uniqueId
                    unlock(plan)
                    return@runSync
                }
                bookLockedPlayers -= player.uniqueId
                startJournaledOperation(player, plan, plannedMode)
            }
        }
    }

    private fun startJournaledOperation(player: Player, plan: BuilderPlan, plannedMode: GameMode) {
        val now = System.currentTimeMillis()
        val record = BuilderJournalRecord(
            operationId = plan.id,
            playerId = player.uniqueId,
            playerName = player.name,
            phase = BuilderJournalPhase.PREPARED,
            plan = plan,
            inventoryBefore = stateService.captureEnvelope(player, now),
            createdAtMillis = plan.createdAtMillis,
            updatedAtMillis = now,
        ).validated(config.maxChanges)
        val operation = ActiveOperation(record, plannedMode)
        activeOperations[player.uniqueId] = operation
        send(player, "operation.started", mapOf("count" to messages.literal(plan.changes.size)))
        writeAsync(
            action = { journal.commit(record) },
            callback = { durable, failure ->
                if (failure != null || durable == null) return@writeAsync failBeforeMutation(player, operation, failure)
                operation.record = durable
                if (operation.cancelled || !player.isOnline) return@writeAsync acknowledgeCancelled(operation)
                val applying = durable.copy(phase = BuilderJournalPhase.APPLYING, updatedAtMillis = System.currentTimeMillis())
                writeAsync(
                    action = { journal.commit(applying) },
                    callback = applyingCommit@{ durableApplying, applyFailure ->
                        if (applyFailure != null || durableApplying == null) {
                            return@applyingCommit failBeforeMutation(player, operation, applyFailure)
                        }
                        operation.record = durableApplying
                        if (operation.cancelled || !player.isOnline) return@applyingCommit acknowledgeCancelled(operation)
                        beginMutation(player, operation)
                    },
                )
            },
        )
    }

    private fun beginMutation(player: Player, operation: ActiveOperation) {
        val record = operation.record
        if (player.gameMode != operation.gameMode) {
            rollback(player, operation, "game mode changed before apply")
            return
        }
        if (!BuilderInventory.snapshotInventoryMatches(player, record.inventoryBefore)) {
            rollback(player, operation, "inventory changed before apply")
            return
        }
        try {
            revalidatePlan(player, record.plan)
            check(BuilderInventory.removeCosts(player.inventory, record.plan.costs)) { "planned costs disappeared" }
            if (record.plan.toolDamage > 0) player.damageItemStack(EquipmentSlot.HAND, record.plan.toolDamage)
            player.updateInventory()
            operation.inventoryMutated = record.plan.costs.isNotEmpty() || record.plan.toolDamage > 0
        } catch (failure: Throwable) {
            rollback(player, operation, failure.message ?: "pre-apply validation failed")
            return
        }
        runMutationBatch(player, operation)
    }

    private fun runMutationBatch(player: Player, operation: ActiveOperation) {
        if (operation.cancelled || !player.isOnline) {
            rollback(player, operation, "player disconnected")
            return
        }
        if (player.gameMode != operation.gameMode) {
            rollback(player, operation, "game mode changed during apply")
            return
        }
        try {
            var processed = 0
            val changes = operation.record.plan.changes
            while (processed < config.blocksPerTick && operation.appliedChanges < changes.size) {
                val change = changes[operation.appliedChanges]
                val block = block(requireWorld(change.position.worldId), change.position)
                ensureMutable(player, block)
                check(block.blockData.asString == change.beforeBlockData) { "block changed after confirmation" }
                val before = Bukkit.createBlockData(change.beforeBlockData)
                val after = Bukkit.createBlockData(change.afterBlockData)
                block.setBlockData(after, false)
                coreProtect?.logChange(operation.record.playerName, block.location, before, after)
                operation.appliedChanges++
                processed++
            }
            if (operation.appliedChanges < changes.size) {
                taskScope.runLater(1L) { runMutationBatch(player, operation) }
                return
            }
            check(BuilderInventory.addRewards(player.inventory, operation.record.plan.rewards)) { "planned rewards no longer fit" }
            player.updateInventory()
            operation.inventoryMutated = operation.inventoryMutated || operation.record.plan.rewards.isNotEmpty()
            commitMutation(player, operation)
        } catch (failure: Throwable) {
            rollback(player, operation, failure.message ?: "mutation failed")
        }
    }

    private fun commitMutation(player: Player, operation: ActiveOperation) {
        val now = System.currentTimeMillis()
        val committed = operation.record.copy(
            phase = BuilderJournalPhase.COMMITTED,
            updatedAtMillis = now,
            committedAtMillis = now,
        )
        writeAsync(
            action = { journal.transition(operation.record, committed) },
            callback = { durable, failure ->
                if (failure != null || durable == null) {
                    if (failure is BuilderJournalUnknownOutcomeException) {
                        operation.uncertainCommit = true
                        recoveryBlocked = true
                        error("Builder-tools commit outcome requires restart recovery for ${operation.record.operationId}", failure)
                        send(player, "errors.recovering")
                        return@writeAsync
                    }
                    rollback(player, operation, failure?.message ?: "commit durability failed")
                    return@writeAsync
                }
                operation.record = durable
                committedRecords[durable.operationId] = durable
                durable.plan.sourceRecordId?.let { consumedUndoSources += it }
                val instanceId = durable.plan.bookInstanceId
                if (instanceId == null) {
                    finalizeCommittedOperation(player, operation)
                } else {
                    val registry = bookRegistry
                    if (registry == null) {
                        operation.uncertainCommit = true
                        recoveryBlocked = true
                        send(player, "errors.recovering")
                        return@writeAsync
                    }
                    registry.consume(instanceId, durable.operationId, System.currentTimeMillis()).whenComplete { consumed, consumeFailure ->
                        taskScope.runSync {
                            if (consumeFailure != null || consumed != true) {
                                operation.uncertainCommit = true
                                recoveryBlocked = true
                                error(
                                    "Builder-book consume outcome requires restart recovery for ${durable.operationId}",
                                    consumeFailure ?: IllegalStateException("consume transition rejected"),
                                )
                                send(player, "errors.recovering")
                            } else {
                                finalizeCommittedOperation(player, operation)
                            }
                        }
                    }
                }
            },
        )
    }

    private fun finalizeCommittedOperation(player: Player, operation: ActiveOperation) {
        val durable = operation.record
        finishOperation(operation)
        send(
            player,
            "operation.completed",
            mapOf("kind" to kindLabel(player, durable.plan.kind), "count" to messages.literal(durable.plan.changes.size)),
        )
        info(debugLine.line("event" to "committed", "operation" to durable.operationId, "player" to durable.playerId, "kind" to durable.plan.kind, "blocks" to durable.plan.changes.size))
        durable.plan.sourceRecordId?.let { markSourceUndone(it) }
        cleanupOldRecords()
    }

    private fun rollback(player: Player, operation: ActiveOperation, reason: String) {
        var failure: Throwable? = null
        try {
            for (index in operation.appliedChanges - 1 downTo 0) {
                val change = operation.record.plan.changes[index]
                val block = block(requireWorld(change.position.worldId), change.position)
                val current = block.blockData.asString
                check(current == change.afterBlockData || current == change.beforeBlockData) {
                    "rollback encountered an externally changed block"
                }
                if (current == change.afterBlockData) {
                    val after = Bukkit.createBlockData(change.afterBlockData)
                    val before = Bukkit.createBlockData(change.beforeBlockData)
                    block.setBlockData(before, false)
                    coreProtect?.logChange(operation.record.playerName, block.location, after, before)
                }
            }
            if (player.isOnline && !player.isDead) {
                stateService.restoreInventoryAndVerify(player, stateCodec.decode(operation.record.inventoryBefore))
            } else if (operation.inventoryMutated) {
                recoveryByPlayer[player.uniqueId] = operation.record
            }
        } catch (rollbackFailure: Throwable) {
            failure = rollbackFailure
            recoveryBlocked = true
            error("Builder-tools rollback requires operator attention for ${operation.record.operationId}", rollbackFailure)
        }
        finishOperation(operation)
        if (failure == null) releasePlanBookReservation(operation.record.plan)
        if (failure == null && player.isOnline) send(player, "operation.rolled-back", mapOf("reason" to messages.literal(reason)))
        if (failure == null && player.uniqueId !in recoveryByPlayer) {
            writeAsync(action = { journal.acknowledge(operation.record.operationId) }, callback = { _, acknowledgeFailure ->
                if (acknowledgeFailure != null) warn("Builder-tools could not acknowledge rolled-back operation {}", operation.record.operationId)
            })
        }
        warn(debugLine.line("event" to "rolled_back", "operation" to operation.record.operationId, "player" to operation.record.playerId, "reason" to reason))
    }

    private fun failBeforeMutation(player: Player, operation: ActiveOperation, failure: Throwable?) {
        finishOperation(operation)
        releasePlanBookReservation(operation.record.plan)
        send(player, "operation.rolled-back", mapOf("reason" to messages.literal("durability unavailable")))
        failure?.let { warn("Builder-tools durability barrier failed: {}", it.message) }
        writeAsync(action = { journal.acknowledge(operation.record.operationId) }, callback = { _, acknowledgeFailure ->
            if (acknowledgeFailure != null) {
                recoveryBlocked = true
                warn("Builder-tools could not acknowledge failed operation {}", operation.record.operationId)
            }
        })
    }

    private fun acknowledgeCancelled(operation: ActiveOperation) {
        finishOperation(operation)
        releasePlanBookReservation(operation.record.plan)
        writeAsync(action = { journal.acknowledge(operation.record.operationId) }, callback = { _, _ -> })
    }

    private fun releasePlanBookReservation(plan: BuilderPlan) {
        val instanceId = plan.bookInstanceId ?: return
        val registry = bookRegistry ?: run {
            recoveryBlocked = true
            return
        }
        registry.release(instanceId, plan.id).whenComplete { released, failure ->
            taskScope.runSync {
                if (failure != null || released != true) {
                    recoveryBlocked = true
                    error(
                        "Builder-book reservation release failed for ${plan.id}",
                        failure ?: IllegalStateException("release transition rejected"),
                    )
                }
            }
        }
    }

    private fun finishOperation(operation: ActiveOperation) {
        activeOperations.remove(operation.record.playerId)
        operation.record.plan.changes.forEach { change -> lockedBlocks.remove(change.position, operation.record.operationId) }
    }

    private fun revalidatePlan(player: Player, plan: BuilderPlan) {
        plan.validated(config.maxChanges)
        plan.changes.forEach { change ->
            val block = block(requireWorld(change.position.worldId), change.position)
            ensureMutable(player, block)
            if (block.blockData.asString != change.beforeBlockData) throw UserFailure("errors.expired")
            if (!block.type.isAir && !safety.isSafeExisting(block) && !safety.isReplaceable(block)) throw unsafeBlock(block)
            val after = Bukkit.createBlockData(change.afterBlockData)
            if (!safety.isSafePlacement(after) && after.material !in safety.replaceable) {
                throw unsafeBlock(block)
            }
        }
    }

    private fun lock(plan: BuilderPlan): Boolean {
        if (plan.changes.any { lockedBlocks.containsKey(it.position) }) return false
        plan.changes.forEach { lockedBlocks[it.position] = plan.id }
        return true
    }

    private fun unlock(plan: BuilderPlan) {
        plan.changes.forEach { change -> lockedBlocks.remove(change.position, plan.id) }
    }

    private fun cancelPlan(player: Player) {
        val active = activeOperations[player.uniqueId]
        if (active != null) {
            active.cancelled = true
            if (active.appliedChanges > 0 || active.inventoryMutated) rollback(player, active, "cancelled")
            else send(player, "plan.cancelled")
            return
        }
        if (pendingPlans.containsKey(player.uniqueId)) {
            discardPendingPlan(player.uniqueId)
            send(player, "plan.cancelled")
        } else {
            throw UserFailure("errors.expired")
        }
    }

    private fun prepareUndo(player: Player) {
        ensureAvailable(player)
        val now = System.currentTimeMillis()
        val source = committedRecords.values
            .asSequence()
            .filter { it.playerId == player.uniqueId && it.phase == BuilderJournalPhase.COMMITTED }
            .filter { it.plan.kind != BuilderPlanKind.UNDO && it.operationId !in consumedUndoSources }
            .filter { (it.committedAtMillis ?: 0L) + config.undoTtl.toMillis() > now }
            .maxByOrNull { it.committedAtMillis ?: 0L }
            ?: throw UserFailure("errors.undo-missing")
        val changes = source.plan.changes.asReversed().map { change ->
            BuilderBlockChange(change.position, change.afterBlockData, change.beforeBlockData)
        }
        val plan = newPlan(
            player = player,
            kind = BuilderPlanKind.UNDO,
            changes = changes,
            costs = source.plan.rewards,
            rewards = source.plan.costs,
            sourceRecordId = source.operationId,
        )
        preparePlan(player, plan)
    }

    private fun showStatus(player: Player) {
        val active = activeOperations[player.uniqueId]
        val plan = pendingPlans[player.uniqueId]?.plan
        val selection = selectionOrNull(player)
        when {
            active != null -> send(player, "status.plan", mapOf("kind" to kindLabel(player, active.record.plan.kind), "count" to messages.literal(active.appliedChanges), "total" to messages.literal(active.record.plan.changes.size)))
            plan != null -> send(player, "status.plan", mapOf("kind" to kindLabel(player, plan.kind), "count" to messages.literal(0), "total" to messages.literal(plan.changes.size)))
            selection != null -> send(player, "status.selection", mapOf("x" to messages.literal(selection.sizeX), "y" to messages.literal(selection.sizeY), "z" to messages.literal(selection.sizeZ), "volume" to messages.literal(selection.volume)))
            else -> send(player, "status.idle")
        }
    }

    private fun ensureMutable(player: Player, block: Block) {
        ensureInRangeAndLoaded(player, block)
        ensureProtected(player, block)
        if (!block.world.worldBorder.isInside(block.location)) throw UserFailure("errors.protection")
    }

    private fun ensureInRangeAndLoaded(player: Player, block: Block) {
        if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) throw UserFailure("errors.plan-failed", mapOf("reason" to messages.literal("unloaded chunk")))
        if (player.world.uid != block.world.uid || player.location.distanceSquared(block.location.clone().add(0.5, 0.5, 0.5)) > config.maximumRange * config.maximumRange) {
            throw UserFailure("errors.plan-failed", mapOf("reason" to messages.literal("too far away")))
        }
    }

    private fun ensureProtected(player: Player, block: Block) {
        val lands = HookRegistry.landsHook
        if ((lands != null && !lands.isProtectedFor(player, block.location)) || (lands == null && config.requireLands)) {
            throw UserFailure("errors.protection")
        }
    }

    private fun block(world: World, position: BuilderBlockPos): Block = world.getBlockAt(position.x, position.y, position.z)

    private fun requireWorld(id: UUID): World = Bukkit.getWorld(id) ?: throw UserFailure("errors.world-not-allowed")

    private fun requireChanges(changes: List<BuilderBlockChange>) {
        if (changes.isEmpty()) throw UserFailure("errors.plan-failed", mapOf("reason" to messages.literal("nothing to change")))
        if (changes.size > config.maxChanges) throw UserFailure("errors.selection-too-large")
    }

    private fun unsafeBlock(block: Block) = UserFailure(
        "errors.unsafe-block",
        mapOf("material" to messages.literal(block.type.key), "x" to messages.literal(block.x), "y" to messages.literal(block.y), "z" to messages.literal(block.z)),
    )

    private fun hourlyUsage(playerId: UUID, now: Long): Int = committedRecords.values
        .asSequence()
        .filter { it.playerId == playerId && it.plan.kind != BuilderPlanKind.UNDO }
        .filter { (it.committedAtMillis ?: 0L) >= now - 3_600_000L }
        .sumOf { it.plan.changes.size }

    private fun hourlyLimit(player: Player): Int =
        BuilderPermissionPolicy.hourlyChanges(player::hasPermission, config.baseHourlyChanges)

    private fun itemsSummary(items: List<BuilderItemAmount>): String = if (items.isEmpty()) {
        "—"
    } else {
        items.take(5).joinToString(", ") { "${it.amount}×${it.materialKey.removePrefix("minecraft:")}" } +
            if (items.size > 5) " +${items.size - 5}" else ""
    }

    private fun kindLabel(player: Player, kind: BuilderPlanKind): Component =
        messages.render("kinds.${kind.name.lowercase(Locale.ROOT)}", locale(player))

    private fun discardPendingPlan(playerId: UUID) {
        pendingPlans.remove(playerId)
        shop.clear(playerId)
        crownBrushAnchors.remove(playerId)
    }

    private fun renderPreviews() {
        val now = System.currentTimeMillis()
        Bukkit.getOnlinePlayers().forEach { player ->
            if (!BuilderGameModePolicy.allows(player.gameMode) || !config.allowsWorld(player.world.name)) return@forEach
            try {
                val playerId = player.uniqueId
                pendingPlans[playerId]?.plan?.takeIf { it.expiresAtMillis > now }?.let { showPlanParticles(player, it) }
                val holdingSelector = isSelector(player.inventory.itemInMainHand) || isSelector(player.inventory.itemInOffHand)
                if (holdingSelector) selectionOrNull(player)?.let { showSelectionOutline(player, it, Particle.FLAME) }
                previewFailurePlayers.remove(playerId)
            } catch (failure: RuntimeException) {
                if (previewFailurePlayers.add(player.uniqueId)) {
                    warn("Builder-tools preview failed for {}: {}", player.name, failure.message)
                }
            }
        }
    }

    private fun showPlanParticles(player: Player, plan: BuilderPlan) {
        if (plan.changes.firstOrNull()?.position?.worldId != player.world.uid) return
        val eye = player.eyeLocation
        val radiusSquared = config.previewRadius * config.previewRadius
        val visible = plan.changes.asSequence().filter { change ->
            val dx = change.position.x + 0.5 - eye.x
            val dy = change.position.y + 0.5 - eye.y
            val dz = change.position.z + 0.5 - eye.z
            dx * dx + dy * dy + dz * dz <= radiusSquared
        }.toList()
        val step = kotlin.math.ceil(visible.size / config.previewMaxPlanParticles.toDouble()).toInt().coerceAtLeast(1)
        visible.asSequence().filterIndexed { index, _ -> index % step == 0 }.take(config.previewMaxPlanParticles).forEach { change ->
            player.spawnParticle(Particle.END_ROD, change.position.x + 0.5, change.position.y + 0.5, change.position.z + 0.5, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun showSelectionOutline(player: Player, selection: BuilderSelection, particle: Particle) {
        if (selection.worldId != player.world.uid) return
        val eye = player.eyeLocation
        BuilderSelectionPreviewGeometry.visibleOutline(
            selection = selection,
            viewerX = eye.x,
            viewerY = eye.y,
            viewerZ = eye.z,
            radius = config.previewRadius,
            spacing = config.previewSpacing,
            maximumPoints = config.previewMaxSelectionParticles,
        ).forEach { point ->
            player.spawnParticle(particle, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun initializeBookContracts() {
        val registry = bookRegistry ?: return
        registry.initialize().whenComplete { _, failure ->
            taskScope.runSync {
                if (failure != null) {
                    bookRegistryFailed = true
                    error("Builder-book MySQL initialization failed", failure)
                    return@runSync
                }
                bookRegistryReady = true
                bookRegistryFailed = false
                info(debugLine.line("event" to "book_registry_ready"))
                recoverOpenBookMints()
                Bukkit.getOnlinePlayers().forEach(::recoverBookDeliveries)
                if (!recovering) reconcileBookReservations()
            }
        }
    }

    private fun recoverOpenBookMints() {
        val registry = bookRegistry ?: return
        val coordinator = bookMintCoordinator ?: return
        registry.openMints().whenComplete { mints, failure ->
            taskScope.runSync {
                if (failure != null || mints == null) {
                    bookRegistryFailed = true
                    bookRegistryReady = false
                    error("Builder-book mint recovery scan failed", failure ?: IllegalStateException("missing mint scan"))
                    return@runSync
                }
                mints.forEach { mint ->
                    coordinator.recover(mint) { result ->
                        when (result) {
                            is BuilderBookMintResult.Issued -> Bukkit.getPlayer(mint.playerId)
                                ?.takeIf(Player::isOnline)
                                ?.let(::recoverBookDeliveries)
                            BuilderBookMintResult.PaymentRejected,
                            BuilderBookMintResult.Refunded,
                            -> releaseBookSource(mint.sourceInstanceId, mint.transactionId)
                            BuilderBookMintResult.ManualReview -> Unit
                            else -> warn(
                                "Builder-book mint recovery incomplete: transaction={} status={} result={}",
                                mint.transactionId,
                                mint.status,
                                result::class.simpleName,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun recoverBookDeliveries(player: Player) {
        if (!bookRegistryReady || !player.isOnline) return
        if (!bookDeliveryRecoveries.add(player.uniqueId)) return
        val registry = bookRegistry ?: run {
            bookDeliveryRecoveries -= player.uniqueId
            return
        }
        val localPending = localPendingBookInstances(player)
        if (localPending.isNotEmpty()) bookLockedPlayers += player.uniqueId
        registry.pendingDeliveries(player.uniqueId).whenComplete { deliveries, failure ->
            taskScope.runSync deliveryLookup@{
                if (!player.isOnline) {
                    bookDeliveryRecoveries -= player.uniqueId
                    return@deliveryLookup
                }
                if (failure != null || deliveries == null) {
                    warn("Builder-book delivery lookup failed for {}: {}", player.name, failure?.message)
                    bookDeliveryRecoveries -= player.uniqueId
                    return@deliveryLookup
                }
                if (deliveries.isEmpty()) {
                    if (localPending.size == 1) {
                        reconcileLocalDeliveredBook(player, localPending.single()) {
                            bookDeliveryRecoveries -= player.uniqueId
                        }
                    }
                    else if (localPending.size > 1) {
                        bookDeliveryWaitingForSpace -= player.uniqueId
                        error("Builder-book local delivery invariant failed for ${player.uniqueId}: ${localPending.size} pending items")
                        send(player, "book.manual-review")
                    } else {
                        bookDeliveryRecoveries -= player.uniqueId
                    }
                    return@deliveryLookup
                }
                bookLockedPlayers += player.uniqueId
                if (deliveries.size != 1) {
                    bookDeliveryWaitingForSpace -= player.uniqueId
                    error("Builder-book delivery invariant failed for ${player.uniqueId}: ${deliveries.size} pending instances")
                    send(player, "book.manual-review")
                    return@deliveryLookup
                }
                val delivery = deliveries.single()
                val instanceId = delivery.instance.instanceId
                if (localPending.isNotEmpty() && (localPending.size != 1 || localPending.single() != instanceId)) {
                    bookDeliveryWaitingForSpace -= player.uniqueId
                    error(
                        "Builder-book pending item does not match authoritative delivery: " +
                            "player=${player.uniqueId} expected=$instanceId local=$localPending",
                    )
                    send(player, "book.manual-review")
                    return@deliveryLookup
                }
                val expectedData = registeredBookData(delivery.blueprint, instanceId, delivery.placement)
                val existing = inventoryBooksWithInstance(player, instanceId)
                if (existing.size > 1 || (existing.size == 1 && existing.single().second != expectedData)) {
                    bookDeliveryWaitingForSpace -= player.uniqueId
                    error("Builder-book recovered item conflicts with local inventory: player=${player.uniqueId} instance=$instanceId")
                    send(player, "book.manual-review")
                    return@deliveryLookup
                }
                if (existing.isEmpty()) {
                    val output = BuildBookItems.create(expectedData)
                    val held = player.inventory.itemInMainHand
                    val heldData = BuildBookCodec.read(held)
                    if (
                        delivery.sourceInstanceId == null && heldData?.draft == true &&
                        heldData.blueprintId == delivery.blueprint.blueprintId
                    ) {
                        if (held.amount > 1 && player.inventory.firstEmpty() == -1) {
                            waitForBookDeliverySpace(player)
                            bookDeliveryRecoveries -= player.uniqueId
                            return@deliveryLookup
                        }
                        replaceOneHeldBook(player, held, output)
                    } else {
                        if (player.inventory.firstEmpty() == -1) {
                            waitForBookDeliverySpace(player)
                            bookDeliveryRecoveries -= player.uniqueId
                            return@deliveryLookup
                        }
                        check(player.inventory.addItem(output).isEmpty()) { "Recovered builder book did not fit after preflight" }
                    }
                    player.updateInventory()
                }
                bookLockedPlayers += player.uniqueId
                bookDeliveryWaitingForSpace -= player.uniqueId
                markBookDelivered(
                    player = player,
                    instanceId = instanceId,
                    transactionId = delivery.instance.transactionId,
                    sourceInstanceId = delivery.sourceInstanceId,
                    recovered = true,
                    finished = { bookDeliveryRecoveries -= player.uniqueId },
                )
            }
        }
    }

    private fun reconcileLocalDeliveredBook(player: Player, instanceId: UUID, finished: () -> Unit) {
        val registry = bookRegistry ?: return finished()
        registry.loadInstance(instanceId).whenComplete { instance, failure ->
            taskScope.runSync {
                if (failure != null) {
                    error(
                        "Builder-book local delivery reconciliation failed for $instanceId",
                        failure,
                    )
                    finished()
                } else if (instance == null) {
                    error("Builder-book local delivery instance is missing: $instanceId")
                    send(player, "book.manual-review")
                } else if (instance.status == BuilderBookInstanceStatus.AVAILABLE) {
                    verifyCompletedLocalDelivery(player, instance, finished)
                } else {
                    error("Builder-book local delivery state is ambiguous: instance=$instanceId status=${instance.status}")
                    send(player, "book.manual-review")
                }
            }
        }
    }

    private fun verifyCompletedLocalDelivery(
        player: Player,
        instance: BuilderBookInstance,
        finished: () -> Unit,
    ) {
        val registry = bookRegistry ?: return finished()
        registry.loadMint(instance.transactionId).whenComplete { mint, failure ->
            taskScope.runSync {
                if (!player.isOnline) {
                    finished()
                    return@runSync
                }
                if (failure != null) {
                    error("Builder-book completed delivery lookup failed for ${instance.instanceId}", failure)
                    finished()
                    return@runSync
                }
                val expected = mint
                    ?.takeIf { it.status == BuilderBookMintStatus.COMPLETED && it.instanceId == instance.instanceId }
                    ?.let { registeredBookData(it.blueprint, instance.instanceId, it.placement) }
                val local = inventoryBooksWithInstance(player, instance.instanceId)
                if (
                    expected == null || local.size != 1 || local.single().second != expected ||
                    !completeLocalBookDelivery(player, instance.instanceId)
                ) {
                    error("Builder-book completed delivery item failed authoritative verification: instance=${instance.instanceId}")
                    send(player, "book.manual-review")
                    return@runSync
                }
                bookLockedPlayers -= player.uniqueId
                bookDeliveryWaitingForSpace -= player.uniqueId
                send(player, "book.delivery-recovered")
                finished()
            }
        }
    }

    private fun reconcileBookReservations() {
        val registry = bookRegistry ?: return
        val serverName = ARC.serverName ?: return
        registry.reservedForServer(serverName).whenComplete { reservations, failure ->
            taskScope.runSync {
                if (failure != null || reservations == null) {
                    recoveryBlocked = true
                    error("Builder-book reservation recovery failed", failure ?: IllegalStateException("missing reservations"))
                    return@runSync
                }
                reservations.forEach { instance -> reconcileBookReservation(instance) }
            }
        }
    }

    private fun reconcileBookReservation(instance: BuilderBookInstance) {
        val registry = bookRegistry ?: return
        val operationId = instance.reservationOperationId ?: return
        val localRecord = committedRecords[operationId] ?: recoveryByPlayer.values.firstOrNull { it.operationId == operationId }
        if (localRecord != null && localRecord.plan.bookInstanceId == instance.instanceId) {
            if (localRecord.phase == BuilderJournalPhase.COMMITTED) {
                registry.consume(instance.instanceId, operationId, System.currentTimeMillis()).whenComplete { consumed, failure ->
                    taskScope.runSync {
                        if (failure != null || consumed != true) {
                            recoveryBlocked = true
                            error(
                                "Builder-book committed reservation could not be consumed: $operationId",
                                failure ?: IllegalStateException("consume rejected"),
                            )
                        }
                    }
                }
            } else if (recoveryByPlayer.values.none { it.operationId == operationId }) {
                releaseRecoveredBookReservation(registry, instance.instanceId, operationId)
            }
            return
        }
        registry.loadMint(operationId).whenComplete { mint, failure ->
            taskScope.runSync {
                if (failure != null) {
                    recoveryBlocked = true
                    error("Builder-book reservation owner lookup failed: $operationId", failure)
                } else if (mint != null && mint.sourceInstanceId == instance.instanceId) {
                    if (mint.status.terminal) {
                        releaseRecoveredBookReservation(registry, instance.instanceId, operationId)
                    }
                } else {
                    releaseRecoveredBookReservation(registry, instance.instanceId, operationId)
                }
            }
        }
    }

    private fun releaseRecoveredBookReservation(registry: BuilderBookRegistry, instanceId: UUID, operationId: UUID) {
        registry.release(instanceId, operationId).whenComplete { released, failure ->
            taskScope.runSync {
                if (failure != null || released != true) {
                    recoveryBlocked = true
                    error(
                        "Builder-book recovered reservation could not be released: $operationId",
                        failure ?: IllegalStateException("release rejected"),
                    )
                }
            }
        }
    }

    private fun loadRecoveryState() {
        writeAsync(
            action = { journal.loadAll() },
            callback = { loaded, failure ->
                if (failure != null || loaded == null) {
                    recoveryBlocked = true
                    recovering = false
                    error("Builder-tools journal recovery failed", failure ?: IllegalStateException("missing journal result"))
                    return@writeAsync
                }
                try {
                    val interruptedByPlayer = loaded.map { it.value }.filter { it.phase == BuilderJournalPhase.PREPARED || it.phase == BuilderJournalPhase.APPLYING }.groupBy { it.playerId }
                    require(interruptedByPlayer.values.all { it.size == 1 }) { "Multiple interrupted builder-tools operations exist for one player" }
                    loaded.map { it.value }.filter { it.phase == BuilderJournalPhase.COMMITTED || it.phase == BuilderJournalPhase.UNDONE }.forEach { record ->
                        committedRecords[record.operationId] = record
                        record.plan.sourceRecordId?.let { consumedUndoSources += it }
                    }
                    val acknowledgeNow = interruptedByPlayer.values.flatten().filter(::recoverRecord)
                    if (acknowledgeNow.isEmpty()) {
                        finishRecovery(loaded.size)
                    } else {
                        writeAsync(
                            action = {
                                acknowledgeNow.forEach { record ->
                                    check(journal.acknowledge(record.operationId)) {
                                        "Builder-tools recovery acknowledgement failed for ${record.operationId}"
                                    }
                                }
                            },
                            callback = { _, acknowledgeFailure ->
                                if (acknowledgeFailure != null) {
                                    recoveryBlocked = true
                                    recovering = false
                                    error("Builder-tools recovery acknowledgement failed", acknowledgeFailure)
                                } else {
                                    finishRecovery(loaded.size)
                                }
                            },
                        )
                    }
                } catch (recoveryFailure: Throwable) {
                    recoveryBlocked = true
                    recovering = false
                    error("Builder-tools recovery stopped on ambiguous state", recoveryFailure)
                }
            },
        )
    }

    private fun finishRecovery(recordCount: Int) {
        recovering = false
        cleanupOldRecords()
        if (bookRegistryReady) reconcileBookReservations()
        info(debugLine.line("event" to "recovery_ready", "records" to recordCount, "pending_players" to recoveryByPlayer.size))
    }

    /** Returns true when the durable record may be acknowledged immediately. */
    private fun recoverRecord(record: BuilderJournalRecord): Boolean {
        record.plan.changes.asReversed().forEach { change ->
            val world = Bukkit.getWorld(change.position.worldId)
                ?: throw IllegalStateException("Builder-tools recovery world is unavailable")
            val block = block(world, change.position)
            when (BuilderRecoveryRules.action(record.phase, block.blockData.asString, change.beforeBlockData, change.afterBlockData)) {
                BuilderRecoveryAction.KEEP_BEFORE -> Unit
                BuilderRecoveryAction.RESTORE_BEFORE -> {
                    val after = Bukkit.createBlockData(change.afterBlockData)
                    val before = Bukkit.createBlockData(change.beforeBlockData)
                    block.setBlockData(before, false)
                    coreProtect?.logChange(record.playerName, block.location, after, before)
                }
            }
        }
        if (record.phase == BuilderJournalPhase.PREPARED) return true
        val player = Bukkit.getPlayer(record.playerId)
        if (player?.isOnline == true && !player.isDead) {
            stateService.restoreInventoryAndVerify(player, stateCodec.decode(record.inventoryBefore))
            return true
        } else {
            recoveryByPlayer[record.playerId] = record
            return false
        }
    }

    private fun markSourceUndone(sourceId: UUID) {
        val source = committedRecords[sourceId] ?: return
        val updated = source.copy(phase = BuilderJournalPhase.UNDONE, updatedAtMillis = System.currentTimeMillis())
        writeAsync(action = { journal.commit(updated) }, callback = { durable, failure ->
            if (durable != null && failure == null) committedRecords[sourceId] = durable
        })
    }

    private fun cleanupOldRecords() {
        val cutoff = System.currentTimeMillis() - config.journalRetention.toMillis()
        val removable = committedRecords.values.filter { (it.committedAtMillis ?: Long.MAX_VALUE) < cutoff }
        removable.forEach { record ->
            committedRecords.remove(record.operationId)
            writeAsync(action = { journal.acknowledge(record.operationId) }, callback = { _, failure ->
                if (failure != null) committedRecords[record.operationId] = record
            })
        }
    }

    private fun <T> writeAsync(action: () -> T, callback: (T?, Throwable?) -> Unit) {
        if (closed) return
        try {
            storageExecutor.submit {
                val result = runCatching(action)
                taskScope.runSync { callback(result.getOrNull(), result.exceptionOrNull()) }
            }
        } catch (failure: RejectedExecutionException) {
            taskScope.runSync { callback(null, failure) }
        }
    }

    private fun send(player: Player, path: String, values: Map<String, Component> = emptyMap()) {
        player.sendMessage(messages.render(path, locale(player), values))
    }

    private fun locale(player: Player): String = player.locale().toLanguageTag()

    private fun java.time.Duration.toTicks(): Long = (toMillis() / 50L).coerceAtLeast(1L)

    private fun filterPrefix(values: List<String>, raw: String?): List<String> {
        val prefix = raw.orEmpty().lowercase(Locale.ROOT)
        return values.filter { it.startsWith(prefix) }.take(100)
    }

    private fun safeMaterialNames(): List<String> = Material.entries.asSequence().filter(safety::isSafeMaterial).map { it.name.lowercase(Locale.ROOT) }.toList()

    private fun leafNames(): List<String> = Material.entries.asSequence().filter(safety::isLeaf).map { it.name.lowercase(Locale.ROOT) }.toList()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (isPlayerLocked(player.uniqueId)) {
            event.isCancelled = true
            return
        }
        val item = event.item ?: return
        val clicked = event.clickedBlock ?: return
        if (isCrownBrush(item)) {
            event.isCancelled = true
            try {
                ensureAvailable(player)
                ensureFeaturePermission(player, BuilderFeature.CROWN)
                when (event.action) {
                    org.bukkit.event.block.Action.LEFT_CLICK_BLOCK -> {
                        setPosition(player, clicked.getRelative(event.blockFace).location, first = true)
                        prepareCrownPlan(player, crownSettings(player))
                        crownBrushAnchors[player.uniqueId] = checkNotNull(selections[player.uniqueId]?.first)
                    }
                    org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK -> {
                        val relative = clicked.getRelative(event.blockFace)
                        val actual = BuilderBlockPos(relative.world.uid, relative.x, relative.y, relative.z)
                        if (actual != crownBrushAnchors[player.uniqueId]) throw UserFailure("crown.same-face")
                        confirm(player)
                    }
                    else -> Unit
                }
            } catch (failure: UserFailure) {
                send(player, failure.path, failure.values)
            }
            return
        }
        if (!isSelector(item)) return
        val first = when (event.action) {
            org.bukkit.event.block.Action.LEFT_CLICK_BLOCK -> true
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK -> false
            else -> return
        }
        event.isCancelled = true
        try {
            setPosition(player, clicked.location, first)
        } catch (failure: UserFailure) {
            send(player, failure.path, failure.values)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val record = recoveryByPlayer[event.player.uniqueId]
        if (record != null) {
            try {
                stateService.restoreInventoryAndVerify(event.player, stateCodec.decode(record.inventoryBefore))
                recoveryByPlayer.remove(event.player.uniqueId)
                releasePlanBookReservation(record.plan)
                writeAsync(action = {
                    check(journal.acknowledge(record.operationId)) { "Builder-tools player recovery acknowledgement failed" }
                }, callback = { _, failure ->
                    if (failure != null) recoveryByPlayer[event.player.uniqueId] = record
                })
                info(debugLine.line("event" to "player_recovered", "operation" to record.operationId, "player" to record.playerId))
            } catch (failure: Throwable) {
                recoveryBlocked = true
                error("Builder-tools player recovery failed for ${record.operationId}", failure)
            }
        }
        recoverBookDeliveries(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        discardPendingPlan(event.player.uniqueId)
        selections.remove(event.player.uniqueId)
        clipboards.remove(event.player.uniqueId)
        pendingBookMints.remove(event.player.uniqueId)
        crownSessions.clear(event.player.uniqueId)
        val operation = activeOperations[event.player.uniqueId] ?: return
        if (operation.uncertainCommit) return
        operation.cancelled = true
        if (operation.appliedChanges > 0 || operation.inventoryMutated) rollback(event.player, operation, "disconnect")
        else acknowledgeCancelled(operation)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        if ((event.whoClicked as? Player)?.uniqueId?.let(::isPlayerLocked) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if ((event.whoClicked as? Player)?.uniqueId?.let(::isPlayerLocked) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDrop(event: PlayerDropItemEvent) {
        if (isPlayerLocked(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPickup(event: EntityPickupItemEvent) {
        if ((event.entity as? Player)?.uniqueId?.let(::isPlayerLocked) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        if (isPlayerLocked(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onHeldSlot(event: PlayerItemHeldEvent) {
        if (isPlayerLocked(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCommandDuringOperation(event: PlayerCommandPreprocessEvent) {
        if (!isPlayerLocked(event.player.uniqueId)) return
        if (event.player.uniqueId in bookLockedPlayers) {
            event.isCancelled = true
            return
        }
        val normalized = event.message.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
        val safeControl = normalized.firstOrNull() == "/builder" &&
            normalized.getOrNull(1) in setOf("status", "cancel", "stop")
        if (!safeControl) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDamage(event: EntityDamageEvent) {
        if ((event.entity as? Player)?.uniqueId?.let(::isPlayerLocked) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) {
        if (isPlayerLocked(event.player.uniqueId) || isLocked(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlace(event: BlockPlaceEvent) {
        if (isPlayerLocked(event.player.uniqueId) || isLocked(event.blockPlaced)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPhysics(event: BlockPhysicsEvent) {
        if (isLocked(event.block) || isLocked(event.sourceBlock)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onFromTo(event: BlockFromToEvent) {
        if (isLocked(event.block) || isLocked(event.toBlock)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onGrow(event: BlockGrowEvent) { if (isLocked(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onSpread(event: BlockSpreadEvent) { if (isLocked(event.block) || isLocked(event.source)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onFade(event: BlockFadeEvent) { if (isLocked(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBurn(event: BlockBurnEvent) { if (isLocked(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onForm(event: BlockFormEvent) { if (isLocked(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityChange(event: EntityChangeBlockEvent) { if (isLocked(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityExplode(event: EntityExplodeEvent) { if (event.blockList().any(::isLocked)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlockExplode(event: BlockExplodeEvent) { if (event.blockList().any(::isLocked) || isLocked(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any { isLocked(it) || isLocked(it.getRelative(event.direction)) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any { isLocked(it) || isLocked(it.getRelative(event.direction)) }) event.isCancelled = true
    }

    private fun isLocked(block: Block): Boolean = BuilderBlockPos(block.world.uid, block.x, block.y, block.z) in lockedBlocks

    private fun isPlayerLocked(playerId: UUID): Boolean = playerId in activeOperations || playerId in bookLockedPlayers

    private fun isSelector(item: ItemStack): Boolean {
        if (item.itemMeta?.persistentDataContainer?.has(wandKey, PersistentDataType.BYTE) == true) return true
        if (item.type != Material.ECHO_SHARD) return false
        return plainDisplayName(item) == "Инструмент демонтажа"
    }

    private fun isCrownBrush(item: ItemStack): Boolean {
        if (item.itemMeta?.persistentDataContainer?.has(crownBrushKey, PersistentDataType.BYTE) == true) return true
        return item.type == Material.BRUSH && plainDisplayName(item) == "Кисть крон"
    }

    private fun plainDisplayName(item: ItemStack): String? = item.itemMeta?.displayName()?.let {
        PlainTextComponentSerializer.plainText().serialize(it)
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        taskScope.close()
        activeOperations.values.toList().forEach { operation ->
            if (operation.uncertainCommit) {
                finishOperation(operation)
                return@forEach
            }
            val player = Bukkit.getPlayer(operation.record.playerId)
            if (player != null && (operation.appliedChanges > 0 || operation.inventoryMutated)) rollback(player, operation, "plugin shutdown")
            else finishOperation(operation)
        }
        storageExecutor.shutdownNow()
        pendingPlans.clear()
        pendingBookMints.clear()
        bookLockedPlayers.clear()
        bookDeliveryWaitingForSpace.clear()
        bookDeliveryRecoveries.clear()
        bookMintCoordinator?.clear()
        bookRegistry?.close()
        shop.close()
        crownBrushAnchors.clear()
        selections.clear()
        clipboards.clear()
        crownSessions.clear()
        previewFailurePlayers.clear()
    }
}
