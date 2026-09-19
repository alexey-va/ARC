package ru.arc.hooks.citizens

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.ai.speech.event.NPCSpeechEvent
import net.citizensnpcs.api.event.NPCDespawnEvent
import net.citizensnpcs.api.event.NPCRemoveEvent
import net.citizensnpcs.api.event.NPCSpawnEvent
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPC.NPCUpdate
import net.citizensnpcs.trait.HologramTrait
import net.citizensnpcs.trait.text.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.core.ScheduledTask
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.util.Common
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.UUID

/**
 * One ARC-owned presentation stack per Citizens NPC: name plus one body layer.
 * Permanent lines and temporary speech reuse the body display, so they cannot
 * overlap. Components are parsed only when content changes; the hot loop only
 * expires bubbles and follows moving NPCs.
 */
internal class ArcNpcHologramService(
    private var config: ArcNpcHologramConfig,
    private val store: NpcPresentationStore,
    scheduler: TaskScheduler = Tasks.scheduler,
) : Listener, AutoCloseable {
    companion object {
        const val DISPLAY_TAG = "arc_npc_hologram"
        private const val NAME_TAG = "arc_npc_hologram_name"
        private const val BODY_TAG = "arc_npc_hologram_body"
        private const val BACKUP_KEY = "arc_npc_hologram_backup_v1"
        private const val DEFAULT_LINE_HEIGHT = 0.25
        private const val DEFAULT_VIEW_RANGE = -1
        private val SPEECH_NEWLINE = Regex("<br>|\\n")
        private val HEADER_COLOR = TextColor.color(0xE8C383)
        private val LABEL_COLOR = TextColor.color(0x9AA8B7)
        private val VALUE_COLOR = TextColor.color(0xE6EDF3)
    }

    private data class Backup(
        val hadHologram: Boolean,
        val hologramLines: List<String>,
        val lineHeight: Double,
        val viewRange: Int,
        val nameplateValue: String,
        val speechBubbles: Boolean?,
        val sendTextToChat: Boolean?,
        val forceNameVisible: Boolean? = null,
    )

    private data class Stack(
        val npcId: Int,
        val npcUuid: UUID,
        val state: NpcHologramState,
        var presentation: NpcPresentation,
        var name: TextDisplay? = null,
        var body: TextDisplay? = null,
        var nameComponent: Component? = null,
        var sourceBodyComponent: Component? = null,
        var bubbleBodyComponent: Component? = null,
    )

    private val stacks = mutableMapOf<Int, Stack>()
    private var catalog = store.load()
    private val tasks = LifecycleTaskScope(scheduler)
    private val warned = mutableSetOf<String>()
    private val speechBridges = mutableSetOf<Int>()
    private val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
    private val miniMessage = MiniMessage.miniMessage()
    private var followTask: ScheduledTask? = null
    private var reconcileTask: ScheduledTask? = null
    private var closed = false
    private var reconciliationReported = false

    fun start() {
        if (followTask != null || !config.enabled) return
        closed = false
        removeAbandonedDisplays()
        startTasks()
        tasks.runLater(1L) { reconcileAll() }
    }

    fun reload(next: ArcNpcHologramConfig) {
        val replacement = store.load()
        catalog = replacement
        config = next
        tasks.restart()
        followTask = null
        reconcileTask = null
        stacks.values.forEach(::removeDisplays)
        reconciliationReported = false
        if (config.enabled) {
            closed = false
            startTasks()
            tasks.runLater(1L) { reconcileAll() }
        } else {
            clearStacks()
        }
    }

    fun reconcileAll() {
        if (closed || !config.enabled) return
        val registry = runCatching { CitizensAPI.getNPCRegistry() }.getOrNull() ?: return
        val npcs = registry.toList()
        // Persist the complete migration before touching any native presentation.
        importPresentations(npcs)
        val seen = mutableSetOf<Int>()
        npcs.forEach { npc ->
            seen += npc.id
            reconcile(npc)
        }
        stacks.keys.toList().filterNot(seen::contains).forEach(::removeStackOnly)
        if (!reconciliationReported) {
            reconciliationReported = true
            info(
                "ARC_NPC_HOLOGRAM phase=RECONCILED registered={} managed={} speech_bridges={}",
                seen.size,
                stacks.size,
                speechBridges.size,
            )
        }
    }

    fun showTemporaryBubble(id: Int, lines: List<String>, ttlTicks: Int): Boolean =
        showTemporaryBubble(id, lines, ttlTicks, owner = null)

    fun showTemporaryBubble(id: Int, lines: List<String>, ttlTicks: Int, owner: String?): Boolean {
        if (closed || !config.enabled) return false
        val visible = lines.map(String::trim).filter(String::isNotEmpty)
        if (visible.isEmpty()) return false
        val npc = CitizensAPI.getNPCRegistry().getById(id) ?: return false
        val stack = stackFor(npc) ?: return false
        stack.state.showBubble(visible, ttlTicks, owner)
        stack.bubbleBodyComponent = npcSpeechComponent(visible.map(::parseRaw), VALUE_COLOR)
        updateDisplays(stack, npc, refreshText = true)
        return true
    }

    fun clearTemporaryBubble(id: Int, owner: String): Boolean {
        if (closed || !config.enabled) return false
        val stack = stacks[id] ?: return false
        if (!stack.state.clearBubble(owner)) return false
        stack.bubbleBodyComponent = null
        val npc = CitizensAPI.getNPCRegistry().getById(id) ?: return true
        updateDisplays(stack, npc, refreshText = true)
        return true
    }

    fun hasTemporaryBubble(id: Int): Boolean = stacks[id]?.state?.bubbleActive() == true

    fun patchHologram(npc: NPC, lines: List<String>? = null, lineHeight: Double? = null, viewRange: Int? = null): Boolean {
        val stack = stackFor(npc) ?: return false
        val authored = lines?.let { resolveNpcHologramPresentation(it, npc.rawName) }
        updatePresentation(npc, stack, stack.presentation.copy(
            hasHologram = true,
            name = authored?.takeIf { it.nameFromAuthoredLine }?.name ?: stack.presentation.name,
            lines = authored?.lines ?: stack.presentation.lines,
            lineHeight = lineHeight ?: stack.presentation.lineHeight,
            viewRange = viewRange ?: stack.presentation.viewRange,
        ))
        return true
    }

    fun clearHologram(npc: NPC): Boolean {
        val stack = stackFor(npc) ?: return false
        updatePresentation(npc, stack, stack.presentation.copy(hasHologram = false, lines = emptyList()))
        return true
    }

    fun patchNameplate(npc: NPC, mode: String): Boolean {
        val stack = stackFor(npc) ?: return false
        val normalized = mode.lowercase()
        updatePresentation(npc, stack, stack.presentation.copy(nameVisible = normalized !in setOf("false", "hidden")))
        return true
    }

    fun patchName(npc: NPC, name: String): Boolean {
        val stack = stackFor(npc) ?: return false
        updatePresentation(npc, stack, stack.presentation.copy(name = name))
        return true
    }

    fun desiredName(npc: NPC): String? = catalog[npc.uniqueId]?.presentation?.name
    fun isManaged(npc: NPC): Boolean = catalog.containsKey(npc.uniqueId)
    fun desiredNameplate(npc: NPC): String? = catalog[npc.uniqueId]?.presentation?.nameVisible?.toString()
    fun desiredSpeechBubbles(npc: NPC): Boolean? = catalog[npc.uniqueId]?.presentation?.speechBubbles

    fun onTextPatched(npc: NPC, desiredSpeechBubbles: Boolean?) {
        val stack = stackFor(npc) ?: return
        val text = npc.getTraitNullable(Text::class.java)
        val desired = desiredSpeechBubbles ?: stack.presentation.speechBubbles
        updatePresentation(npc, stack, stack.presentation.copy(
            speechBubbles = desired,
            sendTextToChat = stack.presentation.sendTextToChat ?: text?.sendTextToChat(),
        ))
        when {
            desired != true -> unbridgeSpeech(npc, text, stack.presentation.sendTextToChat)
            text == null -> speechBridges.remove(npc.id)
            else -> bridgeSpeech(npc, text)
        }
    }

    fun summary(npc: NPC): Map<String, Any?>? {
        val presentation = catalog[npc.uniqueId]?.presentation ?: return null
        if (!presentation.hasHologram) return null
        return linkedMapOf(
            "lines" to presentation.lines,
            "lineHeight" to presentation.lineHeight,
            "viewRange" to presentation.viewRange,
        )
    }

    @EventHandler
    fun onSpawn(event: NPCSpawnEvent) {
        if (!isPrimaryNpc(event.npc)) return
        tasks.runLater(1L) {
            importPresentations(listOf(event.npc))
            reconcile(event.npc)
        }
    }

    @EventHandler
    fun onDespawn(event: NPCDespawnEvent) {
        if (!isPrimaryNpc(event.npc)) return
        stacks[event.npc.id]?.let(::removeDisplays)
    }

    @EventHandler
    fun onRemove(event: NPCRemoveEvent) {
        if (!isPrimaryNpc(event.npc)) return
        removeStackOnly(event.npc.id)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSpeech(event: NPCSpeechEvent) {
        if (!isPrimaryNpc(event.npc)) return
        if (event.npc.id !in speechBridges) return
        showTemporaryBubble(
            event.npc.id,
            SPEECH_NEWLINE.split(event.context.message),
            config.speechDurationTicks,
        )
        if (npcSpeechBridgeCancelsChat(stacks[event.npc.id]?.presentation?.sendTextToChat)) event.isCancelled = true
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        followTask = null
        reconcileTask = null
        clearStacks()
        warned.clear()
        reconciliationReported = false
    }

    private fun startTasks() {
        followTask = tasks.runTimer(config.followIntervalTicks, config.followIntervalTicks) { tickStacks() }
        reconcileTask = tasks.runTimer(config.reconcileIntervalTicks, config.reconcileIntervalTicks) { reconcileAll() }
    }

    private fun reconcile(npc: NPC): Stack? {
        if (closed || !config.enabled) return null
        val record = catalog[npc.uniqueId] ?: return null
        val trait = npc.getTraitNullable(HologramTrait::class.java)
        if (trait != null && hasUnsupportedRenderer(trait)) {
            removeStackOnly(npc.id)
            warnOnce("renderer:${npc.id}", "ARC NPC hologram reconciliation skipped NPC {}: custom/item Citizens renderer remains native", npc.id)
            return null
        }
        if (stacks[npc.id]?.npcUuid?.let { it != npc.uniqueId } == true) removeStackOnly(npc.id)
        val stack = stacks.getOrPut(npc.id) {
            Stack(npc.id, npc.uniqueId, NpcHologramState(), record.presentation)
        }
        stack.presentation = record.presentation
        hideNativeNameplate(npc)
        // Citizens may reattach this trait at spawn. Keep it empty: removeTrait
        // invokes run() and unregisters listeners, causing repeated spawn churn.
        if (trait != null) {
            if (trait.lines.isNotEmpty()) trait.clear()
            if (trait.nameRenderer != null) trait.onDespawn()
        }
        val text = npc.getTraitNullable(Text::class.java)
        if (record.presentation.speechBubbles == true && text != null) bridgeSpeech(npc, text)
        else unbridgeSpeech(npc, text, record.presentation.sendTextToChat)
        applyPresentation(stack, npc)
        return stack
    }

    private fun importPresentations(npcs: List<NPC>) {
        val additions = linkedMapOf<UUID, NpcPresentationRecord>()
        npcs.filterNot { it.uniqueId in catalog }.forEach { npc ->
            val trait = npc.getTraitNullable(HologramTrait::class.java)
            if (trait != null && hasUnsupportedRenderer(trait)) {
                warnOnce("renderer:${npc.id}", "ARC NPC hologram migration skipped NPC {}: custom/item Citizens renderer remains native", npc.id)
                return@forEach
            }
            val raw = npc.data().get<String>(BACKUP_KEY, "").takeIf(String::isNotBlank)
            val backup = if (raw == null) captureBackup(npc, trait, npc.getTraitNullable(Text::class.java)) else {
                try {
                    requireNotNull(Common.gson.fromJson(raw, Backup::class.java)).also {
                        requireNotNull(it.hologramLines).forEach(::requireNotNull)
                        requireNotNull(it.nameplateValue)
                    }
                } catch (failure: Exception) {
                    warnOnce("backup:${npc.id}", "ARC NPC hologram migration skipped NPC {}: invalid legacy backup", npc.id)
                    return@forEach
                }
            }
            val resolved = resolveNpcHologramPresentation(backup.hologramLines, npc.rawName)
            additions[npc.uniqueId] = NpcPresentationRecord(
                npcId = npc.id,
                presentation = NpcPresentation(
                    name = resolved.name,
                    nameVisible = backup.forceNameVisible ?: (resolved.nameFromAuthoredLine || backup.nameplateValue !in setOf("false", "hidden")),
                    lines = resolved.lines,
                    lineHeight = backup.lineHeight,
                    viewRange = backup.viewRange,
                    hasHologram = backup.hadHologram,
                    speechBubbles = backup.speechBubbles,
                    sendTextToChat = backup.sendTextToChat,
                ),
                legacyBackup = raw ?: Common.gson.toJson(backup),
            )
        }
        if (additions.isEmpty()) return
        val replacement = catalog + additions
        store.save(replacement)
        catalog = replacement
    }

    private fun updatePresentation(npc: NPC, stack: Stack, presentation: NpcPresentation) {
        val existing = catalog.getValue(npc.uniqueId)
        if (existing.presentation != presentation) {
            val replacement = catalog + (npc.uniqueId to existing.copy(presentation = presentation))
            store.save(replacement)
            catalog = replacement
        }
        stack.presentation = presentation
        applyPresentation(stack, npc)
    }

    private fun captureBackup(npc: NPC, trait: HologramTrait?, text: Text?): Backup = Backup(
        hadHologram = trait != null,
        hologramLines = trait?.lines?.toList().orEmpty(),
        lineHeight = trait?.lineHeight ?: DEFAULT_LINE_HEIGHT,
        viewRange = trait?.viewRange ?: DEFAULT_VIEW_RANGE,
        nameplateValue = nativeNameplateValue(npc),
        speechBubbles = text?.useSpeechBubbles(),
        sendTextToChat = text?.sendTextToChat(),
    )

    private fun applyPresentation(stack: Stack, npc: NPC) {
        val presentation = stack.presentation
        val source = NpcHologramSource(
            name = presentation.name.takeIf { presentation.nameVisible },
            lines = presentation.lines,
            lineHeight = presentation.lineHeight,
            viewRange = presentation.viewRange,
        )
        val sourceChanged = stack.state.apply(source)
        if (sourceChanged) renderSource(stack)
        updateDisplays(stack, npc, refreshText = sourceChanged)
    }

    private fun stackFor(npc: NPC): Stack? {
        if (closed || !config.enabled) return null
        stacks[npc.id]?.takeIf { it.npcUuid == npc.uniqueId }?.let { return it }
        importPresentations(listOf(npc))
        return reconcile(npc)
    }

    private fun isPrimaryNpc(npc: NPC): Boolean =
        runCatching { npc.owningRegistry === CitizensAPI.getNPCRegistry() }.getOrDefault(false)

    private fun tickStacks() {
        if (closed || !config.enabled) return
        stacks.values.toList().forEach { stack ->
            val npc = CitizensAPI.getNPCRegistry().getById(stack.npcId)
            if (npc == null || npc.uniqueId != stack.npcUuid) {
                removeStackOnly(stack.npcId)
                return@forEach
            }
            val bubbleExpired = stack.state.tick(config.followIntervalTicks.toInt())
            if (bubbleExpired) stack.bubbleBodyComponent = null
            updateDisplays(stack, npc, refreshText = bubbleExpired)
        }
    }

    private fun renderSource(stack: Stack) {
        val source = stack.state.source() ?: return
        stack.nameComponent = source.name
            ?.takeIf(String::isNotBlank)
            ?.let(::parseRaw)
            ?.colorIfAbsent(HEADER_COLOR)
            ?.decorate(TextDecoration.BOLD)
        stack.sourceBodyComponent = source.lines
            .takeIf { it.isNotEmpty() }
            ?.let(::parsePermanentLines)
        if (!stack.state.bubbleActive()) stack.bubbleBodyComponent = null
    }

    private fun updateDisplays(stack: Stack, npc: NPC, refreshText: Boolean = false) {
        val entity = npc.entity
        if (!npc.isSpawned || entity == null || !entity.isValid) {
            removeDisplays(stack)
            return
        }
        val source = stack.state.source() ?: return
        val nameLocation = entity.location.clone().add(0.0, entity.height + config.nameOffset, 0.0)
        val bodyLines = stack.state.visibleLines().size.coerceAtLeast(1)
        val bodyOffset = npcHologramBodyOffset(config.bodyGap, source.lineHeight, bodyLines)
        val viewRange = npcHologramViewRange(source.viewRange, config.viewRange)
        val bodyLocation = nameLocation.clone().add(0.0, if (stack.nameComponent == null) 0.0 else bodyOffset, 0.0)
        stack.name = updateLayer(stack.name, nameLocation, stack.nameComponent, NAME_TAG, 0, viewRange, refreshText)
        stack.body = updateLayer(
            stack.body,
            bodyLocation,
            stack.bubbleBodyComponent ?: stack.sourceBodyComponent,
            BODY_TAG,
            config.backgroundAlpha,
            viewRange,
            refreshText,
        )
    }

    private fun updateLayer(
        current: TextDisplay?,
        location: Location,
        component: Component?,
        tag: String,
        backgroundAlpha: Int,
        viewRange: Float,
        refreshText: Boolean,
    ): TextDisplay? {
        if (component == null) {
            current?.remove()
            return null
        }
        val display = current?.takeIf { it.isValid && it.world == location.world } ?: run {
            current?.remove()
            spawnDisplay(location, component, tag, backgroundAlpha, viewRange)
        }
        if (refreshText && display === current) {
            display.text(component)
            display.viewRange = viewRange
        }
        follow(display, location)
        return display
    }

    private fun spawnDisplay(
        location: Location,
        component: Component,
        tag: String,
        backgroundAlpha: Int,
        viewRange: Float,
    ): TextDisplay =
        requireNotNull(location.world).spawn(location, TextDisplay::class.java) {
            it.text(component)
            it.isPersistent = false
            it.setGravity(false)
            it.isInvulnerable = true
            it.isVisibleByDefault = true
            it.brightness = Display.Brightness(15, 15)
            it.viewRange = viewRange
            it.billboard = Display.Billboard.CENTER
            it.isSeeThrough = false
            it.isShadowed = true
            it.backgroundColor = Color.fromARGB(backgroundAlpha, 15, 23, 30)
            it.lineWidth = config.lineWidth
            it.interpolationDuration = config.teleportDurationTicks
            it.teleportDuration = config.teleportDurationTicks
            it.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(config.scale), AxisAngle4f())
            it.addScoreboardTag(DISPLAY_TAG)
            it.addScoreboardTag(tag)
        }

    private fun follow(display: TextDisplay, destination: Location) {
        val current = display.location
        val distanceSquared = current.distanceSquared(destination)
        if (distanceSquared <= 0.0001) return
        display.teleportDuration = if (distanceSquared > 64.0) 0 else config.teleportDurationTicks
        display.teleport(destination)
    }

    private fun hasUnsupportedRenderer(trait: HologramTrait): Boolean {
        val renderers = buildList {
            addAll(trait.hologramRenderers)
            trait.nameRenderer?.let(::add)
        }
        return renderers.any { !it.javaClass.name.startsWith("net.citizensnpcs.trait.HologramTrait\$TextDisplay") }
    }

    private fun bridgeSpeech(npc: NPC, text: Text) {
        speechBridges += npc.id
        if (text.useSpeechBubbles()) text.toggleSpeechBubbles()
        if (!text.sendTextToChat()) text.toggleSendTextToChat()
    }

    private fun unbridgeSpeech(npc: NPC, text: Text?, desiredSendTextToChat: Boolean?) {
        speechBridges.remove(npc.id)
        if (text != null && desiredSendTextToChat != null && text.sendTextToChat() != desiredSendTextToChat) {
            text.toggleSendTextToChat()
        }
    }

    private fun clearStacks() {
        stacks.values.forEach(::removeDisplays)
        stacks.clear()
        speechBridges.clear()
    }

    private fun removeDisplays(stack: Stack) {
        stack.name?.remove()
        stack.body?.remove()
        stack.name = null
        stack.body = null
    }

    private fun removeStackOnly(id: Int) {
        stacks.remove(id)?.let(::removeDisplays)
        speechBridges.remove(id)
    }

    private fun removeAbandonedDisplays() {
        var removed = 0
        Bukkit.getWorlds().forEach { world ->
            world.getEntitiesByClass(TextDisplay::class.java)
                .filter { DISPLAY_TAG in it.scoreboardTags }
                .forEach { display -> display.remove(); removed++ }
        }
        if (removed > 0) info("ARC_NPC_HOLOGRAM phase=ABANDONED_REMOVED count={}", removed)
    }

    private fun nativeNameplateValue(npc: NPC): String =
        npc.data().get<Any>(NPC.Metadata.NAMEPLATE_VISIBLE, true).toString().lowercase()

    private fun hideNativeNameplate(npc: NPC) {
        if (nativeNameplateValue(npc) == "false") return
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false)
        npc.scheduleUpdate(NPCUpdate.PACKET)
    }

    private fun parsePermanentLines(lines: List<String>): Component =
        lines.mapIndexed { index, raw ->
            parseRaw(raw).colorIfAbsent(if (index == 0) LABEL_COLOR else VALUE_COLOR)
        }.joinLines()

    private fun List<Component>.joinLines(): Component =
        foldIndexed(Component.empty()) { index, result, line ->
            if (index == 0) result.append(line) else result.append(Component.newline()).append(line)
        }

    private fun parseRaw(raw: String): Component = when {
        raw.contains('&') || raw.contains('§') -> legacy.deserialize(raw.replace('§', '&'))
        raw.contains('<') -> runCatching { miniMessage.deserialize(raw) }.getOrElse { Component.text(raw) }
        else -> Component.text(raw)
    }

    private fun warnOnce(key: String, message: String, vararg args: Any?) {
        if (warned.add(key)) warn(message, *args)
    }
}

internal fun npcSpeechComponent(lines: List<Component>, fallbackColor: TextColor): Component =
    lines.map { it.colorIfAbsent(fallbackColor) }
        .foldIndexed(Component.empty()) { index, result, line ->
            if (index == 0) result.append(line) else result.append(Component.newline()).append(line)
        }
