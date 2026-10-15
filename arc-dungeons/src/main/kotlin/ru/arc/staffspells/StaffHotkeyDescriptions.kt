package ru.arc.staffspells

internal data class StaffHotkeyDescription(
    val qName: String, val qDescription: String,
    val fName: String, val fDescription: String,
    val qCooldown: Int, val fCooldown: Int,
)

internal val staffHotkeyDescriptions = mapOf(
    StaffSpell.CHAIN to StaffHotkeyDescription("Шаровая молния", "Ведите взглядом; повторное Q — разряд", "Искровой скачок", "Короткий блинк вперёд", 160, 140),
    StaffSpell.MARK to StaffHotkeyDescription("Гравитационная привязь", "Враг получает удар, покинув привязь", "Шаг в разлом", "Блинк через пространство", 180, 180),
    StaffSpell.FROST to StaffHotkeyDescription("Оттепель", "Расколите собственную заморозку на врагах", "Ледяное скольжение", "Скользите вперёд по земле", 160, 140),
    StaffSpell.LANCE to StaffHotkeyDescription("Солнечный гвоздь", "Прибейте врага к стене за ним", "Солнечный отскок", "Отскок назад с упором копьём", 160, 160),
    StaffSpell.EMBER to StaffHotkeyDescription("Пепельный след", "Проложите след; повторное Q — поджечь", "Реактивный взлёт", "Огненный отскок назад и вверх", 180, 180),
    StaffSpell.NOVA to StaffHotkeyDescription("Живое семя", "Посадите ловушку, оплетающую врага", "Терновая плеть", "Притянитесь к видимой опоре", 180, 140),
    StaffSpell.ICICLE to StaffHotkeyDescription("Обратный залп", "Верните осколки выпущенного венца", "Ледяная ступень", "Двойной толчок по ледяным ступеням", 160, 160),
    StaffSpell.FAULT to StaffHotkeyDescription("Обсидиановая преграда", "Закройтесь от магии; повторное Q — осколки", "Каменный таран", "Короткий мощный рывок вперёд", 200, 160),
    StaffSpell.LUNAR to StaffHotkeyDescription("Возвратный серп", "Серп летит вперёд и возвращается к вам", "Лунная поступь", "Мгновенный короткий фазовый шаг", 160, 120),
    StaffSpell.GALE to StaffHotkeyDescription("Боковой шквал", "Коридор ветра сносит врагов в сторону", "Попутный ветер", "Направляйте короткое парение взглядом", 180, 160),
    StaffSpell.TIDE to StaffHotkeyDescription("Водяной плен", "Капсула удерживает до первого попадания", "Прибой", "Прокатитесь вперёд на волне", 200, 160),
    StaffSpell.REQUIEM to StaffHotkeyDescription("Час расплаты", "Подвесьте колокол; повторное Q — удар", "Шаг за грань", "Блинк под глухой звон", 180, 160),
    StaffSpell.PRISM to StaffHotkeyDescription("Режущие грани", "Поставьте треугольник, опасный при пересечении", "Преломление", "Короткий управляемый разгон", 180, 140),
    StaffSpell.NECROMANCER to StaffHotkeyDescription("Приказ мёртвым", "Соберите лучников или укажите общую цель", "Призрачный переход", "Блинк сквозь завесу душ", 40, 180),
    StaffSpell.GRAVITY to StaffHotkeyDescription("Подвешивание", "Удержите врага; повторное Q — бросить", "Невесомый прыжок", "Баллистический прыжок с мягкой дугой", 200, 180),
    StaffSpell.OSSUARY to StaffHotkeyDescription("Костяная челюсть", "Ловушка захлопывается вокруг врага", "Костяной прорыв", "Рывок на костяном гребне", 180, 160),
)
