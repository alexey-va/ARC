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
import ru.arc.core.Tasks
import ru.arc.util.Common
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

/**
 * One ARC-owned presentation stack per Citizens NPC: name plus one body layer.
 * Permanent lines and temporary speech reuse the body display, so they cannot
 * overlap. Components are parsed only when content changes; the hot loop only
 * expires bubbles and follows moving NPCs.
 */
internal class ArcNpcHologramService(
    private var config: ArcNpcHologramConfig,
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
        val state: NpcHologramState,
        var backup: Backup,
        var reportHologram: Boolean,
        var name: TextDisplay? = null,
        var body: TextDisplay? = null,
        var nameComponent: Component? = null,
        var sourceBodyComponent: Component? = null,
        var bubbleBodyComponent: Component? = null,
    )

    private val stacks = mutableMapOf<Int, Stack>()
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
        Tasks.scheduler.runLater(1L) { reconcileAll() }
    }

    fun reload(next: ArcNpcHologramConfig) {
        config = next
        followTask?.cancel()
        reconcileTask?.cancel()
        followTask = null
        reconcileTask = null
        stacks.values.forEach(::removeDisplays)
        reconciliationReported = false
        if (config.enabled) {
            closed = false
            startTasks()
            Tasks.scheduler.runLater(1L) { reconcileAll() }
        } else {
            restoreAll()
        }
    }

    fun reconcileAll() {
        if (closed || !config.enabled) return
        val registry = runCatching { CitizensAPI.getNPCRegistry() }.getOrNull() ?: return
        val seen = mutableSetOf<Int>()
        registry.forEach { npc ->
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

    fun showTemporaryBubble(id: Int, lines: List<String>, ttlTicks: Int): Boolean {
        if (closed || !config.enabled) return false
        val visible = lines.map(String::trim).filter(String::isNotEmpty)
        if (visible.isEmpty()) return false
        val npc = CitizensAPI.getNPCRegistry().getById(id) ?: return false
        val stack = stackFor(npc) ?: return false
        stack.state.showBubble(visible, ttlTicks)
        stack.bubbleBodyComponent = parseSpeechLines(visible)
        updateDisplays(stack, npc, refreshText = true)
        return true
    }

    fun hasTemporaryBubble(id: Int): Boolean = stacks[id]?.state?.bubbleActive() == true

    fun patchHologram(npc: NPC, lines: List<String>? = null, lineHeight: Double? = null, viewRange: Int? = null): Boolean {
        val stack = stackFor(npc) ?: return false
        stack.backup = stack.backup.copy(
            hadHologram = true,
            hologramLines = lines?.toList() ?: stack.backup.hologramLines,
            lineHeight = lineHeight ?: stack.backup.lineHeight,
            viewRange = viewRange ?: stack.backup.viewRange,
        )
        stack.reportHologram = true
        saveBackup(npc, stack.backup)
        applyBackup(stack, npc)
        return true
    }

    fun clearHologram(npc: NPC): Boolean {
        val stack = stackFor(npc) ?: return false
        npc.getTraitNullable(HologramTrait::class.java)?.let { npc.removeTrait(HologramTrait::class.java) }
        stack.backup = stack.backup.copy(hadHologram = false, hologramLines = emptyList())
        stack.reportHologram = false
        saveBackup(npc, stack.backup)
        applyBackup(stack, npc)
        return true
    }

    fun patchNameplate(npc: NPC, mode: String): Boolean {
        val stack = stackFor(npc) ?: return false
        val normalized = mode.lowercase()
        stack.backup = stack.backup.copy(
            nameplateValue = normalized,
            forceNameVisible = normalized != "false" && normalized != "hidden",
        )
        saveBackup(npc, stack.backup)
        applyBackup(stack, npc)
        return true
    }

    fun desiredNameplate(npc: NPC): String? = stacks[npc.id]?.backup?.nameplateValue

    fun desiredSpeechBubbles(npc: NPC): Boolean? = stacks[npc.id]?.backup?.speechBubbles

    fun onTextPatched(npc: NPC, desiredSpeechBubbles: Boolean?) {
        val stack = stackFor(npc) ?: return
        val text = npc.getTraitNullable(Text::class.java)
        val desired = desiredSpeechBubbles ?: stack.backup.speechBubbles
        stack.backup = stack.backup.copy(
            speechBubbles = desired,
            sendTextToChat = stack.backup.sendTextToChat ?: text?.sendTextToChat(),
        )
        saveBackup(npc, stack.backup)
        when {
            desired != true -> unbridgeSpeech(npc, text, stack.backup.sendTextToChat)
            text == null -> speechBridges.remove(npc.id)
            else -> bridgeSpeech(npc, text)
        }
    }

    fun summary(npc: NPC): Map<String, Any?>? {
        val stack = stacks[npc.id] ?: return null
        if (!stack.reportHologram) return null
        return linkedMapOf(
            "lines" to stack.backup.hologramLines,
            "lineHeight" to stack.backup.lineHeight,
            "viewRange" to stack.backup.viewRange,
        )
    }

    @EventHandler
    fun onSpawn(event: NPCSpawnEvent) {
        if (!isPrimaryNpc(event.npc)) return
        Tasks.scheduler.runLater(1L) { reconcile(event.npc) }
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
        if (npcSpeechBridgeCancelsChat(stacks[event.npc.id]?.backup?.sendTextToChat)) event.isCancelled = true
    }

    override fun close() {
        if (closed) return
        closed = true
        followTask?.cancel()
        reconcileTask?.cancel()
        followTask = null
        reconcileTask = null
        restoreAll()
        warned.clear()
        reconciliationReported = false
    }

    private fun startTasks() {
        followTask = Tasks.scheduler.runTimer(config.followIntervalTicks, config.followIntervalTicks) { tickStacks() }
        reconcileTask = Tasks.scheduler.runTimer(config.reconcileIntervalTicks, config.reconcileIntervalTicks) { reconcileAll() }
    }

    private fun reconcile(npc: NPC): Stack? {
        if (closed || !config.enabled) return null
        val trait = npc.getTraitNullable(HologramTrait::class.java)
        val existing = stacks[npc.id]
        if (trait != null && hasUnsupportedRenderer(trait)) {
            existing?.let { releaseToNative(npc, it, preserveCurrentTrait = true) }
            warnOnce("renderer:${npc.id}", "ARC NPC hologram migration skipped NPC {}: custom/item Citizens renderer remains native", npc.id)
            return null
        }

        val text = npc.getTraitNullable(Text::class.java)
        val stored = existing?.backup ?: readBackup(npc)
        val desiredSpeech = stored?.speechBubbles ?: text?.useSpeechBubbles()

        var backup = stored ?: captureBackup(npc, trait, text)
        if (backup.sendTextToChat == null && text != null) {
            backup = backup.copy(sendTextToChat = text.sendTextToChat())
        }
        if (trait != null && existing != null) {
            backup = backup.copy(
                hadHologram = true,
                hologramLines = trait.lines.toList(),
                lineHeight = trait.lineHeight,
                viewRange = trait.viewRange,
            )
        }
        val stack = existing ?: Stack(npc.id, NpcHologramState(), backup, backup.hadHologram).also { stacks[npc.id] = it }
        stack.backup = backup
        stack.reportHologram = backup.hadHologram
        saveBackup(npc, backup)
        if (trait != null) npc.removeTrait(HologramTrait::class.java)
        hideNativeNameplate(npc)
        if (backup.speechBubbles == true && text != null) bridgeSpeech(npc, text)
        applyBackup(stack, npc)
        return stack
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

    private fun applyBackup(stack: Stack, npc: NPC) {
        val presentation = resolveNpcHologramPresentation(stack.backup.hologramLines, npc.rawName)
        val showName = stack.backup.forceNameVisible ?: (
            presentation.nameFromAuthoredLine || stack.backup.nameplateValue !in setOf("false", "hidden")
        )
        val source = NpcHologramSource(
            name = presentation.name.takeIf { showName },
            lines = presentation.lines,
            lineHeight = stack.backup.lineHeight,
            viewRange = stack.backup.viewRange,
        )
        val sourceChanged = stack.state.apply(source)
        if (sourceChanged) renderSource(stack)
        updateDisplays(stack, npc, refreshText = sourceChanged)
    }

    private fun stackFor(npc: NPC): Stack? = stacks[npc.id] ?: reconcile(npc)

    private fun isPrimaryNpc(npc: NPC): Boolean =
        runCatching { npc.owningRegistry === CitizensAPI.getNPCRegistry() }.getOrDefault(false)

    private fun tickStacks() {
        if (closed || !config.enabled) return
        stacks.values.toList().forEach { stack ->
            val npc = CitizensAPI.getNPCRegistry().getById(stack.npcId)
            if (npc == null) {
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

    private fun restoreAll() {
        val registry = runCatching { CitizensAPI.getNPCRegistry() }.getOrNull()
        stacks.values.toList().forEach { stack ->
            val npc = registry?.getById(stack.npcId)
            if (npc != null) restoreNative(npc, stack) else removeDisplays(stack)
        }
        stacks.clear()
        speechBridges.clear()
    }

    private fun restoreNative(npc: NPC, stack: Stack) {
        removeDisplays(stack)
        if (stack.backup.hadHologram) {
            val trait = npc.getOrAddTrait(HologramTrait::class.java)
            trait.clear()
            stack.backup.hologramLines.forEach(trait::addLine)
            trait.lineHeight = stack.backup.lineHeight
            trait.viewRange = stack.backup.viewRange
        } else {
            npc.getTraitNullable(HologramTrait::class.java)?.let { npc.removeTrait(HologramTrait::class.java) }
        }
        restoreNativeNameplate(npc, stack.backup.nameplateValue)
        restoreSpeech(npc, stack.backup.speechBubbles, stack.backup.sendTextToChat)
        npc.data().remove(BACKUP_KEY)
    }

    private fun releaseToNative(npc: NPC, stack: Stack, preserveCurrentTrait: Boolean) {
        removeDisplays(stack)
        if (!preserveCurrentTrait || npc.getTraitNullable(HologramTrait::class.java) == null) {
            if (stack.backup.hadHologram) {
                val trait = npc.getOrAddTrait(HologramTrait::class.java)
                trait.clear()
                stack.backup.hologramLines.forEach(trait::addLine)
                trait.lineHeight = stack.backup.lineHeight
                trait.viewRange = stack.backup.viewRange
            }
        }
        restoreNativeNameplate(npc, stack.backup.nameplateValue)
        restoreSpeech(npc, stack.backup.speechBubbles, stack.backup.sendTextToChat)
        npc.data().remove(BACKUP_KEY)
        stacks.remove(npc.id)
        speechBridges.remove(npc.id)
    }

    private fun restoreSpeech(npc: NPC, desired: Boolean?, desiredSendTextToChat: Boolean?) {
        val text = npc.getTraitNullable(Text::class.java) ?: return
        if (desired != null && text.useSpeechBubbles() != desired) text.toggleSpeechBubbles()
        if (desiredSendTextToChat != null && text.sendTextToChat() != desiredSendTextToChat) text.toggleSendTextToChat()
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

    private fun restoreNativeNameplate(npc: NPC, value: String) {
        val restored: Any = when (value.lowercase()) {
            "false", "hidden" -> false
            "hover" -> "hover"
            else -> true
        }
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, restored)
        npc.scheduleUpdate(NPCUpdate.PACKET)
    }

    private fun saveBackup(npc: NPC, backup: Backup) {
        npc.data().setPersistent(BACKUP_KEY, Common.gson.toJson(backup))
    }

    private fun readBackup(npc: NPC): Backup? {
        val raw = npc.data().get<String>(BACKUP_KEY, "").takeIf(String::isNotBlank) ?: return null
        return runCatching { Common.gson.fromJson(raw, Backup::class.java) }
            .onFailure { warnOnce("backup:${npc.id}", "ARC NPC hologram backup is invalid for NPC {}", npc.id) }
            .getOrNull()
    }

    private fun parsePermanentLines(lines: List<String>): Component =
        lines.mapIndexed { index, raw ->
            parseRaw(raw).colorIfAbsent(if (index == 0) LABEL_COLOR else VALUE_COLOR)
        }.joinLines()

    private fun parseSpeechLines(lines: List<String>): Component =
        Component.text("◆ ", HEADER_COLOR).append(
            lines.map { parseRaw(it).colorIfAbsent(VALUE_COLOR) }.joinLines(),
        )

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
