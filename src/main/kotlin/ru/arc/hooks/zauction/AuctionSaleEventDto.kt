package ru.arc.hooks.zauction

data class AuctionSaleEventDto(
    val listingId: String,
    val sellerUuid: String?,
    val sellerName: String,
    val buyerName: String,
    val itemDisplay: String,
    val amount: Int,
    val price: String,
    val occurredAt: Long,
)
