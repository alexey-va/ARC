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
import ru.arc.core.LifecycleTaskScope
import ru.arc.hooks.HookRegistry
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.text.LocalizedMiniMessage
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.ceil

internal class BuilderToolsRuntime(
    private val plugin: ARC,
    private val config: BuilderToolsConfig,
) : Listener, CommandExecutor, TabCompleter, AutoCloseable {
    private data class SelectionDraft(var first: BuilderBlockPos? = null, var second: BuilderBlockPos? = null)

    private data class ActiveOperation(
        var record: BuilderJournalRecord,
        var appliedChanges: Int = 0,
        var inventoryMutated: Boolean = false,
        var cancelled: Boolean = false,
        var uncertainCommit: Boolean = false,
    )

    private class UserFailure(val path: String, val values: Map<String, Component> = emptyMap()) : RuntimeException(path)

    private val messages: LocalizedMiniMessage = config.messages()
    private val safety = BuilderBlockSafety(plugin, config.replaceableMaterials)
    private val coreProtect = BuilderCoreProtectBridge.resolve()
    private val journal = BuilderJournalStore(plugin.dataPath, config.maxChanges)
    private val stateService = PaperPlayerStateService()
    private val stateCodec = PaperPlayerStateCodec()
    private val taskScope = LifecycleTaskScope()
    private val storageExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "arc-builder-tools-storage").apply { isDaemon = true }
    }
    private val debugLine = StructuredDebugLine("ARC_BUILDER_TOOLS")
    private val wandKey = org.bukkit.NamespacedKey(plugin, "builder_selector")
    private val crownBrushKey = org.bukkit.NamespacedKey(plugin, "crown_brush")
    private val selections = mutableMapOf<UUID, SelectionDraft>()
    private val clipboards = mutableMapOf<UUID, BuilderClipboard>()
    private val pendingPlans = mutableMapOf<UUID, BuilderPlan>()
    private val activeOperations = mutableMapOf<UUID, ActiveOperation>()
    private val lockedBlocks = mutableMapOf<BuilderBlockPos, UUID>()
    private val committedRecords = mutableMapOf<UUID, BuilderJournalRecord>()
    private val recoveryByPlayer = mutableMapOf<UUID, BuilderJournalRecord>()
    private val consumedUndoSources = mutableSetOf<UUID>()
    private var recovering = true
    private var recoveryBlocked = false
    private var closed = false

    init {
        require(!config.requireLands || HookRegistry.landsHook != null) {
            "Builder-tools requires the active Lands integration"
        }
        require(!config.requireWorldGuard || HookRegistry.wgHook != null) {
            "Builder-tools requires the active WorldGuard integration"
        }
        require(!config.requireCoreProtect || coreProtect != null) {
            "Builder-tools requires the active CoreProtect API"
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
        loadRecoveryState()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage("Builder tools are player-only.")
            return true
        }
        try {
            when (command.name.lowercase(Locale.ROOT)) {
                "deconstruction" -> handleLegacyDeconstruction(player, args)
                "crown" -> handleLegacyCrown(player, args)
                else -> handleBuilder(player, args)
            }
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
        if (command.name.equals("deconstruction", true)) {
            return filterPrefix(listOf("wand", "start", "confirm", "stop", "status", "pause", "resume"), args.lastOrNull())
        }
        if (command.name.equals("crown", true)) {
            if (args.size <= 1) return filterPrefix(leafNames(), args.lastOrNull())
            return if (args.size == 2) filterPrefix((3..10).map(Int::toString), args.last()) else emptyList()
        }
        if (args.size == 1) {
            return filterPrefix(
                listOf("help", "wand", "pos1", "pos2", "fill", "copy", "paste", "deconstruct", "crown", "confirm", "cancel", "undo", "status"),
                args[0],
            )
        }
        if (args.size == 2 && args[0].equals("fill", true)) return filterPrefix(safeMaterialNames(), args[1])
        if (args.size == 2 && args[0].equals("crown", true)) return filterPrefix(leafNames(), args[1])
        if (args.size == 3 && args[0].equals("crown", true)) return filterPrefix((3..10).map(Int::toString), args[2])
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
            "paste" -> preparePlan(player, planPaste(player))
            "deconstruct" -> preparePlan(player, planDeconstruct(player))
            "crown" -> {
                ensureFeaturePermission(player, "arc.buildertools.crown", "arc.crown")
                val material = args.getOrNull(1)?.let { materialArgument(player, it) } ?: Material.OAK_LEAVES
                val radius = args.getOrNull(2)?.toIntOrNull() ?: 5
                preparePlan(player, planCrown(player, material, radius))
            }
            "confirm" -> confirm(player)
            "cancel", "stop" -> cancelPlan(player)
            "undo" -> prepareUndo(player)
            "status" -> showStatus(player)
            else -> messages.renderLines("help", locale(player)).forEach(player::sendMessage)
        }
    }

    private fun handleLegacyDeconstruction(player: Player, args: Array<out String>) {
        ensureAvailable(player)
        ensureFeaturePermission(player, "arc.buildertools.deconstruct", "arc.deconstruction")
        when (args.firstOrNull()?.lowercase(Locale.ROOT) ?: "status") {
            "wand" -> giveWand(player)
            "start" -> preparePlan(player, planDeconstruct(player))
            "confirm" -> confirm(player)
            "stop" -> cancelPlan(player)
            "status" -> showStatus(player)
            "pause", "resume" -> send(player, "legacy.atomic")
            else -> showStatus(player)
        }
    }

    private fun handleLegacyCrown(player: Player, args: Array<out String>) {
        ensureAvailable(player)
        ensureFeaturePermission(player, "arc.buildertools.crown", "arc.crown")
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "confirm" -> return confirm(player)
            "undo" -> return prepareUndo(player)
            "cancel", "stop" -> return cancelPlan(player)
            "status" -> return showStatus(player)
            "brush", "wand" -> return giveCrownBrush(player)
            "help", "settings", "palette", "shape", "radius", "density", "noise" -> {
                messages.renderLines("help", locale(player)).forEach(player::sendMessage)
                return
            }
        }
        val material = if (args.isEmpty()) Material.OAK_LEAVES else materialArgument(player, args[0])
        val radius = args.getOrNull(1)?.toIntOrNull() ?: 5
        preparePlan(player, planCrown(player, material, radius))
    }

    private fun ensureAvailable(player: Player) {
        if (!hasUsePermission(player)) throw UserFailure("errors.no-permission")
        if (recovering || recoveryBlocked || player.uniqueId in recoveryByPlayer) throw UserFailure("errors.recovering")
        if (player.gameMode != GameMode.SURVIVAL) throw UserFailure("errors.survival-only")
        if (player.world.name.lowercase(Locale.ROOT) !in config.allowedWorlds) throw UserFailure("errors.world-not-allowed")
    }

    private fun ensureFeaturePermission(player: Player, modern: String, legacy: String) {
        if (!player.hasPermission(modern) && !player.hasPermission(legacy) && !player.hasPermission("arc.buildertools.use")) {
            throw UserFailure("errors.no-permission")
        }
    }

    private fun hasUsePermission(player: Player): Boolean =
        player.hasPermission("arc.buildertools.use") || player.hasPermission("arc.deconstruction") || player.hasPermission("arc.crown")

    private fun giveWand(player: Player) {
        if (player.inventory.firstEmpty() == -1) throw UserFailure("wand.inventory-full")
        val wand = ItemStack(Material.ECHO_SHARD)
        wand.editMeta { meta ->
            meta.displayName(messages.render("wand.name", locale(player)))
            meta.lore(messages.renderLines("wand.lore", locale(player)))
            meta.persistentDataContainer.set(wandKey, PersistentDataType.BYTE, 1)
        }
        player.inventory.addItem(wand)
        send(player, "wand.received")
    }

    private fun giveCrownBrush(player: Player) {
        if (player.inventory.firstEmpty() == -1) throw UserFailure("crown-brush.inventory-full")
        val brush = ItemStack(Material.BRUSH)
        brush.editMeta { meta ->
            meta.displayName(messages.render("crown-brush.name", locale(player)))
            meta.lore(messages.renderLines("crown-brush.lore", locale(player)))
            meta.persistentDataContainer.set(crownBrushKey, PersistentDataType.BYTE, 1)
        }
        player.inventory.addItem(brush)
        send(player, "crown-brush.received")
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
        val permissionLimit = listOf(100, 80, 60, 40, 20).firstOrNull { player.hasPermission("arc.deconstruction.size.$it") } ?: 20
        return minOf(permissionLimit, config.absoluteMaxAxis)
    }

    private fun planFill(player: Player, material: Material): BuilderPlan {
        ensureFeaturePermission(player, "arc.buildertools.fill", "arc.deconstruction")
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
        val cost = BuilderItemCodec.aggregate(listOf(ItemStack(material, changes.size)))
        return newPlan(player, BuilderPlanKind.FILL, changes, cost, emptyList())
    }

    private fun copySelection(player: Player) {
        ensureFeaturePermission(player, "arc.buildertools.copy", "arc.deconstruction")
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
        clipboards[player.uniqueId] = BuilderClipboard(blocks, now, now + config.clipboardTtl.toMillis()).validated(config.maxClipboardBlocks)
        send(player, "clipboard.saved", mapOf("count" to messages.literal(blocks.size)))
    }

    private fun planPaste(player: Player): BuilderPlan {
        ensureFeaturePermission(player, "arc.buildertools.paste", "arc.deconstruction")
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
            costs += BuilderPlacementCost.item(after)
            BuilderBlockChange(position, block.blockData.asString, after.asString)
        }
        requireChanges(changes)
        return newPlan(player, BuilderPlanKind.PASTE, changes, BuilderItemCodec.aggregate(costs), emptyList())
    }

    private fun planDeconstruct(player: Player): BuilderPlan {
        ensureFeaturePermission(player, "arc.buildertools.deconstruct", "arc.deconstruction")
        val selection = requiredSelection(player)
        val world = requireWorld(selection.worldId)
        val tool = player.inventory.itemInMainHand.clone()
        if (tool.type.isAir || tool.type.maxDurability <= 0) throw UserFailure("errors.tool")
        val drops = mutableListOf<ItemStack>()
        val air = Material.AIR.createBlockData().asString
        val changes = selection.positionsTopDown().mapNotNull { position ->
            val block = block(world, position)
            if (block.type.isAir) return@mapNotNull null
            if (safety.isReplaceable(block)) return@mapNotNull null
            if (!safety.isSafeExisting(block)) throw unsafeBlock(block)
            if (!block.isPreferredTool(tool)) throw UserFailure("errors.tool")
            ensureMutable(player, block)
            drops += block.getDrops(tool, player).map(ItemStack::clone)
            BuilderBlockChange(position, block.blockData.asString, air)
        }.take(config.maxChanges + 1).toList()
        requireChanges(changes)
        val fingerprint = BuilderItemCodec.encodePrototype(tool)
        val rewards = BuilderItemCodec.aggregate(drops)
        if (!BuilderInventory.canApply(player, emptyList(), rewards, fingerprint, changes.size)) throw UserFailure("errors.inventory")
        return newPlan(
            player = player,
            kind = BuilderPlanKind.DECONSTRUCT,
            changes = changes,
            costs = emptyList(),
            rewards = rewards,
            toolFingerprint = fingerprint,
            toolDamage = changes.size,
        )
    }

    private fun planCrown(player: Player, material: Material, radius: Int): BuilderPlan {
        if (!safety.isLeaf(material) || radius !in 3..10) throw UserFailure("errors.material")
        val center = selections[player.uniqueId]?.first ?: throw UserFailure("errors.selection-missing")
        val world = requireWorld(center.worldId)
        val data = placementData(material)
        val seed = player.uniqueId.mostSignificantBits xor player.uniqueId.leastSignificantBits xor center.x.toLong().shl(32) xor center.z.toLong()
        val changes = BuilderCrownGeometry.offsets(radius, seed).mapNotNull { (dx, dy, dz) ->
            val position = BuilderBlockPos(center.worldId, center.x + dx, center.y + dy, center.z + dz).validated()
            val block = block(world, position)
            if (block.blockData.asString == data.asString) return@mapNotNull null
            if (!safety.isReplaceable(block)) return@mapNotNull null
            ensureMutable(player, block)
            BuilderBlockChange(position, block.blockData.asString, data.asString)
        }.take(config.maxChanges + 1).toList()
        requireChanges(changes)
        val costs = BuilderItemCodec.aggregate(listOf(ItemStack(material, changes.size)))
        return newPlan(player, BuilderPlanKind.CROWN, changes, costs, emptyList())
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
            createdAtMillis = now,
            expiresAtMillis = now + config.planTtl.toMillis(),
        ).validated(config.maxChanges)
    }

    private fun preparePlan(player: Player, plan: BuilderPlan) {
        if (player.uniqueId in activeOperations) throw UserFailure("errors.busy")
        val used = hourlyUsage(player.uniqueId, System.currentTimeMillis())
        if (plan.kind != BuilderPlanKind.UNDO && used + plan.changes.size > hourlyLimit(player)) {
            throw UserFailure("errors.plan-failed", mapOf("reason" to messages.literal("hourly change limit")))
        }
        if (!BuilderInventory.canApply(player, plan.costs, plan.rewards, plan.toolFingerprintBase64, plan.toolDamage)) {
            throw UserFailure("errors.inventory")
        }
        pendingPlans[player.uniqueId] = plan
        showPlanParticles(player, plan)
        send(
            player,
            "plan.ready",
            mapOf(
                "kind" to messages.literal(plan.kind.name.lowercase(Locale.ROOT)),
                "count" to messages.literal(plan.changes.size),
                "cost" to messages.literal(itemsSummary(plan.costs)),
                "reward" to messages.literal(itemsSummary(plan.rewards)),
                "seconds" to messages.literal(config.planTtl.seconds),
            ),
        )
        val planId = plan.id
        taskScope.runLater(config.planTtl.toTicks()) {
            if (pendingPlans[player.uniqueId]?.id == planId) pendingPlans.remove(player.uniqueId)
        }
    }

    private fun confirm(player: Player) {
        ensureAvailable(player)
        if (player.uniqueId in activeOperations) throw UserFailure("errors.busy")
        val plan = pendingPlans.remove(player.uniqueId) ?: throw UserFailure("errors.expired")
        if (plan.expiresAtMillis <= System.currentTimeMillis()) throw UserFailure("errors.expired")
        revalidatePlan(player, plan)
        if (!BuilderInventory.canApply(player, plan.costs, plan.rewards, plan.toolFingerprintBase64, plan.toolDamage)) {
            throw UserFailure("errors.inventory")
        }
        if (!lock(plan)) throw UserFailure("errors.busy")
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
        val operation = ActiveOperation(record)
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
                finishOperation(operation)
                send(
                    player,
                    "operation.completed",
                    mapOf("kind" to messages.literal(durable.plan.kind.name.lowercase(Locale.ROOT)), "count" to messages.literal(durable.plan.changes.size)),
                )
                info(debugLine.line("event" to "committed", "operation" to durable.operationId, "player" to durable.playerId, "kind" to durable.plan.kind, "blocks" to durable.plan.changes.size))
                durable.plan.sourceRecordId?.let { markSourceUndone(it) }
                cleanupOldRecords()
            },
        )
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
        writeAsync(action = { journal.acknowledge(operation.record.operationId) }, callback = { _, _ -> })
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

    private fun cancelPlan(player: Player) {
        val active = activeOperations[player.uniqueId]
        if (active != null) {
            active.cancelled = true
            if (active.appliedChanges > 0 || active.inventoryMutated) rollback(player, active, "cancelled")
            else send(player, "plan.cancelled")
            return
        }
        if (pendingPlans.remove(player.uniqueId) != null) send(player, "plan.cancelled") else throw UserFailure("errors.expired")
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
        val plan = pendingPlans[player.uniqueId]
        val selection = selectionOrNull(player)
        when {
            active != null -> send(player, "status.plan", mapOf("kind" to messages.literal(active.record.plan.kind), "count" to messages.literal(active.appliedChanges), "total" to messages.literal(active.record.plan.changes.size)))
            plan != null -> send(player, "status.plan", mapOf("kind" to messages.literal(plan.kind), "count" to messages.literal(0), "total" to messages.literal(plan.changes.size)))
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
        if (HookRegistry.wgHook?.canBuild(player, block.location) == false) throw UserFailure("errors.protection")
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
        listOf(200_000, 150_000, 100_000, 50_000, 20_000)
            .firstOrNull {
                player.hasPermission("arc.deconstruction.hourly.$it") || player.hasPermission("arc.deconstruction.limit.$it")
            }
            ?.coerceAtMost(200_000)
            ?: config.baseHourlyChanges

    private fun itemsSummary(items: List<BuilderItemAmount>): String = if (items.isEmpty()) {
        "—"
    } else {
        items.take(5).joinToString(", ") { "${it.amount}×${it.materialKey.removePrefix("minecraft:")}" } +
            if (items.size > 5) " +${items.size - 5}" else ""
    }

    private fun showPlanParticles(player: Player, plan: BuilderPlan) {
        val step = ceil(plan.changes.size / 180.0).toInt().coerceAtLeast(1)
        plan.changes.asSequence().filterIndexed { index, _ -> index % step == 0 }.take(180).forEach { change ->
            player.spawnParticle(Particle.END_ROD, change.position.x + 0.5, change.position.y + 0.5, change.position.z + 0.5, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun showSelectionOutline(player: Player, selection: BuilderSelection, particle: Particle) {
        val minX = selection.minX.toDouble()
        val maxX = selection.maxX + 1.0
        val minY = selection.minY.toDouble()
        val maxY = selection.maxY + 1.0
        val minZ = selection.minZ.toDouble()
        val maxZ = selection.maxZ + 1.0
        val points = mutableSetOf<Triple<Double, Double, Double>>()
        fun edge(a: Double, b: Double, fixed1: Double, fixed2: Double, axis: Int) {
            val steps = ceil((b - a) * 2).toInt().coerceAtLeast(1)
            for (index in 0..steps) {
                val value = a + (b - a) * index / steps
                points += when (axis) {
                    0 -> Triple(value, fixed1, fixed2)
                    1 -> Triple(fixed1, value, fixed2)
                    else -> Triple(fixed1, fixed2, value)
                }
            }
        }
        for (y in listOf(minY, maxY)) for (z in listOf(minZ, maxZ)) edge(minX, maxX, y, z, 0)
        for (x in listOf(minX, maxX)) for (z in listOf(minZ, maxZ)) edge(minY, maxY, x, z, 1)
        for (x in listOf(minX, maxX)) for (y in listOf(minY, maxY)) edge(minZ, maxZ, x, y, 2)
        points.take(300).forEach { (x, y, z) -> player.spawnParticle(particle, x, y, z, 1, 0.0, 0.0, 0.0, 0.0) }
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
        if (player.uniqueId in activeOperations) {
            event.isCancelled = true
            return
        }
        val item = event.item ?: return
        val clicked = event.clickedBlock ?: return
        if (isCrownBrush(item)) {
            event.isCancelled = true
            try {
                ensureAvailable(player)
                ensureFeaturePermission(player, "arc.buildertools.crown", "arc.crown")
                when (event.action) {
                    org.bukkit.event.block.Action.LEFT_CLICK_BLOCK -> {
                        setPosition(player, clicked.getRelative(event.blockFace).location, first = true)
                        preparePlan(player, planCrown(player, Material.OAK_LEAVES, 5))
                    }
                    org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK -> confirm(player)
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
        val record = recoveryByPlayer[event.player.uniqueId] ?: return
        try {
            stateService.restoreInventoryAndVerify(event.player, stateCodec.decode(record.inventoryBefore))
            recoveryByPlayer.remove(event.player.uniqueId)
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

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        pendingPlans.remove(event.player.uniqueId)
        val operation = activeOperations[event.player.uniqueId] ?: return
        if (operation.uncertainCommit) return
        operation.cancelled = true
        if (operation.appliedChanges > 0 || operation.inventoryMutated) rollback(event.player, operation, "disconnect")
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        if ((event.whoClicked as? Player)?.uniqueId in activeOperations) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if ((event.whoClicked as? Player)?.uniqueId in activeOperations) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDrop(event: PlayerDropItemEvent) {
        if (event.player.uniqueId in activeOperations) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPickup(event: EntityPickupItemEvent) {
        if ((event.entity as? Player)?.uniqueId in activeOperations) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        if (event.player.uniqueId in activeOperations) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onHeldSlot(event: PlayerItemHeldEvent) {
        if (event.player.uniqueId in activeOperations) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCommandDuringOperation(event: PlayerCommandPreprocessEvent) {
        if (event.player.uniqueId !in activeOperations) return
        val normalized = event.message.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
        val safeControl = normalized.firstOrNull() in setOf("/builder", "/buildtools", "/deconstruction") &&
            normalized.getOrNull(1) in setOf("status", "cancel", "stop")
        if (!safeControl) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDamage(event: EntityDamageEvent) {
        if ((event.entity as? Player)?.uniqueId in activeOperations) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) {
        if (event.player.uniqueId in activeOperations || isLocked(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlace(event: BlockPlaceEvent) {
        if (event.player.uniqueId in activeOperations || isLocked(event.blockPlaced)) event.isCancelled = true
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
        selections.clear()
        clipboards.clear()
    }
}
