package ru.arc.stock.gui

import ru.arc.gui.ArcMenus
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId

internal object StockMenuTopology {
    fun rows(menu: MenuId): Int = ArcMenus.current().catalog.require(menu).rows

    fun slot(menu: MenuId, element: String): Int =
        ArcMenus.current().catalog.require(menu).slot(element).index

    fun region(menu: MenuId, region: MenuRegionId): List<Int> =
        ArcMenus.current().catalog.require(menu).region(region).map { it.index }

    fun localX(menu: MenuId, element: String): Int = slot(menu, element) % 9

    fun localY(menu: MenuId, element: String): Int = slot(menu, element) / 9
}
