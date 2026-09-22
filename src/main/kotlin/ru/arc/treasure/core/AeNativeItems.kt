package ru.arc.treasure.core

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * Materializes the small allowlist of AE rewards as AE-owned ItemStacks.
 *
 * AE 9.24.13 has no public item factory in AEAPI, so this adapter binds the
 * verified implementation factories once against that exact plugin classloader.
 * It never dispatches a command or manufactures AE metadata itself.
 */
object AeNativeItems {
    private const val PLUGIN_NAME = "AdvancedEnchantments"
    private const val PLUGIN_VERSION = "9.24.13"

    private val materializer = AeNativeItemMaterializer(::nativeFactories)

    fun supports(treasure: Treasure.Ae): Boolean = materializer.supports(treasure)

    fun available(): Boolean = materializer.available()

    fun create(
        treasure: Treasure.Ae,
        preview: Boolean = false,
    ): List<ItemStack>? = materializer.create(treasure, preview)

    @Volatile
    private var cachedBinding: CachedBinding? = null

    private val bindingLock = Any()

    private fun nativeFactories(): AeNativeItemFactories? {
        val provider = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) ?: return null
        if (!provider.isEnabled || provider.description.version != PLUGIN_VERSION) {
            return null
        }

        cachedBinding?.takeIf { it.provider === provider }?.let { return it.factories }
        return synchronized(bindingLock) {
            cachedBinding?.takeIf { it.provider === provider }?.let { return@synchronized it.factories }
            val factories = ReflectiveAeNativeItemFactories.bind(provider)
            cachedBinding = CachedBinding(provider, factories)
            factories
        }
    }

    private data class CachedBinding(
        val provider: Plugin,
        val factories: AeNativeItemFactories?,
    )
}

internal interface AeNativeItemFactories {
    fun magicDust(group: String, successPercent: Int): ItemStack?

    fun whiteScroll(): ItemStack?

    fun blackScroll(successPercent: Int): ItemStack?

    fun randomizer(group: String): ItemStack?

    fun holyWhiteScroll(): ItemStack?
}

/** Small pure materialization boundary so reward shape/quantity can be tested without an AE server. */
internal class AeNativeItemMaterializer(
    private val factoryProvider: () -> AeNativeItemFactories?,
) {
    fun supports(treasure: Treasure.Ae): Boolean = request(treasure) != null

    fun available(): Boolean = factoryProvider() != null

    fun create(
        treasure: Treasure.Ae,
        preview: Boolean = false,
    ): List<ItemStack>? {
        val request = request(treasure) ?: return null
        val factories = factoryProvider() ?: return null
        val prototype =
            when (request.kind) {
                NativeAeKind.MAGIC_DUST ->
                    factories.magicDust(
                        group(request.groupArg, preview) ?: return null,
                        value(request.valueArg, preview) ?: return null,
                    )

                NativeAeKind.WHITE_SCROLL -> factories.whiteScroll()
                NativeAeKind.BLACK_SCROLL -> factories.blackScroll(value(request.valueArg, preview) ?: return null)
                NativeAeKind.RANDOMIZER -> factories.randomizer(group(request.groupArg, preview) ?: return null)
                NativeAeKind.HOLY_WHITE_SCROLL -> factories.holyWhiteScroll()
            } ?: return null

        return split(prototype, request.amount)
    }

    private fun request(treasure: Treasure.Ae): NativeAeRequest? {
        if (treasure.kind != AeKind.ITEM || treasure.amount !in 1..64) return null

        return when (treasure.itemName?.lowercase(Locale.ROOT)) {
            "magic" -> {
                if (treasure.args.size != 2 || treasure.args[0] != AeArg.RandomTier) return null
                val percent = percentage(treasure.args[1]) ?: return null
                NativeAeRequest(NativeAeKind.MAGIC_DUST, treasure.amount, AeArg.RandomTier, percent)
            }

            "whitescroll" ->
                if (treasure.args.isEmpty()) NativeAeRequest(NativeAeKind.WHITE_SCROLL, treasure.amount) else null

            "blackscroll" -> {
                if (treasure.args.size != 1) return null
                val percent = percentage(treasure.args.single()) ?: return null
                NativeAeRequest(NativeAeKind.BLACK_SCROLL, treasure.amount, valueArg = percent)
            }

            "randomizer" ->
                if (treasure.args == listOf(AeArg.RandomTier)) {
                    NativeAeRequest(NativeAeKind.RANDOMIZER, treasure.amount, groupArg = AeArg.RandomTier)
                } else {
                    null
                }

            "holywhitescroll" ->
                if (treasure.args.isEmpty()) NativeAeRequest(NativeAeKind.HOLY_WHITE_SCROLL, treasure.amount) else null

            else -> null
        }
    }

    private fun percentage(arg: AeArg): AeArg.IntRange? =
        (arg as? AeArg.IntRange)?.takeIf { it.min in 1..100 && it.max in it.min..100 }

    private fun group(
        arg: AeArg?,
        preview: Boolean,
    ): String? =
        when {
            arg == null -> null
            preview -> "SIMPLE"
            else -> AeLoot.resolveArg(arg)
        }

    private fun value(
        arg: AeArg.IntRange?,
        preview: Boolean,
    ): Int? {
        arg ?: return null
        if (preview) return arg.min + (arg.max - arg.min) / 2
        return AeLoot.rollInt(arg.min, arg.max)
    }

    private fun split(
        prototype: ItemStack,
        amount: Int,
    ): List<ItemStack>? {
        val maxStackSize = prototype.maxStackSize
        if (maxStackSize <= 0) return null

        val stacks = mutableListOf<ItemStack>()
        var remaining = amount
        while (remaining > 0) {
            val stack = prototype.clone()
            val stackAmount = minOf(remaining, maxStackSize)
            stack.amount = stackAmount
            stacks += stack
            remaining -= stackAmount
        }
        return stacks
    }
}

