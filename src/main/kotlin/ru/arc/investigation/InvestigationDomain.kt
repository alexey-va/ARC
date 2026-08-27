package ru.arc.investigation

import kotlin.random.Random

enum class InvestigationVerdict(val commandValue: String) {
    AMOUNT_MISMATCH("amount"),
    FORGED_SEAL("seal"),
    CARGO_SUBSTITUTION("cargo"),
    DUPLICATE_ENTRY("duplicate"),
    CLEAN("clean"),
    ;

    companion object {
        fun parse(raw: String?): InvestigationVerdict? = entries.firstOrNull { it.commandValue == raw?.lowercase() }
    }
}

enum class InvestigationWitness(val commandValue: String, val bit: Int) {
    STAVR("stavr", 1),
    PROKHOR("prokhor", 2),
    GORDEY("gordey", 4),
    AGATA("agata", 8),
    TIKHON("tikhon", 16),
    ;

    companion object {
        fun parse(raw: String?): InvestigationWitness? = entries.firstOrNull { it.commandValue == raw?.lowercase() }
    }
}

enum class AmountTrap {
    NONE,
    ARITHMETIC,
    ARCHIVE_COPY,
}

enum class SealTrap {
    NONE,
    SYMBOL,
    WAX,
    INITIALS,
}

enum class CargoTrap {
    NONE,
    GOODS,
    QUANTITY,
}

enum class LedgerTrap {
    NONE,
    DUPLICATE,
}

