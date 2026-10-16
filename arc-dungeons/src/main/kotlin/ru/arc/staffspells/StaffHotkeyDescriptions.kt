package ru.arc.staffspells

internal data class StaffHotkeyDescription(
    val qName: String, val qDescription: String,
    val fName: String, val fDescription: String,
    val qCooldown: Int, val fCooldown: Int,
)

internal val staffHotkeyDescriptions = mapOf(
    StaffSpell.CHAIN to StaffHotkeyDescription("Шаровая молния", "Взрывается рядом с врагом или в конце полёта", "Искровой скачок", "Искровой блинк вперёд", 160, 80),
    StaffSpell.MARK to StaffHotkeyDescription("Гравитационная привязь", "Враг получает удар, покинув привязь", "Шаг в разлом", "Блинк через пространство", 180, 100),
    StaffSpell.FROST to StaffHotkeyDescription("Оттепель", "Расколите собственную заморозку на врагах", "Ледяное скольжение", "Скользите вперёд по земле", 160, 80),
    StaffSpell.LANCE to StaffHotkeyDescription("Солнечный гвоздь", "Прибейте врага к стене за ним", "Солнечный отскок", "Отскок назад с упором копьём", 160, 90),
    StaffSpell.EMBER to StaffHotkeyDescription("Пепельный след", "Проложите след; повторное Q — поджечь", "Реактивный взлёт", "Огненный отскок назад и вверх", 180, 100),
    StaffSpell.NOVA to StaffHotkeyDescription("Живое семя", "Посадите ловушку, оплетающую врага", "Терновая плеть", "Притянитесь к видимой опоре", 180, 90),
    StaffSpell.ICICLE to StaffHotkeyDescription("Кристальный всплеск", "Кристаллы поражают врагов вокруг в радиусе 4,5 блока", "Ледяная ступень", "Прыжок по ледяным ступеням", 160, 90),
    StaffSpell.FAULT to StaffHotkeyDescription("Обсидиановая преграда", "Закройтесь от магии; повторное Q — осколки", "Каменный таран", "Мощный каменный рывок вперёд", 200, 100),
    StaffSpell.LUNAR to StaffHotkeyDescription("Возвратный серп", "Серп летит вперёд и возвращается к вам", "Лунная поступь", "Мгновенный фазовый шаг", 160, 80),
    StaffSpell.GALE to StaffHotkeyDescription("Око урагана", "Огромный ураган поднимает и бьёт врагов вокруг точки призыва", "Попутный ветер", "Направляйте парение взглядом", 600, 100),
    StaffSpell.TIDE to StaffHotkeyDescription("Водяной плен", "Капсула удерживает до первого попадания", "Прибой", "Прокатитесь вперёд на волне", 200, 100),
    StaffSpell.REQUIEM to StaffHotkeyDescription("Час расплаты", "Подвесьте колокол; повторное Q — удар", "Шаг за грань", "Блинк под глухой звон", 180, 100),
    StaffSpell.PRISM to StaffHotkeyDescription("Режущие грани", "Поставьте треугольник, опасный при пересечении", "Преломление", "Управляемый разгон", 180, 80),
    StaffSpell.NECROMANCER to StaffHotkeyDescription("Приказ мёртвым", "Соберите лучников или укажите общую цель", "Призрачный переход", "Блинк сквозь завесу душ", 40, 120),
    StaffSpell.GRAVITY to StaffHotkeyDescription("Подвешивание", "Удержите врага; повторное Q — бросить", "Невесомый прыжок", "Баллистический прыжок с мягкой дугой", 200, 110),
    StaffSpell.OSSUARY to StaffHotkeyDescription("Костяная челюсть", "Ловушка захлопывается вокруг врага", "Костяной прорыв", "Рывок на костяном гребне", 180, 100),
)
