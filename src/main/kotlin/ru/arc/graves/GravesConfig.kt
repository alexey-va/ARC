package ru.arc.graves

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

internal data class GravesSettings(
    val defaultFee: Double,
    val freePermission: String,
    val nearbyGlowRadius: Int,
    val hudRefreshTicks: Int,
    val trackingEnabledByDefault: Boolean,
    private val texts: Map<String, String>,
) {
    private val miniMessage = MiniMessage.miniMessage()

    fun text(key: String, vararg placeholders: Pair<String, String>): Component = miniMessage.deserialize(
        texts.getValue(key),
        TagResolver.resolver(placeholders.map { (name, value) -> Placeholder.unparsed(name, value) }),
    )
}

internal class GravesConfig(private val source: Config) {
    fun snapshot(): GravesSettings {
        val defaultFee = source.double("teleport.default-fee", DEFAULT_FEE)
        require(defaultFee.isFinite() && defaultFee > 0.0) { "Graves teleport.default-fee must be finite and positive" }
        val freePermission = required("teleport.free-permission", FREE_PERMISSION)
        val nearbyGlowRadius = source.int("glow.nearby-radius", DEFAULT_GLOW_RADIUS)
        require(nearbyGlowRadius in 1..128) { "Graves glow.nearby-radius must be in 1..128" }
        val hudRefreshTicks = source.int("hud.refresh-ticks", DEFAULT_HUD_REFRESH_TICKS)
        require(hudRefreshTicks in 1..20) { "Graves hud.refresh-ticks must be in 1..20" }

        return GravesSettings(
            defaultFee = defaultFee,
            freePermission = freePermission,
            nearbyGlowRadius = nearbyGlowRadius,
            hudRefreshTicks = hudRefreshTicks,
            trackingEnabledByDefault = source.bool("tracking.enabled-by-default", true),
            texts = DEFAULT_TEXT.mapValues { (key, fallback) -> required("text.$key", fallback) },
        )
    }

    private fun required(path: String, fallback: String): String = source.string(path, fallback).also {
        require(it.isNotBlank()) { "Graves value '$path' cannot be blank" }
        require(it.length <= 2_000) { "Graves value '$path' is too long" }
    }

