package ru.arc.enchanting

import net.advancedplugins.ae.api.AEAPI
import net.advancedplugins.ae.api.EnchantApplyEvent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.persistence.PersistentDataType
import ru.arc.eliteloot.isAdvancedEnchantmentsBook
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

private const val advancedEnchantmentsPluginName = "AdvancedEnchantments"

private data class AdvancedBookPresentationBinding(
    val provider: Plugin,
    val createBook: Method,
    val failureOnBook: Method,
    val hasWhiteScroll: Method,
    val removeWhiteScroll: Method,
    val enchantmentFromApplyEvent: Method,
    val destroyItemEventConstructor: Constructor<*>,
)

private data class AdvancedBookData(
    val enchantment: String,
    val level: Int,
    val success: Int,
    val failure: Int,
)

private val advancedBookLegacyKey = NamespacedKey("advancedenchantments", "book")
private val advancedBookEnchantmentKey = NamespacedKey("advancedenchantments", "ae_book")
private val advancedBookLevelKey = NamespacedKey("advancedenchantments", "ae_book_level")
private val advancedBookSuccessKey = NamespacedKey("advancedenchantments", "ae_book_success")
private val advancedBookFailureKey = NamespacedKey("advancedenchantments", "ae_book_failure")
private val advancedMagicDustKey = NamespacedKey("advancedenchantments", "magic")
private val advancedMagicDustGroupKey = NamespacedKey("advancedenchantments", "grouptype")

@Volatile
private var advancedBookPresentationBinding: AdvancedBookPresentationBinding? = null
private val advancedBookPresentationFailureLogged = AtomicBoolean()

/** Resolve the version-specific native book methods once while the AE provider is enabled. */
internal fun bindAdvancedBookPresentation() {
    advancedBookPresentationBinding = null
    val provider = Bukkit.getPluginManager().getPlugin(advancedEnchantmentsPluginName) ?: return
    if (!provider.isEnabled) return

    try {
        require(provider.description.version == "9.24.15") {
            "AdvancedEnchantments application contract is only verified for 9.24.15"
        }
        val api = AEAPI::class.java
        val integer = Int::class.javaPrimitiveType!!
        val createBook = api.getMethod(
            "createEnchantmentBook",
            String::class.java,
            integer,
            integer,
            integer,
            Player::class.java,
        )
        val failureOnBook = api.getMethod("getFailureOnBook", ItemStack::class.java)
        val hasWhiteScroll = api.getMethod("hasWhiteScroll", ItemStack::class.java)
        val removeWhiteScroll = api.getMethod("removeWhiteScroll", ItemStack::class.java)
        val applyEventClass = EnchantApplyEvent::class.java
        val enchantmentFromApplyEvent = applyEventClass.getMethod("getEnchantment")
        val destroyItemEventClass = Class.forName(
            "net.advancedplugins.ae.api.EnchantDestroyItemEvent",
            false,
            api.classLoader,
        )
        val destroyItemEventConstructor = destroyItemEventClass.getConstructor(
            enchantmentFromApplyEvent.returnType,
            ItemStack::class.java,
            integer,
            Player::class.java,
            ItemStack::class.java,
        )
        require(Event::class.java.isAssignableFrom(destroyItemEventClass)) {
            "EnchantDestroyItemEvent must extend Event"
        }
        require(Cancellable::class.java.isAssignableFrom(destroyItemEventClass)) {
            "EnchantDestroyItemEvent must implement Cancellable"
        }
        require(Modifier.isStatic(createBook.modifiers)) { "createEnchantmentBook must be static" }
        require(createBook.returnType == ItemStack::class.java) { "createEnchantmentBook must return ItemStack" }
        require(Modifier.isStatic(failureOnBook.modifiers)) { "getFailureOnBook must be static" }
        require(failureOnBook.returnType == integer) { "getFailureOnBook must return int" }
        require(Modifier.isStatic(hasWhiteScroll.modifiers)) { "hasWhiteScroll must be static" }
        require(hasWhiteScroll.returnType == Boolean::class.javaPrimitiveType) {
            "hasWhiteScroll must return boolean"
        }
        require(Modifier.isStatic(removeWhiteScroll.modifiers)) { "removeWhiteScroll must be static" }
        require(removeWhiteScroll.returnType == ItemStack::class.java) { "removeWhiteScroll must return ItemStack" }
        advancedBookPresentationBinding = AdvancedBookPresentationBinding(
            provider,
            createBook,
            failureOnBook,
            hasWhiteScroll,
            removeWhiteScroll,
            enchantmentFromApplyEvent,
            destroyItemEventConstructor,
        )
    } catch (failure: ReflectiveOperationException) {
        logAdvancedBookPresentationFailure(failure)
    } catch (failure: LinkageError) {
        logAdvancedBookPresentationFailure(failure)
    } catch (failure: SecurityException) {
        logAdvancedBookPresentationFailure(failure)
    } catch (failure: IllegalArgumentException) {
        logAdvancedBookPresentationFailure(failure)
    }
}

