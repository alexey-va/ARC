package ru.arc.investigation

import kotlin.random.Random

internal data class GeneratedInvestigationStory(
    val seller: String,
    val goods: String,
    val correctVerdict: InvestigationVerdict,
    val narrative: InvestigationNarrative,
)

private data class StoryVariables(
    val seller: String,
    val goods: String,
    val suspect: String,
    val runner: String,
    val lot: Int,
    val color: String,
    val gate: String,
)

private data class ConclusionDraft(
    val title: String,
    val explanation: List<String>,
    val correct: Boolean = false,
)

private data class StoryDraft(
    val plotId: String,
    val title: String,
    val briefing: List<String>,
    val question: String,
    val suspiciousLead: String,
    val timeline: List<InvestigationTimelineBeat>,
    val testimonies: Map<String, InvestigationTestimony>,
    val crossChecks: List<InvestigationCrossCheck>,
    val conclusions: List<ConclusionDraft>,
)

/** Authored plot catalog. Random values and shuffled reconstruction slots make each paid case distinct. */
internal object InvestigationStoryCatalog {
    private val builders: List<(StoryVariables, String) -> StoryDraft> =
        listOf(
            ::sideGateSwitch,
            ::borrowedBell,
            ::twinRegistry,
            ::wetWax,
            ::lockedArchive,
            ::falseReturn,
            ::vanishingWeight,
            ::absentBuyer,
            ::delayedInk,
            ::maskedCrate,
            ::nightCommission,
            ::honestMistake,
        )

    val plotIds: Set<String> =
        setOf(
            "side_gate_switch",
            "borrowed_bell",
            "twin_registry",
            "wet_wax",
            "locked_archive",
            "false_return",
            "vanishing_weight",
            "absent_buyer",
            "delayed_ink",
            "masked_crate",
            "night_commission",
            "honest_mistake",
        )

    fun generate(random: Random, caseNumber: String): GeneratedInvestigationStory {
        val variables =
            StoryVariables(
                seller = SELLERS.random(random),
                goods = GOODS.random(random),
                suspect = SUSPECTS.random(random),
                runner = RUNNERS.random(random),
                lot = random.nextInt(17, 98),
                color = COLORS.random(random),
                gate = GATES.random(random),
            )
        val draft = builders.random(random)(variables, caseNumber)
        require(draft.conclusions.size == InvestigationVerdict.entries.size)
        require(draft.conclusions.count(ConclusionDraft::correct) == 1)

        val shuffledConclusions = draft.conclusions.shuffled(random)
        val conclusions = linkedMapOf<String, InvestigationConclusion>()
        var correctVerdict: InvestigationVerdict? = null
        InvestigationVerdict.entries.forEachIndexed { index, slot ->
            val conclusion = shuffledConclusions[index]
            conclusions[slot.commandValue] = InvestigationConclusion(conclusion.title, conclusion.explanation)
            if (conclusion.correct) correctVerdict = slot
        }
        val correct = requireNotNull(correctVerdict)
        val narrative =
            InvestigationNarrative(
                schemaVersion = InvestigationNarrative.CURRENT_SCHEMA,
                plotId = draft.plotId,
                title = draft.title,
                briefing = draft.briefing,
                question = draft.question,
                suspiciousLead = draft.suspiciousLead,
                timeline = draft.timeline,
                testimonies = draft.testimonies,
                crossChecks = draft.crossChecks,
                conclusions = conclusions,
            ).validated(correct)
        return GeneratedInvestigationStory(variables.seller, variables.goods, correct, narrative)
    }

