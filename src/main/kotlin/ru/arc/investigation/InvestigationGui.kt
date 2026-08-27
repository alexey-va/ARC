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
        GuiUtils.constructAndShowAsync({ buildHub(player, latest) }, player)
    }

    fun openConfirmation(player: Player, balanceMinor: Long?, feeMinor: Long, rewardMinor: Long) {
        GuiUtils.constructAndShowAsync({ buildConfirmation(player, balanceMinor, feeMinor, rewardMinor) }, player)
    }

    fun openCase(player: Player, record: InvestigationJournalRecord) {
        GuiUtils.constructAndShowAsync({ buildCase(player, record) }, player)
    }

    internal fun buildHub(player: Player, latest: InvestigationJournalRecord?): ChestGui =
        gui(guiConfig.string("hub.title", "<dark_gray>Палата сделок"), 3, player, guiConfig) {
            background()
            staticPane(width = 9, height = 3) {
                item(4, 0) {
                    style(InvestigationGuiRole.DOSSIER)
                    display("<gold><bold>Ревизорская проба")
                    lore(
                        listOf(
                            "<gray>Фома выдаст случайное торговое дело.",
                            "<gray>За <white>90 секунд <gray>сверьте показания",
                            "<gray>Ставра, Прохора и Гордея.",
                            "",
                            "<yellow>Двух показаний достаточно для риска.",
                            "<gray>Третье обычно разоблачает уловку.",
                        ),
                    )
                }
                item(2, 1) {
                    style(InvestigationGuiRole.START)
                    display("<green><bold>Взять дело")
                    lore(
                        listOf(
                            "<gray>Взнос: <gold>100 <white>💰",
                            "<gray>Награда за вердикт: <gold>300 <white>💰",
                            "<gray>Повтор: <white>раз в 20 часов",
                            "",
                            cooldownLine(latest),
                            "<green>Нажмите для сверки оплаты.",
                        ),
                    )
                    onClick { InvestigationModule.openConfirmation(player) }
                }
                item(6, 1) {
                    style(InvestigationGuiRole.CONTRACTS)
                    display("<aqua><bold>Заказы палаты")
                    lore(
                        listOf(
                            "<gray>Палата покупает чернила, книги",
                            "<gray>и золотые слитки по повышенной ставке.",
                            "",
                            "<aqua>Открыть книгу контрактов.",
                        ),
                    )
                    onClick { InvestigationModule.openContracts(player) }
                }
                item(4, 2) {
                    style(InvestigationGuiRole.BACK)
                    display("<gray>Закрыть")
                    onClick { player.closeInventory() }
                }
            }
        }

    internal fun buildConfirmation(
        player: Player,
        balanceMinor: Long?,
        feeMinor: Long,
        rewardMinor: Long,
    ): ChestGui =
        gui(guiConfig.string("confirmation.title", "<dark_gray>Подтвердить дело"), 3, player, guiConfig) {
            background()
            staticPane(width = 9, height = 3) {
                item(4, 0) {
                    style(InvestigationGuiRole.DOSSIER)
                    display("<gold><bold>Оплата ревизорской пробы")
                    lore(
                        listOf(
                            "<gray>Будет списано: <gold>${InvestigationModule.money(feeMinor)} <white>💰",
                            "<gray>За верный ответ: <gold>${InvestigationModule.money(rewardMinor)} <white>💰",
                            "<gray>Ваш баланс: <white>${balanceMinor?.let(InvestigationModule::money) ?: "недоступен"} <white>💰",
                            "",
                            "<yellow>Таймер и перерыв начнутся",
                            "<yellow>только после подтверждённого списания.",
                        ),
                    )
                }
                item(2, 2) {
                    style(InvestigationGuiRole.CANCEL)
                    display("<red><bold>Отмена")
                    lore(listOf("<gray>Вернуться без списания."))
                    onClick { InvestigationModule.open(player) }
                }
                item(6, 2) {
                    style(InvestigationGuiRole.CONFIRM)
                    display("<green><bold>Оплатить и начать")
                    lore(listOf("<gray>Списать <gold>${InvestigationModule.money(feeMinor)} <white>💰<gray> и открыть дело."))
                    onClick { InvestigationModule.startCase(player) }
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
                    lore(record.case.dossier() + listOf("", timeLine(record), "<gray>Показания: <white>${record.clueCount()}/3"))
                }

                witnessItem(1, player, record, InvestigationWitness.STAVR, InvestigationGuiRole.STAVR, "Глашатай Ставр", "южная стойка")
                witnessItem(4, player, record, InvestigationWitness.PROKHOR, InvestigationGuiRole.PROKHOR, "Архивариус Прохор", "северная лестница")
                witnessItem(7, player, record, InvestigationWitness.GORDEY, InvestigationGuiRole.GORDEY, "Пристав Гордей", "южная лестница")

                verdictItem(1, player, record, InvestigationVerdict.AMOUNT_MISMATCH, InvestigationGuiRole.AMOUNT, "Ошибка в сумме", "Цифры ведомости не сходятся.")
                verdictItem(4, player, record, InvestigationVerdict.FORGED_SEAL, InvestigationGuiRole.SEAL, "Поддельная печать", "Знак, воск или подпись не из реестра.")
                verdictItem(7, player, record, InvestigationVerdict.CLEAN, InvestigationGuiRole.CLEAN, "Сделка чиста", "Подозрительная деталь была уловкой.")

                item(0, 3) {
                    style(InvestigationGuiRole.BACK)
                    display("<gray>Закрыть ведомость")
                    lore(listOf("<dark_gray>Таймер продолжит идти."))
                    onClick { player.closeInventory() }
                }
                item(4, 3) {
                    material(Material.CLOCK)
                    display("<yellow><bold>Правило палаты")
                    lore(
                        listOf(
                            "<gray>После двух показаний можно рискнуть.",
                            "<gray>Ошибочный вердикт сразу закрывает дело.",
                            "<gray>Третье показание помогает отличить",
                            "<gray>нарушение от правдоподобной уловки.",
                        ),
                    )
                }
                item(8, 3) {
                    style(InvestigationGuiRole.CONTRACTS)
                    display("<aqua>Заказы палаты")
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
                        "<dark_gray>Нужно хотя бы два показания."
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
}
