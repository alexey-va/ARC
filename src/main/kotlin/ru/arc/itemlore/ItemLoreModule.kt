package ru.arc.itemlore

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import ru.arc.ARC
import ru.arc.gui.ArcMenus
import ru.arc.core.PluginModule
import ru.arc.hooks.HookRegistry
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import ru.arc.util.Logging.info
import ru.arc.util.TextUtil
import java.math.BigDecimal
import java.util.UUID

/** NPC-only native editor for ordinary, non-service item lore. */
object ItemLoreModule : PluginModule, Listener {
    override val name = "ItemLore"
    override val priority = 76

    private var settings: ItemLoreSettings? = null
    private var payments: ItemLorePayments? = null
    private var editor: ItemLoreEditor? = null

    override fun init() {
        val loaded = ItemLoreSettings.load(ARC.instance.dataPath)
        val paymentService = ItemLorePayments(journalFactory = { FileItemLorePaymentJournal(ARC.instance.dataPath) })
        settings = loaded
        payments = paymentService
        editor = ItemLoreEditor({ settings }, { payments })
        paymentService.start()
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        info("Item lore NPC editor initialized for Citizens id ${loaded.npcId}")
    }

    override fun reload() {
        HandlerList.unregisterAll(this)
        editor?.close()
        editor = null
        payments?.close()
        payments = null
        settings = null
        init()
    }

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        editor?.close()
        editor = null
        payments?.close()
        payments = null
        settings = null
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onNpcRightClick(event: NPCRightClickEvent) {
        val activeSettings = settings ?: return
        if (activeSettings.npcId <= 0 || event.npc.id != activeSettings.npcId) return
        event.isCancelled = true
        editor?.open(event.clicker, event.npc)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        editor?.quit(event.player.uniqueId)
    }
}

