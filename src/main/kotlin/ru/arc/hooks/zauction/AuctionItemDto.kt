package ru.arc.hooks.zauction

data class AuctionItemDto(
    val display: String? = null,
    val seller: String? = null,
    val price: String? = null,
    val expire: Long = 0L,
    val category: String? = null,
    val amount: Int = 0,
    val priority: Int = 0,
    val uuid: String? = null,
    val exist: Boolean = false,
    val lore: List<String> = emptyList(),
)
