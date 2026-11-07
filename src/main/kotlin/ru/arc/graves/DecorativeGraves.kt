package ru.arc.graves

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Level

/** Main-thread catalog and dialogs; only immutable snapshots cross the background storage boundary. */
internal class DecorativeGraves(
    private val plugin: Plugin,
    private val settings: GravesSettings,
    private val server: String,
    private val store: CompletableFuture<DecorativeGraveStore>,
    private val worker: Executor,
    private val epitaphs: GraveEpitaphs,
    private val recoverable: () -> List<GraveView>,
    private val refresh: () -> Unit,
    private val appearance: (DecorativeGraveRecord) -> GraveAppearance,
    private val showDialog: (Player, PaperDialogScreen, (() -> Unit)?, () -> Unit) -> Unit = { player, screen, reopen, onDismiss ->
        ArcMenus.openDialog(player, screen, reopen = reopen, onDismiss = onDismiss)
    },
    private val beginFlow: (Player) -> Unit = ArcMenus::beginDialogFlow,
    private val closeDialog: (Player) -> Unit = ArcMenus::closeDialog,
    private val currentPlayer: (UUID) -> Player? = Bukkit::getPlayer,
    private val onHit: (UUID, Location, GraveAppearance, Int) -> Unit = { _, _, _, _ -> },
    private val onBreak: (Location, GraveAppearance) -> Unit = { _, _ -> },
) : Listener {
    private class Draft(val player: Player, val target: DecorativeGraveTarget, val original: DecorativeGraveRecord?,
                        val id: UUID, var style: GraveStyle, var epitaph: String, var signature: String) {
        var visit = 0L
    }
    private val tasks = LifecycleTaskScope()
    private val drafts = mutableMapOf<UUID, Draft>()
    private val blocks = DecorativeGraveBlocks(plugin, ::height)
    private val hits = mutableMapOf<UUID, Int>()
    private var records = emptyList<DecorativeGraveRecord>()
    private var ready = false
    private var writing = false
    private var closed = false
    private var drain = CompletableFuture.completedFuture<Void>(null)

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        val loaded = store.thenCompose { value -> CompletableFuture.supplyAsync(value::load, worker) }
        drain = loaded.thenApply { null }
        loaded.whenCompleteSync(tasks) { loadedRecords, failure ->
            if (closed) return@whenCompleteSync
            if (failure != null) plugin.logger.log(Level.WARNING, "Could not load decorative graves; recovery remains available", failure)
            records = loadedRecords.orEmpty().toList()
            blocks.start(records)
            ready = failure == null
            refresh()
        }
    }

    fun hasJournaledBarrier(block: Block): Boolean = blocks.hasJournaledBarrier(block)

    fun hasBarrier(id: UUID, block: Block): Boolean = blocks.record(block)?.id == id

    fun refreshBlocks() = blocks.refreshLoaded()

    private fun height(record: DecorativeGraveRecord) = DecorativeGraveTarget.collisionHeight(record, appearance(record).model)

    fun views(): List<GraveView> = if (closed) emptyList() else records.filter {
        Bukkit.getWorld(it.worldId)?.name == it.world
    }.map { it.view(server) }

    fun command(player: Player, args: List<String>) {
        invalidate(player)
        if (!allowed(player)) return
        if (!ready) { feedback(player, "unavailable"); return }
        when {
            args == listOf("create") || args == listOf("create", "random") -> {
                val target = DecorativeGraveTarget.capture(player, blocks::hasJournaledBarrier)
                if (target == null) { feedback(player, "target"); return }
                if (occupied(target, null)) { feedback(player, "occupied"); return }
                val draft = Draft(player, target, null, UUID.randomUUID(), GraveStyle.RANDOM, randomEpitaph(), "")
                begin(player, draft)
                if (args.size == 2) commit(draft, draft.visit, remove = false) else editor(draft)
            }
            args == listOf("edit") || args == listOf("remove") -> {
                val record = DecorativeGraveTarget.select(player, records, appearance, blocks::record)
                if (record == null) { feedback(player, "not-found"); return }
                val draft = Draft(player, DecorativeGraveTarget.of(record), record, record.id, record.style, record.epitaph, record.signature)
                begin(player, draft)
                if (args.single() == "remove") removal(draft) else editor(draft)
            }
            else -> feedback(player, "usage")
        }
    }

    private fun begin(player: Player, draft: Draft) {
        player.closeInventory()
        beginFlow(player)
        drafts[player.uniqueId] = draft
    }

    private fun editor(draft: Draft, notice: String? = null) {
        if (!current(draft)) return
        val visit = ++draft.visit
        fun capture(text: String?, signature: String?): Boolean {
            val acceptedText = acceptedText(text.orEmpty())
            val acceptedSignature = acceptedText(signature.orEmpty(), signature = true)
            // Keep each valid submitted field even when the other one needs correction.
            if (acceptedText != null) draft.epitaph = acceptedText
            if (acceptedSignature != null) draft.signature = acceptedSignature
            if (acceptedText == null || acceptedSignature == null) { editor(draft, "invalid-text"); return false }
            return true
        }
        val buttons = listOf(
            PaperDialogButton(PaperDialogActionId.of("decor_save"), text(if (draft.original == null) "create" else "save"),
                width = 200, onClick = { context ->
                    if (current(draft, visit) && capture(context.text(TEXT), context.text(SIGNATURE))) commit(draft, visit, remove = false)
                }),
            PaperDialogButton(PaperDialogActionId.of("decor_models"), text("models"), width = 200, onClick = { context ->
                if (current(draft, visit) && capture(context.text(TEXT), context.text(SIGNATURE))) models(draft)
            }),
            PaperDialogButton(PaperDialogActionId.of("decor_reroll"), text("reroll"), width = 200, onClick = { context ->
                if (current(draft, visit)) {
                    val signature = acceptedText(context.text(SIGNATURE).orEmpty(), signature = true)
                    if (signature == null) editor(draft, "invalid-text")
                    else { draft.signature = signature; draft.epitaph = randomEpitaph(); editor(draft) }
                }
            }),
        )
        show(draft, visit, PaperDialogScreen(id = "graves.decorative", title = text(if (draft.original == null) "title" else "edit-title"),
            body = listOf(panel(text("summary", "world" to draft.target.world, "x" to draft.target.x.toString(),
                "y" to draft.target.y.toString(), "z" to draft.target.z.toString(), "model" to plain(styleName(draft.style))))) + notice(notice),
            inputs = listOf(PaperDialogTextInput(TEXT, text("text-label"), draft.epitaph, WIDTH, GravePreferences.MAX_TEXT_LENGTH, true),
                PaperDialogTextInput(SIGNATURE, text("signature-label"), draft.signature, WIDTH, DecorativeGraveRecord.MAX_SIGNATURE_LENGTH, true)),
            buttons = buttons, columns = 2, exitButton = close(draft)), { editor(draft) })
    }

    private fun models(draft: Draft) {
        if (!current(draft)) return
        val visit = ++draft.visit
        show(draft, visit, PaperDialogScreen(id = "graves.decorative.models", title = text("models-title"),
            body = listOf(panel(text("models-body"))),
            buttons = GraveStyle.entries.map { style -> PaperDialogButton(PaperDialogActionId.of("decor_style_${style.name.lowercase(Locale.ROOT)}"),
                text(if (draft.style == style) "selected" else "choice", "label" to plain(styleName(style))),
                tooltip = text("style-${style.name.lowercase(Locale.ROOT)}-description"), width = 200, onClick = {
                    if (current(draft, visit)) { draft.style = style; models(draft) }
                }) }, columns = 2,
            exitButton = PaperDialogButton(PaperDialogActionId.of("back"), text("back"), width = 200,
                onClick = { if (current(draft, visit)) editor(draft) })), { models(draft) })
    }

    private fun removal(draft: Draft) {
        if (!current(draft)) return
        val visit = ++draft.visit
        show(draft, visit, PaperDialogScreen(id = "graves.decorative.remove", title = text("remove-title"),
            body = listOf(panel(text("remove-body", "world" to draft.target.world, "x" to draft.target.x.toString(),
                "y" to draft.target.y.toString(), "z" to draft.target.z.toString(), "epitaph" to draft.epitaph))),
            buttons = listOf(PaperDialogButton(PaperDialogActionId.of("decor_remove"), text("remove"), width = 200,
                onClick = { if (current(draft, visit)) commit(draft, visit, remove = true) })), exitButton = close(draft)), { removal(draft) })
    }

    private fun commit(draft: Draft, visit: Long, remove: Boolean) {
        if (!current(draft, visit)) return
        val player = draft.player
        if (writing) { feedback(player, "busy"); if (remove) removal(draft) else editor(draft); return }
        if (!ready || !draft.target.reachable(player)) { reject(draft, "target-changed"); return }
        val original = draft.original
        if (original != null && (original.createdBy != player.uniqueId || records.firstOrNull { it.id == original.id } != original)) {
            reject(draft, "changed"); return
        }
        if (!remove && occupied(draft.target, original?.id)) { reject(draft, "occupied"); return }
        if (!remove && original == null && records.size >= DecorativeGraveStore.MAX_RECORDS) { reject(draft, "full"); return }
        if (remove && original == null) return
        if (!remove && (acceptedText(draft.epitaph) == null || acceptedText(draft.signature, signature = true) == null)) { editor(draft, "invalid-text"); return }
        val changed = if (remove) checkNotNull(original) else DecorativeGraveRecord(
            draft.id, player.uniqueId, draft.target.worldId, draft.target.world, draft.target.x, draft.target.y, draft.target.z,
            original?.createdAt ?: System.currentTimeMillis(), (original?.revision ?: -1) + 1, draft.style, draft.epitaph, draft.signature, draft.target.yaw,
        )
        if (!remove) {
            val height = height(changed)
            if (!draft.target.placeable(player, height) { original != null && blocks.record(it)?.id == original.id }) {
                reject(draft, "target-changed")
                return
            }
            if (occupied(draft.target, original?.id, height)) { reject(draft, "occupied"); return }
        }
        val next = records.filterNot { it.id == draft.id } + if (remove) emptyList() else listOf(changed)
        val pendingVisit = ++draft.visit
        show(draft, pendingVisit, PaperDialogScreen(id = if (remove) "graves.decorative.remove" else "graves.decorative",
            title = text(if (remove) "remove-title" else "title"), body = listOf(PaperDialogBody(text("saving"), WIDTH)),
            buttons = emptyList(), exitButton = close(draft)), null)
        save(next, changed) { failure ->
            if (!current(draft, pendingVisit)) return@save
            drafts.remove(player.uniqueId)
            closeDialog(player)
            feedback(player, if (failure != null) "save-failed" else if (remove) "removed" else if (original == null) "created" else "saved")
        }
    }

    private fun save(next: List<DecorativeGraveRecord>, changed: DecorativeGraveRecord, completed: (Throwable?) -> Unit) {
        // Admission is the mutation boundary. A later close/revocation cannot undo an accepted atomic write.
        writing = true
        val saved = store.thenCompose { value -> CompletableFuture.runAsync({ value.save(next) }, worker) }
        drain = saved
        saved.whenCompleteSync(tasks) { _, failure ->
            if (closed) return@whenCompleteSync
            writing = false
            if (failure != null) {
                ready = false // A rename may have committed before its readback failed. Reload before another write.
                plugin.logger.log(Level.WARNING, "Could not confirm decorative grave write; catalog locked until reload", failure)
            } else {
                records = next
                blocks.update(records, changed)
                hits.remove(changed.id)
                refresh()
            }
            completed(failure)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun strike(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val record = event.clickedBlock?.let(blocks::record) ?: return
        event.isCancelled = true
        strike(event.player, record)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun protect(event: BlockBreakEvent) {
        if (blocks.record(event.block) != null) event.isCancelled = true
    }

    fun strikeFrame(player: Player, id: UUID) {
        if (!DecorativeGraveTarget.loadedRay(player)) return
        val record = player.rayTraceBlocks(DecorativeGraveTarget.MAX_DISTANCE, FluidCollisionMode.NEVER)
            ?.hitBlock?.let(blocks::record)?.takeIf { it.id == id } ?: return
        strike(player, record)
    }

    private fun strike(player: Player, record: DecorativeGraveRecord) {
        val creative = player.gameMode == GameMode.CREATIVE
        if ((!creative && player.gameMode != GameMode.SURVIVAL) || record.createdBy != player.uniqueId ||
            !DecorativeGraveTarget.of(record).reachable(player) || !allowed(player)) return
        if (!ready) { feedback(player, "unavailable"); return }
        if (writing) { feedback(player, "busy"); return }
        val at = Location(player.world, record.x + .5, record.y + .45, record.z + .5)
        val appearance = appearance(record)
        val hit = (hits[record.id] ?: 0) + 1
        if (!creative && hit < BREAK_HITS) {
            hits[record.id] = hit
            onHit(record.id, at, appearance, hit)
            return
        }
        save(records.filterNot { it.id == record.id }, record) { failure ->
            if (failure == null) onBreak(at, appearance)
            else if (player.isOnline) feedback(player, "save-failed")
        }
    }

    private fun occupied(target: DecorativeGraveTarget, except: UUID?, height: Int = 1): Boolean =
        (recoverable() + records.filter { it.id != except }.map { it.view(server) }).any {
            it.world == target.world && it.x == target.x && it.y in target.y until target.y + height && it.z == target.z
        }

    private fun allowed(player: Player): Boolean {
        if (closed || !player.isOnline || player.isDead || currentPlayer(player.uniqueId) !== player) return false
        if (player.hasPermission(GRAVES_ADMIN_PERMISSION)) return true
        invalidate(player); closeDialog(player); feedback(player, "denied")
        return false
    }

    private fun current(draft: Draft, visit: Long = draft.visit): Boolean = drafts[draft.player.uniqueId] === draft && draft.visit == visit &&
        allowed(draft.player) && draft.player.world.uid == draft.target.worldId

    private fun show(draft: Draft, visit: Long, screen: PaperDialogScreen, reopen: (() -> Unit)?) =
        showDialog(draft.player, screen, reopen) { if (draft.visit == visit) draft.visit++ }

    private fun close(draft: Draft) = PaperDialogButton(PaperDialogActionId.of("close_menu"), text("close"),
        width = 200, closeDialogBeforeAction = true, onClick = { invalidate(draft.player) })
    private fun reject(draft: Draft, key: String) { invalidate(draft.player); closeDialog(draft.player); feedback(draft.player, key) }
    private fun randomEpitaph(): String = epitaphs.choose(UUID.randomUUID(), GraveDeathKind.entries.random())
    private fun styleName(style: GraveStyle) = text("style-${style.name.lowercase(Locale.ROOT)}")
    private fun text(key: String, vararg values: Pair<String, String>) = settings.text("decor-$key", *values)
    private fun feedback(player: Player, key: String) = player.sendMessage(text(key))
    private fun notice(key: String?) = listOfNotNull(key?.let { PaperDialogBody(text(it), WIDTH) })
    private fun panel(content: Component) = DialogTables.filledBody(listOf(listOf(content)), null, 320, listOf(1), DialogTables.PanelSkin.PLUM)
    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    private fun invalidate(player: Player) { drafts.remove(player.uniqueId) }

    @EventHandler(priority = EventPriority.LOWEST) fun command(event: PlayerCommandPreprocessEvent) = invalidate(event.player)
    @EventHandler(priority = EventPriority.LOWEST) fun inventory(event: InventoryOpenEvent) { (event.player as? Player)?.let(::invalidate) }
    @EventHandler fun quit(event: PlayerQuitEvent) = invalidate(event.player)
    @EventHandler fun world(event: PlayerChangedWorldEvent) = invalidate(event.player)
    @EventHandler fun death(event: PlayerDeathEvent) = invalidate(event.entity)

    fun closeAsync(): CompletableFuture<Void> {
        if (!closed) {
            closed = true
            val players = drafts.values.map { it.player }
            drafts.clear(); tasks.close(); HandlerList.unregisterAll(this)
            hits.clear()
            blocks.close()
            players.forEach(closeDialog)
        }
        return drain.copy()
    }

    companion object {
        private const val WIDTH = 400
        private const val BREAK_HITS = 3
        private val TEXT = PaperDialogInputId.of("decor_epitaph")
        private val SIGNATURE = PaperDialogInputId.of("decor_signature")
        internal fun acceptedText(raw: String, signature: Boolean = false): String? {
            if (!GravePreferences.validText(raw) || (signature && raw.length > DecorativeGraveRecord.MAX_SIGNATURE_LENGTH)) return null
            val trimmed = raw.trim()
            return trimmed.takeIf { (signature || it.isNotEmpty()) && DialogTextLayout.naturalWidth(Component.text(it)) != null }
        }
    }
}
