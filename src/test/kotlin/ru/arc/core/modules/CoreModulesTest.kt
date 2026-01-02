@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.core.modules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.core.PluginModule

/**
 * Tests for core module implementations.
 */
class CoreModulesTest : TestBase() {

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
    }

    // ==================== Module Configuration Tests ====================

    @Nested
    @DisplayName("Module Configuration")
    inner class ModuleConfigTests {

        @Test
        @DisplayName("RedisModule has correct name and priority")
        fun testRedisModule() {
            assertEquals("Redis", RedisModule.name)
            assertEquals(10, RedisModule.priority)
            assertTrue(RedisModule.enabled)
        }

        @Test
        @DisplayName("NetworkModule has correct name and priority")
        fun testNetworkModule() {
            assertEquals("Network", NetworkModule.name)
            assertEquals(15, NetworkModule.priority)
        }

        @Test
        @DisplayName("HooksModule has correct name and priority")
        fun testHooksModule() {
            assertEquals("Hooks", HooksModule.name)
            assertEquals(20, HooksModule.priority)
        }

        @Test
        @DisplayName("EconomyModule has correct name and priority")
        fun testEconomyModule() {
            assertEquals("Economy", EconomyModule.name)
            assertEquals(25, EconomyModule.priority)
        }

        @Test
        @DisplayName("ConfigModule has correct name and priority")
        fun testConfigModule() {
            assertEquals("Config", ConfigModule.name)
            assertEquals(30, ConfigModule.priority)
        }

        @Test
        @DisplayName("BuildingModule has correct name and priority")
        fun testBuildingModule() {
            assertEquals("Building", BuildingModule.name)
            assertEquals(90, BuildingModule.priority)
        }

        @Test
        @DisplayName("SyncModule has correct name and priority")
        fun testSyncModule() {
            assertEquals("Sync", SyncModule.name)
            assertEquals(100, SyncModule.priority)
        }
    }

    // ==================== Module Priority Order Tests ====================

    @Nested
    @DisplayName("Module Priority Order")
    inner class PriorityOrderTests {

        private val allModules = listOf(
            RedisModule,
            NetworkModule,
            HooksModule,
            EconomyModule,
            ConfigModule,
            LocationPoolModule,
            BoardModule,
            ParticleModule,
            CooldownModule,
            HeadCacheModule,
            AuditModule,
            FarmModule,
            AnnounceModule,
            XActionModule,
            StockModule,
            StoreModule,
            TreasureModule,
            EliteLootModule,
            LeafDecayModule,
            PersonalLootModule,
            MobSpawnModule,
            JoinMessagesModule,
            BuildingModule,
            SyncModule
        )

        @Test
        @DisplayName("All modules have unique names")
        fun testUniqueNames() {
            val names = allModules.map { it.name }
            val uniqueNames = names.toSet()
            assertEquals(names.size, uniqueNames.size, "All module names should be unique")
        }

        @Test
        @DisplayName("Core infrastructure modules have lowest priority")
        fun testCoreInfrastructurePriority() {
            assertTrue(RedisModule.priority < ConfigModule.priority)
            assertTrue(NetworkModule.priority < ConfigModule.priority)
            assertTrue(HooksModule.priority < ConfigModule.priority)
            assertTrue(EconomyModule.priority < ConfigModule.priority)
        }

        @Test
        @DisplayName("Configuration modules come before game features")
        fun testConfigBeforeGameFeatures() {
            assertTrue(ConfigModule.priority < FarmModule.priority)
            assertTrue(LocationPoolModule.priority < FarmModule.priority)
            assertTrue(BoardModule.priority < FarmModule.priority)
        }

        @Test
        @DisplayName("Building module comes after game features")
        fun testBuildingAfterGameFeatures() {
            assertTrue(BuildingModule.priority > FarmModule.priority)
            assertTrue(BuildingModule.priority > StockModule.priority)
            assertTrue(BuildingModule.priority > TreasureModule.priority)
        }

        @Test
        @DisplayName("Sync module has highest priority (runs last)")
        fun testSyncModuleHighestPriority() {
            val maxPriority = allModules.maxOf { it.priority }
            assertEquals(SyncModule.priority, maxPriority)
        }

        @Test
        @DisplayName("Modules are sortable by priority")
        fun testModulesSortable() {
            val sorted = allModules.sortedBy { it.priority }

            // First should be Redis (priority 10)
            assertEquals("Redis", sorted.first().name)

            // Last should be Sync (priority 100)
            assertEquals("Sync", sorted.last().name)
        }
    }

    // ==================== Module Enabled State Tests ====================

    @Nested
    @DisplayName("Module Enabled State")
    inner class EnabledStateTests {

        @Test
        @DisplayName("All core modules are enabled by default")
        fun testAllModulesEnabled() {
            val modules = listOf(
                RedisModule,
                NetworkModule,
                HooksModule,
                EconomyModule,
                ConfigModule,
                ParticleModule,
                CooldownModule
            )

            modules.forEach { module ->
                assertTrue(module.enabled, "${module.name} should be enabled by default")
            }
        }
    }

    // ==================== EconomyModule Tests ====================

    @Nested
    @DisplayName("EconomyModule")
    inner class EconomyModuleTests {

        @Test
        @DisplayName("getEconomy returns null when Vault not present")
        fun testGetEconomyWithoutVault() {
            // In test environment, Vault is not loaded
            // EconomyModule.init() should not crash
            EconomyModule.init()

            // Economy should be null since Vault is not present
            assertNull(EconomyModule.getEconomy())
        }

        @Test
        @DisplayName("Shutdown does nothing (no resources to clean)")
        fun testShutdownSafe() {
            // Should not throw
            EconomyModule.shutdown()
        }
    }

    // ==================== PluginModule Interface Compliance ====================

    @Nested
    @DisplayName("PluginModule Interface Compliance")
    inner class InterfaceComplianceTests {

        private val allModules: List<PluginModule> = listOf(
            RedisModule,
            NetworkModule,
            HooksModule,
            EconomyModule,
            ConfigModule,
            LocationPoolModule,
            BoardModule,
            ParticleModule,
            CooldownModule,
            HeadCacheModule,
            AuditModule,
            FarmModule,
            AnnounceModule,
            XActionModule,
            StockModule,
            StoreModule,
            TreasureModule,
            EliteLootModule,
            LeafDecayModule,
            PersonalLootModule,
            MobSpawnModule,
            JoinMessagesModule,
            BuildingModule,
            SyncModule
        )

        @Test
        @DisplayName("All modules implement PluginModule")
        fun testAllImplementPluginModule() {
            allModules.forEach { module ->
                assertTrue(module is PluginModule, "${module.name} should implement PluginModule")
            }
        }

        @Test
        @DisplayName("All modules have non-empty names")
        fun testAllHaveNames() {
            allModules.forEach { module ->
                assertTrue(module.name.isNotBlank(), "Module name should not be blank")
            }
        }

        @Test
        @DisplayName("All modules have valid priority (positive)")
        fun testAllHaveValidPriority() {
            allModules.forEach { module ->
                assertTrue(module.priority > 0, "${module.name} should have positive priority")
            }
        }
    }

    // ==================== Module Count Test ====================

    @Test
    @DisplayName("Total module count is 24")
    fun testTotalModuleCount() {
        val allModules = listOf(
            RedisModule,
            NetworkModule,
            HooksModule,
            EconomyModule,
            ConfigModule,
            LocationPoolModule,
            BoardModule,
            ParticleModule,
            CooldownModule,
            HeadCacheModule,
            AuditModule,
            FarmModule,
            AnnounceModule,
            XActionModule,
            StockModule,
            StoreModule,
            TreasureModule,
            EliteLootModule,
            LeafDecayModule,
            PersonalLootModule,
            MobSpawnModule,
            JoinMessagesModule,
            BuildingModule,
            SyncModule
        )

        assertEquals(24, allModules.size, "Should have 24 modules")
    }
}