internal fun clearAdvancedBookPresentation() {
    advancedBookPresentationBinding = null
}

internal data class AdvancedMagicDust(val successPercent: Int, val lowerDestroy: Boolean)

/** AE 9.24.15 TinkererItems' native success dust; secret/mystery dust have different markers. */
internal fun readAdvancedMagicDust(item: ItemStack): AdvancedMagicDust? {
    val binding = advancedBookPresentationBinding ?: return null
    if (!binding.provider.isEnabled) return null
    val pdc = item.itemMeta?.persistentDataContainer ?: return null
    if (pdc.get(advancedMagicDustGroupKey, PersistentDataType.STRING).isNullOrBlank()) return null
    val boost = pdc.get(advancedMagicDustKey, PersistentDataType.STRING)?.toIntOrNull()
        ?.takeIf { it > 0 }?.coerceAtMost(100) ?: return null
    return AdvancedMagicDust(boost, binding.provider.config.getBoolean("settings.lower-destroy-with-magic-dust"))
}

/** Read the effective AE application chances only after the actual apply getters are verified safe. */
internal fun advancedBookChances(item: ItemStack): BookApplicationChances {
    require(isAdvancedEnchantmentsBook(item)) { "Item is not an AdvancedEnchantments book" }
    check(isAdvancedBookRiskSafe(item)) { "AdvancedEnchantments book risk is not normalized" }
    val container = checkNotNull(item.itemMeta?.persistentDataContainer) {
        "AdvancedEnchantments book has no item metadata"
    }
    val success = checkNotNull(container.get(advancedBookSuccessKey, PersistentDataType.INTEGER)) {
        "AdvancedEnchantments book has no success chance"
    }
    val failure = checkNotNull(container.get(advancedBookFailureKey, PersistentDataType.INTEGER)) {
        "AdvancedEnchantments book has no failure chance"
    }
    return BookApplicationChances(success, failure)
}

/** Return the white-scroll-free target copy, or null if it is not protected; never fail open. */
internal fun protectedAdvancedBookTarget(target: ItemStack): ItemStack? {
    val binding = requireAdvancedBookPresentationBinding("white-scroll protection")
    if (!invokeHasWhiteScroll(target, binding)) return null

    val protectedTarget = invokeRemoveWhiteScroll(target.clone(), binding)
    check(protectedTarget.type == target.type) { "AE white-scroll removal changed the target type" }
    check(!invokeHasWhiteScroll(protectedTarget, binding)) { "AE white-scroll removal did not remove protection" }
    return protectedTarget
}