    companion object {
        private const val DEFAULT_FEE = 100.0
        private const val DEFAULT_GLOW_RADIUS = 32
        private const val DEFAULT_HUD_REFRESH_TICKS = 2
        private const val FREE_PERMISSION = "arc.graves.teleport.free"
        private const val RESOURCE = "modules/graves.yml"

        fun load(dataPath: Path): GravesConfig {
            val source = ConfigManager.of(dataPath, RESOURCE)
            source.mergeMissingFromBundled(RESOURCE)
            return GravesConfig(source)
        }

        private val DEFAULT_TEXT = linkedMapOf(
            "list-title" to "<italic:false><#c4a7e7>Ваши могилы",
            "list-body" to "<italic:false><#e8dfd2>Активные могилы: <#ffffff><count><newline><#e8dfd2>Страница <#ffffff><page>/<pages>",
            "list-empty" to "<italic:false><#e8dfd2>Активных могил сейчас нет.",
            "list-unavailable" to "<italic:false><#f4bd6a>Не удалось загрузить могилы. Попробуйте открыть список позже.",
            "list-stale" to "<italic:false><#f4bd6a>Эта могила больше недоступна. Список обновлён.",
            "entry-label" to "<italic:false><#c4a7e7><server> · <world> · <x>, <y>, <z> ›",
            "entry-tooltip" to "<italic:false><#e8dfd2>Открыть могилу. <#f4bd6a><expiry>",
            "detail-title" to "<italic:false><#c4a7e7>Карточка могилы",
            "detail-body" to "<italic:false><#e8dfd2>Место и содержимое выбранной могилы.",
            "detail-tracked" to "<italic:false><#9bd48d>Эта могила выбрана для компаса.",
            "detail-items-title" to "<italic:false><#f4bd6a>Содержимое могилы",
            "items-empty" to "<italic:false><#e8dfd2>В этой могиле нет предметов.",
            "items-page" to "<italic:false><#e8dfd2>Предметы: страница <#ffffff><page>/<pages>",
            "item-name-unavailable" to "<italic:false><#e8dfd2>Название предмета недоступно",
            "row-server" to "<italic:false><#e8dfd2>Сервер",
            "row-world" to "<italic:false><#e8dfd2>Мир",
            "row-coordinates" to "<italic:false><#e8dfd2>Координаты",
            "row-expiry" to "<italic:false><#e8dfd2>Срок хранения",
            "row-experience" to "<italic:false><#e8dfd2>Опыт",
            "row-label-heading" to "<italic:false><#e8dfd2>Параметр",
            "row-value-heading" to "<italic:false><#e8dfd2>Значение",
            "row-index" to "<italic:false><#e8dfd2>№",
            "row-item" to "<italic:false><#e8dfd2>Предмет",
            "row-price" to "<italic:false><#e8dfd2>Стоимость перехода",
            "expiry-eternal" to "<italic:false><#9bd48d>Бессрочно",
            "expiry-time" to "<italic:false><#f4bd6a>Осталось <time>",
            "expiry-under-minute" to "<italic:false><#f4bd6a>меньше минуты",
            "previous-label" to "<italic:false><#92bed8>‹ Предыдущая страница",
            "previous-tooltip" to "<italic:false><#e8dfd2>Показать предыдущую страницу.",
            "next-label" to "<italic:false><#92bed8>Следующая страница ›",
            "next-tooltip" to "<italic:false><#e8dfd2>Показать следующую страницу.",
            "tracking-enabled-label" to "<italic:false><#9bd48d>✔ Компас",
            "tracking-enabled-tooltip" to "<italic:false><#e8dfd2>Нажмите, чтобы отключить направление к выбранной могиле.",
            "tracking-disabled-label" to "<italic:false><#ffffff>○ Компас",
            "tracking-disabled-tooltip" to "<italic:false><#e8dfd2>Нажмите, чтобы включить направление к выбранной могиле.",
            "track-label" to "<italic:false><#c4a7e7>Выбрать для компаса ›",
            "track-tooltip" to "<italic:false><#e8dfd2>Указывать направление к этой могиле.",
            "teleport-label" to "<italic:false><#91c4ff>Перейти к могиле ›",
            "teleport-tooltip" to "<italic:false><#e8dfd2>Сначала проверьте место и стоимость перехода.",
            "teleport-unavailable" to "<italic:false><#f4bd6a>Стоимость перехода сейчас недоступна.",
            "confirm-title" to "<italic:false><#f4bd6a>Переход к могиле",
            "confirm-body" to "<italic:false><#e8dfd2>Подтвердите переход к выбранной могиле. Показанная стоимость зафиксирована для этого подтверждения.",
            "confirm-label" to "<italic:false><#9bd48d>Подтвердить переход",
            "confirm-tooltip" to "<italic:false><#e8dfd2>Перейти к выбранной могиле по указанной стоимости.",
            "price-value" to "<italic:false><#f4bd6a><price> <white>💰</white>",
            "free-price" to "<italic:false><#9bd48d>Бесплатно по праву",
            "hud-notice" to "<italic:false><#e8dfd2>Могилы: <#ffffff><count> · /graves — открыть меню",
            "hud-target" to "<italic:false><#c4a7e7>Могила · <#ffffff><distance> м<vertical> · /graves — меню",
            "hud-remote" to "<italic:false><#c4a7e7>Могила: <#ffffff><server> · <world> · /graves",
            "hud-world" to "<italic:false><#c4a7e7>Могила в мире <#ffffff><world> · /graves",
            "created" to "<italic:false><#f4bd6a>Вещи остались в могиле. <#ffffff><click:run_command:/graves>Открыть могилы ›</click>",
            "changed" to "<italic:false><#f4bd6a>Могила или стоимость изменились. Откройте карточку заново.",
            "insufficient" to "<italic:false><#ff9a9a>Недостаточно монет для перехода к могиле.",
            "payment-pending" to "<italic:false><#92bed8>Переход к могиле уже выполняется.",
            "payment-unavailable" to "<italic:false><#f4bd6a>Платёж сейчас недоступен. Попробуйте позже; при повторении обратитесь к администрации.",
            "payment-failed" to "<italic:false><#ff9a9a>Оплата не прошла. Монеты не списаны.",
            "teleported" to "<italic:false><#9bd48d>Вы у могилы.",
            "teleport-failed" to "<italic:false><#ff9a9a>Не удалось перейти к могиле. Монеты не списаны.",
            "transfer" to "<italic:false><#92bed8>Переходим на сервер <#ffffff><server>…",
            "unavailable" to "<italic:false><#f4bd6a>Сервер могилы сейчас недоступен. Попробуйте позже.",
            "tracking-save-failed" to "<italic:false><#f4bd6a>Не удалось сохранить настройку компаса.",
        )
    }
}
