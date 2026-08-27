package ru.arc.investigation

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.gui
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import java.time.Instant

object InvestigationGui {
    private val guiConfig: Config by lazy {
        ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/investigations.yml")
    }

    fun openHub(player: Player, latest: InvestigationJournalRecord?) {
        val policy = InvestigationModule.configOrNull() ?: return
        GuiUtils.constructAndShowAsync({ buildHub(player, latest, policy) }, player)
    }

    fun openCase(player: Player, record: InvestigationJournalRecord) {
        GuiUtils.constructAndShowAsync({ buildCase(player, record) }, player)
    }

    internal fun buildHub(
        player: Player,
        latest: InvestigationJournalRecord?,
        policy: InvestigationConfig,
    ): ChestGui =
        gui(guiConfig.string("hub.title", "<dark_gray>Бюро расследований"), 3, player, guiConfig) {
            background()
            staticPane(width = 9, height = 3) {
                item(4, 0) {
                    style(InvestigationGuiRole.DOSSIER)
                    display("<gold><bold>Ревизорская проба")
                    lore(
                        listOf(
                            "<gray>Фома выдаст случайное торговое дело.",
                            "<gray>За <white>${policy.duration.seconds} секунд <gray>сверьте документы",
                            "<gray>и показания пяти сотрудников бюро.",
                            "",
                            "<yellow>Три показания откроют вердикт.",
                            "<gray>Остальные помогут распознать уловку.",
                        ),
                    )
                }
                item(2, 1) {
                    style(InvestigationGuiRole.START)
                    display("<green><bold>Взять дело")
                    lore(
                        listOf(
                            "<gray>Взнос: <gold>${InvestigationModule.money(policy.feeMinor)} <white>💰",
                            "<gray>Награда за вердикт: <gold>${InvestigationModule.money(policy.rewardMinor)} <white>💰",
                            "<gray>Повтор: <white>${formatCooldown(policy.cooldown.toMinutes())}",
                            "",
                            cooldownLine(latest),
                            "<green>Нажмите, чтобы оплатить и начать.",
                        ),
                    )
                    onClick { InvestigationModule.startCase(player) }
                }
                item(6, 1) {
                    style(InvestigationGuiRole.CONTRACTS)
                    display("<aqua><bold>Заказы бюро")
                    lore(
                        listOf(
                            "<gray>Бюро покупает чернила, книги",
                            "<gray>и золотые слитки по повышенной ставке.",
                            "",
                            "<aqua>Открыть книгу контрактов.",
                        ),
                    )
                    onClick { InvestigationModule.openContracts(player) }
                }
            }
        }

    internal fun buildCase(player: Player, record: InvestigationJournalRecord): ChestGui =
        gui(guiConfig.string("case.title", "<dark_gray>Ревизорская проба"), 4, player, guiConfig) {
            background()
            staticPane(width = 9, height = 4) {
                item(4, 0) {
                    style(InvestigationGuiRole.DOSSIER)
                    display("<gold><bold>Ведомость ${record.case.caseNumber}")
                    lore(record.case.dossier() + listOf("", timeLine(record), "<gray>Показания: <white>${record.clueCount()}/5"))
                }

                witnessItem(0, player, record, InvestigationWitness.STAVR, InvestigationGuiRole.STAVR, "Глашатай Ставр", "стол у входа")
                witnessItem(2, player, record, InvestigationWitness.PROKHOR, InvestigationGuiRole.PROKHOR, "Архивариус Прохор", "стол у входа")
                witnessItem(4, player, record, InvestigationWitness.GORDEY, InvestigationGuiRole.GORDEY, "Пристав Гордей", "патруль первого этажа")
                witnessItem(6, player, record, InvestigationWitness.AGATA, InvestigationGuiRole.AGATA, "Почерковед Агата", "второй этаж")
                witnessItem(8, player, record, InvestigationWitness.TIKHON, InvestigationGuiRole.TIKHON, "Счётовод Тихон", "второй этаж")

                verdictItem(0, player, record, InvestigationVerdict.AMOUNT_MISMATCH, InvestigationGuiRole.AMOUNT, "Ошибка в сумме", "Цифры ведомости не сходятся.")
                verdictItem(2, player, record, InvestigationVerdict.FORGED_SEAL, InvestigationGuiRole.SEAL, "Поддельная печать", "Знак, воск или подпись не из реестра.")
                verdictItem(4, player, record, InvestigationVerdict.CARGO_SUBSTITUTION, InvestigationGuiRole.CARGO, "Подмена груза", "Содержимое или число мест подменено.")
                verdictItem(6, player, record, InvestigationVerdict.DUPLICATE_ENTRY, InvestigationGuiRole.DUPLICATE, "Повторная запись", "Один груз проведён по реестру дважды.")
                verdictItem(8, player, record, InvestigationVerdict.CLEAN, InvestigationGuiRole.CLEAN, "Сделка чиста", "Подозрительная деталь была уловкой.")

                item(4, 3) {
                    material(Material.CLOCK)
                    display("<yellow><bold>Правило бюро")
                    lore(
                        listOf(
                            "<gray>После трёх показаний можно рискнуть.",
                            "<gray>Ошибочный вердикт сразу закрывает дело.",
                            "<gray>Пять показаний позволяют сверить сумму,",
                            "<gray>печать, груз и записи реестра.",
                        ),
                    )
                }
                item(8, 3) {
                    style(InvestigationGuiRole.CONTRACTS)
                    display("<aqua>Заказы бюро")
                    lore(listOf("<gray>Открыть ресурсные контракты.", "<dark_gray>Таймер продолжит идти."))
                    onClick { InvestigationModule.openContracts(player) }
                }
            }
        }