/** Fire AE's native cancellable destroy event without linking ARC to AE's implementation type. */
internal fun advancedDestructionCancelled(
    applyEvent: EnchantApplyEvent,
    target: ItemStack,
    player: Player,
    book: ItemStack,
): Boolean {
    val binding = requireAdvancedBookPresentationBinding("native item destruction event")
    return try {
        val enchantment = binding.enchantmentFromApplyEvent.invoke(applyEvent)
            ?: error("AE apply event has no enchantment")
        val nativeEvent = binding.destroyItemEventConstructor.newInstance(
            enchantment,
            target.clone(),
            applyEvent.level,
            player,
            book.clone(),
        ) as? Event ?: error("AE destroy event constructor returned no Event")
        val cancellable = nativeEvent as? Cancellable
            ?: error("AE destroy event is not cancellable")
        Bukkit.getPluginManager().callEvent(nativeEvent)
        cancellable.isCancelled
    } catch (failure: ReflectiveOperationException) {
        throw destroyEventBridgeFailure(failure)
    } catch (failure: LinkageError) {
        throw destroyEventBridgeFailure(failure)
    } catch (failure: RuntimeException) {
        throw destroyEventBridgeFailure(failure)
    }
}

/** Old AE books may still carry a destruction rate above the supported one-percent cap. */
internal fun isAdvancedBookRiskSafe(item: ItemStack): Boolean {
    if (!isAdvancedEnchantmentsBook(item)) return true
    val binding = advancedBookPresentationBinding ?: return false
    if (!binding.provider.isEnabled) return false
    val container = item.itemMeta?.persistentDataContainer ?: return false
    if (container.has(advancedBookLegacyKey)) return false

    val enchantment = container.get(advancedBookEnchantmentKey, PersistentDataType.STRING) ?: return false
    val level = container.get(advancedBookLevelKey, PersistentDataType.INTEGER) ?: return false
    val success = container.get(advancedBookSuccessKey, PersistentDataType.INTEGER) ?: return false
    val failure = container.get(advancedBookFailureKey, PersistentDataType.INTEGER) ?: return false
    if (level < 1 || success !in 0..100 || failure !in 0..1) return false

    return try {
        AEAPI.getBookEnchantment(item) == enchantment &&
            AEAPI.getBookEnchantmentLevel(item) == level &&
            AEAPI.getSuccessOnBook(item) == success &&
            invokeFailureOnBook(item, binding) == failure
    } catch (problem: ReflectiveOperationException) {
        logAdvancedBookPresentationFailure(problem)
        false
    } catch (problem: LinkageError) {
        logAdvancedBookPresentationFailure(problem)
        false
    } catch (problem: RuntimeException) {
        logAdvancedBookPresentationFailure(problem)
        false
    }
}

/** Migrate legacy AE book data and cap the failure field that AE's apply getter will read. */
internal fun normalizeAdvancedBookRisk(item: ItemStack, viewer: Player): ItemStack {
    if (!isAdvancedEnchantmentsBook(item)) return item
    val binding = advancedBookPresentationBinding ?: return item
    if (!binding.provider.isEnabled) return item

    return try {
        val book = readAdvancedBookData(item, binding)
        if (book.failure in 0..1 && isAdvancedBookRiskSafe(item)) return item
        val nativeBook = createNativeBook(book.copy(failure = book.failure.coerceIn(0, 1)), viewer, binding)
        val normalized = copyNativeAdvancedBookMetadata(item, nativeBook)
            ?: error("AE native book factory returned incomplete canonical metadata")
        check(isAdvancedBookRiskSafe(normalized)) { "AE native failure getter disagrees with normalized metadata" }
        normalized
    } catch (failure: ReflectiveOperationException) {
        logAdvancedBookPresentationFailure(failure)
        item
    } catch (failure: LinkageError) {
        logAdvancedBookPresentationFailure(failure)
        item
    } catch (failure: RuntimeException) {
        logAdvancedBookPresentationFailure(failure)
        item
    }
}