/** Immutable evidence generated before any money is withdrawn. */
data class InvestigationCase(
    val caseNumber: String,
    val seller: String,
    val goods: String,
    val quantity: Int,
    val unitPrice: Int,
    val announcedTotal: Int,
    val archiveTotal: Int,
    val registeredSeal: String,
    val documentSeal: String,
    val registeredWax: String,
    val documentWax: String,
    val registeredInitials: String,
    val documentInitials: String,
    val oddity: String,
    val amountTrap: AmountTrap,
    val sealTrap: SealTrap,
    val verdict: InvestigationVerdict,
    val stavrVariant: Int,
    val prokhorVariant: Int,
    val gordeyVariant: Int,
    /** Nullable fields keep journal records written by the first live revision readable. */
    val manifestGoods: String? = null,
    val observedGoods: String? = null,
    val manifestQuantity: Int? = null,
    val observedQuantity: Int? = null,
    val ledgerReference: String? = null,
    val duplicateReference: String? = null,
    val cargoTrap: CargoTrap? = null,
    val ledgerTrap: LedgerTrap? = null,
    val agataVariant: Int = 0,
    val tikhonVariant: Int = 0,
) {
    val expectedTotal: Int get() = Math.multiplyExact(quantity, unitPrice)
    val declaredGoods: String get() = manifestGoods ?: goods
    val inspectedGoods: String get() = observedGoods ?: goods
    val declaredQuantity: Int get() = manifestQuantity ?: quantity
    val inspectedQuantity: Int get() = observedQuantity ?: quantity
    val entryReference: String get() = ledgerReference ?: caseNumber
    val effectiveCargoTrap: CargoTrap get() = cargoTrap ?: CargoTrap.NONE
    val effectiveLedgerTrap: LedgerTrap get() = ledgerTrap ?: LedgerTrap.NONE

    fun validated(): InvestigationCase {
        require(CASE_PATTERN.matches(caseNumber)) { "Invalid investigation case number" }
        listOf(seller, goods, registeredSeal, documentSeal, registeredWax, documentWax, registeredInitials, documentInitials, oddity)
            .forEach { require(it.isNotBlank() && it.length <= 96 && it.none(Char::isISOControl)) { "Invalid investigation text" } }
        require(quantity in 8..96 && unitPrice in 3..24) { "Invalid investigation arithmetic" }
        require(announcedTotal > 0 && archiveTotal > 0) { "Invalid investigation totals" }
        listOf(declaredGoods, inspectedGoods, entryReference)
            .forEach { require(it.isNotBlank() && it.length <= 96 && it.none(Char::isISOControl)) { "Invalid investigation text" } }
        require(declaredQuantity in 8..96 && inspectedQuantity in 1..128) { "Invalid cargo quantity" }
        require(duplicateReference == null || CASE_PATTERN.matches(duplicateReference)) { "Invalid duplicate reference" }
        require(stavrVariant in 0..3 && prokhorVariant in 0..3 && gordeyVariant in 0..3) { "Invalid witness variant" }
        require(agataVariant in 0..3 && tikhonVariant in 0..3) { "Invalid specialist variant" }
        when (verdict) {
            InvestigationVerdict.AMOUNT_MISMATCH -> {
                require(amountTrap != AmountTrap.NONE && sealTrap == SealTrap.NONE && effectiveCargoTrap == CargoTrap.NONE && effectiveLedgerTrap == LedgerTrap.NONE) { "Amount case has invalid traps" }
                require(sealMatches()) { "Amount case must have a valid seal" }
                require(cargoMatches() && duplicateReference == null) { "Amount case has conflicting evidence" }
                when (amountTrap) {
                    AmountTrap.ARITHMETIC -> require(announcedTotal != expectedTotal && archiveTotal == expectedTotal)
                    AmountTrap.ARCHIVE_COPY -> require(announcedTotal == expectedTotal && archiveTotal != expectedTotal)
                    AmountTrap.NONE -> error("Amount case lacks a trap")
                }
            }

            InvestigationVerdict.FORGED_SEAL -> {
                require(amountTrap == AmountTrap.NONE && sealTrap != SealTrap.NONE && effectiveCargoTrap == CargoTrap.NONE && effectiveLedgerTrap == LedgerTrap.NONE) { "Seal case has invalid traps" }
                require(announcedTotal == expectedTotal && archiveTotal == expectedTotal) { "Seal case must have exact totals" }
                require(cargoMatches() && duplicateReference == null) { "Seal case has conflicting evidence" }
                require(!sealMatches()) { "Seal case must contain one forged field" }
                require(
                    listOf(
                        registeredSeal != documentSeal,
                        registeredWax != documentWax,
                        registeredInitials != documentInitials,
                    ).count { it } == 1,
                ) { "Seal case must contain exactly one decisive mismatch" }
            }

            InvestigationVerdict.CARGO_SUBSTITUTION -> {
                require(amountTrap == AmountTrap.NONE && sealTrap == SealTrap.NONE && effectiveCargoTrap != CargoTrap.NONE && effectiveLedgerTrap == LedgerTrap.NONE) { "Cargo case has invalid traps" }
                require(announcedTotal == expectedTotal && archiveTotal == expectedTotal && sealMatches()) { "Cargo case has conflicting document evidence" }
                require(!cargoMatches() && duplicateReference == null) { "Cargo case lacks a substitution" }
                require((declaredGoods != inspectedGoods) xor (declaredQuantity != inspectedQuantity)) { "Cargo case must contain one decisive mismatch" }
            }

            InvestigationVerdict.DUPLICATE_ENTRY -> {
                require(amountTrap == AmountTrap.NONE && sealTrap == SealTrap.NONE && effectiveCargoTrap == CargoTrap.NONE && effectiveLedgerTrap == LedgerTrap.DUPLICATE) { "Duplicate case has invalid traps" }
                require(announcedTotal == expectedTotal && archiveTotal == expectedTotal && sealMatches() && cargoMatches()) { "Duplicate case has conflicting evidence" }
                require(duplicateReference != null && duplicateReference != entryReference) { "Duplicate case lacks a linked entry" }
            }

            InvestigationVerdict.CLEAN -> {
                require(amountTrap == AmountTrap.NONE && sealTrap == SealTrap.NONE && effectiveCargoTrap == CargoTrap.NONE && effectiveLedgerTrap == LedgerTrap.NONE) { "Clean case cannot contain a trap" }
                require(announcedTotal == expectedTotal && archiveTotal == expectedTotal && sealMatches() && cargoMatches() && duplicateReference == null) {
                    "Clean case evidence must agree"
                }
            }
        }
        return this
    }

    fun fingerprint(): String =
        listOf(
            seller,
            goods,
            quantity,
            unitPrice,
            announcedTotal,
            archiveTotal,
            registeredSeal,
            documentSeal,
            registeredWax,
            documentWax,
            registeredInitials,
            documentInitials,
            oddity,
            amountTrap,
            sealTrap,
            declaredGoods,
            inspectedGoods,
            declaredQuantity,
            inspectedQuantity,
            entryReference,
            duplicateReference,
            effectiveCargoTrap,
            effectiveLedgerTrap,
            verdict,
        ).joinToString("|")

    fun dossier(): List<String> =
        listOf(
            "<gold><bold>Дело $caseNumber</bold> <dark_gray>· <white>$seller",
            "<gray>Накладная: <white>$declaredQuantity × $declaredGoods <gray>по <white>$unitPrice <gray>монет.",
            "<gray>Итог в ведомости: <white>$announcedTotal<gray>. Печать: <white>$documentWax, $documentSeal, $documentInitials<gray>.",
            "<gray>Запись реестра: <white>$entryReference<gray>.",
            "<dark_gray>Странность: $oddity",
        )

    fun testimony(witness: InvestigationWitness): List<String> =
        when (witness) {
            InvestigationWitness.STAVR ->
                when (stavrVariant) {
                    0 -> listOf("<gold>Ставр:</gold> <gray>Я объявлял: <white>$declaredQuantity × $unitPrice<gray>, итог <white>$announcedTotal<gray>.", "<dark_gray>$oddity")
                    1 -> listOf("<gold>Ставр:</gold> <gray>С помоста звучало: <white>$declaredGoods, $declaredQuantity штук, $announcedTotal монет<gray>.", "<dark_gray>$oddity")
                    2 -> listOf("<gold>Ставр:</gold> <gray>Цена была <white>$unitPrice<gray> за штуку; названный итог — <white>$announcedTotal<gray>.", "<dark_gray>$oddity")
                    else -> listOf("<gold>Ставр:</gold> <gray>Продавец подтвердил партию <white>$declaredQuantity × $declaredGoods<gray>.", "<dark_gray>А вот странность помню: $oddity")
                }

            InvestigationWitness.PROKHOR ->
                when (prokhorVariant) {
                    0 -> listOf("<aqua>Прохор:</aqua> <gray>В архивной копии итог <white>$archiveTotal<gray>.", "<gray>Реестр печатей: <white>$registeredWax, $registeredSeal, $registeredInitials<gray>.")
                    1 -> listOf("<aqua>Прохор:</aqua> <gray>Карточка дела даёт <white>$archiveTotal монет<gray> и знак <white>$registeredSeal<gray>.", "<gray>Воск <white>$registeredWax<gray>, инициалы <white>$registeredInitials<gray>.")
                    2 -> listOf("<aqua>Прохор:</aqua> <gray>Сверил два корешка: сумма <white>$archiveTotal<gray>.", "<gray>Допущенная печать — <white>$registeredWax / $registeredSeal / $registeredInitials<gray>.")
                    else -> listOf("<aqua>Прохор:</aqua> <gray>Карточка <white>$entryReference<gray> зарегистрирована на <white>$archiveTotal монет<gray>.", duplicateReference?.let { "<red>Но такой же корешок уже лежит в деле <white>$it<red>." } ?: "<gray>Повторной карточки в этом окне нет.")
                }

            InvestigationWitness.GORDEY ->
                when (gordeyVariant) {
                    0 -> listOf("<red>Гордей:</red> <gray>На самом листе вижу: <white>$documentWax, $documentSeal, $documentInitials<gray>.", "<dark_gray>Бумага цела; $oddity")
                    1 -> listOf("<red>Гордей:</red> <gray>Под лампой проявились <white>$documentSeal<gray> и подпись <white>$documentInitials<gray>.", "<gray>Воск <white>$documentWax<gray>. <dark_gray>$oddity")
                    2 -> listOf("<red>Гордей:</red> <gray>Оттиск на ведомости: <white>$documentWax / $documentSeal / $documentInitials<gray>.", "<dark_gray>Следов переклейки нет; $oddity")
                    else -> listOf("<red>Гордей:</red> <gray>Лист не вскрывали. На нём <white>$documentWax, $documentSeal, $documentInitials<gray>.", "<dark_gray>Подозрительно выглядит другое: $oddity")
                }

            InvestigationWitness.AGATA ->
                when (agataVariant) {
                    0 -> listOf("<light_purple>Агата:</light_purple> <gray>Почерк и нажим совпадают с образцом <white>$registeredInitials<gray>.", "<gray>На листе стоят инициалы <white>$documentInitials<gray>.")
                    1 -> listOf("<light_purple>Агата:</light_purple> <gray>Под лупой воск выглядит как <white>$documentWax<gray>.", "<gray>В реестре для этого окна указан <white>$registeredWax<gray>.")
                    2 -> listOf("<light_purple>Агата:</light_purple> <gray>Контур документа — <white>$documentSeal<gray>; эталон — <white>$registeredSeal<gray>.", "<dark_gray>Свечной след на полях к оттиску не относится.")
                    else -> listOf("<light_purple>Агата:</light_purple> <gray>Сверка трёх признаков: <white>$documentWax / $documentSeal / $documentInitials<gray>.", "<gray>Эталон: <white>$registeredWax / $registeredSeal / $registeredInitials<gray>.")
                }

            InvestigationWitness.TIKHON ->
                when (tikhonVariant) {
                    0 -> listOf("<blue>Тихон:</blue> <gray>На складе пересчитано: <white>$inspectedQuantity × $inspectedGoods<gray>.", "<gray>Накладная требует <white>$declaredQuantity × $declaredGoods<gray>.")
                    1 -> listOf("<blue>Тихон:</blue> <gray>Бирки груза читаются как <white>$inspectedGoods<gray>, мест <white>$inspectedQuantity<gray>.", duplicateReference?.let { "<yellow>Номер ещё встречается в карточке <white>$it<yellow>." } ?: "<gray>Другого корешка с этим номером нет.")
                    2 -> listOf("<blue>Тихон:</blue> <gray>Приёмка дала <white>$inspectedQuantity<gray> мест; в ведомости <white>$declaredQuantity<gray>.", "<gray>Содержимое: <white>$inspectedGoods<gray>.")
                    else -> listOf("<blue>Тихон:</blue> <gray>Груз и реестр связаны кодом <white>$entryReference<gray>.", duplicateReference?.let { "<red>Этот код уже связан с делом <white>$it<red>." } ?: "<gray>Связь единственная.")
                }
        }

    private fun sealMatches(): Boolean =
        registeredSeal == documentSeal && registeredWax == documentWax && registeredInitials == documentInitials

    private fun cargoMatches(): Boolean = declaredGoods == inspectedGoods && declaredQuantity == inspectedQuantity

    companion object {
        private val CASE_PATTERN = Regex("[А-Я]-[0-9]{4}")
    }
}

