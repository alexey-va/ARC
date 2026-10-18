package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.MetadataHandler
import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule
import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatRuntime
import com.magmaguy.elitemobs.advancedcombat.abilities.AbilityContribution
import com.magmaguy.elitemobs.advancedcombat.abilities.ClassAbilityEngine
import com.magmaguy.elitemobs.advancedcombat.classes.AbilitySlot
import com.magmaguy.elitemobs.advancedcombat.classes.ClassBand
import com.magmaguy.elitemobs.advancedcombat.content.BuiltInClassContent
import com.magmaguy.elitemobs.advancedcombat.input.ClassAbilityInputRouter
import com.magmaguy.elitemobs.advancedcombat.menu.ClassSelectionMenu
import com.magmaguy.elitemobs.advancedcombat.passives.ClassPassiveRuntime
import com.magmaguy.elitemobs.advancedcombat.progression.ClassContentAvailability
import com.magmaguy.elitemobs.advancedcombat.progression.ClassProgressionModule
import com.magmaguy.elitemobs.advancedcombat.progression.ClassProgressionStore
import com.magmaguy.elitemobs.advancedcombat.progression.FoundationLevelSnapshot
import com.magmaguy.elitemobs.advancedcombat.progression.StoredClassProfile
import com.magmaguy.elitemobs.advancedcombat.progression.StoredClassProgress
import com.magmaguy.elitemobs.commands.AdvancedClassInfoCommand
import com.magmaguy.elitemobs.combatsystem.combattag.DungeonCombatRuntime
import com.magmaguy.elitemobs.combatsystem.combattag.PlayerCombatState
import com.magmaguy.elitemobs.config.MobCombatSettingsConfig
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfigFields
import com.magmaguy.elitemobs.dungeons.EliteMobsWorld
import com.magmaguy.elitemobs.magmacore.command.CommandManager
import com.magmaguy.elitemobs.presentation.actionbar.ActionBarCompositor
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.SkillXPCalculator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.block.BlockFace
import org.bukkit.command.PluginCommand
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.Optional
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.ToIntFunction
import java.util.logging.Logger