/** Restyle a genuine AE book from AE's factory and canonicalize its four application fields. */
internal fun presentAdvancedBook(item: ItemStack, viewer: Player): ItemStack {
    if (!isAdvancedEnchantmentsBook(item)) return item
    val binding = advancedBookPresentationBinding ?: return item
    if (!binding.provider.isEnabled) return item

    return try {
        val book = readAdvancedBookData(item, binding)
        val nativeBook = createNativeBook(book.copy(failure = book.failure.coerceIn(0, 1)), viewer, binding)
        copyNativeAdvancedBookPresentation(item, nativeBook)
            ?: error("AE native book factory returned incomplete presentation metadata")
    } catch (failure: ReflectiveOperationException) {
        logAdvancedBookPresentationFailure(failure)
        item
    } catch (failure: LinkageError) {
        logAdvancedBookPresentationFailure(failure)
        item
    } catch (failure: RuntimeException) {
        logAdvancedBookPresentationFailure(failure)
        item
    }
}

private fun readAdvancedBookData(
    item: ItemStack,
    binding: AdvancedBookPresentationBinding,
): AdvancedBookData {
    val enchantment = AEAPI.getBookEnchantment(item)?.takeIf(String::isNotBlank)
        ?: error("AE book has no enchantment identifier")
    val level = AEAPI.getBookEnchantmentLevel(item)
    val success = AEAPI.getSuccessOnBook(item)
    val failure = invokeFailureOnBook(item, binding)
    return AdvancedBookData(enchantment, level, success, failure)
}

private fun createNativeBook(
    book: AdvancedBookData,
    viewer: Player,
    binding: AdvancedBookPresentationBinding,
): ItemStack {
    val nativeBook = (binding.createBook.invoke(
        null,
        book.enchantment,
        book.level,
        book.success,
        book.failure,
        viewer,
    ) as? ItemStack)?.takeIf { it.type == Material.ENCHANTED_BOOK }
        ?: error("AE native book factory returned no enchanted book")

    check(AEAPI.getBookEnchantment(nativeBook) == book.enchantment) {
        "AE factory changed the enchantment identifier"
    }
    check(AEAPI.getBookEnchantmentLevel(nativeBook) == book.level) { "AE factory changed the enchantment level" }
    check(AEAPI.getSuccessOnBook(nativeBook) == book.success) {
        "AE factory changed the success chance"
    }
    check(invokeFailureOnBook(nativeBook, binding) == book.failure) {
        "AE factory changed the failure chance"
    }
    return nativeBook
}

/** Copy generated presentation and the canonical AE book fields, dropping the legacy override. */
internal fun copyNativeAdvancedBookPresentation(source: ItemStack, nativeBook: ItemStack): ItemStack? {
    if (source.type != Material.ENCHANTED_BOOK || nativeBook.type != Material.ENCHANTED_BOOK) return null
    val nativeMeta = nativeBook.itemMeta ?: return null
    val nativeName = nativeMeta.displayName() ?: return null
    val nativeLore = nativeMeta.lore() ?: return null
    val failure = nativeFailureValue(nativeMeta.persistentDataContainer) ?: return null

    val result = source.clone()
    val resultMeta = result.itemMeta ?: return null
    resultMeta.displayName(nativeName.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE))
    resultMeta.lore(nativeLore.map { it.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE) })
    if (!copyCanonicalAdvancedBookFields(
            resultMeta.persistentDataContainer,
            nativeMeta.persistentDataContainer,
            failure,
        )
    ) {
        return null
    }
    result.itemMeta = resultMeta
    return result
}

/** Copy only native AE book fields onto a source clone during apply preflight. */
internal fun copyNativeAdvancedBookMetadata(source: ItemStack, nativeBook: ItemStack): ItemStack? {
    if (source.type != Material.ENCHANTED_BOOK || nativeBook.type != Material.ENCHANTED_BOOK) return null
    val nativeMeta = nativeBook.itemMeta ?: return null
    val failure = nativeFailureValue(nativeMeta.persistentDataContainer) ?: return null
    val result = source.clone()
    val meta = result.itemMeta ?: return null
    if (!copyCanonicalAdvancedBookFields(
            meta.persistentDataContainer,
            nativeMeta.persistentDataContainer,
            failure,
        )
    ) {
        return null
    }
    result.itemMeta = meta
    return result
}

