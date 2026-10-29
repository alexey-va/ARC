package ru.arc.helpcenter

/** Authored player documentation. Actions only reference typed, separately authorized routes. */
data class HelpCenterGuideText(
    val title: String,
    val description: String,
    val body: String,
    val keywords: String,
)

internal sealed interface HelpCenterGuideAction {
    data class Command(val id: String) : HelpCenterGuideAction
    data class Page(val page: HelpCenterPage, val labelKey: String) : HelpCenterGuideAction
    data class Topic(val id: String) : HelpCenterGuideAction
    data object SlimefunMenu : HelpCenterGuideAction
}

internal data class HelpCenterGuideTopic(
    val id: String,
    val category: HelpCenterCategory,
    val defaults: HelpCenterGuideText,
    val actions: List<HelpCenterGuideAction>,
    val coveredCommands: Set<String> = emptySet(),
)

internal object HelpCenterGuideCatalog {
    val topics: List<HelpCenterGuideTopic> = listOf(
        HelpCenterGuideTopic(
            "first-steps", HelpCenterCategory.START,
            HelpCenterGuideText(
                "Первые шаги",
                "Набор новичка, своё место и первое занятие.",
                "Получите стартовый набор и выберите мир для выживания. Мир добычи предназначен для ресурсов; постоянную базу стройте в мире выживания.\n\nСохраните дом для возвращения и создайте приват для защиты постройки. Затем выберите профессию, задание или другое занятие в меню.",
                "новичок начало старт как начать обучение набор вещи kit menu",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.GUIDE, "guide-label"), HelpCenterGuideAction.Page(HelpCenterPage.TRAVEL, "travel-label"), HelpCenterGuideAction.Page(HelpCenterPage.PRIVAT, "privat-label")),
            setOf("menu", "kit"),
        ),
        HelpCenterGuideTopic(
            "rules", HelpCenterCategory.START,
            HelpCenterGuideText(
                "Правила и честная игра",
                "Общение, честная игра и правила PvP.",
                "Прочитайте правила перед игрой и общением. Запреты на бой в привате и внутри клана действуют вместе с общими правилами сервера.\n\nЕсли возникла спорная ситуация, сохраните обстоятельства и обратитесь к администрации. Кнопка ниже открывает действующие правила.",
                "правила можно нельзя запрещено грифер жалоба чит pvp",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.RULES, "rules-label")),
            setOf("rules"),
        ),
        HelpCenterGuideTopic(
            "profile", HelpCenterCategory.START,
            HelpCenterGuideText(
                "Профиль и баланс",
                "Монеты, жетоны, ранг, дома и текущий мир.",
                "В профиле собраны ваш ранг, отдельные балансы монет и жетонов, дома, приваты и координаты. Это удобная проверка перед покупкой, путешествием или расширением территории.\n\nДом сохраняет точку телепортации, а приват защищает территорию: одно не заменяет другое.",
                "мой профиль про меня баланс деньги жетоны координаты где я",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.MY, "now-label"), HelpCenterGuideAction.Topic("money")),
        ),
        HelpCenterGuideTopic(
            "recovery", HelpCenterCategory.START,
            HelpCenterGuideText(
                "Если что-то не работает",
                "Проверить телепортацию, приват, игрока или пропавшие вещи.",
                "Откройте «Что случилось?» и выберите проблему. Подсказки учитывают доступные действия, текущий мир и ограничения.\n\nЕсли вы застряли, используйте возврат на спавн. После смерти сначала проверьте могилы; потерянная добыча из данжей хранится отдельно.",
                "помощь проблема застрял потерялся не работает ошибка умер смерть вещи",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.RECOVERY, "recovery-label"), HelpCenterGuideAction.Topic("graves"), HelpCenterGuideAction.Topic("lost-loot")),
            setOf("stuck", "back"),
        ),
        HelpCenterGuideTopic(
            "graves", HelpCenterCategory.START,
            HelpCenterGuideText(
                "Могилы и вещи после смерти",
                "Найти свою могилу и забрать сохранённые вещи.",
                "После смерти проверьте свои могилы. Когда есть активная могила, главное меню показывает отдельную кнопку для неё. Откройте список и проверьте место и доступные действия.\n\nВозврат на прежнее место и могила — разные возможности. Не рассчитывайте на восстановление предметов, которых нет в могиле или другом хранилище.",
                "могила могилы смерть умер вещи инвентарь graves вернуть после смерти",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.ROOT, "main-menu-label"), HelpCenterGuideAction.Page(HelpCenterPage.RECOVERY, "recovery-label")),
        ),
        HelpCenterGuideTopic(
            "lost-loot", HelpCenterCategory.START,
            HelpCenterGuideText(
                "Потерянная добыча",
                "Забрать сохранённую добычу из данжей.",
                "Этот раздел хранит подходящую добычу элитных мобов, которую сервер успел сохранить за вами. Откройте хранилище, заберите нужное и проверьте доступную продажу ненужного.\n\nЭто не общий архив инвентаря: обычные выброшенные вещи и содержимое могил ищутся другими способами. Добыча и расчёты в данжах используют свою валюту — кристаллы.",
                "потерянная добыча лут loot elitemobs вещи пропали кристаллы",
            ),
            listOf(HelpCenterGuideAction.Command("loot"), HelpCenterGuideAction.Topic("graves")),
            setOf("loot"),
        ),
        HelpCenterGuideTopic(
            "worlds", HelpCenterCategory.TRAVEL,
            HelpCenterGuideText(
                "Миры и случайная телепортация",
                "Выбрать мир для базы, ресурсов или исследования.",
                "В разделе телепортации выберите случайное место и нужный мир. Обычный мир и мир новых биомов подходят для выживания; мир добычи служит источником ресурсов и обновляется.\n\nСначала проверьте выбранный мир, затем подтвердите переход. Сохраните подходящее место как дом, если планируете возвращаться.",
                "миры обычный ванильный новые биомы добыча шахта ресурсы ртп rtp random случайное место",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.RTP, "rtp-label"), HelpCenterGuideAction.Page(HelpCenterPage.TRAVEL, "travel-label")),
            setOf("rtp", "spawn"),
        ),
        HelpCenterGuideTopic(
            "homes", HelpCenterCategory.TRAVEL,
            HelpCenterGuideText(
                "Дома",
                "Сохранить точку, вернуться домой или перенести дом.",
                "Откройте телепортацию и создайте дом в текущем месте. В карточке дома можно вернуться к нему, перенести точку сюда или удалить её. Перенос и удаление требуют подтверждения.\n\nКоличество доступных домов показано в меню. Дом не защищает блоки: для этого создайте приват.",
                "дом дома хом home sethome delhome edithome сохранить точку перенести удалить вернуться",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.TRAVEL, "travel-label"), HelpCenterGuideAction.Topic("lands")),
        ),
        HelpCenterGuideTopic(
            "public-homes", HelpCenterCategory.TRAVEL,
            HelpCenterGuideText(
                "Публичные дома",
                "Открыть свою точку для гостей и находить чужие дома.",
                "Выберите свой дом в меню телепортации и сделайте его публичным. После этого другие игроки смогут найти точку в списке публичных домов и телепортироваться к ней.\n\nЧтобы закрыть посещение через этот список, снова сделайте дом личным. Публичный дом сам по себе не даёт гостям права строить в привате.",
                "публичный дом личный открытый гости посетить public privacy",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.TRAVEL, "travel-label"), HelpCenterGuideAction.Topic("land-members")),
        ),
        HelpCenterGuideTopic(
            "player-warps", HelpCenterCategory.TRAVEL,
            HelpCenterGuideText(
                "Варпы игроков",
                "Найти магазин, постройку или открытую точку игрока.",
                "Откройте варпы и выберите интересующее место. Перед переходом проверьте владельца, мир, описание и указанную стоимость телепортации.\n\nВарп отличается от личного дома: это публичная точка для посетителей. Правила и цены владельца смотрите в карточке самой точки.",
                "варпы варп warp warps pw точки посетить магазин телепорт",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.WARPS, "warps-label")),
            setOf("warps"),
        ),
        HelpCenterGuideTopic(
            "player-teleport", HelpCenterCategory.TRAVEL,
            HelpCenterGuideText(
                "Телепортация к игроку",
                "Отправить запрос другу и ответить на входящий запрос.",
                "Найдите игрока в сетевом списке и откройте его карточку. Выберите телепортацию и дождитесь согласия получателя.\n\nВходящие запросы доступны отдельно. Если запросы отключены в личных настройках, проверьте переключатель; ограничения мира или привата тоже могут влиять на переход.",
                "телепорт к другу tpa tpahere принять отклонить запрос игрок",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.PLAYERS, "players-label"), HelpCenterGuideAction.Page(HelpCenterPage.REQUESTS, "requests-short-label"), HelpCenterGuideAction.Page(HelpCenterPage.SETTINGS, "category-settings-label")),
        ),
        HelpCenterGuideTopic(
            "origin-portals", HelpCenterCategory.TRAVEL,
            HelpCenterGuideText(
                "Порталы на спавне",
                "Переходить между игровыми направлениями через порталы.",
                "Вернитесь на спавн и подойдите к нужному порталу. Направление и условия перехода указаны у него; некоторые переходы требуют находиться рядом.\n\nДля Slimefun используйте его портал. После перехода на сервер технологий откройте местное меню острова и гайд. Дом и обычный случайный телепорт не заменяют этот вход.",
                "портал порталы спавн сервер сеть переход слизь slimefun skyblock",
            ),
            listOf(HelpCenterGuideAction.Command("spawn"), HelpCenterGuideAction.Topic("slimefun")),
        ),
        HelpCenterGuideTopic(
            "lands", HelpCenterCategory.PROTECTION,
            HelpCenterGuideText(
                "Приваты и защита построек",
                "Создать поселение и защитить свою территорию.",
                "Выберите постоянное место и откройте «Приваты». Создайте поселение, затем добавьте нужные чанки. До подтверждения проверьте стоимость и доступные лимиты.\n\nУправление участниками, ролями и территорией находится в том же разделе. Сохранённый дом не является приватом.",
                "приват приваты земля lands поселение защита дом гриферы создать claim",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.PRIVAT, "privat-label")),
            setOf("privat"),
        ),
        HelpCenterGuideTopic(
            "land-members", HelpCenterCategory.PROTECTION,
            HelpCenterGuideText(
                "Участники и права привата",
                "Пригласить друга и определить, что ему разрешено.",
                "Откройте нужное поселение и его участников. Пригласите игрока, затем назначьте подходящую роль и проверьте права этой роли.\n\nЕсли доступ больше не нужен, исключите участника. Членство в клане и право строить в конкретном привате — разные настройки.",
                "добавить пригласить друга участник доступ доверить trust untrust исключить права роли",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.PRIVAT, "privat-label"), HelpCenterGuideAction.Page(HelpCenterPage.PLAYERS, "players-label")),
        ),
        HelpCenterGuideTopic(
            "land-growth", HelpCenterCategory.PROTECTION,
            HelpCenterGuideText(
                "Территория и границы",
                "Расширить приват, увидеть чанки и управлять его областями.",
                "В управлении поселением выберите добавление или освобождение территории. Проверяйте, к какому поселению относится выбранный участок, сколько чанков доступно и что стоит действие.\n\nГраницы помогают увидеть занятые чанки. Главный блок и отдельные области настраиваются внутри выбранного поселения.",
                "границы чанки расширить занять освободить участок область главный блок колокол костер",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.PRIVAT, "privat-label"), HelpCenterGuideAction.Command("lands-borders")),
            setOf("lands-borders"),
        ),
        HelpCenterGuideTopic(
            "pvp", HelpCenterCategory.PROTECTION,
            HelpCenterGuideText(
                "PvP, клан и территория",
                "Разобраться, почему бой разрешён или запрещён.",
                "На возможность боя влияют правила территории и отношения внутри клана. Проверьте оба условия, если урон не проходит там, где вы его ожидали.\n\nНастройки привата меняются в его управлении, клана — в меню клана. Для согласованного поединка используйте дуэли и заранее прочитайте их условия.",
                "пвп pvp бой урон не проходит клан территория друзья сражение",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.PRIVAT, "privat-label"), HelpCenterGuideAction.Command("teams"), HelpCenterGuideAction.Topic("duels")),
        ),
        HelpCenterGuideTopic(
            "earning", HelpCenterCategory.TRADE,
            HelpCenterGuideText(
                "Как заработать",
                "Выбрать профессию, подработку, контракт или торговлю.",
                "Профессии оплачивают подходящие игровые действия. Подработки на спавне — отдельные смены у жителей. Ресурсные контракты принимают нужные предметы по текущим условиям.\n\nПредметы можно продавать игрокам или через доступную быструю продажу. Сначала выберите занятие и посмотрите его условия: одинакового гарантированного дохода у этих способов нет.",
                "заработок заработать зарабатывать деньги доход монеты работа подработка профессия продажа",
            ),
            listOf(HelpCenterGuideAction.Topic("jobs"), HelpCenterGuideAction.Topic("odd-jobs"), HelpCenterGuideAction.Topic("resource-contracts"), HelpCenterGuideAction.Topic("sell")),
        ),
        HelpCenterGuideTopic(
            "money", HelpCenterCategory.TRADE,
            HelpCenterGuideText(
                "Деньги и переводы",
                "Проверить баланс, перевести монеты и различать валюты.",
                "Монеты, премиальные жетоны и кристаллы данжей — разные валюты. Их балансы и назначения не складываются в одну сумму. Монеты и жетоны видны в профиле.\n\nДля перевода монет найдите игрока, укажите сумму и проверьте получателя на экране подтверждения. Банковский счёт открывается отдельно.",
                "деньги монеты баланс валюта жетоны токены кристаллы перевод заплатить pay",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.MY, "now-label"), HelpCenterGuideAction.Page(HelpCenterPage.PLAYERS, "players-label"), HelpCenterGuideAction.Topic("bank"), HelpCenterGuideAction.Topic("earning")),
        ),
        HelpCenterGuideTopic(
            "jobs", HelpCenterCategory.TRADE,
            HelpCenterGuideText(
                "Работы и профессии",
                "Получать прогресс за подходящие действия выбранной профессии.",
                "Откройте работы, выберите профессию и изучите действия, которые она учитывает. Следите за активной профессией и её прогрессом; условия начисления показаны в её меню.\n\nЭто длительное развитие профессии. Разовая смена у жителя на спавне относится к подработкам и запускается у самого жителя.",
                "работа работы профессия профессии зарплата заработок деньги ecojobs arcjobs jobs",
            ),
            listOf(HelpCenterGuideAction.Command("jobs"), HelpCenterGuideAction.Topic("odd-jobs")),
            setOf("jobs"),
        ),
        HelpCenterGuideTopic(
            "odd-jobs", HelpCenterCategory.TRADE,
            HelpCenterGuideText(
                "Подработки у жителей",
                "Выполнять отдельные смены в конюшне и кузне.",
                "На спавне найдите Сеню в конюшне или Эдгара в кузне. Зажмите Shift и нажмите по жителю правой кнопкой мыши, затем прочитайте условия смены и начните её.\n\nВыполняйте показанные задания и возвращайтесь к рабочему месту. Состояние и ограничения смены проверяются там же; профессия в меню «Работы» — отдельная механика.",
                "подработка подработки подзаработать смена сеня конюшня эдгар кузня npc работа у жителей",
            ),
            listOf(HelpCenterGuideAction.Command("spawn"), HelpCenterGuideAction.Topic("jobs")),
        ),
        HelpCenterGuideTopic(
            "resource-contracts", HelpCenterCategory.TRADE,
            HelpCenterGuideText(
                "Ресурсные контракты",
                "Сдавать запрошенные ресурсы по действующим заказам.",
                "Откройте доску контрактов и выберите действующую закупку. Проверьте нужный предмет, доступный объём, срок и текущую выплату. Подготовьте ресурсы и следуйте к указанному месту сдачи.\n\nПеред подтверждением ещё раз проверьте принимаемое количество и итог. Личные ограничения и оставшийся объём закупки могут меняться.",
                "контракт контракты ресурсы закупка заказ сдать сдача бюджет ресурсные деньги заработок",
            ),
            listOf(HelpCenterGuideAction.Command("resource-contracts"), HelpCenterGuideAction.Topic("rank-contracts")),
            setOf("resource-contracts"),
        ),
        HelpCenterGuideTopic(
            "shops", HelpCenterCategory.TRADE,
            HelpCenterGuideText(
                "Магазины и покупка",
                "Выбрать товар и проверить стоимость перед покупкой.",
                "Откройте магазины и выберите нужный раздел. Проверьте предмет, количество и итоговую цену перед подтверждением. У торговых жителей и магазинов игроков ассортимент может отличаться.\n\nДля точной покупки по известному пути товара существует /buy <путь> <количество>. Путь берётся из конкретного каталога; он не является названием предмета наугад.",
                "магазин магазины купить покупка buy shop shops товар точное количество",
            ),
            listOf(HelpCenterGuideAction.Command("shops"), HelpCenterGuideAction.Topic("player-shops")),
            setOf("shops"),
        ),
        HelpCenterGuideTopic(
            "sell", HelpCenterCategory.TRADE,
            HelpCenterGuideText(
                "Быстрая продажа",
                "Продать предметы, которые принимает текущий каталог.",
                "Откройте быструю продажу и выберите подходящие предметы. Перед подтверждением проверьте принимаемый ассортимент, количество и итоговую сумму. Не каждый предмет подходит для выкупа сервером.\n\nАукцион продаёт вещи другим игрокам, а ресурсные контракты принимают конкретные заказы. Это отдельные способы торговли.",
                "быстрая продажа быстро продать продажа sell деньги сбыт скупка выкуп",
            ),
            listOf(HelpCenterGuideAction.Command("sell"), HelpCenterGuideAction.Topic("resource-contracts"), HelpCenterGuideAction.Topic("auction")),
            setOf("sell"),
        ),
        HelpCenterGuideTopic(
            "auction", HelpCenterCategory.TRADE,
            HelpCenterGuideText(
                "Аукцион",
                "Покупать и выставлять предметы для других игроков.",
                "Откройте аукцион, найдите предмет и проверьте свойства лота, количество и цену. Для своей продажи используйте управление лотами.\n\nДо выставления прочитайте комиссию и срок. Размещение лота ещё не означает продажу: предмет должен купить другой игрок. Следите за завершёнными и истёкшими лотами.",
                "аукцион ah лот выставить продать купить торговать игроки комиссия",
            ),
            listOf(HelpCenterGuideAction.Command("auction"), HelpCenterGuideAction.Topic("player-shops")),
            setOf("auction"),
        ),
        HelpCenterGuideTopic(
            "bank", HelpCenterCategory.TRADE,
            HelpCenterGuideText(
                "Банк",
                "Управлять личным банковским счётом.",
                "Откройте банк, проверьте баланс счёта и выберите доступную операцию внесения или снятия. Перед подтверждением смотрите сумму и условия в окне банка.\n\nМонеты на счёте и наличный баланс — разные места хранения. Вклад сам по себе не является заработком. Проценты на денежный вклад сейчас отключены.",
                "банк банковский счет счёт вклад хранить деньги положить снять bank",
            ),
            listOf(HelpCenterGuideAction.Command("bank"), HelpCenterGuideAction.Topic("money")),
            setOf("bank"),
        ),
        HelpCenterGuideTopic(
            "player-shops", HelpCenterCategory.TRADE,
            HelpCenterGuideText(
                "Магазины игроков",
                "Торговать через магазины с сундуками.",
                "Найдите магазин игрока и взаимодействуйте с его торговой табличкой или витриной. Проверьте, продаёт он предметы вам или покупает их у вас, а также цену и количество.\n\nПри создании своего магазина следуйте подсказкам у сундука. Доступ к сундуку, запас товара и права на территории влияют на работу магазина.",
                "магазины игроков сундук табличка quickshop покупка продажа свой магазин chestshop",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.WARPS, "warps-label"), HelpCenterGuideAction.Topic("auction")),
        ),
        HelpCenterGuideTopic(
            "autosell", HelpCenterCategory.TRADE,
            HelpCenterGuideText(
                "Автопродажа",
                "Продавать подходящие ресурсы через сундук автопродажи.",
                "Сундук автопродажи использует доступный ассортимент выкупа. Проверьте его описание и настройки, положите подходящие ресурсы и следите за результатом операции.\n\nПредметы, которых нет в принимаемом каталоге, не становятся продаваемыми от помещения в сундук. Условия быстрой продажи и автоматической продажи зависят от текущего выкупа сервера.",
                "автопродажа авто продажа autosell сундук автоматически продать ресурсы",
            ),
            listOf(HelpCenterGuideAction.Topic("sell"), HelpCenterGuideAction.Command("shops")),
        ),
        HelpCenterGuideTopic(
            "ranks", HelpCenterCategory.PROGRESS,
            HelpCenterGuideText(
                "Ранги",
                "Проверить текущий ранг и условия следующего повышения.",
                "Откройте ранги: меню показывает ваше положение и следующую цель. Изучите требования, выполните их и подтвердите повышение, когда оно станет доступно.\n\nПути развития, задания, контракты, усиления и награды находятся в связанных разделах ранга. Условия конкретного уровня проверяйте в его карточке.",
                "ранг ранги rank rankup повысить повышение развитие уровень требования",
            ),
            listOf(HelpCenterGuideAction.Command("rank"), HelpCenterGuideAction.Topic("rank-paths"), HelpCenterGuideAction.Topic("daily-quests")),
            setOf("rank", "rankup"),
        ),
        HelpCenterGuideTopic(
            "rank-paths", HelpCenterCategory.PROGRESS,
            HelpCenterGuideText(
                "Пути развития",
                "Следить за отдельными направлениями прогресса ранга.",
                "В меню ранга откройте пути и выберите направление. Карточка показывает текущий прогресс, следующую цель и подходящие действия.\n\nРазвивайте нужные направления в обычной игре. Не путайте прогресс пути с балансом монет или опытом отдельного навыка.",
                "пути путь мастерство специализация развитие прогресс направление",
            ),
            listOf(HelpCenterGuideAction.Command("rank")),
        ),
        HelpCenterGuideTopic(
            "rank-contracts", HelpCenterCategory.PROGRESS,
            HelpCenterGuideText(
                "Контракты ранга",
                "Выполнять личные цели и забирать награды.",
                "Откройте контракты в меню ранга. Прочитайте личную цель, прогресс, доступную награду и оставшееся время. После выполнения заберите награду через этот раздел.\n\nКонтракты ранга отличаются от ресурсных закупок: у них собственные условия и порядок получения наград. Актуальные ограничения видны в карточке.",
                "контракты ранга недельный контракт задание награда личная цель",
            ),
            listOf(HelpCenterGuideAction.Command("rank"), HelpCenterGuideAction.Topic("resource-contracts")),
        ),
        HelpCenterGuideTopic(
            "daily-quests", HelpCenterCategory.PROGRESS,
            HelpCenterGuideText(
                "Задания и квесты",
                "Выполнять персональные цели и следить за прогрессом.",
                "Откройте квесты, выберите цель и проверьте подходящие действия, награду и оставшееся время. У ежедневных и недельных заданий разные окна выполнения.\n\nПосле выполнения проверьте получение награды в самом задании. Отображение активных целей на экране настраивается отдельно в интерфейсе.",
                "квест квесты задания дейлики ежедневные недельные цели награды quests",
            ),
            listOf(HelpCenterGuideAction.Command("quests"), HelpCenterGuideAction.Page(HelpCenterPage.SETTINGS, "category-settings-label")),
            setOf("quests"),
        ),
        HelpCenterGuideTopic(
            "rank-rewards", HelpCenterCategory.PROGRESS,
            HelpCenterGuideText(
                "Награды и наборы ранга",
                "Проверить доступные награды и возможность повторного получения.",
                "В меню ранга откройте награды или наборы. Проверьте, что доступно вашему рангу и сколько осталось ждать до следующего получения.\n\nПолучайте награду через её кнопку и проверяйте результат. Награды за задания, контракты и голосования открываются в своих разделах.",
                "награда награды набор кит еженедельный weekly kit бонус получить забрать",
            ),
            listOf(HelpCenterGuideAction.Command("rank"), HelpCenterGuideAction.Page(HelpCenterPage.REQUESTS, "requests-short-label")),
        ),
        HelpCenterGuideTopic(
            "boosts", HelpCenterCategory.PROGRESS,
            HelpCenterGuideText(
                "Усиления",
                "Посмотреть действующие усиления и условия активации.",
                "Откройте усиления в меню ранга. Перед покупкой или активацией проверьте эффект, срок, стоимость и используемую валюту.\n\nРазные усиления меняют разные действия. Их длительность и совместимость смотрите в текущем меню; усиление не означает выплату за любое действие.",
                "усиления бусты boost множитель опыт профессия деньги эффект",
            ),
            listOf(HelpCenterGuideAction.Command("rank"), HelpCenterGuideAction.Topic("jobs")),
        ),
        HelpCenterGuideTopic(
            "skills", HelpCenterCategory.PROGRESS,
            HelpCenterGuideText(
                "Навыки",
                "Развивать способности обычными игровыми действиями.",
                "Откройте навыки и выберите интересующий. Меню показывает уровень, прогресс и связанные способности. Выполняйте действия этого навыка, чтобы продвигаться дальше.\n\nНавык, профессия и ранг развиваются отдельно. Их условия и эффекты смотрите в соответствующих разделах.",
                "навыки auraskills skills способности умения прокачка опыт уровень",
            ),
            listOf(HelpCenterGuideAction.Command("skills"), HelpCenterGuideAction.Topic("jobs")),
            setOf("skills"),
        ),
        HelpCenterGuideTopic(
            "players", HelpCenterCategory.SOCIAL,
            HelpCenterGuideText(
                "Игроки сети",
                "Найти друга, открыть его действия и пригласить в игру.",
                "Откройте список игроков и введите ник или часть ника. Список позволяет искать на всей сети или только на текущем сервере.\n\nВ карточке игрока доступны подходящие действия: сообщение, запрос телепортации, перевод монет, дуэль и приглашение в поселение. Перед переводом проверьте получателя и сумму.",
                "игрок игроки друг друзья онлайн найти ник пригласить",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.PLAYERS, "players-label"), HelpCenterGuideAction.Topic("money")),
        ),
        HelpCenterGuideTopic(
            "messages", HelpCenterCategory.SOCIAL,
            HelpCenterGuideText(
                "Чат и личные сообщения",
                "Выбрать канал общения или написать конкретному игроку.",
                "Локальный чат предназначен для общения рядом, глобальный — для общего общения сети. Текущий канал можно выбрать в настройках.\n\nДля личного сообщения найдите игрока и откройте его карточку. Соблюдайте правила общения; сообщения в публичном канале видит не только выбранный собеседник.",
                "чат локальный глобальный общий лс личка написать сообщение msg g l",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.PLAYERS, "players-label"), HelpCenterGuideAction.Page(HelpCenterPage.SETTINGS, "category-settings-label")),
            setOf("chat-global", "chat-local"),
        ),
        HelpCenterGuideTopic(
            "clans", HelpCenterCategory.SOCIAL,
            HelpCenterGuideText(
                "Кланы",
                "Играть вместе и управлять составом своего клана.",
                "Откройте кланы, создайте свой или примите приглашение. В меню доступны участники и действия, разрешённые вашей роли.\n\nДля совместной стройки отдельно выдайте права на нужный приват. Настройки клана и территории могут влиять на PvP между участниками.",
                "клан кланы гильдия команда teams clans guild участники друзья вступить",
            ),
            listOf(HelpCenterGuideAction.Command("teams"), HelpCenterGuideAction.Topic("land-members")),
            setOf("teams"),
        ),
        HelpCenterGuideTopic(
            "requests", HelpCenterCategory.SOCIAL,
            HelpCenterGuideText(
                "Входящие запросы",
                "Отвечать на запросы и проверять доступные награды.",
                "Откройте входящие запросы и выберите нужный. Перед принятием проверьте отправителя и действие, на которое вы соглашаетесь.\n\nЗдесь же собраны переходы к доступным наградам и заданиям. Если ожидаемый запрос не появляется, проверьте личные настройки и состояние соответствующего раздела.",
                "входящие запросы приглашения принять отклонить tpa награды",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.REQUESTS, "requests-short-label"), HelpCenterGuideAction.Page(HelpCenterPage.SETTINGS, "category-settings-label")),
        ),
        HelpCenterGuideTopic(
            "dungeons", HelpCenterCategory.ACTIVITIES,
            HelpCenterGuideText(
                "Данжи и элитные мобы",
                "Подготовиться к походу, собрать группу и получить добычу.",
                "Откройте данжи, изучите гайд и выберите портал. В подробном руководстве описаны подготовка, снаряжение, бой, группа, навыки и награды.\n\nПоходы начинаются через предусмотренные порталы. С выживания сначала перейдите к ним на спавн; потерянную сохранённую добычу можно проверить отдельно.",
                "данж данжи подземелье боссы elite elitemobs мобы кристаллы группа поход",
            ),
            listOf(HelpCenterGuideAction.Command("dungeons"), HelpCenterGuideAction.Page(HelpCenterPage.DUNGEONS_GUIDE, "dungeons-guide-label"), HelpCenterGuideAction.Topic("lost-loot")),
            setOf("dungeons"),
        ),
        HelpCenterGuideTopic(
            "events", HelpCenterCategory.ACTIVITIES,
            HelpCenterGuideText(
                "События",
                "Участвовать в доступных серверных событиях.",
                "Откройте события и выберите доступное. Перед входом прочитайте правила, состояние и условия участия.\n\nСостав событий меняется: отсутствие активного события означает, что сейчас вход не открыт. Результаты и награды проверяйте в самом событии.",
                "событие события ивент event minigame миниигра участвовать",
            ),
            listOf(HelpCenterGuideAction.Command("events")),
            setOf("events"),
        ),
        HelpCenterGuideTopic(
            "duels", HelpCenterCategory.ACTIVITIES,
            HelpCenterGuideText(
                "Дуэли",
                "Вызвать игрока на согласованный поединок.",
                "Откройте дуэли или карточку игрока и отправьте вызов. Согласуйте доступные условия и дождитесь принятия.\n\nПеред началом прочитайте правила выбранного режима. Дуэль и обычный бой в мире могут иметь разные ограничения.",
                "дуэль дуэли поединок pvp пвп сразиться арена бой",
            ),
            listOf(HelpCenterGuideAction.Command("duels"), HelpCenterGuideAction.Page(HelpCenterPage.PLAYERS, "players-label")),
            setOf("duels"),
        ),
        HelpCenterGuideTopic(
            "parkour", HelpCenterCategory.ACTIVITIES,
            HelpCenterGuideText(
                "Паркур",
                "Выбрать трассу и проходить её через контрольные точки.",
                "Откройте паркур и выберите доступную трассу. Следуйте её маршруту и правилам; контрольные точки и результат принадлежат выбранной трассе.\n\nПеред стартом проверьте условия завершения и награды. Для возвращения используйте штатный выход из прохождения.",
                "паркур parkour pa pkr прыжки трасса контрольные точки рекорд",
            ),
            listOf(HelpCenterGuideAction.Command("parkour")),
            setOf("parkour"),
        ),
        HelpCenterGuideTopic(
            "farms", HelpCenterCategory.ACTIVITIES,
            HelpCenterGuideText(
                "Ферма и шахта",
                "Выполнять общие заказы и пользоваться шахтным лифтом.",
                "На спавне подойдите к порталу фермы или шахты. Это общие занятия: участники выполняют один заказ и продвигают его вместе.\n\nНа ферме следуйте этапам подготовки, посадки, ухода и сбора. В шахте выполняйте разведку, добычу и погрузку по текущим подсказкам. У лифта вызовите кабину и выберите этаж; меню ниже показывает доступные занятия.",
                "ферма фермы урожай шахта рудник руда добыча общие заказы minelift лифт этаж кабина arcfarms",
            ),
            listOf(HelpCenterGuideAction.Command("farms"), HelpCenterGuideAction.Command("spawn")),
            setOf("farms"),
        ),
        HelpCenterGuideTopic(
            "civs", HelpCenterCategory.ACTIVITIES,
            HelpCenterGuideText(
                "Города с жителями",
                "Строить город, снабжать здания и назначать жителей.",
                "Откройте города с жителями и изучите встроенный гайд. Основав город, выбирайте здания, привозите материалы на стройку и следите за очередью работ.\n\nЖителям нужны назначение и снабжение; запасы, склады и перенос ресурсов проверяются в управлении города. Территория города и защитный приват требуют отдельных настроек.",
                "город города жители поселение civs city здание ратуша склад рабочие снабжение",
            ),
            listOf(HelpCenterGuideAction.Command("civs"), HelpCenterGuideAction.Topic("lands")),
            setOf("civs"),
        ),
        HelpCenterGuideTopic(
            "casino", HelpCenterCategory.ACTIVITIES,
            HelpCenterGuideText(
                "Игры в казино",
                "Найти физический стол и посмотреть правила игры.",
                "На спавне посетите казино и взаимодействуйте с игровым столом или автоматом. Прочитайте правила, выберите доступное место и проверьте ставку до подтверждения.\n\nРезультат игры не гарантирует выигрыш. Для игры с друзьями используйте приглашения самого стола; административная команда казино для участия не нужна.",
                "казино карты дурак боулинг рулетка блекджек блэкджек покер слоты автоматы ставка игры",
            ),
            listOf(HelpCenterGuideAction.Command("spawn")),
        ),
        HelpCenterGuideTopic(
            "votes", HelpCenterCategory.ACTIVITIES,
            HelpCenterGuideText(
                "Голосование за сервер",
                "Открыть доступные площадки и проверить награду за голос.",
                "Откройте голосование и выберите площадку. Перейдите по её ссылке, выполните условия и дождитесь подтверждения голоса.\n\nДоступность следующего голосования и получение награды проверяйте в этом разделе. Разные площадки могут принимать голоса в разное время.",
                "голос голосовать голосование vote votes рейтинг мониторинг награда",
            ),
            listOf(HelpCenterGuideAction.Command("vote")),
            setOf("vote"),
        ),
        HelpCenterGuideTopic(
            "giveaways", HelpCenterCategory.ACTIVITIES,
            HelpCenterGuideText(
                "Розыгрыши",
                "Посмотреть текущие розыгрыши и условия участия.",
                "Откройте розыгрыши и выберите действующий. Проверьте приз, срок и требования, затем выполните доступное действие участия.\n\nУчастие не гарантирует победу. После завершения проверьте результат и способ получения приза в самом розыгрыше.",
                "розыгрыш розыгрыши giveaway конкурс приз участие",
            ),
            listOf(HelpCenterGuideAction.Command("giveaways")),
            setOf("giveaways"),
        ),
        HelpCenterGuideTopic(
            "slimefun", HelpCenterCategory.TECHNOLOGY,
            HelpCenterGuideText(
                "Slimefun: технологии",
                "Механизмы, рецепты и развитие на отдельном сервере технологий.",
                "На спавне найдите портал Slimefun и подойдите к нему для перехода. Уже на сервере Slimefun откройте местное меню: там находятся остров, гайд и игровые сервисы.\n\nИзучайте рецепты и устройство механизмов в гайде технологий. Наличие отдельных предметов Slimefun на спавне не заменяет вход в этот игровой режим.",
                "slimefun слайм фан слаймфан слаймфун слимфан слимефун sf сф технологии механизмы рецепты",
            ),
            listOf(HelpCenterGuideAction.SlimefunMenu, HelpCenterGuideAction.Command("spawn"), HelpCenterGuideAction.Topic("skyblock")),
            setOf("slimefun"),
        ),
        HelpCenterGuideTopic(
            "skyblock", HelpCenterCategory.TECHNOLOGY,
            HelpCenterGuideText(
                "Остров Slimefun",
                "Создать остров и управлять его развитием.",
                "Сначала перейдите через портал Slimefun на спавне. На сервере технологий откройте местное меню /skyblock: выберите создание острова или управление существующим.\n\nГости, участники и сервисы острова настраиваются в местном меню. Перед крупными действиями прочитайте условия; остров не является приватом обычного выживания.",
                "skyblock sky block скайблок скай блок остров острова island sb slimefun",
            ),
            listOf(HelpCenterGuideAction.SlimefunMenu, HelpCenterGuideAction.Topic("slimefun")),
        ),
        HelpCenterGuideTopic(
            "items", HelpCenterCategory.TECHNOLOGY,
            HelpCenterGuideText(
                "Предмет в руке и рецепты",
                "Узнать назначение предмета и найти связанные действия.",
                "Возьмите предмет в основную руку и откройте его раздел. Доступные рецепты, каталог и торговые действия зависят от самого предмета и установленных возможностей.\n\nОписание предмета подсказывает назначение и условия использования. Особые предметы, зачарования и механизмы могут иметь собственные руководства.",
                "предмет предметы рецепт рецепты крафт itemsadder ia что это назначение в руке",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.ITEM, "context-item-label"), HelpCenterGuideAction.Topic("slimefun"), HelpCenterGuideAction.Topic("enchants")),
            setOf("items"),
        ),
        HelpCenterGuideTopic(
            "enchants", HelpCenterCategory.TECHNOLOGY,
            HelpCenterGuideText(
                "Зачарования",
                "Подобрать улучшение для оружия, брони или инструмента.",
                "Откройте зачарования и выберите тип снаряжения. Карточка объясняет эффект, подходящие предметы, уровни и способы получения.\n\nПеред применением проверьте совместимость и условия самой книги или услуги. Особые предметы и полезные расходники представлены в том же справочнике.",
                "зачарование зачарования чары энчанты enchants ae advancedenchantments меч броня копье копьё",
            ),
            listOf(HelpCenterGuideAction.Command("enchants"), HelpCenterGuideAction.Command("enchanter")),
            setOf("enchants", "enchanter"),
        ),
        HelpCenterGuideTopic(
            "builder", HelpCenterCategory.TECHNOLOGY,
            HelpCenterGuideText(
                "Инструменты строителя",
                "Выбирать область и выполнять доступные строительные операции.",
                "Откройте инструменты строителя и получите селектор. Отметьте две точки области, затем выберите доступную операцию заполнения, замены, копирования или разборки.\n\nДо подтверждения проверяйте размер области, материалы и права на территории. Строительные книги — отдельный способ перенести подготовленный проект.",
                "строитель builder arcbuild селектор wand выделение заполнить заменить копировать разборка",
            ),
            listOf(HelpCenterGuideAction.Command("builder"), HelpCenterGuideAction.Topic("build-books")),
            setOf("builder"),
        ),
        HelpCenterGuideTopic(
            "build-books", HelpCenterCategory.TECHNOLOGY,
            HelpCenterGuideText(
                "Строительные книги",
                "Использовать готовый проект постройки.",
                "Изучите описание строительной книги и её требования. Выберите место, проверьте положение будущей постройки и наличие материалов перед началом.\n\nСоздание и использование проектов зависит от доступных прав строителя. Книга не отменяет защиту территории и проверку ресурсов.",
                "строительная книга книга проекта чертеж чертёж схема schematic buildbook постройка",
            ),
            listOf(HelpCenterGuideAction.Command("builder"), HelpCenterGuideAction.Topic("lands")),
        ),
        HelpCenterGuideTopic(
            "mounts", HelpCenterCategory.TECHNOLOGY,
            HelpCenterGuideText(
                "Маунты",
                "Выбирать ездовых существ и управлять коллекцией.",
                "Откройте маунтов, выберите существо и прочитайте его свойства. В карточке видны доступность, уровень и действия управления.\n\nПеред открытием или улучшением проверьте цену и валюту. Способности маунта и ограничения использования зависят от его вида и текущего места.",
                "маунт маунты mount mounts транспорт ездовые существа питомец конь летать коллекция",
            ),
            listOf(HelpCenterGuideAction.Command("mounts")),
            setOf("mounts"),
        ),
        HelpCenterGuideTopic(
            "controls", HelpCenterCategory.SETTINGS,
            HelpCenterGuideText(
                "Управление меню",
                "Настроить Shift + F, Escape и взаимодействия.",
                "В настройках управления выберите действие сочетания Shift + F и поведение Escape. Возврат ведёт к предыдущему экрану; закрытие завершает весь поток меню.\n\nТам же настраиваются доступные взаимодействия с табличками и ступеньками. Выбранный режим подписан словами рядом с настройкой.",
                "настройки управление shift f escape esc горячая клавиша закрыть назад таблички сидеть ступеньки",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.SETTINGS, "category-settings-label")),
        ),
        HelpCenterGuideTopic(
            "interface", HelpCenterCategory.SETTINGS,
            HelpCenterGuideText(
                "Интерфейс и ресурспак",
                "Настроить панели, подсказки и видимость игровых элементов.",
                "Откройте настройки интерфейса. Здесь собраны боковая панель, список игроков, отображение квестов, подсказки о блоках, посохи и предпросмотр контейнеров.\n\nДоступные переключатели частиц, автототема и ресурспака находятся там же. Меняйте нужный элемент и проверяйте результат в игре.",
                "интерфейс ресурспак ресурс пак текстуры sidebar scoreboard tab таб панель hud hologram подсказки посохи контейнер автототем",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.SETTINGS, "category-settings-label")),
        ),
        HelpCenterGuideTopic(
            "chat-settings", HelpCenterCategory.SETTINGS,
            HelpCenterGuideText(
                "Личные настройки общения",
                "Выбрать канал, уведомления и входящие запросы.",
                "В группе общения выберите локальный или глобальный чат, настройте объявления и входящие запросы телепортации. Фразы входа и выхода меняются в отдельном пункте.\n\nЕсли друг не может прислать запрос, сначала проверьте этот переключатель. Список игроков позволяет открыть личное сообщение отдельно от общего чата.",
                "настройки чат сообщения объявления уведомления вход выход фразы tpignore запросы игнор",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.SETTINGS, "category-settings-label"), HelpCenterGuideAction.Page(HelpCenterPage.PLAYERS, "players-label")),
            setOf("tpa-ignore"),
        ),
        HelpCenterGuideTopic(
            "world-settings", HelpCenterCategory.SETTINGS,
            HelpCenterGuideText(
                "Мир, тропинки и порталы",
                "Настроить следы ходьбы, границы и поведение порталов.",
                "В группе мира и перемещения находятся тропинки, границы земель и настройки порталов. Можно изменить вид портала и доступ к своим или чужим порталам, если эта возможность доступна.\n\nПереключатель тропинок управляет их созданием от ваших шагов. Настройки интерфейса не изменяют права на чужую территорию.",
                "тропинки тропы trails границы порталы внешний вид земля ходьба мир настройки",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.SETTINGS, "category-settings-label")),
            setOf("trails-on", "trails-off"),
        ),
        HelpCenterGuideTopic(
            "flight", HelpCenterCategory.SETTINGS,
            HelpCenterGuideText(
                "Полёт и заряд",
                "Управлять доступным полётом и его пополнением.",
                "Откройте настройки мира и перемещения, затем полёт. Проверьте доступность, текущий заряд и выбранный режим.\n\nРучное пополнение открывает окно покупки; автоматическая оплата настраивается отдельно. Перед подтверждением проверьте источник оплаты и цену. Ограничения мира и права продолжают действовать.",
                "полёт полет летать fly flight charge заряд пополнить автопокупка",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.SETTINGS, "category-settings-label")),
        ),
        HelpCenterGuideTopic(
            "particles", HelpCenterCategory.SETTINGS,
            HelpCenterGuideText(
                "Личные частицы",
                "Настроить отображение личных эффектов.",
                "Откройте настройки интерфейса и найдите личные частицы. Доступный переключатель включает или выключает ваш эффект.\n\nЭто не управление всеми эффектами мира: боевые способности, события и эффекты других игровых механик имеют собственные правила.",
                "частицы эффекты партиклы pp playerparticles косметика отключить",
            ),
            listOf(HelpCenterGuideAction.Page(HelpCenterPage.SETTINGS, "category-settings-label")),
            setOf("particles"),
        ),
    )
    val byId: Map<String, HelpCenterGuideTopic> = topics.associateBy { it.id }

    fun searchEntries(settings: HelpCenterSettings): List<HelpCenterSearchEntry> = topics.map { topic ->
        val text = settings.guide(topic.id)
        HelpCenterSearchEntry(
            id = "guide-${topic.id}",
            label = text.title,
            description = text.description,
            keywords = text.keywords,
            action = HelpCenterSearchAction.OpenGuide(topic.id),
        )
    }
}
