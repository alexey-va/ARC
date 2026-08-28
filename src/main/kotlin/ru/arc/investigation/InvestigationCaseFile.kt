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
            }
        }
    }

    internal fun caseFileLore(record: InvestigationJournalRecord): List<String> {
        val story = record.case.narrative
        val briefing = story?.briefing ?: record.case.dossier().map { it.replace(Regex("<[^>]+>"), "") }.take(3)
        val suspiciousLead = story?.suspiciousLead ?: record.case.oddity
        val witnesses =
            record.case.witnesses().mapIndexed { index, witness ->
                val mark = if (record.hasClue(witness)) "<green>✔" else "<yellow>${index + 1}."
                "$mark <white>${witness.displayName} <dark_gray>— <gray>${witness.locationHint}"
            }
        return wrapInvestigationLore(
            listOf(
                "<gold><bold>Что произошло",
            ) + briefing.map { "<gray>$it" } +
                listOf(
                    "",
                    "<yellow><bold>Нужно установить",
                    "<white>${record.case.question()}",
                    "",
                    "<light_purple><bold>Подозрительная зацепка",
                    "<gray>$suspiciousLead",
                    "",
                    "<aqua><bold>Как вести дело",
                    "<white>1. <gray>Найдите отмеченных ниже свидетелей.",
                    "<white>2. <gray>Нажмите на NPC и запишите показание.",
                    "<white>3. <gray>После трёх показаний вернитесь к Фоме.",
                    "<white>4. <gray>Он покажет пять версий — выберите одну.",
                    "<red>Ошибка сразу закрывает дело без награды.",
                    "",
                    "<gold><bold>Кого опросить <gray>· <white>${record.clueCount()}/5",
                ) + witnesses +
                listOf(
                    "",
                    "<gray>Награда: <gold>${formatCaseMoney(record.rewardMinor)} 💰",
                    "<gray>Оставшееся время видно в материалах дела.",
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

    private fun caseFileSlots(player: Player): List<Int> =
        player.inventory.contents.indices.filter { slot -> owner(player.inventory.getItem(slot)) == player.uniqueId }

    private fun transactionId(stack: ItemStack?): String? =
        stack?.itemMeta?.persistentDataContainer?.get(transactionKey, PersistentDataType.STRING)

    private fun owner(stack: ItemStack?): UUID? =
        stack?.itemMeta?.persistentDataContainer?.get(ownerKey, PersistentDataType.STRING)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private val RIGHT_CLICK_ACTIONS = setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)
}

private fun formatCaseMoney(minor: Long): String =
    java.math.BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()
