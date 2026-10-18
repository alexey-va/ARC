package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule
import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatRuntime
import com.magmaguy.elitemobs.advancedcombat.CombatHealthFormatter
import com.magmaguy.elitemobs.advancedcombat.abilities.AbilityFailureReason
import com.magmaguy.elitemobs.advancedcombat.abilities.AbilityResult
import com.magmaguy.elitemobs.advancedcombat.abilities.ClassAbilityEngine
import com.magmaguy.elitemobs.advancedcombat.classes.AbilitySlot
import com.magmaguy.elitemobs.advancedcombat.classes.ClassLineage
import com.magmaguy.elitemobs.advancedcombat.input.ClassAbilityInputRouter
import com.magmaguy.elitemobs.advancedcombat.menu.ClassSelectionMenu
import com.magmaguy.elitemobs.advancedcombat.passives.FixedPassiveRegistry
import com.magmaguy.elitemobs.advancedcombat.passives.PassiveMechanicTrait
import com.magmaguy.elitemobs.advancedcombat.passives.PassiveProfile
import com.magmaguy.elitemobs.advancedcombat.passives.PassiveTrait
import com.magmaguy.elitemobs.commands.AdvancedClassInfoCommand
import com.magmaguy.elitemobs.magmacore.command.AdvancedCommand
import com.magmaguy.elitemobs.magmacore.command.CommandData
import com.magmaguy.elitemobs.magmacore.command.CommandManager
import com.magmaguy.elitemobs.magmacore.command.SenderType
import com.magmaguy.elitemobs.magmacore.command.arguments.DynamicListStringCommandArgument
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.function.Function

/**
 * Main-thread, reversible adapter for the audited stock EliteMobs 10.9.8 and 10.9.10 runtimes.
 * Native progression, trials, combat listeners and their scheduler remain the owners. Only the
 * engine interface, immutable balance data and menu renderer references lack public setters.
 * Validate every borrowed reference before changing any of them; never restore a previous module's
 * global HUD or input listener after /em reload has created a new native module.
 */
