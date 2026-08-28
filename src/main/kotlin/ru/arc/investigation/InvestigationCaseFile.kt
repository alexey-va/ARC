package ru.arc.investigation

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.ItemBuilder
import ru.arc.util.TextUtil
import java.util.UUID

/** A bound, replaceable summary of the player's active investigation. */
object InvestigationCaseFile : Listener {
    private val guiConfig: Config by lazy {
        ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/investigations.yml")
    }
    private val transactionKey: NamespacedKey by lazy { NamespacedKey(ARC.instance, "investigation_case") }
    private val ownerKey: NamespacedKey by lazy { NamespacedKey(ARC.instance, "investigation_case_owner") }
    private val expiresAtKey: NamespacedKey by lazy { NamespacedKey(ARC.instance, "investigation_case_expires_at") }

    fun canIssue(player: Player): Boolean = caseFileSlots(player).isNotEmpty() || player.inventory.firstEmpty() >= 0

    fun issue(player: Player, record: InvestigationJournalRecord): Boolean {
        val ownedSlots = caseFileSlots(player)
        val targetSlot = ownedSlots.firstOrNull() ?: player.inventory.firstEmpty()
        if (targetSlot < 0) return false
        ownedSlots.forEach { player.inventory.setItem(it, null) }
        player.inventory.setItem(targetSlot, create(record, player.uniqueId))
        return true
    }

    fun remove(player: Player, expectedTransactionId: String? = null) {
        player.inventory.contents.forEachIndexed { slot, stack ->
            if (owner(stack) == player.uniqueId &&
                (expectedTransactionId == null || transactionId(stack) == expectedTransactionId)
            ) {
                player.inventory.setItem(slot, null)
            }
        }
    }

    internal fun create(record: InvestigationJournalRecord, owner: UUID): ItemStack {
        val builder = ItemBuilder.standalone(guiConfig)
        builder.material(Material.BOOK)
        builder.display("<gold><bold>Дело ${record.case.caseNumber}</bold> <dark_gray>· <white>${record.case.displayTitle()}")
        builder.lore(caseFileLore(record))
        builder.fromConfig(guiConfig, "items.${InvestigationGuiRole.CASE_FILE.configKey}")
        return builder.build().item.also { stack ->
            stack.editMeta { meta ->
                meta.persistentDataContainer.set(transactionKey, PersistentDataType.STRING, record.transactionId)
                meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, owner.toString())
                meta.persistentDataContainer.set(expiresAtKey, PersistentDataType.LONG, requireNotNull(record.expiresAt))
            }
        }
    }

    internal fun caseFileLore(record: InvestigationJournalRecord): List<String> {
        val story = record.case.narrative
        val briefing = story?.briefing ?: record.case.dossier().map { it.replace(Regex("<[^>]+>"), "") }.take(3)
        val humanContext =
            if (story?.requester == null || story.stakes.isNullOrEmpty()) {
                emptyList()
            } else {
                listOf(
                    "",
                    "<aqua><bold>Кто обратился",
                    "<gray>${story.requester}",
                    "",
                    "<light_purple><bold>Почему это важно",
                ) + story.stakes.map { "<gray>$it" }
            }
        val witnesses =
            record.case.witnesses().mapIndexed { index, witness ->
                val mark = if (record.hasClue(witness)) "<white>✔</white>" else "<yellow>${index + 1}."
                "$mark <white>${witness.displayName} <dark_gray>— <gray>${witness.locationHint}"
            }
        return wrapInvestigationLore(
            listOf(
                "<gold><bold>Что произошло",
            ) + briefing.map { "<gray>$it" } +
                humanContext +
                listOf(
                    "",
                    "<yellow><bold>Главный вопрос",
                    "<white>${record.case.question()}",
                    "",
                    "<aqua><bold>Что делать",
                    "<white>1. <gray>Ищите светящихся свидетелей.",
                    "<white>2. <gray>Нажмите на NPC и прочитайте его слова.",
                    "<white>3. <gray>Соберите показания и вернитесь к Фоме.",
                    "<white>4. <gray>Сопоставьте факты и выберите версию.",
                    "<red>Ошибка сразу закрывает дело без награды.",
                    "",
                    "<gold><bold>Кого опросить <gray>· <white>${record.clueCount()}/5",
                ) + witnesses +
                listOf(
                    "",
                    "<gray>Награда: <gold>${formatCaseMoney(record.rewardMinor)} <white>💰</white>",
                    "<gray>Срок: <white>${formatCaseDuration(requireNotNull(record.expiresAt) - requireNotNull(record.activeAt))}",
                    "",
                    "<green><bold>ПКМ предметом — открыть материалы",
                ),
            maxVisibleCharacters = 44,
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action !in RIGHT_CLICK_ACTIONS) return
        val stack = event.item ?: return
        val transactionId = transactionId(stack) ?: return
        event.isCancelled = true
        val player = event.player
        if (owner(stack) != player.uniqueId) {
            player.sendActionBar(TextUtil.mm("<red>Это дело выдано другому следователю."))
            return
        }
        InvestigationModule.openCaseFile(player, transactionId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (transactionId(event.itemDrop.itemStack) == null) return
        event.isCancelled = true
        event.player.sendActionBar(TextUtil.mm("<yellow>Дело нельзя выбросить, пока расследование не закрыто."))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        cleanupExpired(event.player)
        InvestigationModule.scheduleCaseFileCleanup(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        InvestigationTargetGlow.clear(event.player)
    }

    fun cleanupExpired(
        player: Player,
        now: Long = System.currentTimeMillis(),
    ): Int {
        var removed = 0
        player.inventory.contents.forEachIndexed { slot, stack ->
            if (transactionId(stack) == null) return@forEachIndexed
            if (shouldRemoveCaseFile(owner(stack), expiresAt(stack), player.uniqueId, now)) {
                player.inventory.setItem(slot, null)
                removed++
            }
        }
        return removed
    }

    private fun caseFileSlots(player: Player): List<Int> =
        player.inventory.contents.indices.filter { slot -> owner(player.inventory.getItem(slot)) == player.uniqueId }

    private fun transactionId(stack: ItemStack?): String? =
        stack?.itemMeta?.persistentDataContainer?.get(transactionKey, PersistentDataType.STRING)

    private fun owner(stack: ItemStack?): UUID? =
        stack?.itemMeta?.persistentDataContainer?.get(ownerKey, PersistentDataType.STRING)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun expiresAt(stack: ItemStack?): Long? =
        stack?.itemMeta?.persistentDataContainer?.get(expiresAtKey, PersistentDataType.LONG)

    private val RIGHT_CLICK_ACTIONS = setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)
}

internal fun shouldRemoveCaseFile(
    owner: UUID?,
    expiresAt: Long?,
    playerId: UUID,
    now: Long,
): Boolean = owner != playerId || expiresAt == null || expiresAt <= now

private fun formatCaseMoney(minor: Long): String =
    java.math.BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()

private fun formatCaseDuration(millis: Long): String {
    val minutes = (millis.coerceAtLeast(0L) + 59_999L) / 60_000L
    return "$minutes мин"
}