    private fun sideGateSwitch(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "side_gate_switch",
            title = "Телега у ${v.gate}",
            briefing =
                listOf(
                    "После торгов лот ${v.lot} отправили на склад.",
                    "Утром внутри нашли не ${v.goods}, а камни.",
                    "Пломба и запись дела $case выглядят целыми.",
                ),
            question = "Где и когда подменили груз?",
            lead = "На дворе осталась свежая синяя бечева.",
            timeline =
                timeline(
                    beat(1, "До колокола", InvestigationWitness.STAVR, "${v.seller} показал закрытый лот ${v.lot}"),
                    beat(2, "После торгов", InvestigationWitness.PROKHOR, "В реестр внесли одну опломбированную телегу"),
                    beat(3, "У ворот", InvestigationWitness.GORDEY, "${v.runner} вернул телегу за якобы забытой биркой"),
                    beat(4, "Перед полуночью", InvestigationWitness.AGATA, "Возвратная записка написана поверх старого текста"),
                    beat(5, "На рассвете", InvestigationWitness.TIKHON, "Вес совпал, но тара стала тяжелее товара"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("Лот ${v.lot} был закрыт ещё до объявления.", "После удара колокола я его не открывал."),
                    prokhor = listOf("В книге только один выезд и один приход.", "Возврат между ними никто не зарегистрировал."),
                    gordey = listOf("${v.runner} вернул телегу к ${v.gate}.", "Сказал, что на дышле забыли бирку."),
                    agata = listOf("Слово «бирка» вписано позже остальной записки.", "Чернила совпадают с пером ${v.suspect}."),
                    tikhon = listOf("Утренний вес верен только вместе с новой тарой.", "Камни повторяют вес исчезнувших ${v.goods}."),
                ),
            checks =
                checks(
                    "Ставр подтвердил: до торгов лот был настоящим.",
                    "В реестре нет возврата, который видел Гордей.",
                    "Записку для ворот дописали после торгов.",
                    "Вес сохранили камнями и более тяжёлой тарой.",
                    "Бирка была предлогом для второго въезда.",
                ),
            conclusions =
                conclusions(
                    correct("Подмена при ложном возврате", "${v.runner} вернул телегу после торгов.", "У ворот товар сменили на равный по весу груз."),
                    wrong("Подмена до объявления", "Ставр видел настоящий товар в закрытом лоте."),
                    wrong("Кража на складе", "Утренняя пломба не объясняет незаписанный возврат."),
                    wrong("Ошибка весов", "Камни и новая тара намеренно сохранили общий вес."),
                    wrong("Дело чисто", "Возвратная записка и второй въезд противоречат реестру."),
                ),
        )

    private fun borrowedBell(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "borrowed_bell",
            title = "Колокол на четверть часа раньше",
            briefing =
                listOf(
                    "Лот ${v.lot} исчез между объявлением и выдачей.",
                    "Стража уверена, что закрыла двор по колоколу.",
                    "В деле $case время расходится на четверть часа.",
                ),
            question = "Как груз покинул ещё открытый двор?",
            lead = "Один свидетель слышал два одинаковых удара.",
            timeline =
                timeline(
                    beat(1, "До закрытия", InvestigationWitness.AGATA, "На приказе заранее поставили время отбоя"),
                    beat(2, "Первый звон", InvestigationWitness.STAVR, "Из переулка прозвучала копия закрывающего удара"),
                    beat(3, "Смена караула", InvestigationWitness.GORDEY, "Стражники ушли запирать боковой двор"),
                    beat(4, "Через четверть часа", InvestigationWitness.TIKHON, "Лот ${v.lot} прошёл через главные ворота"),
                    beat(5, "Настоящий звон", InvestigationWitness.PROKHOR, "Архив отметил закрытие после ухода телеги"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("Первый удар был моим ритмом, но не моим колоколом.", "Звук пришёл из переулка, не с помоста."),
                    prokhor = listOf("Архивные часы отмечают закрытие позже.", "Запись дела $case сделана после настоящего звона."),
                    gordey = listOf("По первому удару я отвёл людей к боковому двору.", "Главные ворота ещё не получили приказ закрыться."),
                    agata = listOf("Время на приказе написано до основного текста.", "${v.suspect} подготовил его заранее."),
                    tikhon = listOf("Лот ${v.lot} выдали между двумя ударами.", "Получателем записан ${v.runner}."),
                ),
            checks =
                checks(
                    "Первый звон не совпадает с архивным закрытием.",
                    "Караул ушёл раньше официального приказа.",
                    "Подложное время подготовили до смены караула.",
                    "Выдача прошла в промежутке между звонками.",
                    "Главные ворота были открыты после ложного сигнала.",
                ),
            conclusions =
                conclusions(
                    correct("Ложный звон отвёл караул", "${v.suspect} подготовил ранний приказ.", "${v.runner} вывез лот до настоящего закрытия."),
                    wrong("Стража открыла ворота ночью", "Телега ушла до официального закрытия."),
                    wrong("Ставр ошибся со временем", "Он различил направление и тембр двух звонков."),
                    wrong("Лот не покидал двор", "Тихон записал его выдачу между звонками."),
                    wrong("Архив переписал часы", "Чернила приказа старше, а не моложе записи."),
                ),
        )

    private fun twinRegistry(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "twin_registry",
            title = "Два покупателя одного лота",
            briefing =
                listOf(
                    "${v.goods.replaceFirstChar(Char::titlecase)} продали утром.",
                    "Вечером тот же лот ${v.lot} продали ещё раз.",
                    "Обе выдачи ссылаются на дело $case.",
                ),
            question = "Как одна партия получила две законные выдачи?",
            lead = "У двух записей разные поля, но одна клякса.",
            timeline =
                timeline(
                    beat(1, "Утренние торги", InvestigationWitness.STAVR, "Первый покупатель оплатил лот ${v.lot}"),
                    beat(2, "После оплаты", InvestigationWitness.TIKHON, "Склад выдал всю партию по белому корешку"),
                    beat(3, "Днём", InvestigationWitness.AGATA, "С белого корешка сняли точную копию"),
                    beat(4, "Вечерние торги", InvestigationWitness.GORDEY, "Копию предъявили вместе с пустой телегой"),
                    beat(5, "После закрытия", InvestigationWitness.PROKHOR, "В архив подшили оба корешка под одним номером"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("Название лота звучало дважды, утром и вечером.", "Во второй раз продавцом выступил ${v.suspect}."),
                    prokhor = listOf("Под номером $case лежат два корешка.", "Их края разные, но регистрационная клякса одна."),
                    gordey = listOf("Вечерний покупатель получил пустую телегу.", "${v.runner} убеждал его, что груз ждёт на складе."),
                    agata = listOf("Второй корешок отпечатан с первого.", "Даже случайная клякса повторена точка в точку."),
                    tikhon = listOf("Вся партия ушла утром.", "К вечеру на остатке лота ${v.lot} был ноль."),
                ),
            checks =
                checks(
                    "Объявления доказывают две продажи одного номера.",
                    "Вечерняя телега не могла получить уже выданный груз.",
                    "Второй корешок является механической копией.",
                    "Складской остаток обнулился после первой выдачи.",
                    "Вечером продали только дубликат права на груз.",
                ),
            conclusions =
                conclusions(
                    correct("Продали дубликат корешка", "${v.suspect} скопировал утреннюю запись.", "Вечером он продал право на уже выданный груз."),
                    wrong("Склад выдал груз дважды", "Вечерняя телега ушла пустой."),
                    wrong("Первый покупатель вернул товар", "Возврата и нового остатка в книге нет."),
                    wrong("Совпали два номера", "Клякса доказывает происхождение от одной записи."),
                    wrong("Ошибка глашатая", "Поддельный корешок существовал до второго объявления."),
                ),
        )

    private fun wetWax(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "wet_wax",
            title = "Печать на ещё пустом листе",
            briefing =
                listOf(
                    "Разрешение по делу $case открыло склад ночью.",
                    "Подпись, печать и сумма выглядят правильными.",
                    "Но порядок появления следов на листе невозможен.",
                ),
            question = "Когда изготовили подложное разрешение?",
            lead = "Чернила заходят на сургуч, но не под него.",
            timeline =
                timeline(
                    beat(1, "За день до торгов", InvestigationWitness.STAVR, "Из канцелярии вынесли лист с одной настоящей печатью"),
                    beat(2, "После торгов", InvestigationWitness.PROKHOR, "Архив создал подлинную запись дела $case"),
                    beat(3, "Поздним вечером", InvestigationWitness.AGATA, "Текст дописали вокруг старого сургуча"),
                    beat(4, "Ночью", InvestigationWitness.GORDEY, "${v.runner} предъявил разрешение у склада"),
                    beat(5, "На рассвете", InvestigationWitness.TIKHON, "Со склада исчез лот ${v.lot}"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("За день до торгов вынесли лист с одной печатью.", "Ночного разрешения не объявляли; лот ${v.lot} должен был ждать утра."),
                    prokhor = listOf("Подлинник дела $case всё время лежал в архиве.", "В нём нет разрешения на ночную выдачу."),
                    gordey = listOf("${v.runner} показал лист с настоящим оттиском.", "Я проверил знак, но не возраст чернил."),
                    agata = listOf("Сургуч лёг на чистую бумагу.", "Текст позже обвёл уже застывшую печать.", "Почерк текста принадлежит ${v.suspect}."),
                    tikhon = listOf("Лот ${v.lot} выдан ночью целиком.", "Основанием записан лист, которого нет в архиве."),
                ),
            checks =
                checks(
                    "Торги не создавали ночного разрешения.",
                    "Предъявленный лист отсутствует среди подлинников.",
                    "Печать настоящая, но поставлена до текста.",
                    "Именно подложный лист открыл ночную выдачу.",
                    "Лот должен был оставаться на складе до утра.",
                ),
            conclusions =
                conclusions(
                    correct("Украли заранее отпечатанный лист", "Настоящую печать поставили на пустую бумагу.", "Позже ${v.suspect} дописал ночное разрешение."),
                    wrong("Подделали саму печать", "Оттиск подлинный; неверен порядок печати и текста."),
                    wrong("Гордей открыл склад без документа", "Он видел лист и проверил настоящий знак."),
                    wrong("Архив потерял второй подлинник", "Чернила физически моложе сургуча."),
                    wrong("Выдача была разрешена устно", "Ставр прямо исключил ночную выдачу."),
                ),
        )

    private fun lockedArchive(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "locked_archive",
            title = "Пропажа из запертой комнаты",
            briefing =
                listOf(
                    "После закрытия из архива исчезла опись лота ${v.lot}.",
                    "Дверь всю ночь была заперта и опечатана.",
                    "Утром в папке дела $case нашли чистые листы.",
                ),
            question = "Как опись исчезла из закрытого архива?",
            lead = "На полу есть следы, но они не доходят до шкафа.",
            timeline =
                timeline(
                    beat(1, "До закрытия", InvestigationWitness.PROKHOR, "${v.suspect} попросил опись для повторного счёта"),
                    beat(2, "Перед печатью", InvestigationWitness.TIKHON, "Опись не вернули в папку дела $case"),
                    beat(3, "На обходе", InvestigationWitness.GORDEY, "Архив закрыли и опечатали пустым"),
                    beat(4, "Ночью", InvestigationWitness.STAVR, "Шум у двери поднял ложную тревогу"),
                    beat(5, "Утром", InvestigationWitness.AGATA, "Следы на полу оказались старой известью"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("Ночью я слышал скребущий звук у двери.", "Позже выяснилось: ставня тёрлась о камень."),
                    prokhor = listOf("Перед закрытием ${v.suspect} забрал опись.", "Я пометил выдачу на внутренней обложке."),
                    gordey = listOf("Печать двери не нарушалась.", "Ни один след не пересёк мой свежий песок."),
                    agata = listOf("Белые следы старше ночной уборки.", "Это известь со шкафа, а не свежая пыль."),
                    tikhon = listOf("Я считал без описи и просил вернуть её.", "Ответили, что лист уже лежит в папке."),
                ),
            checks =
                checks(
                    "Шум объясняет ставня, а не ночной взлом.",
                    "Опись отсутствовала ещё до запечатывания.",
                    "Старая известь не является следом вора.",
                    "Тихон подтвердил невозвращённую опись.",
                    "Запертую комнату использовали как ложное алиби.",
                ),
            conclusions =
                conclusions(
                    correct("Опись вынесли до закрытия", "${v.suspect} получил лист для пересчёта.", "Архив заперли уже без него."),
                    wrong("Вор вошёл через окно", "Следы не свежие, а ставня объясняет шум."),
                    wrong("Гордей сорвал и вернул печать", "Песок и оттиск не нарушались."),
                    wrong("Прохор потерял лист утром", "Выдача отмечена до закрытия."),
                    wrong("Опись растворили в архиве", "Она отсутствовала при вечернем счёте."),
                ),
        )

    private fun falseReturn(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "false_return",
            title = "Возврат не той телеги",
            briefing =
                listOf(
                    "${v.seller} вернул лот ${v.lot} после отказа покупателя.",
                    "Склад принял пломбу и номер без замечаний.",
                    "Позже в телеге оказались дешёвые обрезки.",
                ),
            question = "На каком этапе подменили возвращённый лот?",
            lead = "На возвратной бирке две разные дырки от шнура.",
            timeline =
                timeline(
                    beat(1, "На торгах", InvestigationWitness.STAVR, "Покупатель отказался от лота ${v.lot}"),
                    beat(2, "У помоста", InvestigationWitness.GORDEY, "Настоящую телегу отвели в ряд возвратов"),
                    beat(3, "В переулке", InvestigationWitness.AGATA, "Бирку сняли и перевесили на другую телегу"),
                    beat(4, "У склада", InvestigationWitness.TIKHON, "Склад принял номер без проверки тары"),
                    beat(5, "После приёмки", InvestigationWitness.PROKHOR, "${v.suspect} погасил возврат в реестре"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("Покупатель отказался, не вскрывая лот.", "Я видел настоящие ${v.goods} под пологом."),
                    prokhor = listOf("Возврат погасил ${v.suspect}.", "Он сослался на совпавший номер, не на описание тары."),
                    gordey = listOf("Я довёл настоящую телегу до ряда возвратов.", "Дальше её забрал ${v.runner}."),
                    agata = listOf("Бирку перевязывали: старое отверстие вытянуто.", "Новый шнур имеет цвет ${v.color}."),
                    tikhon = listOf("Приёмщик сверил номер, но не метки колёс.", "Колёса возвратной телеги другого размера."),
                ),
            checks =
                checks(
                    "На торгах товар ещё был настоящим.",
                    "После ряда возвратов телегой распоряжался ${v.runner}.",
                    "Бирку физически переносили между телегами.",
                    "Склад проверил номер, но пропустил другую тару.",
                    "Подмена случилась до складской приёмки.",
                ),
            conclusions =
                conclusions(
                    correct("Перевесили бирку на другую телегу", "${v.runner} забрал настоящий возврат.", "Склад получил дешёвую телегу с его биркой."),
                    wrong("Покупатель подменил товар", "Он не вскрывал лот и отказался при свидетелях."),
                    wrong("Склад заменил содержимое", "Другая тара прибыла уже к приёмке."),
                    wrong("Ставр объявил неверный номер", "Номер верен; перенесена сама бирка."),
                    wrong("Возврат был настоящим", "Размер колёс и два отверстия это исключают."),
                ),
        )

    private fun vanishingWeight(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "vanishing_weight",
            title = "Вес, исчезнувший за ночь",
            briefing =
                listOf(
                    "Закрытый лот ${v.lot} к утру стал легче.",
                    "Пломбы целы, а ${v.goods} остались внутри.",
                    "${v.seller} требует признать ночную кражу.",
                ),
            question = "Была ли кража, если никто не вскрывал тару?",
            lead = "Под телегой осталась широкая мокрая полоса.",
            timeline =
                timeline(
                    beat(1, "До торгов", InvestigationWitness.TIKHON, "Тару взвесили сразу после сильного дождя"),
                    beat(2, "На торгах", InvestigationWitness.STAVR, "Объявили вес вместе с мокрым пологом"),
                    beat(3, "После закрытия", InvestigationWitness.GORDEY, "Телега всю ночь стояла под навесом"),
                    beat(4, "Ночью", InvestigationWitness.AGATA, "Вода стекала через неповреждённые швы"),
                    beat(5, "На рассвете", InvestigationWitness.PROKHOR, "Сухой вес совпал со старой нормой партии"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("Я объявил число с первой весовой дощечки.", "Никто не сказал, что полог насквозь мокрый."),
                    prokhor = listOf("Старая норма этой партии равна утреннему весу.", "Разница точно совпадает с водой в пологе."),
                    gordey = listOf("Печати и узлы не трогали.", "Под навесом телега только высохла."),
                    agata = listOf("На швах нет нового прокола.", "Вода выходила по старой ткани сама."),
                    tikhon = listOf("Первый вес сняли сразу после дождя.", "Сухую тару тогда не вычитали."),
                ),
            checks =
                checks(
                    "Объявили мокрый, а не товарный вес.",
                    "Караул не видел вскрытия или смены тары.",
                    "Швы пропускали только воду, не товар.",
                    "Утренний вес совпадает с архивной нормой.",
                    "Потерянная масса равна воде в пологе.",
                ),
            conclusions =
                conclusions(
                    correct("Кражи не было — высох полог", "Первый вес включал дождевую воду.", "К утру тара высохла при целых пломбах."),
                    wrong("Товар вынесли через шов", "Швы не расширяли, а вес товара совпал с нормой."),
                    wrong("Подменили весовую дощечку", "Обе записи объясняет измеренная вода."),
                    wrong("Гордей пропустил ночного вора", "Пломбы, песок и тара не нарушены."),
                    wrong("Продавец заранее облегчил лот", "На торгах лишний вес, а не недостача товара."),
                ),
        )

    private fun absentBuyer(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "absent_buyer",
            title = "Покупатель, которого никто не видел",
            briefing =
                listOf(
                    "Лот ${v.lot} выдали покупателю по имени Радим.",
                    "В книге есть ставка, оплата и подпись Радима.",
                    "Ни один человек на торгах его не помнит.",
                ),
            question = "Кто создал несуществующего покупателя?",
            lead = "Подпись Радима повторяет изгиб номера лота.",
            timeline =
                timeline(
                    beat(1, "Перед торгами", InvestigationWitness.AGATA, "${v.suspect} вывел подпись с цифр старой ведомости"),
                    beat(2, "Во время ставок", InvestigationWitness.STAVR, "Голос Радима звучал из-за закрытой ширмы"),
                    beat(3, "После победы", InvestigationWitness.PROKHOR, "В реестр внесли нового покупателя без адреса"),
                    beat(4, "При выдаче", InvestigationWitness.GORDEY, "${v.runner} предъявил доверенность от Радима"),
                    beat(5, "После выдачи", InvestigationWitness.TIKHON, "Оплата вернулась на счёт ${v.suspect}"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("Ставки Радима передавал голос за ширмой.", "Я ни разу не видел самого покупателя."),
                    prokhor = listOf("Карточка Радима создана в день торгов.", "Адрес и прежние сделки отсутствуют."),
                    gordey = listOf("Груз получил ${v.runner} по доверенности.", "Он не смог описать своего доверителя."),
                    agata = listOf("Подпись собрана из цифр старого лота.", "Так пишет ${v.suspect}, когда копирует формы."),
                    tikhon = listOf("Платёж прошёл кругом через промежуточный счёт.", "В конце монеты вернулись ${v.suspect}."),
                ),
            checks =
                checks(
                    "Ставр слышал посредника, но не видел покупателя.",
                    "Карточка без истории появилась в день торгов.",
                    "Доверенность написана рукой создателя карточки.",
                    "Платёж вернулся тому, кто оформил подпись.",
                    "${v.runner} забрал груз для вымышленного лица.",
                ),
            conclusions =
                conclusions(
                    correct("${v.suspect} выдумал покупателя", "Он создал подпись и замкнул платёж на себя.", "${v.runner} забрал лот по фиктивной доверенности."),
                    wrong("Радим скрывался под ширмой", "У карточки нет адреса, истории и отдельного платежа."),
                    wrong("Гордей выдал груз без бумаг", "Доверенность была, но относилась к вымышленному лицу."),
                    wrong("Ставр придумал победную ставку", "Подпись и денежный круг ведут к другому человеку."),
                    wrong("Покупатель перепродал лот", "Оплата вернулась ещё до возможной перепродажи."),
                ),
        )

    private fun delayedInk(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "delayed_ink",
            title = "Запись, сделанная следующим утром",
            briefing =
                listOf(
                    "Ночная выдача лота ${v.lot} выглядит законной.",
                    "Журнал утверждает, что приказ внесли до закрытия.",
                    "Но одна строка высохла позже соседних страниц.",
                ),
            question = "Что пытались узаконить задним числом?",
            lead = "Песок прилип только к строке ночной выдачи.",
            timeline =
                timeline(
                    beat(1, "Перед закрытием", InvestigationWitness.PROKHOR, "Строки ночной выдачи в книге ещё не было"),
                    beat(2, "После звонка", InvestigationWitness.GORDEY, "${v.runner} вывез лот по устному приказу"),
                    beat(3, "Ночью", InvestigationWitness.STAVR, "Никакого дополнительного приказа не объявляли"),
                    beat(4, "На рассвете", InvestigationWitness.AGATA, "${v.suspect} вписал приказ свежими чернилами"),
                    beat(5, "После записи", InvestigationWitness.TIKHON, "Остаток склада исправили под новую строку"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("После закрытия я не объявлял выдач.", "Устный приказ не проходил через помост."),
                    prokhor = listOf("При вечерней описи место строки было пустым.", "Утром там появилась запись дела $case."),
                    gordey = listOf("${v.runner} сослался на срочное устное распоряжение.", "Бумаги он обещал принести утром."),
                    agata = listOf("Чернила строки свежие, а песок ещё держится.", "Почерк принадлежит ${v.suspect}."),
                    tikhon = listOf("Остаток исправлен тем же свежим пером.", "До правки склад показывал пропажу."),
                ),
            checks =
                checks(
                    "Объявленного приказа ночью не существовало.",
                    "Телегу выпустили до появления записи.",
                    "Свежий почерк связывает приказ с ${v.suspect}.",
                    "Складской остаток подогнали после выдачи.",
                    "Устное распоряжение стало алиби для телеги.",
                ),
            conclusions =
                conclusions(
                    correct("Выдачу узаконили задним числом", "${v.runner} вывез лот без письменного приказа.", "Утром ${v.suspect} дописал книгу и остаток."),
                    wrong("Прохор пропустил вечернюю строку", "Свежие чернила и песок доказывают утреннюю запись."),
                    wrong("Ставр забыл объявление", "Книга была пустой при независимой вечерней описи."),
                    wrong("Лот вернули до рассвета", "Складской остаток исправили, а не восстановили."),
                    wrong("Приказ был, но потерялся", "Его почерк появился только после выдачи."),
                ),
        )

    private fun maskedCrate(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "masked_crate",
            title = "Ящик под знакомым пологом",
            briefing =
                listOf(
                    "Покупатель получил полог и пломбу лота ${v.lot}.",
                    "Под пологом оказался ящик другой формы.",
                    "Склад настаивает, что ничего не менял.",
                ),
            question = "Почему проверили полог, но не сам ящик?",
            lead = "На полозьях осталась краска цвета ${v.color}.",
            timeline =
                timeline(
                    beat(1, "До торгов", InvestigationWitness.TIKHON, "Настоящий ящик получил метку на левом полозе"),
                    beat(2, "На помосте", InvestigationWitness.STAVR, "Лот показывали под закрытым пологом"),
                    beat(3, "После торгов", InvestigationWitness.GORDEY, "${v.runner} сменил тележку за колонной"),
                    beat(4, "Перед выдачей", InvestigationWitness.AGATA, "Пломбу перенесли вместе с краем полога"),
                    beat(5, "В архиве", InvestigationWitness.PROKHOR, "В деле описан ящик, а не внешний полог"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("Публике показали только полог и номер.", "Форму ящика из зала было не видно."),
                    prokhor = listOf("Опись требует низкий ящик с левым клеймом.", "Выданный ящик высокий и без клейма."),
                    gordey = listOf("${v.runner} остановился за колонной на минуту.", "Оттуда вышла тележка с другими полозьями."),
                    agata = listOf("Печать не снимали с ткани.", "Отрезали край полога и пришили к другому."),
                    tikhon = listOf("Настоящее клеймо было на левом полозе.", "У выданного ящика там свежая краска ${v.color}."),
                ),
            checks =
                checks(
                    "Объявление подтверждает лишь внешний полог.",
                    "За колонной появилась тележка другой формы.",
                    "Пломба переехала вместе с куском ткани.",
                    "Архивное описание не совпадает с выданной тарой.",
                    "Свежая краска скрывает отсутствие складского клейма.",
                ),
            conclusions =
                conclusions(
                    correct("Перенесли опломбированный полог", "${v.runner} сменил ящик за колонной.", "Край ткани с пломбой пришили к подмене."),
                    wrong("Склад ошибся формой ящика", "Клеймо и полозья показывают другую тару."),
                    wrong("Пломбу подделали", "Печать настоящая, перенесён её кусок ткани."),
                    wrong("Ставр назвал чужой номер", "Номер полога верен, под ним сменили ящик."),
                    wrong("Покупатель получил правильный лот", "Выданная тара противоречит описи и клейму."),
                ),
        )

    private fun nightCommission(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "night_commission",
            title = "Следственная комиссия без следователей",
            briefing =
                listOf(
                    "Ночью лот ${v.lot} вывезла следственная комиссия.",
                    "Утром бюро не нашло приказа о проверке.",
                    "На воротах остался список из трёх ревизоров.",
                ),
            question = "Как обычная телега стала комиссией?",
            lead = "Все три ревизора расписались одним нажимом.",
            timeline =
                timeline(
                    beat(1, "После торгов", InvestigationWitness.PROKHOR, "${v.suspect} запросил старый бланк комиссии"),
                    beat(2, "Перед ночью", InvestigationWitness.AGATA, "На бланке появились три руки одного автора"),
                    beat(3, "У ворот", InvestigationWitness.GORDEY, "${v.runner} назвался младшим ревизором"),
                    beat(4, "При выезде", InvestigationWitness.STAVR, "Телега потребовала торжественно объявить проверку"),
                    beat(5, "На рассвете", InvestigationWitness.TIKHON, "Комиссия увезла только лот ${v.lot}"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("Меня заставили объявить срочную проверку.", "Текст принёс ${v.runner}; печати бюро на нём не было."),
                    prokhor = listOf("${v.suspect} брал старый пустой бланк.", "Он обещал вернуть его перечёркнутым."),
                    gordey = listOf("Я видел троих в одинаковых плащах.", "Говорил за всех только ${v.runner}."),
                    agata = listOf("Три подписи сделаны одним пером и рукой.", "Все три имитирует почерк ${v.suspect}."),
                    tikhon = listOf("Настоящая комиссия проверяла бы весь ряд.", "Эти люди забрали один заранее выбранный лот."),
                ),
            checks =
                checks(
                    "Объявление опиралось на принесённый текст без печати.",
                    "Старый бланк связывает комиссию с ${v.suspect}.",
                    "Три подписи принадлежат одному человеку.",
                    "Маршрут комиссии не похож на настоящую проверку.",
                    "${v.runner} заранее знал нужный номер лота.",
                ),
            conclusions =
                conclusions(
                    correct("Комиссию сыграли сообщники", "${v.suspect} дал старый бланк и три подписи.", "${v.runner} вывез выбранный лот под видом проверки."),
                    wrong("Бюро потеряло приказ", "У бланка нет печати, а подписи принадлежат одной руке."),
                    wrong("Ставр сам придумал проверку", "Он читал принесённый текст при свидетелях."),
                    wrong("Гордей пропустил обычную телегу", "Ложная комиссия предъявила оформленный бланк."),
                    wrong("Комиссия ошиблась номером", "Она проверила только заранее выбранный лот."),
                ),
        )

    private fun honestMistake(v: StoryVariables, case: String): StoryDraft =
        story(
            id = "honest_mistake",
            title = "Два времени на одной накладной",
            briefing =
                listOf(
                    "Накладная дела $case показывает два времени выдачи.",
                    "Из-за этого ${v.seller} обвиняют в повторном получении.",
                    "Склад утверждает, что партия ушла только однажды.",
                ),
            question = "Это двойная выдача или ошибка разных часов?",
            lead = "Верхняя башня спешит на девять минут.",
            timeline =
                timeline(
                    beat(1, "Башенные 18:40", InvestigationWitness.STAVR, "Ставр объявил окончание торгов"),
                    beat(2, "Складские 18:31", InvestigationWitness.TIKHON, "Склад начал оформлять единственную выдачу"),
                    beat(3, "У ворот 18:36", InvestigationWitness.GORDEY, "Одна телега покинула двор"),
                    beat(4, "После сверки", InvestigationWitness.AGATA, "Обе отметки сделаны одной рукой подряд"),
                    beat(5, "На следующий день", InvestigationWitness.PROKHOR, "Разницу часов внесли в примечание"),
                ),
            testimonies =
                testimony(
                    stavr = listOf("Я записал время верхней башни: 18:40.", "Её часы в тот вечер спешили на девять минут."),
                    prokhor = listOf("В архиве есть акт сверки двух часов.", "Разница полностью объясняет обе отметки."),
                    gordey = listOf("Через ворота прошла одна телега.", "Вторая запись выезда в карауле отсутствует."),
                    agata = listOf("Обе отметки написал один писарь подряд.", "Между ними нет смены пера или дописки."),
                    tikhon = listOf("Склад использовал свои часы: 18:31.", "Остаток уменьшился ровно один раз."),
                ),
            checks =
                checks(
                    "Разные часы описывают один и тот же промежуток.",
                    "Караул видел только одну телегу.",
                    "Вторая отметка не является поздней допиской.",
                    "Складской остаток изменился один раз.",
                    "Объявление и выдача совпадают после поправки часов.",
                ),
            conclusions =
                conclusions(
                    correct("Двойной выдачи не было", "Башня и склад показывали разное время.", "Одна телега получила одну партию."),
                    wrong("Писарь скрыл второй выезд", "Почерк непрерывен, а караул второго выезда не видел."),
                    wrong("Склад дважды уменьшил остаток", "Тихон подтвердил единственное изменение."),
                    wrong("Ставр объявил торги после выдачи", "Поправка часов ставит объявление раньше выезда."),
                    wrong("${v.seller} вернул первую телегу", "Ни ворота, ни склад не фиксируют возврат."),
                ),
        )

    private fun story(
        id: String,
        title: String,
        briefing: List<String>,
        question: String,
        lead: String,
        timeline: List<InvestigationTimelineBeat>,
        testimonies: Map<String, InvestigationTestimony>,
        checks: List<InvestigationCrossCheck>,
        conclusions: List<ConclusionDraft>,
    ): StoryDraft = StoryDraft(id, title, briefing, question, lead, timeline, testimonies, checks, conclusions)

    private fun timeline(vararg beats: InvestigationTimelineBeat): List<InvestigationTimelineBeat> = beats.toList()

    private fun beat(
        order: Int,
        time: String,
        witness: InvestigationWitness,
        event: String,
    ): InvestigationTimelineBeat = InvestigationTimelineBeat(order, time, witness.commandValue, event)

    private fun testimony(
        stavr: List<String>,
        prokhor: List<String>,
        gordey: List<String>,
        agata: List<String>,
        tikhon: List<String>,
    ): Map<String, InvestigationTestimony> =
        linkedMapOf(
            InvestigationWitness.STAVR.commandValue to InvestigationTestimony(stavr),
            InvestigationWitness.PROKHOR.commandValue to InvestigationTestimony(prokhor),
            InvestigationWitness.GORDEY.commandValue to InvestigationTestimony(gordey),
            InvestigationWitness.AGATA.commandValue to InvestigationTestimony(agata),
            InvestigationWitness.TIKHON.commandValue to InvestigationTestimony(tikhon),
        )

    /** A five-edge cycle guarantees that every possible three-witness route unlocks a comparison. */
    private fun checks(
        stavrProkhor: String,
        prokhorGordey: String,
        gordeyAgata: String,
        agataTikhon: String,
        tikhonStavr: String,
    ): List<InvestigationCrossCheck> =
        listOf(
            InvestigationCrossCheck("stavr", "prokhor", stavrProkhor),
            InvestigationCrossCheck("prokhor", "gordey", prokhorGordey),
            InvestigationCrossCheck("gordey", "agata", gordeyAgata),
            InvestigationCrossCheck("agata", "tikhon", agataTikhon),
            InvestigationCrossCheck("tikhon", "stavr", tikhonStavr),
        )

    private fun conclusions(vararg entries: ConclusionDraft): List<ConclusionDraft> = entries.toList()

    private fun correct(title: String, vararg explanation: String): ConclusionDraft =
        ConclusionDraft(title, explanation.toList(), correct = true)

    private fun wrong(title: String, vararg explanation: String): ConclusionDraft =
        ConclusionDraft(title, explanation.toList())

    private val SELLERS =
        listOf("купец Авдей", "артель Ладоги", "лавка Кручины", "мастер Путята", "обоз Милована", "гость Нежата", "двор Вышаты", "дом Яромира")
    private val GOODS =
        listOf("тюки шёлка", "мешки зерна", "ящики красителя", "связки инструментов", "кипы бумаги", "брусья тиса", "короба пряностей", "листы меди")
    private val SUSPECTS = listOf("писарь Добрын", "приёмщик Карп", "оценщик Ладо", "подрядчик Силан", "старший возчик Микула")
    private val RUNNERS = listOf("посыльный Левко", "возчик Твердило", "младший писарь Онфим", "носильщик Ждан", "приказчик Борята")
    private val COLORS = listOf("синего", "алого", "зелёного", "янтарного", "чёрного")
    private val GATES = listOf("северных ворот", "боковой арки", "речного проезда", "старой заставы")
}