internal class PassiveClassCompatibility(
    private val plugin: Plugin,
    private val openClasses: (Player) -> Unit,
    private val openForm: (Player, String, Boolean) -> Unit,
) : AutoCloseable {
    private var binding: Binding? = null
    private var closed = false

    fun start() {
        check(Bukkit.isPrimaryThread()) { "Passive class integration must start on the server thread" }
        check(!closed) { "Passive class integration is closed" }
        val native = Bukkit.getPluginManager().getPlugin("EliteMobs") as? JavaPlugin
            ?: error("EliteMobs is unavailable")
        requireSupportedVersion(native.description.version)
        check(AdvancedCombatModule.isInitialized()) { "EliteMobs Advanced Combat must be enabled and initialized" }
        val module = AdvancedCombatModule.get()
        if (binding?.module === module) return
        restore()
        check(activeOwner == null) { "Passive class integration already has an owner" }

        val access = EliteMobsPassiveAccess()
        val registry = PassiveClassContent.registry()
        val originalRegistry = access.passiveRegistry.get(module) as FixedPassiveRegistry
        check(PassiveClassContent.fingerprint(originalRegistry) == PassiveClassContent.STOCK_FINGERPRINT) {
            "Another extension has already replaced the native passive catalog"
        }
        val originalEngine = access.abilityEngine.get(module) as ClassAbilityEngine
        val router = HandlerList.getRegisteredListeners(native).map { it.listener }
            .filterIsInstance<ClassAbilityInputRouter>().distinct().singleOrNull()
            ?: error("Expected exactly one stock class input router")
        val manager = native.getCommand("elitemobs")?.executor as? CommandManager
            ?: error("Stock EliteMobs command registry is unavailable")
        val infoCommands = manager.commands.filter { it.javaClass == AdvancedClassInfoCommand::class.java }
        check(infoCommands.size == 1) { "Expected one stock class info command" }
        val originalInfo = infoCommands.single()
        val replacementInfo = ClassInfoCommand(openForm)
        check(originalInfo.aliases == replacementInfo.aliases && originalInfo.permission == replacementInfo.permission &&
            originalInfo.senderType == replacementInfo.senderType && originalInfo.argumentsMap == replacementInfo.argumentsMap &&
            originalInfo.usage == replacementInfo.usage) { "Stock class info command contract changed" }

        @Suppress("UNCHECKED_CAST")
        val originalHud = access.hudProvider.get(null) as Function<Player, String>
        val hud = Function<Player, String> { player -> renderHud(module, player) }
        val renderer = access.renderer(openClasses, openForm)
        val prepared = Binding(module, native, access, originalRegistry, registry, originalEngine,
            PassiveOnlyAbilityEngine(originalEngine), router, originalHud, hud, renderer,
            access.dialogs.get(access.coordinator), access.inventories.get(access.coordinator), manager, originalInfo, replacementInfo)
        binding = prepared // A failure from any mutation below is owned by rollback.
        try {
            Bukkit.getOnlinePlayers().forEach(originalEngine::deactivate)
            access.passiveRegistry.set(module, registry)
            access.abilityEngine.set(module, prepared.engine)
            router.shutdown()
            HandlerList.unregisterAll(router)
            prepared.routerRemoved = true
            // Leave native sessions/tokens owned by their listener until closed or consumed. Calling
            // shutdown here would remove click protection from an inventory already open on reload.
            access.dialogs.set(access.coordinator, renderer)
            access.inventories.set(access.coordinator, renderer)
            manager.commands[manager.commands.indexOf(originalInfo)] = replacementInfo
            AdvancedCombatRuntime.installHudProvider(hud)
            activeOwner = this
            plugin.logger.info("Passive-only classes installed for stock EliteMobs ${native.description.version}")
        } catch (failure: Throwable) {
            runCatching(::restore).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        check(Bukkit.isPrimaryThread()) { "Passive class integration must close on the server thread" }
        restore()
        closed = true
    }

    private fun restore() {
        val state = binding ?: return
        val failures = mutableListOf<Throwable>()
        fun restoring(action: () -> Unit) { runCatching(action).exceptionOrNull()?.let(failures::add) }
        val sameModule = AdvancedCombatModule.isInitialized() && AdvancedCombatModule.get() === state.module
        restoring { if (state.access.dialogs.get(state.access.coordinator) === state.renderer)
            state.access.dialogs.set(state.access.coordinator, state.originalDialogs) }
        restoring { if (state.access.inventories.get(state.access.coordinator) === state.renderer)
            state.access.inventories.set(state.access.coordinator, state.originalInventories) }
        restoring {
            val index = state.manager.commands.indexOfFirst { it === state.info }
            if (index >= 0) state.manager.commands[index] = state.originalInfo
        }
        if (sameModule) {
            restoring { if (state.access.hudProvider.get(null) === state.hud)
                AdvancedCombatRuntime.installHudProvider(state.originalHud) }
            restoring { if (state.access.abilityEngine.get(state.module) === state.engine)
                state.access.abilityEngine.set(state.module, state.originalEngine) }
            restoring { if (state.access.passiveRegistry.get(state.module) === state.registry)
                state.access.passiveRegistry.set(state.module, state.originalRegistry) }
            restoring {
                if (state.routerRemoved && state.native.isEnabled) {
                    Bukkit.getPluginManager().registerEvents(state.router, state.native)
                    state.routerRemoved = false
                }
            }
        }
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            throw first // Keep the binding so an owning lifecycle can retry cleanup.
        }
        binding = null
        if (activeOwner === this) activeOwner = null
    }

    private class Binding(
        val module: AdvancedCombatModule,
        val native: Plugin,
        val access: EliteMobsPassiveAccess,
        val originalRegistry: FixedPassiveRegistry,
        val registry: FixedPassiveRegistry,
        val originalEngine: ClassAbilityEngine,
        val engine: PassiveOnlyAbilityEngine,
        val router: ClassAbilityInputRouter,
        val originalHud: Function<Player, String>,
        val hud: Function<Player, String>,
        val renderer: Any,
        val originalDialogs: Any,
        val originalInventories: Any,
        val manager: CommandManager,
        val originalInfo: AdvancedCommand,
        val info: AdvancedCommand,
        var routerRemoved: Boolean = false,
    )

    private class ClassInfoCommand(private val open: (Player, String, Boolean) -> Unit) : AdvancedCommand(listOf("class")) {
        init {
            addLiteral("info")
            addArgument("class", DynamicListStringCommandArgument(
                { AdvancedCombatModule.get().catalog().forms().map { it.id() } }, "<class>"))
            setUsage("/em class info <class>")
            setDescription("Shows one class form and its passive effects.")
            setPermission("elitemobs.command")
            setSenderType(SenderType.PLAYER)
        }
        override fun execute(data: CommandData) = open(data.playerSender, data.getStringArgument("class"), false)
    }

    companion object {
        @Volatile private var activeOwner: PassiveClassCompatibility? = null
        fun isInstalled(): Boolean = activeOwner != null

        internal fun requireSupportedVersion(version: String) = check(version in PassiveClassContent.SUPPORTED_NATIVE_VERSIONS) {
            "Passive class integration supports stock EliteMobs ${PassiveClassContent.SUPPORTED_NATIVE_VERSIONS.joinToString()}; found $version"
        }

        internal fun renderHud(module: AdvancedCombatModule, player: Player): String {
            val health = CombatHealthFormatter.format(player.health)
            val maximum = CombatHealthFormatter.format(player.getAttribute(Attribute.MAX_HEALTH)?.value ?: player.health)
            val identity = AdvancedCombatModule.classProgressSnapshot(player.uniqueId).orElse(null)
                ?.let { " &8| &f[${it.effectiveLevel()}] &b${module.catalog().require(it.formId()).displayName()}" }.orEmpty()
            return "&cHP $health/$maximum$identity"
        }
    }
}