internal class ItemLoreEditor(
    private val currentSettings: () -> ItemLoreSettings?,
    private val currentPayments: () -> ItemLorePayments?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private enum class Stage { EDITING, PREVIEW, CONFIRMING, PAYMENT, COMPLETE, RETIRED }

    private data class Session(
        val playerId: UUID,
        val sourceNpcId: Int,
        val sourceNpcUuid: UUID,
        val sourceWorldId: UUID,
        val sourceLocation: Location,
        val itemSlot: Int,
        val originalItem: ItemStack,
        val originalBytes: ByteArray,
        val startedAtMillis: Long,
        val generation: Long,
        var values: List<String>,
        var draft: ItemLorePolicy.Draft? = null,
        var quotedPriceMinor: Long = 0L,
        var paymentOperationId: UUID? = null,
        var applied: Boolean = false,
        var stage: Stage = Stage.EDITING,
    )

    private val sessions = mutableMapOf<UUID, Session>()
    private var generation = 0L
    private var active = true
    private val miniMessage = MiniMessage.miniMessage()

    fun open(player: org.bukkit.entity.Player, npc: NPC) {
        if (!active || !player.isOnline || player.isDead) return
        val settings = currentSettings() ?: return
        val payments = currentPayments()
        if (payments?.isReady != true) return player.sendMessage(text(settings, "unavailable"))
        if (!npc.isSpawned || npc.id != settings.npcId || settings.npcUuid?.let { it != npc.uniqueId } == true) {
            player.sendMessage(text(settings, "unavailable"))
            return
        }
        val stack = player.inventory.itemInMainHand
        if (stack.type.isAir || protected(stack)) {
            player.sendMessage(text(settings, if (stack.type.isAir) "missing-item" else "protected-item"))
            return
        }
        val originalLore = stack.itemMeta?.lore()
        val originalRows = ItemLorePolicy.originalRows(originalLore)
        val rowTooLong = originalRows.any { !ItemLorePolicy.rowFits(it) }
        if (originalRows.size > ItemLorePolicy.MAX_ROWS) {
            player.sendMessage(text(settings, "too-many-rows"))
            return
        }
        if (rowTooLong) {
            player.sendMessage(text(settings, "row-too-long"))
            return
        }
        val sourceLocation = npc.entity.location.clone()
        val slot = player.inventory.heldItemSlot
        val snapshot = stack.clone()
        val session = Session(
            playerId = player.uniqueId,
            sourceNpcId = npc.id,
            sourceNpcUuid = npc.uniqueId,
            sourceWorldId = sourceLocation.world.uid,
            sourceLocation = sourceLocation,
            itemSlot = slot,
            originalItem = snapshot,
            originalBytes = snapshot.serializeAsBytes(),
            startedAtMillis = nowMillis(),
            generation = generation,
            values = originalRows.map(ItemLorePolicy::editableText),
        )
        sessions[player.uniqueId]?.stage = Stage.RETIRED
        sessions[player.uniqueId] = session
        ArcMenus.beginDialogFlow(player)
        openEditor(player, session, session.values, root = true)
    }

    fun quit(playerId: UUID) {
        sessions.remove(playerId)?.stage = Stage.RETIRED
    }

    fun close() {
        active = false
        generation++
        sessions.values.forEach { it.stage = Stage.RETIRED }
        sessions.clear()
    }

    private fun openEditor(player: org.bukkit.entity.Player, session: Session, values: List<String>, root: Boolean = false, problem: String? = null) {
        if (!ensureValid(player, session)) return
        val settings = requireNotNull(currentSettings())
        session.stage = Stage.EDITING
        session.values = values
        val price = formatPrice(settings.pricePerCharacterMinor)
        val body = ItemLorePreview.editorHelp(text(settings, "editor-body", "price" to price)) {
            text(settings, it)
        }.toMutableList()
        if (values.size == ItemLorePolicy.MAX_ROWS) body += PaperDialogBody(text(settings, "row-limit"), WIDTH)
        problem?.let { body += PaperDialogBody(text(settings, it), WIDTH) }
        val buttons = buildList {
            if (values.size < ItemLorePolicy.MAX_ROWS) {
                add(contextButton("add_row", text(settings, "add-row")) { resizeEditor(it, add = true) })
            }
            if (values.isNotEmpty()) {
                add(contextButton("remove_row", text(settings, "remove-row")) { resizeEditor(it, add = false) })
            }
            add(contextButton("preview", text(settings, "preview-label"), ::submitEditor))
        }
        val screen = PaperDialogScreen(
            id = EDITOR_SCREEN,
            title = text(settings, "editor-title"),
            body = body,
            inputs = values.mapIndexed { index, value ->
                PaperDialogTextInput(
                    PaperDialogInputId.of("lore_${index + 1}"),
                    text(settings, "line-label", "number" to (index + 1).toString()),
                    value,
                    width = WIDTH,
                    maxLength = INPUT_MAX_LENGTH,
                )
            },
            buttons = buttons,
            columns = 1,
        )
        ArcMenus.openDialog(
            player,
            screen,
            reopen = { if (canReopen(session)) openEditor(player, session, session.values, root = true) },
            onDismiss = if (root) ({ retire(session) }) else ({}),
        )
    }

    private fun editorValues(context: PaperDialogClickContext, session: Session): List<String> =
        session.values.mapIndexed { index, value ->
            context.text(PaperDialogInputId.of("$INPUT_PREFIX${index + 1}")) ?: value
        }

    private fun resizeEditor(context: PaperDialogClickContext, add: Boolean) {
        val session = sessions[context.player.uniqueId] ?: return
        if (session.stage != Stage.EDITING || !ensureValid(context.player, session)) return
        // Every form action submits its inputs. Capture them before rebuilding the
        // screen so adding/removing a field cannot discard text typed in other rows.
        val submitted = editorValues(context, session)
        val resized = when {
            add && submitted.size < ItemLorePolicy.MAX_ROWS -> submitted + ""
            !add && submitted.isNotEmpty() -> submitted.dropLast(1)
            else -> submitted
        }
        openEditor(context.player, session, resized, root = true)
    }

    private fun submitEditor(context: PaperDialogClickContext) {
        val player = context.player
        val session = sessions[player.uniqueId] ?: return
        if (session.stage != Stage.EDITING) return
        if (!ensureValid(player, session)) return
        val submitted = editorValues(context, session)
        val settings = requireNotNull(currentSettings())
        val result = ItemLorePolicy.build(session.originalItem.itemMeta?.lore(), submitted)
        if (result is ItemLorePolicy.Result.Rejected) {
            val textKey = when (result.reason) {
                ItemLorePolicy.Reason.TOO_MANY_EXISTING_ROWS -> "too-many-rows"
                ItemLorePolicy.Reason.EXISTING_ROW_TOO_LONG, ItemLorePolicy.Reason.ROW_TOO_LONG -> "row-too-long"
                ItemLorePolicy.Reason.TOO_MANY_FIELDS, ItemLorePolicy.Reason.MULTILINE_INPUT -> "input-invalid"
                ItemLorePolicy.Reason.COMPLEX_EDIT -> "complex-edit"
            }
            openEditor(player, session, submitted, root = true, problem = textKey)
            return
        }
        val draft = (result as ItemLorePolicy.Result.Ready).draft
        session.values = submitted
        session.draft = draft
        val quote = ItemLorePolicy.quoteMinor(draft.editDistance, session.originalItem.amount, settings.pricePerCharacterMinor)
        if (quote == null) {
            player.sendMessage(text(settings, "unavailable"))
            return
        }
        session.quotedPriceMinor = quote
        if (draft.editDistance == 0 && replacement(session, draft).serializeAsBytes().contentEquals(session.originalBytes)) {
            player.sendMessage(text(settings, "unchanged"))
            openEditor(player, session, submitted, root = true)
            return
        }
        if (!isNearSource(player, session, settings)) {
            invalidateWithMessage(player, session, "too-far")
            return
        }
        openPreview(player, session, draft)
    }

    private fun openPreview(player: org.bukkit.entity.Player, session: Session, draft: ItemLorePolicy.Draft) {
        if (!ensureValid(player, session)) return
        val settings = requireNotNull(currentSettings())
        if (!isNearSource(player, session, settings)) return invalidateWithMessage(player, session, "too-far")
        session.stage = Stage.PREVIEW
        val item = replacement(session, draft)
        val after = ItemLorePolicy.originalRows(draft.lore)
        val body = buildList {
            add(PaperDialogBody(itemHover(item), WIDTH))
            add(ItemLorePreview.framed(after) { text(settings, it) })
            add(PaperDialogBody(text(settings, "preview-body", "price" to formatPrice(session.quotedPriceMinor)), WIDTH))
        }
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = PREVIEW_SCREEN,
                title = text(settings, "preview-title"),
                body = body,
                buttons = listOf(contextButton("continue", text(settings, "preview-confirm")) { openConfirm(player, session, draft) }),
                exitButton = dialogButton("edit", text(settings, "preview-back")) {
                    openEditor(player, session, session.values, root = false)
                },
                columns = 1,
            ),
            reopen = { if (canReopen(session)) openPreview(player, session, session.draft ?: draft) },
        )
    }

    private fun openConfirm(player: org.bukkit.entity.Player, session: Session, draft: ItemLorePolicy.Draft) {
        if (!ensureValid(player, session)) return
        val settings = requireNotNull(currentSettings())
        if (!isNearSource(player, session, settings)) return invalidateWithMessage(player, session, "too-far")
        val currentQuote = ItemLorePolicy.quoteMinor(draft.editDistance, session.originalItem.amount, settings.pricePerCharacterMinor)
        if (currentQuote == null) return invalidateWithMessage(player, session, "unavailable")
        if (currentQuote != session.quotedPriceMinor) {
            session.quotedPriceMinor = currentQuote
            openPreview(player, session, draft)
            return
        }
        session.stage = Stage.CONFIRMING
        val isClear = draft.lore.isNullOrEmpty() && ItemLorePolicy.originalRows(session.originalItem.itemMeta?.lore()).isNotEmpty()
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = CONFIRM_SCREEN,
                title = text(settings, "confirm-title"),
                body = listOf(PaperDialogBody(text(settings, if (isClear) "confirm-clear" else "confirm-body", "price" to formatPrice(currentQuote)), WIDTH)),
                buttons = listOf(contextButton("save", text(settings, "save", "price" to formatPrice(currentQuote))) { submitSave(player, session, draft, currentQuote) }),
                exitButton = dialogButton("back", text(settings, "confirm-back")) { openPreview(player, session, draft) },
                columns = 1,
            ),
            reopen = { if (canReopen(session)) openConfirm(player, session, session.draft ?: draft) },
        )
    }

    private fun submitSave(player: org.bukkit.entity.Player, session: Session, draft: ItemLorePolicy.Draft, shownPriceMinor: Long) {
        if (session.stage != Stage.CONFIRMING) return
        if (!ensureValid(player, session)) return
        val settings = requireNotNull(currentSettings())
        if (!isNearSource(player, session, settings)) return invalidateWithMessage(player, session, "too-far")
        val currentQuote = ItemLorePolicy.quoteMinor(draft.editDistance, session.originalItem.amount, settings.pricePerCharacterMinor)
        if (currentQuote == null) return invalidateWithMessage(player, session, "unavailable")
        if (currentQuote != shownPriceMinor || currentQuote != session.quotedPriceMinor) {
            session.quotedPriceMinor = currentQuote
            openPreview(player, session, draft)
            return
        }
        if (currentQuote <= 0L) {
            player.sendMessage(text(settings, "unchanged"))
            return openEditor(player, session, session.values)
        }
        val payment = currentPayments()
        if (payment?.isReady != true) return invalidateWithMessage(player, session, "unavailable")
        val currentStack = player.inventory.getItem(session.itemSlot)
        if (!sameOriginal(currentStack, session)) return invalidateWithMessage(player, session, "stale-item")
        val edited = replacement(session, draft)
        val editedBytes = edited.serializeAsBytes()
        val operationId = UUID.randomUUID()
        session.stage = Stage.PAYMENT
        session.paymentOperationId = operationId
        openSaving(player, session)
        payment.submit(
            ItemLorePaymentRequest(
                operationId = operationId,
                playerId = session.playerId,
                slot = session.itemSlot,
                originalItemBytes = session.originalBytes.copyOf(),
                replacementItemBytes = editedBytes.copyOf(),
                priceMinor = currentQuote,
            ),
            validate = {
                    active && session.stage == Stage.PAYMENT && session.paymentOperationId == operationId &&
                    validSource(player, session, currentSettings() ?: return@submit false) &&
                    sameOriginal(player.inventory.getItem(session.itemSlot), session) &&
                    ItemLorePolicy.quoteMinor(draft.editDistance, session.originalItem.amount, currentSettings()!!.pricePerCharacterMinor) == shownPriceMinor
            },
            apply = {
                player.inventory.setItem(session.itemSlot, edited.clone())
                check(player.inventory.getItem(session.itemSlot)?.serializeAsBytes()?.contentEquals(editedBytes) == true) {
                    "Item lore payment succeeded but the held slot did not retain its exact replacement"
                }
                session.applied = true
            },
            completion = { outcome -> finishPayment(player, session, outcome, currentQuote) },
        )
    }

    private fun openSaving(player: org.bukkit.entity.Player, session: Session) {
        val settings = requireNotNull(currentSettings())
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = SAVING_SCREEN,
                title = text(settings, "saving-title"),
                body = listOf(PaperDialogBody(text(settings, "saving-body"), WIDTH)),
                buttons = emptyList(),
                columns = 1,
            ),
            reopen = { if (session.stage == Stage.PAYMENT) openSaving(player, session) },
            onDismiss = { retire(session) },
        )
    }

    private fun finishPayment(player: org.bukkit.entity.Player, session: Session, outcome: ItemLorePaymentOutcome, priceMinor: Long) {
        if (session.paymentOperationId == null) return
        when (outcome) {
            ItemLorePaymentOutcome.SUCCESS -> {
                val stillOwnsFlow = active && session.stage == Stage.PAYMENT && session.generation == generation &&
                    sessions[session.playerId] === session
                if (!stillOwnsFlow) {
                    retire(session)
                    if (player.isOnline && session.applied) {
                        currentSettings()?.let { player.sendMessage(text(it, "saved-body", "price" to formatPrice(priceMinor))) }
                    }
                    return
                }
                session.stage = Stage.COMPLETE
                sessions.remove(session.playerId, session)
                if (player.isOnline) {
                    ArcMenus.beginDialogFlow(player)
                    showTerminal(player, session, "saved-title", "saved-body", priceMinor, closeOnly = true)
                }
            }
            ItemLorePaymentOutcome.CANCELLED -> {
                if (session.stage == Stage.RETIRED) return
                val settings = currentSettings() ?: return
                val stillValid = player.isOnline && validSource(player, session, settings) && sameOriginal(player.inventory.getItem(session.itemSlot), session)
                val key = if (stillValid) "cancelled" else "stale-item"
                session.stage = Stage.CONFIRMING
                if (player.isOnline) openResult(player, session, key, allowBack = key == "cancelled") else retire(session)
            }
            ItemLorePaymentOutcome.INSUFFICIENT_FUNDS -> {
                if (session.stage == Stage.RETIRED) return
                session.stage = Stage.CONFIRMING
                openResult(player, session, "insufficient-funds", allowBack = true)
            }
            ItemLorePaymentOutcome.BUSY_OR_DUPLICATE -> {
                if (session.stage == Stage.RETIRED) return
                session.stage = Stage.CONFIRMING
                openResult(player, session, "busy", allowBack = false)
            }
            ItemLorePaymentOutcome.OUTCOME_UNKNOWN -> {
                if (session.stage == Stage.RETIRED) return
                session.stage = Stage.RETIRED
                sessions.remove(session.playerId, session)
                ArcMenus.beginDialogFlow(player)
                showTerminal(player, session, "payment-unknown", null, priceMinor, closeOnly = true)
            }
            ItemLorePaymentOutcome.UNAVAILABLE, ItemLorePaymentOutcome.FAILED_UNCHARGED -> {
                if (session.stage == Stage.RETIRED) return
                session.stage = Stage.CONFIRMING
                openResult(player, session, "payment-failed", allowBack = false)
            }
        }
    }

    private fun openResult(player: org.bukkit.entity.Player, session: Session, messageKey: String, allowBack: Boolean) {
        if (!player.isOnline) return retire(session)
        val settings = currentSettings() ?: return retire(session)
        val draft = session.draft ?: return retire(session)
        // The original save screen is no longer actionable once the payment
        // owner has returned. A retry uses this explicit button and the same
        // session snapshot, after full validation.
        ArcMenus.beginDialogFlow(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = RESULT_SCREEN,
                title = text(settings, "confirm-title"),
                body = listOf(PaperDialogBody(text(settings, messageKey), WIDTH)),
                buttons = if (allowBack) listOf(contextButton("try_again", text(settings, "preview-confirm")) { openConfirm(player, session, draft) }) else emptyList(),
                exitButton = dialogButton("close", text(settings, "editor-back"), close = true) { retire(session) },
                columns = 1,
            ),
            reopen = { if (session.stage == Stage.CONFIRMING) openResult(player, session, messageKey, allowBack) },
        )
    }

    private fun showTerminal(player: org.bukkit.entity.Player, session: Session, titleKey: String, bodyKey: String?, priceMinor: Long, closeOnly: Boolean) {
        val settings = currentSettings() ?: return
        val body = text(settings, bodyKey ?: titleKey, "price" to formatPrice(priceMinor))
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = RESULT_SCREEN,
                title = text(settings, titleKey, "price" to formatPrice(priceMinor)),
                body = listOf(PaperDialogBody(body, WIDTH)),
                buttons = emptyList(),
                exitButton = dialogButton("close", text(settings, "editor-back"), close = closeOnly) { retire(session) },
                columns = 1,
            ),
        )
    }

    private fun replacement(session: Session, draft: ItemLorePolicy.Draft): ItemStack {
        if (draft.editDistance == 0) return session.originalItem.clone()
        return session.originalItem.clone().also { copy -> copy.editMeta { meta -> meta.lore(draft.lore) } }
    }

    private fun itemHover(item: ItemStack): Component {
        val displayName = item.itemMeta?.displayName() ?: Component.translatable(item.type.translationKey())
        val amount = if (item.amount > 1) Component.text(" × ${item.amount}", NamedTextColor.WHITE) else Component.empty()
        return displayName.append(amount).hoverEvent(item.asHoverEvent())
    }

    private fun ensureValid(player: org.bukkit.entity.Player, session: Session): Boolean {
        if (!active || sessions[player.uniqueId] !== session || session.generation != generation ||
            session.stage in setOf(Stage.COMPLETE, Stage.RETIRED, Stage.PAYMENT) || nowMillis() - session.startedAtMillis > SESSION_TTL_MILLIS
        ) {
            retire(session)
            currentSettings()?.let { player.sendMessage(text(it, "stale-item")) }
            return false
        }
        val settings = currentSettings()
        if (settings == null || !validSource(player, session, settings)) {
            invalidateWithMessage(player, session, "too-far")
            return false
        }
        if (!sameOriginal(player.inventory.getItem(session.itemSlot), session)) {
            invalidateWithMessage(player, session, "stale-item")
            return false
        }
        return true
    }

    private fun validSource(player: org.bukkit.entity.Player, session: Session, settings: ItemLoreSettings): Boolean =
        active && player.isOnline && !player.isDead && sessions[player.uniqueId] === session &&
            session.generation == generation && nowMillis() - session.startedAtMillis <= SESSION_TTL_MILLIS &&
            session.sourceNpcId == settings.npcId && settings.npcId > 0 &&
            (settings.npcUuid == null || settings.npcUuid == session.sourceNpcUuid) &&
            runCatching {
                val npc = CitizensAPI.getNPCRegistry().getById(session.sourceNpcId) ?: return@runCatching false
                if (!npc.isSpawned || npc.uniqueId != session.sourceNpcUuid) return@runCatching false
                val location = npc.entity.location
                val npcWorld = location.world ?: return@runCatching false
                val playerLocation = player.location
                val playerWorld = playerLocation.world ?: return@runCatching false
                session.sourceLocation.world?.uid == session.sourceWorldId && npcWorld.uid == session.sourceWorldId &&
                playerWorld.uid == session.sourceWorldId && player.inventory.heldItemSlot == session.itemSlot &&
                    playerLocation.distanceSquared(location) <= settings.maxDistance * settings.maxDistance
            }.getOrDefault(false)

    private fun isNearSource(player: org.bukkit.entity.Player, session: Session, settings: ItemLoreSettings): Boolean =
        validSource(player, session, settings)

    private fun sameOriginal(current: ItemStack?, session: Session): Boolean =
        current != null && !current.type.isAir &&
            runCatching { ItemLorePolicy.sameSnapshot(session.originalBytes, current.serializeAsBytes()) }.getOrDefault(false)

    private fun protected(stack: ItemStack): Boolean {
        if (HookRegistry.sfHook?.isSlimefunItem(stack) == true) return true
        if (Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) {
            if (runCatching { EliteItemManager.isEliteMobsItem(stack) }.getOrDefault(true)) return true
        }
        val pdc: PersistentDataContainer = stack.itemMeta?.persistentDataContainer ?: return false
        return pdc.keys.isNotEmpty()
    }

    private fun contextButton(id: String, label: Component, action: (PaperDialogClickContext) -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), label, width = WIDTH, onClick = action)

    private fun dialogButton(id: String, label: Component, close: Boolean = false, action: () -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), label, width = WIDTH, closeDialogBeforeAction = close, onClick = { action() })

    private fun text(settings: ItemLoreSettings, key: String, vararg values: Pair<String, String>): Component {
        val resolvers = values.map { (name, value) -> Placeholder.unparsed(name, value) }.toTypedArray()
        val component = if (resolvers.isEmpty()) TextUtil.mm(settings.text(key)) else miniMessage.deserialize(settings.text(key), *resolvers)
        return component.decoration(TextDecoration.ITALIC, false)
    }

    private fun formatPrice(priceMinor: Long): String = BigDecimal.valueOf(priceMinor, 2).stripTrailingZeros().toPlainString() + " монет"

    private fun invalidateWithMessage(player: org.bukkit.entity.Player, session: Session, messageKey: String) {
        val settings = currentSettings()
        retire(session)
        if (player.isOnline && settings != null) player.sendMessage(text(settings, messageKey))
    }

    private fun retire(session: Session) {
        if (sessions[session.playerId] === session) sessions.remove(session.playerId)
        session.stage = Stage.RETIRED
    }

    private fun canReopen(session: Session): Boolean =
        active && sessions[session.playerId] === session && session.stage !in setOf(Stage.COMPLETE, Stage.RETIRED, Stage.PAYMENT) &&
            nowMillis() - session.startedAtMillis <= SESSION_TTL_MILLIS

    companion object {
        const val EDITOR_SCREEN = "item-lore.editor"
        const val PREVIEW_SCREEN = "item-lore.preview"
        const val CONFIRM_SCREEN = "item-lore.confirm"
        const val SAVING_SCREEN = "item-lore.saving"
        const val RESULT_SCREEN = "item-lore.result"
        const val INPUT_PREFIX = "lore_"
        const val INPUT_MAX_LENGTH = ItemLorePolicy.MAX_INPUT_LENGTH
        private const val WIDTH = 520
        private const val SESSION_TTL_MILLIS = 5 * 60 * 1000L
    }
}