object InvestigationCaseGenerator {
    fun generate(
        random: Random,
        previous: InvestigationCase? = null,
    ): InvestigationCase {
        repeat(12) {
            val generated = generateOnce(random)
            if (previous == null || generated.fingerprint() != previous.fingerprint()) return generated
        }
        return generateOnce(random)
    }

    private fun generateOnce(random: Random): InvestigationCase {
        val verdict = InvestigationVerdict.entries.random(random)
        val goods = GOODS.random(random)
        val quantity = QUANTITIES.random(random)
        val unitPrice = UNIT_PRICES.random(random)
        val expected = quantity * unitPrice
        val seal = SEALS.random(random)
        val wax = WAXES.random(random)
        val initials = INITIALS.random(random)
        var announced = expected
        var archive = expected
        var documentSeal = seal
        var documentWax = wax
        var documentInitials = initials
        var amountTrap = AmountTrap.NONE
        var sealTrap = SealTrap.NONE
        var observedGoods = goods
        var observedQuantity = quantity
        var cargoTrap = CargoTrap.NONE
        var ledgerTrap = LedgerTrap.NONE
        var duplicateReference: String? = null

        when (verdict) {
            InvestigationVerdict.AMOUNT_MISMATCH -> {
                amountTrap = listOf(AmountTrap.ARITHMETIC, AmountTrap.ARCHIVE_COPY).random(random)
                val delta = DELTAS.random(random) * if (random.nextBoolean()) 1 else -1
                if (amountTrap == AmountTrap.ARITHMETIC) announced = expected + delta else archive = expected + delta
            }

            InvestigationVerdict.FORGED_SEAL -> {
                sealTrap = listOf(SealTrap.SYMBOL, SealTrap.WAX, SealTrap.INITIALS).random(random)
                when (sealTrap) {
                    SealTrap.SYMBOL -> documentSeal = SEALS.filterNot { it == seal }.random(random)
                    SealTrap.WAX -> documentWax = WAXES.filterNot { it == wax }.random(random)
                    SealTrap.INITIALS -> documentInitials = INITIALS.filterNot { it == initials }.random(random)
                    SealTrap.NONE -> Unit
                }
            }

            InvestigationVerdict.CARGO_SUBSTITUTION -> {
                cargoTrap = listOf(CargoTrap.GOODS, CargoTrap.QUANTITY).random(random)
                if (cargoTrap == CargoTrap.GOODS) {
                    observedGoods = GOODS.filterNot { it == goods }.random(random)
                } else {
                    observedQuantity = (quantity + listOf(-7, -5, -3, 4, 6, 9).random(random)).coerceAtLeast(1)
                }
            }

            InvestigationVerdict.DUPLICATE_ENTRY -> {
                ledgerTrap = LedgerTrap.DUPLICATE
            }

            InvestigationVerdict.CLEAN -> Unit
        }

        val caseNumber = randomCaseNumber(random)
        if (ledgerTrap == LedgerTrap.DUPLICATE) {
            do {
                duplicateReference = randomCaseNumber(random)
            } while (duplicateReference == caseNumber)
        }

        return InvestigationCase(
            caseNumber = caseNumber,
            seller = SELLERS.random(random),
            goods = goods,
            quantity = quantity,
            unitPrice = unitPrice,
            announcedTotal = announced,
            archiveTotal = archive,
            registeredSeal = seal,
            documentSeal = documentSeal,
            registeredWax = wax,
            documentWax = documentWax,
            registeredInitials = initials,
            documentInitials = documentInitials,
            oddity = ODDITIES.random(random),
            amountTrap = amountTrap,
            sealTrap = sealTrap,
            verdict = verdict,
            stavrVariant = random.nextInt(4),
            prokhorVariant = random.nextInt(4),
            gordeyVariant = random.nextInt(4),
            manifestGoods = goods,
            observedGoods = observedGoods,
            manifestQuantity = quantity,
            observedQuantity = observedQuantity,
            ledgerReference = caseNumber,
            duplicateReference = duplicateReference,
            cargoTrap = cargoTrap,
            ledgerTrap = ledgerTrap,
            agataVariant = random.nextInt(4),
            tikhonVariant = random.nextInt(4),
        ).validated()
    }