class PassiveClassCompatibilityTest : FreeSpec({
    "unsupported versions fail before changing any native state" {
        listOf("10.9.1", "10.9.8-ruscrafting.1", "10.9.9", "10.9.10-custom", "10.9.11").forEach {
            shouldThrow<IllegalStateException> { PassiveClassCompatibility.requireSupportedVersion(it) }
        }
        PassiveClassCompatibility.requireSupportedVersion("10.9.8")
        PassiveClassCompatibility.requireSupportedVersion("10.9.10")
    }

    "install is idempotent and close restores exact native references and command contract" {
        failOnUnsupportedMockBukkitOperation { PassiveHostFixture().use { f ->
            val registry = f.access.passiveRegistry.get(f.module)
            val engine = f.access.abilityEngine.get(f.module)
            val dialog = f.access.dialogs.get(f.access.coordinator)
            val inventory = f.access.inventories.get(f.access.coordinator)
            val hud = f.access.hudProvider.get(null)
            val progression = field(f.module, "progression")
            val passiveRuntime = field(f.module, "passiveRuntime")
            val updateTask = field(f.module, "updateTask")
            f.adapter.start()
            f.adapter.start()
            (f.access.passiveRegistry.get(f.module) === PassiveClassContent.registry()) shouldBe true
            (f.access.abilityEngine.get(f.module) is PassiveOnlyAbilityEngine) shouldBe true
            (field(f.module, "progression") === progression) shouldBe true
            (field(f.module, "passiveRuntime") === passiveRuntime) shouldBe true
            (field(f.module, "updateTask") === updateTask) shouldBe true
            f.manager.commands.single().permission shouldBe f.info.permission
            f.manager.commands.single().argumentsMap shouldBe f.info.argumentsMap
            f.manager.commands.single().argumentsList.map { it.javaClass } shouldBe f.info.argumentsList.map { it.javaClass }
            f.manager.commands.single().argumentsList.last().getSuggestions(f.player, "occ") shouldBe
                f.info.argumentsList.last().getSuggestions(f.player, "occ")
            PassiveClassCompatibility.isInstalled() shouldBe true
            f.adapter.close()
            f.adapter.close()
            (f.access.passiveRegistry.get(f.module) === registry) shouldBe true
            (f.access.abilityEngine.get(f.module) === engine) shouldBe true
            (f.access.dialogs.get(f.access.coordinator) === dialog) shouldBe true
            (f.access.inventories.get(f.access.coordinator) === inventory) shouldBe true
            (f.access.hudProvider.get(null) === hud) shouldBe true
            (f.manager.commands.single() === f.info) shouldBe true
            f.nativeListeners().count { it is ClassAbilityInputRouter } shouldBe 1
            PassiveClassCompatibility.isInstalled() shouldBe false
        } }
    }

    "native combat keeps progression and ordinary healing while casts cannot spend or produce effects" {
        failOnUnsupportedMockBukkitOperation { PassiveHostFixture().use { f ->
            f.adapter.start()
            f.module.selectForm(f.player, "cleric").accepted() shouldBe true
            val before = f.module.profile(f.player.uniqueId).orElseThrow()
            val tutorial = f.progression.tutorialProgress(f.player.uniqueId)
            val resource = AdvancedCombatModule.resourceSnapshot(f.player.uniqueId)
            for (slot in AbilitySlot.entries) {
                val result = f.module.useAbility(f.player, slot)
                result.successful() shouldBe false
                result.contribution() shouldBe AbilityContribution.NONE
                result.continuesAsynchronously() shouldBe false
            }
            f.module.profile(f.player.uniqueId).orElseThrow() shouldBe before
            AdvancedCombatModule.resourceSnapshot(f.player.uniqueId) shouldBe resource
            f.progression.tutorialProgress(f.player.uniqueId) shouldBe tutorial
            val healing = f.paper.callEvent(EntityRegainHealthEvent(f.player, 10.0, EntityRegainHealthEvent.RegainReason.CUSTOM))
            healing.amount shouldBe (10.455 plusOrMinus .0000001)
            f.nativeListeners().any { it === f.module } shouldBe true
            f.nativeListeners().any { it is ClassPassiveRuntime } shouldBe true
            f.module.mechanicsActive(f.player) shouldBe true
        } }
    }

    "swap hotbar and weapon clicks pass through after input removal" {
        failOnUnsupportedMockBukkitOperation { PassiveHostFixture().use { f ->
            f.adapter.start()
            f.module.selectForm(f.player, "ranger").accepted() shouldBe true
            f.nativeListeners().none { it is ClassAbilityInputRouter } shouldBe true
            for (sneaking in listOf(false, true)) {
                f.player.isSneaking = sneaking
                repeat(2) {
                    f.paper.callEvent(PlayerSwapHandItemsEvent(f.player, ItemStack(Material.AIR), ItemStack(Material.AIR)))
                        .isCancelled shouldBe false
                }
            }
            f.paper.callEvent(PlayerItemHeldEvent(f.player, 0, 1)).isCancelled shouldBe false
            for (action in listOf(Action.LEFT_CLICK_BLOCK, Action.RIGHT_CLICK_BLOCK)) {
                val event = PlayerInteractEvent(f.player, action, ItemStack(Material.STONE_SWORD),
                    f.player.world.getBlockAt(0, 64, 0), BlockFace.UP)
                val item = event.useItemInHand()
                val block = event.useInteractedBlock()
                f.paper.callEvent(event)
                event.useItemInHand() shouldBe item
                event.useInteractedBlock() shouldBe block
            }
            AdvancedCombatModule.isAbilityGestureOpen(f.player.uniqueId) shouldBe false
            val speed = f.player.getAttribute(Attribute.MOVEMENT_SPEED)!!
            speed.value shouldBe (speed.baseValue * 1.06075 plusOrMinus .0000001)
        } }
    }

    "a replaced native module keeps its own HUD and never receives the previous input router" {
        failOnUnsupportedMockBukkitOperation { PassiveHostFixture().use { f ->
            f.adapter.start()
            val replacement = mockk<AdvancedCombatModule>()
            val replacementHud = Function<Player, String> { "replacement" }
            val instance = AdvancedCombatModule::class.java.getDeclaredField("instance").apply { isAccessible = true }
            instance.set(null, replacement)
            AdvancedCombatRuntime.installHudProvider(replacementHud)
            try {
                f.adapter.close()
                (f.access.hudProvider.get(null) === replacementHud) shouldBe true
                f.nativeListeners().none { it is ClassAbilityInputRouter } shouldBe true
                (f.manager.commands.single() === f.info) shouldBe true
            } finally { instance.set(null, f.module) }
        } }
    }

    "missing native command rejects installation without touching listeners or fields" {
        failOnUnsupportedMockBukkitOperation { PassiveHostFixture().use { f ->
            val registry = f.access.passiveRegistry.get(f.module)
            val engine = f.access.abilityEngine.get(f.module)
            f.manager.commands.clear()
            shouldThrow<IllegalStateException> { f.adapter.start() }
            (f.access.passiveRegistry.get(f.module) === registry) shouldBe true
            (f.access.abilityEngine.get(f.module) === engine) shouldBe true
            f.nativeListeners().count { it is ClassAbilityInputRouter } shouldBe 1
            PassiveClassCompatibility.isInstalled() shouldBe false
        } }
    }

    "both native menu renderers delegate overview controls and details to the existing addon UI" {
        failOnUnsupportedMockBukkitOperation { PassiveHostFixture().use { f ->
            f.adapter.start()
            val namespace = "com.magmaguy.elitemobs.advancedcombat.menu."
            val projector = Class.forName(namespace + "ClassMenuProjector")
            val project = projector.declaredMethods.single { it.name == "project" }.apply { isAccessible = true }
            val view = project.invoke(null, f.module.catalog(), f.module.profile(f.player.uniqueId).orElseThrow(),
                ToIntFunction<SkillType> { 100 })
            val form = view.javaClass.getDeclaredMethod("requireForm", String::class.java)
                .apply { isAccessible = true }.invoke(view, "occultist")
            val rendererType = Class.forName(namespace + "ClassMenuRenderer")
            for (renderer in listOf(f.access.dialogs.get(f.access.coordinator), f.access.inventories.get(f.access.coordinator))) {
                for (name in listOf("showOverview", "showControls")) {
                    rendererType.declaredMethods.single { it.name == name }.apply { isAccessible = true }
                        .invoke(renderer, f.player, view)
                }
                rendererType.declaredMethods.single { it.name == "showForm" && it.parameterCount == 4 }
                    .apply { isAccessible = true }.invoke(renderer, f.player, view, form, false)
            }
            f.opened shouldBe listOf("overview", "overview", "occultist:true", "overview", "overview", "occultist:true")
            val renderer = f.access.dialogs.get(f.access.coordinator)
            rendererType.declaredMethods.single { it.name == "showForm" && it.parameterCount == 3 }
                .apply { isAccessible = true }.invoke(renderer, f.player, view, form)
            f.opened.last() shouldBe "occultist:false"
        } }
    }

    "install preserves click protection for a native inventory already open on addon reload" {
        failOnUnsupportedMockBukkitOperation { PassiveHostFixture().use { f ->
            val namespace = "com.magmaguy.elitemobs.advancedcombat.menu."
            val projector = Class.forName(namespace + "ClassMenuProjector")
            val project = projector.declaredMethods.single { it.name == "project" }.apply { isAccessible = true }
            val view = project.invoke(null, f.module.catalog(), f.module.profile(f.player.uniqueId).orElseThrow(),
                ToIntFunction<SkillType> { 100 })
            val renderer = f.access.inventories.get(f.access.coordinator)
            val rendererType = Class.forName(namespace + "ClassMenuRenderer")
            f.paper.server.pluginManager.registerEvents(ClassSelectionMenu.listener(), f.native)
            rendererType.declaredMethods.single { it.name == "showControls" }.apply { isAccessible = true }
                .invoke(renderer, f.player, view)
            try {
                val inventory = f.player.openInventory.topInventory
                inventory.getItem(4)?.type shouldBe Material.WRITABLE_BOOK
                f.adapter.start()
                val click = InventoryClickEvent(f.player.openInventory, InventoryType.SlotType.CONTAINER,
                    4, ClickType.LEFT, InventoryAction.PICKUP_ALL)
                f.paper.callEvent(click).isCancelled shouldBe true
                inventory.getItem(4)?.type shouldBe Material.WRITABLE_BOOK
            } finally {
                f.player.closeInventory()
            }
        } }
    }

    "a failure after mutations rolls back every borrowed reference" {
        failOnUnsupportedMockBukkitOperation { PassiveHostFixture().use { f ->
            val originalHud = f.access.hudProvider.get(null)
            val originalRegistry = f.access.passiveRegistry.get(f.module)
            val originalEngine = f.access.abilityEngine.get(f.module)
            val originalDialog = f.access.dialogs.get(f.access.coordinator)
            val owner = mockk<Plugin>()
            val logger = mockk<Logger>()
            every { owner.logger } returns logger
            every { logger.info(any<String>()) } throws IllegalStateException("test install failure")
            val candidate = PassiveClassCompatibility(owner, {}, { _, _, _ -> })
            try {
                shouldThrow<IllegalStateException> { candidate.start() }.message shouldBe "test install failure"
                (f.access.hudProvider.get(null) === originalHud) shouldBe true
                (f.access.passiveRegistry.get(f.module) === originalRegistry) shouldBe true
                (f.access.abilityEngine.get(f.module) === originalEngine) shouldBe true
                (f.access.dialogs.get(f.access.coordinator) === originalDialog) shouldBe true
                (f.manager.commands.single() === f.info) shouldBe true
                f.nativeListeners().count { it is ClassAbilityInputRouter } shouldBe 1
                PassiveClassCompatibility.isInstalled() shouldBe false
            } finally { candidate.close() }
        } }
    }
})