    private fun ru.arc.gui.StaticPaneBuilder.witnessItem(
        x: Int,
        player: Player,
        record: InvestigationJournalRecord,
        witness: InvestigationWitness,
        role: InvestigationGuiRole,
        name: String,
        location: String,
    ) {
        item(x, 1) {
            style(role)
            display(if (record.hasClue(witness)) "<green><bold>$name" else "<yellow><bold>$name")
            lore(
                if (record.hasClue(witness)) {
                    record.case.testimony(witness) + listOf("", "<green>Показание записано.")
                } else {
                    listOf(
                        "<gray>Найдите свидетеля: <white>$location<gray>.",
                        "<gray>Нажмите по NPC, чтобы услышать показание.",
                        "",
                        "<dark_gray>Из ведомости нельзя опрашивать удалённо.",
                    )
                },
            )
            onClick { player.sendActionBar(TextUtil.mm("<yellow>Поговорите со свидетелем лично: <white>$location<yellow>.")) }
        }
    }

    private fun ru.arc.gui.StaticPaneBuilder.verdictItem(
        x: Int,
        player: Player,
        record: InvestigationJournalRecord,
        verdict: InvestigationVerdict,
        role: InvestigationGuiRole,
        name: String,
        description: String,
    ) {
        item(x, 2) {
            style(role)
            display(if (record.clueCount() >= InvestigationService.MIN_CLUES) "<gold><bold>$name" else "<dark_gray><bold>$name")
            lore(
                listOf(
                    "<gray>$description",
                    "",
                    if (record.clueCount() >= InvestigationService.MIN_CLUES) {
                        "<red>Нажмите, чтобы вынести окончательный вердикт."
                    } else {
                        "<dark_gray>Нужно хотя бы три показания."
                    },
                ),
            )
            onClick { InvestigationModule.submit(player, verdict) }
        }
    }

    private fun ru.arc.gui.ItemBuilder.style(role: InvestigationGuiRole) {
        material(role.fallback)
        fromConfig(guiConfig, "items.${role.configKey}")
    }

    private fun cooldownLine(latest: InvestigationJournalRecord?): String {
        val until = latest?.cooldownUntil ?: return "<gray>Готово к выдаче."
        if (until <= System.currentTimeMillis()) return "<gray>Готово к выдаче."
        val seconds = ((until - System.currentTimeMillis()) + 999L) / 1_000L
        val hours = seconds / 3_600L
        val minutes = seconds % 3_600L / 60L
        return "<yellow>Перерыв: <white>${hours}ч ${minutes}м"
    }

    private fun timeLine(record: InvestigationJournalRecord): String {
        val remainingMillis = (requireNotNull(record.expiresAt) - Instant.now().toEpochMilli()).coerceAtLeast(0L)
        val seconds = (remainingMillis + 999L) / 1_000L
        return if (seconds > 20L) "<yellow>Осталось: <white>${seconds}с" else "<red><bold>Осталось: ${seconds}с"
    }

    private fun formatCooldown(minutes: Long): String {
        val hours = minutes / 60L
        val remainder = minutes % 60L
        return when {
            remainder == 0L -> "раз в $hours ч"
            hours == 0L -> "раз в $remainder мин"
            else -> "раз в $hours ч $remainder мин"
        }
    }
}