/** Denial happens before native spending/commit effects. Cleanup still belongs to the native engine. */
internal class PassiveOnlyAbilityEngine(private val native: ClassAbilityEngine) : ClassAbilityEngine {
    override fun execute(player: Player, lineage: ClassLineage, slot: AbilitySlot, effectiveLevel: Int): AbilityResult =
        AbilityResult.failure("passive-only.${slot.name.lowercase(Locale.ROOT)}", AbilityFailureReason.INVALID_PLAYER)
    override fun deactivate(player: Player) = native.deactivate(player)
    override fun close() = native.close()
}

/** All private third-party access stays here; public native gameplay APIs are used everywhere else. */
internal class EliteMobsPassiveAccess {
    val passiveRegistry = field(AdvancedCombatModule::class.java, "passiveRegistry", FixedPassiveRegistry::class.java)
    val abilityEngine = field(AdvancedCombatModule::class.java, "abilityEngine", ClassAbilityEngine::class.java)
    val hudProvider = field(AdvancedCombatRuntime::class.java, "hudProvider", Function::class.java, static = true, final = false)
    private val loader = ClassSelectionMenu::class.java.classLoader
    private val coordinatorType = Class.forName("com.magmaguy.elitemobs.advancedcombat.menu.ClassMenuCoordinator", false, loader)
    private val rendererType = Class.forName("com.magmaguy.elitemobs.advancedcombat.menu.ClassMenuRenderer", false, loader)
    private val viewType = Class.forName("com.magmaguy.elitemobs.advancedcombat.menu.ClassMenuView", false, loader)
    private val formType = Class.forName("com.magmaguy.elitemobs.advancedcombat.menu.ClassMenuView\$FormView", false, loader)
    val coordinator: Any = field(ClassSelectionMenu::class.java, "COORDINATOR", coordinatorType, static = true).get(null)
    val dialogs = field(coordinatorType, "dialogs", rendererType)
    val inventories = field(coordinatorType, "inventories", rendererType)
    private val formId = formType.getDeclaredMethod("id").also {
        check(it.returnType == String::class.java && it.trySetAccessible()) { "Native class form identity is inaccessible" }
    }

    init {
        check(rendererType.isInterface && rendererType.declaredMethods.size == 4)
        listOf(
            "showOverview" to arrayOf(Player::class.java, viewType),
            "showControls" to arrayOf(Player::class.java, viewType),
            "showForm" to arrayOf(Player::class.java, viewType, formType),
            "showForm" to arrayOf(Player::class.java, viewType, formType, Boolean::class.javaPrimitiveType!!),
        ).forEach { (name, parameters) -> check(rendererType.getDeclaredMethod(name, *parameters).returnType == Void.TYPE) }
        check(rendererType.isInstance(dialogs.get(coordinator)) && rendererType.isInstance(inventories.get(coordinator)))
    }

    fun renderer(overview: (Player) -> Unit, form: (Player, String, Boolean) -> Unit): Any =
        Proxy.newProxyInstance(loader, arrayOf(rendererType)) { proxy, method, args ->
            when (method.name) {
                "showOverview", "showControls" -> { overview(args[0] as Player); null }
                "showForm" -> { form(args[0] as Player, formId.invoke(args[2]) as String,
                    args.size == 4 && args[3] == false); null }
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ArcDungeonsPassiveClassRenderer"
                else -> error("Unexpected native class renderer method: ${method.name}")
            }
        }

    companion object {
        private val registryConstructor by lazy {
            FixedPassiveRegistry::class.java.getDeclaredConstructor(Map::class.java, Map::class.java, Map::class.java).also {
                check(Modifier.isPrivate(it.modifiers) && it.trySetAccessible()) { "Native passive registry constructor is inaccessible" }
            }
        }

        fun newRegistry(profiles: Map<String, PassiveProfile>, traits: Map<String, List<PassiveTrait>>,
            mechanics: Map<String, List<PassiveMechanicTrait>>): FixedPassiveRegistry =
            registryConstructor.newInstance(profiles, traits, mechanics)

        private fun field(owner: Class<*>, name: String, type: Class<*>, static: Boolean = false, final: Boolean = true): Field =
            owner.getDeclaredField(name).also {
                check(it.type == type && Modifier.isPrivate(it.modifiers) && Modifier.isStatic(it.modifiers) == static &&
                    Modifier.isFinal(it.modifiers) == final && it.trySetAccessible()) { "Unsupported native field: ${owner.simpleName}.$name" }
            }
    }
}