/** Builds the actual stock module with its existing injectable store seam; no native engine copy. */
private class PassiveHostFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    private val previousPlugin = MetadataHandler.PLUGIN
    val native = paper.createSimplePlugin("EliteMobs")
    val player = paper.addPlayer("PassiveClassTester")
    val access: EliteMobsPassiveAccess
    val info = AdvancedClassInfoCommand()
    val manager: CommandManager
    val progression: ClassProgressionModule
    val module: AdvancedCombatModule
    val adapter: PassiveClassCompatibility
    val opened = mutableListOf<String>()

    init {
        var createdModule: AdvancedCombatModule? = null
        try {
            access = EliteMobsPassiveAccess()
            PluginDescriptionFile::class.java.getDeclaredField("version").apply { isAccessible = true }
                .set(native.description, eliteMobsTestRuntimeVersion())
            MetadataHandler.PLUGIN = native
            val command = PluginCommand::class.java.getDeclaredConstructor(String::class.java, Plugin::class.java)
                .apply { isAccessible = true }.newInstance("elitemobs", native)
            paper.server.commandMap.register("elitemobs", command)
            manager = CommandManager(native, "elitemobs").also { it.commands.add(info) }
            MobCombatSettingsConfig(Files.createTempDirectory("arc-passive-native").resolve("MobCombatSettings.yml").toFile())
            EliteMobsWorld.create(player.world.uid, ContentPackagesConfigFields("passive-test.yml", true))
            val catalog = BuiltInClassContent.catalog()
            val store = mockk<ClassProgressionStore>(relaxed = true)
            every { store.loadOrCreateProfile(any(), any()) } answers {
                StoredClassProfile(firstArg(), "cleric", "default", secondArg())
            }
            every { store.loadAllProgress(any()) } answers {
                catalog.roots().map {
                    val xp = if (it.band() == ClassBand.STARTER) SkillXPCalculator.totalXPForLevel(it.band().effectiveEnd()) else 0L
                    StoredClassProgress(firstArg(), it.id(), xp, catalog.persistenceVersion(), true)
                }
            }
            every { store.loadProgressOrZero(any(), any(), any()) } answers { StoredClassProgress.zero(firstArg(), secondArg(), thirdArg()) }
            progression = ClassProgressionModule(catalog,
                { id -> Optional.of(FoundationLevelSnapshot(id, SkillType.entries.associateWith { 100 })) }, store, Runnable::run)
            progression.load(player.uniqueId).join()
            val constructor = AdvancedCombatModule::class.java.getDeclaredConstructor(
                PlayerCombatState::class.java, Function::class.java, Predicate::class.java).apply { isAccessible = true }
            module = constructor.newInstance(DungeonCombatRuntime(200, 20),
                Function<ClassContentAvailability, ClassProgressionModule> { progression }, Predicate<Player> { true })
            createdModule = module
            AdvancedCombatModule::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, module)
            AdvancedCombatModule::class.java.getDeclaredMethod("startGameplay").apply { isAccessible = true }.invoke(module)
            adapter = PassiveClassCompatibility(native, { opened += "overview" }, { _, id, atTrainer -> opened += "$id:$atTrainer" })
        } catch (failure: Throwable) {
            runCatching { createdModule?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { DungeonCombatRuntime.shutdownIfInitialized() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { ActionBarCompositor.shutdown() }.exceptionOrNull()?.let(failure::addSuppressed)
            EliteMobsWorld.destroy(player.world.uid)
            MetadataHandler.PLUGIN = previousPlugin
            runCatching { paper.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun nativeListeners() = HandlerList.getRegisteredListeners(native).map { it.listener }.distinct()

    override fun close() {
        try {
            adapter.close()
            module.close()
        } finally {
            DungeonCombatRuntime.shutdownIfInitialized()
            ActionBarCompositor.shutdown()
            EliteMobsWorld.destroy(player.world.uid)
            MetadataHandler.PLUGIN = previousPlugin
            paper.close()
        }
    }
}

private fun field(module: AdvancedCombatModule, name: String): Any =
    AdvancedCombatModule::class.java.getDeclaredField(name).apply { isAccessible = true }.get(module)