private fun copyCanonicalAdvancedBookFields(
    target: org.bukkit.persistence.PersistentDataContainer,
    native: org.bukkit.persistence.PersistentDataContainer,
    failure: Int,
): Boolean {
    val enchantment = native.get(advancedBookEnchantmentKey, PersistentDataType.STRING) ?: return false
    val level = native.get(advancedBookLevelKey, PersistentDataType.INTEGER) ?: return false
    val success = native.get(advancedBookSuccessKey, PersistentDataType.INTEGER) ?: return false
    if (level < 1 || success !in 0..100 || failure !in 0..1) return false

    target.remove(advancedBookLegacyKey)
    target.set(advancedBookEnchantmentKey, PersistentDataType.STRING, enchantment)
    target.set(advancedBookLevelKey, PersistentDataType.INTEGER, level)
    target.set(advancedBookSuccessKey, PersistentDataType.INTEGER, success)
    target.set(advancedBookFailureKey, PersistentDataType.INTEGER, failure)
    return true
}

private fun nativeFailureValue(container: org.bukkit.persistence.PersistentDataContainer): Int? =
    container.get(advancedBookFailureKey, PersistentDataType.INTEGER)

private fun invokeFailureOnBook(item: ItemStack, binding: AdvancedBookPresentationBinding): Int =
    binding.failureOnBook.invoke(null, item) as? Int
        ?: error("AE returned no book failure chance")

private fun requireAdvancedBookPresentationBinding(purpose: String): AdvancedBookPresentationBinding {
    val binding = advancedBookPresentationBinding
        ?: error("AdvancedEnchantments is unavailable; refusing $purpose")
    check(binding.provider.isEnabled) { "AdvancedEnchantments is disabled; refusing $purpose" }
    return binding
}

private fun invokeHasWhiteScroll(item: ItemStack, binding: AdvancedBookPresentationBinding): Boolean =
    try {
        binding.hasWhiteScroll.invoke(null, item) as? Boolean
            ?: error("AE returned no white-scroll status")
    } catch (failure: ReflectiveOperationException) {
        throw whiteScrollBridgeFailure("read white-scroll status", failure)
    } catch (failure: LinkageError) {
        throw whiteScrollBridgeFailure("read white-scroll status", failure)
    } catch (failure: RuntimeException) {
        throw whiteScrollBridgeFailure("read white-scroll status", failure)
    }

private fun invokeRemoveWhiteScroll(item: ItemStack, binding: AdvancedBookPresentationBinding): ItemStack =
    try {
        binding.removeWhiteScroll.invoke(null, item) as? ItemStack
            ?: error("AE returned no target after white-scroll removal")
    } catch (failure: ReflectiveOperationException) {
        throw whiteScrollBridgeFailure("remove white-scroll protection", failure)
    } catch (failure: LinkageError) {
        throw whiteScrollBridgeFailure("remove white-scroll protection", failure)
    } catch (failure: RuntimeException) {
        throw whiteScrollBridgeFailure("remove white-scroll protection", failure)
    }

private fun whiteScrollBridgeFailure(action: String, failure: Throwable): IllegalStateException {
    val cause = (failure as? InvocationTargetException)?.targetException ?: failure
    logAdvancedBookPresentationFailure(cause)
    return IllegalStateException("Could not $action through AdvancedEnchantments; refusing the operation", cause)
}

private fun destroyEventBridgeFailure(failure: Throwable): IllegalStateException {
    val cause = (failure as? InvocationTargetException)?.targetException ?: failure
    logAdvancedBookPresentationFailure(cause)
    return IllegalStateException(
        "Could not fire AdvancedEnchantments' native destroy event; refusing to destroy the target",
        cause,
    )
}

private fun logAdvancedBookPresentationFailure(failure: Throwable) {
    if (!advancedBookPresentationFailureLogged.compareAndSet(false, true)) return
    val cause = (failure as? InvocationTargetException)?.targetException ?: failure
    Bukkit.getLogger().log(
        Level.WARNING,
        "ARC could not present AdvancedEnchantments books through its native factory; AE books are left unchanged.",
        cause,
    )
}