    private fun randomCaseNumber(random: Random): String =
        "${CASE_PREFIXES.random(random)}-${random.nextInt(1000, 10000)}"

    private val CASE_PREFIXES = listOf("А", "Б", "В", "Г", "Д", "К", "М", "Р", "Т")
    private val SELLERS = listOf("купец Авдей", "артель Ладоги", "лавка Кручины", "мастер Путята", "обоз Милована", "гость Нежата", "двор Вышаты", "торговый дом Яромира")
    private val GOODS = listOf("тюки шёлка", "мешки зерна", "ящики красителя", "связки инструментов", "кипы бумаги", "брусья тиса", "короба пряностей", "листы меди", "мотки шерсти", "бутыли масла")
    private val QUANTITIES = listOf(12, 16, 18, 20, 24, 28, 32, 36, 40, 48, 54, 60, 64, 72, 80, 96)
    private val UNIT_PRICES = listOf(3, 4, 5, 6, 7, 8, 9, 11, 12, 14, 15, 18, 20, 24)
    private val DELTAS = listOf(7, 9, 11, 13, 17, 21, 25, 27, 33, 40)
    private val SEALS = listOf("сокол", "ключ", "ладья", "дубовый лист", "башня", "волчья голова", "звезда", "молот")
    private val WAXES = listOf("алый воск", "синий воск", "зелёный воск", "чёрный воск", "янтарный воск")
    private val INITIALS = listOf("АК", "БР", "ВЛ", "ГС", "ДМ", "КТ", "НР", "ПФ")
    private val ODDITIES =
        listOf(
            "обоз опоздал к первому колоколу",
            "два ящика перевязаны новой бечевой",
            "дата написана бледнее остального текста",
            "партии выдали новый номер после дождя",
            "продавец трижды переспросил имя писаря",
            "на полях остался след от свечного воска",
            "одна бирка пришита ниткой другого цвета",
            "свидетель видел груз у боковых ворот",
            "верхний лист пахнет свежей мятой",
            "на обложке зачёркнуто старое место хранения",
        )
}