private data class NativeAeRequest(
    val kind: NativeAeKind,
    val amount: Int,
    val groupArg: AeArg? = null,
    val valueArg: AeArg.IntRange? = null,
)

private enum class NativeAeKind {
    MAGIC_DUST,
    WHITE_SCROLL,
    BLACK_SCROLL,
    RANDOMIZER,
    HOLY_WHITE_SCROLL,
}

private class ReflectiveAeNativeItemFactories(
    private val groupClass: Class<*>,
    private val matchGroup: Method,
    private val magicDustNonRandom: Method,
    private val whiteScrollMake: Method,
    private val blackScroll: Method,
    private val randomizationScroll: Method,
    private val holyWhiteScroll: Method,
) : AeNativeItemFactories {
    private val failureLogged = AtomicBoolean(false)

    override fun magicDust(group: String, successPercent: Int): ItemStack? {
        val nativeGroup = resolveGroup(group) ?: return null
        return invokeItem(magicDustNonRandom, nativeGroup, successPercent)
    }

    override fun whiteScroll(): ItemStack? = invokeItem(whiteScrollMake)

    override fun blackScroll(successPercent: Int): ItemStack? = invokeItem(blackScroll, successPercent)

    override fun randomizer(group: String): ItemStack? {
        val nativeGroup = resolveGroup(group) ?: return null
        return invokeItem(randomizationScroll, nativeGroup)
    }

    override fun holyWhiteScroll(): ItemStack? = invokeItem(holyWhiteScroll, 1)

    private fun resolveGroup(name: String): Any? =
        invoke(matchGroup, null, name)?.takeIf(groupClass::isInstance)

    private fun invokeItem(
        method: Method,
        vararg args: Any,
    ): ItemStack? = invoke(method, null, *args) as? ItemStack

    private fun invoke(
        method: Method,
        receiver: Any?,
        vararg args: Any,
    ): Any? =
        try {
            method.invoke(receiver, *args)
        } catch (failure: ReflectiveOperationException) {
            logFailure(method, failure)
            null
        } catch (failure: IllegalArgumentException) {
            logFailure(method, failure)
            null
        } catch (failure: LinkageError) {
            logFailure(method, failure)
            null
        }

    private fun logFailure(
        method: Method,
        failure: Throwable,
    ) {
        if (failureLogged.compareAndSet(false, true)) {
            val cause = (failure as? InvocationTargetException)?.targetException ?: failure
            Bukkit.getLogger().log(
                Level.WARNING,
                "ARC could not create an AdvancedEnchantments native item through ${method.declaringClass.name}.${method.name}.",
                cause,
            )
        }
    }

    companion object {
        private const val GROUP_CLASS = "net.advancedplugins.ae.enchanthandler.enchantments.AdvancedGroup"
        private const val TINKERER_ITEMS_CLASS = "net.advancedplugins.ae.features.tinkerer.TinkererItems"
        private const val WHITE_SCROLL_CLASS = "net.advancedplugins.ae.items.WhiteScroll"
        private const val OTHER_ITEMS_CLASS = "net.advancedplugins.ae.items.OtherItems"
        private const val HOLY_WHITE_SCROLL_CLASS = "net.advancedplugins.ae.items.HolyWhiteScroll"

        fun bind(provider: Plugin): AeNativeItemFactories? =
            try {
                val loader = provider.javaClass.classLoader
                fun load(name: String): Class<*> = Class.forName(name, false, loader)

                val itemStackClass = ItemStack::class.java
                val group = load(GROUP_CLASS)
                val matchGroup = method(load(GROUP_CLASS), "matchGroup", group, String::class.java)
                val magicDust = method(load(TINKERER_ITEMS_CLASS), "magicDustNonRandom", itemStackClass, group, Int::class.javaPrimitiveType!!)
                val whiteScroll = method(load(WHITE_SCROLL_CLASS), "make", itemStackClass)
                val otherItems = load(OTHER_ITEMS_CLASS)
                val blackScroll = method(otherItems, "BlackScroll", itemStackClass, Int::class.javaPrimitiveType!!)
                val randomizer = method(otherItems, "RandomizationScroll", itemStackClass, group)
                val holyWhiteScroll = method(load(HOLY_WHITE_SCROLL_CLASS), "get", itemStackClass, Int::class.javaPrimitiveType!!)

                ReflectiveAeNativeItemFactories(
                    group,
                    matchGroup,
                    magicDust,
                    whiteScroll,
                    blackScroll,
                    randomizer,
                    holyWhiteScroll,
                )
            } catch (failure: ReflectiveOperationException) {
                logIncompatibleSurface(failure)
                null
            } catch (failure: LinkageError) {
                logIncompatibleSurface(failure)
                null
            } catch (failure: SecurityException) {
                logIncompatibleSurface(failure)
                null
            } catch (failure: IllegalArgumentException) {
                logIncompatibleSurface(failure)
                null
            }

        private fun method(
            owner: Class<*>,
            name: String,
            expectedReturn: Class<*>,
            vararg parameters: Class<*>,
        ): Method {
            val method = owner.getMethod(name, *parameters)
            require(Modifier.isStatic(method.modifiers)) { "${owner.name}.$name must be static" }
            require(expectedReturn.isAssignableFrom(method.returnType)) { "${owner.name}.$name has an unexpected return type" }
            return method
        }

        private fun logIncompatibleSurface(failure: Throwable) {
            Bukkit.getLogger().log(
                Level.WARNING,
                "ARC AdvancedEnchantments native rewards require the verified 9.24.13 item-factory surface; integration is disabled.",
                failure,
            )
        }
    }
}
