package ru.arc.investigation

import kotlin.random.Random

enum class InvestigationVerdict(val commandValue: String) {
    AMOUNT_MISMATCH("amount"),
    FORGED_SEAL("seal"),
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
) {
    val expectedTotal: Int get() = Math.multiplyExact(quantity, unitPrice)

    fun validated(): InvestigationCase {
        require(CASE_PATTERN.matches(caseNumber)) { "Invalid investigation case number" }
        listOf(seller, goods, registeredSeal, documentSeal, registeredWax, documentWax, registeredInitials, documentInitials, oddity)
            .forEach { require(it.isNotBlank() && it.length <= 96 && it.none(Char::isISOControl)) { "Invalid investigation text" } }
        require(quantity in 8..96 && unitPrice in 3..24) { "Invalid investigation arithmetic" }
        require(announcedTotal > 0 && archiveTotal > 0) { "Invalid investigation totals" }
        require(stavrVariant in 0..2 && prokhorVariant in 0..2 && gordeyVariant in 0..2) { "Invalid witness variant" }
        when (verdict) {
            InvestigationVerdict.AMOUNT_MISMATCH -> {
                require(amountTrap != AmountTrap.NONE && sealTrap == SealTrap.NONE) { "Amount case has invalid traps" }
                require(sealMatches()) { "Amount case must have a valid seal" }
                when (amountTrap) {
                    AmountTrap.ARITHMETIC -> require(announcedTotal != expectedTotal && archiveTotal == expectedTotal)
                    AmountTrap.ARCHIVE_COPY -> require(announcedTotal == expectedTotal && archiveTotal != expectedTotal)
                    AmountTrap.NONE -> error("Amount case lacks a trap")
                }
            }

            InvestigationVerdict.FORGED_SEAL -> {
                require(amountTrap == AmountTrap.NONE && sealTrap != SealTrap.NONE) { "Seal case has invalid traps" }
                require(announcedTotal == expectedTotal && archiveTotal == expectedTotal) { "Seal case must have exact totals" }
                require(!sealMatches()) { "Seal case must contain one forged field" }
                require(
                    listOf(
                        registeredSeal != documentSeal,
                        registeredWax != documentWax,
                        registeredInitials != documentInitials,
                    ).count { it } == 1,
                ) { "Seal case must contain exactly one decisive mismatch" }
            }

            InvestigationVerdict.CLEAN -> {
                require(amountTrap == AmountTrap.NONE && sealTrap == SealTrap.NONE) { "Clean case cannot contain a trap" }
                require(announcedTotal == expectedTotal && archiveTotal == expectedTotal && sealMatches()) {
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
            verdict,
        ).joinToString("|")

    fun dossier(): List<String> =
        listOf(
            "<gold><bold>Дело $caseNumber</bold> <dark_gray>· <white>$seller",
            "<gray>Партия: <white>$quantity × $goods <gray>по <white>$unitPrice <gray>монет.",
            "<gray>Итог в ведомости: <white>$announcedTotal<gray>. Печать: <white>$documentWax, $documentSeal, $documentInitials<gray>.",
            "<dark_gray>Странность: $oddity",
        )

    fun testimony(witness: InvestigationWitness): List<String> =
        when (witness) {
            InvestigationWitness.STAVR ->
                when (stavrVariant) {
                    0 -> listOf("<gold>Ставр:</gold> <gray>Я объявлял: <white>$quantity × $unitPrice<gray>, итог <white>$announcedTotal<gray>.", "<dark_gray>$oddity")
                    1 -> listOf("<gold>Ставр:</gold> <gray>Слух не слушай. С помоста звучало: <white>$goods, $quantity штук, $announcedTotal монет<gray>.", "<dark_gray>$oddity")
                    else -> listOf("<gold>Ставр:</gold> <gray>Цена была <white>$unitPrice<gray> за штуку; названный итог — <white>$announcedTotal<gray>.", "<dark_gray>$oddity")
                }

            InvestigationWitness.PROKHOR ->
                when (prokhorVariant) {
                    0 -> listOf("<aqua>Прохор:</aqua> <gray>В архивной копии итог <white>$archiveTotal<gray>.", "<gray>Реестр печатей: <white>$registeredWax, $registeredSeal, $registeredInitials<gray>.")
                    1 -> listOf("<aqua>Прохор:</aqua> <gray>Карточка дела даёт <white>$archiveTotal монет<gray> и знак <white>$registeredSeal<gray>.", "<gray>Воск <white>$registeredWax<gray>, инициалы <white>$registeredInitials<gray>.")
                    else -> listOf("<aqua>Прохор:</aqua> <gray>Сверил два корешка: сумма <white>$archiveTotal<gray>.", "<gray>Допущенная печать — <white>$registeredWax / $registeredSeal / $registeredInitials<gray>.")
                }

            InvestigationWitness.GORDEY ->
                when (gordeyVariant) {
                    0 -> listOf("<red>Гордей:</red> <gray>На самом листе вижу: <white>$documentWax, $documentSeal, $documentInitials<gray>.", "<dark_gray>Бумага цела; $oddity")
                    1 -> listOf("<red>Гордей:</red> <gray>Под лампой проявились <white>$documentSeal<gray> и подпись <white>$documentInitials<gray>.", "<gray>Воск <white>$documentWax<gray>. <dark_gray>$oddity")
                    else -> listOf("<red>Гордей:</red> <gray>Оттиск на ведомости: <white>$documentWax / $documentSeal / $documentInitials<gray>.", "<dark_gray>Следов переклейки нет; $oddity")
                }
        }

    private fun sealMatches(): Boolean =
        registeredSeal == documentSeal && registeredWax == documentWax && registeredInitials == documentInitials

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

            InvestigationVerdict.CLEAN -> Unit
        }

        return InvestigationCase(
            caseNumber = "${CASE_PREFIXES.random(random)}-${random.nextInt(1000, 10000)}",
            seller = SELLERS.random(random),
            goods = GOODS.random(random),
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
            stavrVariant = random.nextInt(3),
            prokhorVariant = random.nextInt(3),
            gordeyVariant = random.nextInt(3),
        ).validated()
    }

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
