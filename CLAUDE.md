# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ARC is a comprehensive Minecraft plugin (Spigot/Paper) written in Java and Kotlin that provides multiple gameplay
features including treasure hunts, auto-building system, stock market simulation, cross-server announcements,
farms/mines, and more. It's a monolithic core plugin designed for a personal server with heavy Redis integration for
cross-server functionality.

## CRITICAL: Development Philosophy

**ALWAYS prioritize these principles when working on this codebase:**

1. **Migrate to Kotlin**: When touching Java code, ALWAYS suggest migration to Kotlin. New code must be in Kotlin unless
   it's a public API for other plugins.

2. **Use Kotest + MockK for tests**: All Kotlin tests should use Kotest for assertions and MockK for mocking. Never use
   JUnit assertions or Mockito in Kotlin tests.

3. **Use DSLs where possible**: Leverage and improve existing DSLs (EventDsl, TaskDsl, GuiDsl, ItemStackDsl). If you see
   repetitive patterns, create new DSLs.

4. **Configuration pattern**: Always use the `get()` accessor pattern for configuration (see Core Context section). This
   enables auto-reload and testability.

5. **Write tests for everything**: Write tests for both old logic (when refactoring) AND new logic. Minimum 80%
   coverage. Never use `@Ignore` or `@Disabled`.

6. **Make code testable**: Use constructor injection, avoid static calls, extract interfaces. If it's hard to test,
   refactor it.

## Build and Development Commands

### Java Requirements

**CRITICAL**: This project requires **Java 25 or higher**.

**Setting JAVA_HOME for Gradle:**

```bash
# Use Java 25 (Temurin)
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home

# Verify Java version
$JAVA_HOME/bin/java -version  # Should show version 25
```

**Or set it inline with each Gradle command:**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew build
```

**Add to your shell profile** (~/.zshrc or ~/.bashrc):

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
```

### Building

```bash
# Build the plugin (creates shadowJar)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew build

# Build without tests
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew build -x test

# Create shadowJar only
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew shadowJar
```

Output: `build/libs/ARC-1.0.jar` (shaded with dependencies)

### Testing

```bash
# Run all tests
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test

# Run specific test class
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test --tests "ru.arc.farm.FarmServiceTest"

# Run tests with a pattern
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test --tests "*FarmTest"

# Run tests continuously on changes
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test --continuous
```

**Important for macOS users**: Tests use Testcontainers for Redis integration tests. The build.gradle.kts configures
Colima automatically if on macOS. Ensure Docker (via Colima or Docker Desktop) is running before running tests.

### Code Quality

```bash
# Check for compilation errors
./gradlew compileKotlin compileJava
```

## Architecture Overview

### Module System and Dependency Injection

The plugin uses a lightweight module system without a DI framework:

- **`PluginModule`** interface: Defines lifecycle (`init()`, `reload()`, `shutdown()`, `priority`)
- **`ModuleRegistry`**: Singleton that manages all modules, initializing them in priority order (lower = earlier)
- Modules are registered in `ARC.java` during `onEnable()` via `ModuleRegistry.registerAll()`

Priority order determines initialization sequence:

- 10: Redis connection
- 20: External plugin hooks
- 30: Configuration system
- 40-50: Core infrastructure (LocationPools, Audit)
- 70-90: Game features (Farms, Stock, Treasures, etc.)

When creating new features, implement `PluginModule` and register in `ModuleRegistry`.

### Repository Pattern (Three-Tier Data Architecture)

The codebase uses a sophisticated repository pattern for data persistence:

1. **Local Cache Layer** (`ConcurrentLocalCache<T>`):
    - Thread-safe in-memory cache
    - Dirty tracking for efficient saves
    - Context-aware cleanup (removes entities for offline players)

2. **Storage Layer** (`Storage<T>` interface):
    - Abstracts persistence (Redis hash maps, files, etc.)
    - Includes retry logic with exponential backoff
    - Implementations: `RedisStorage`, file-based storage

3. **Orchestration Layer** (`CachedRepository<T>`):
    - Combines cache + storage + background sync
    - Load deduplication (prevents concurrent loads)
    - Background save job (configurable interval)
    - Kotlin Flow-based reactive updates
    - Methods: `load()`, `save()`, `delete()`, `getAll()`, `getOrLoad()`

**Key Pattern**: Entities are dirty-tracked. Background jobs periodically save only modified entities to Redis, then
broadcast updates via pub/sub to other servers.

### Redis Integration for Cross-Server Communication

**`RedisManager`** provides three communication patterns:

1. **Pub/Sub for real-time events**:
    - `publish(channel, message)` - Broadcast to all servers
    - `registerChannelUnique(channel, handler)` - Subscribe to channel
    - Messages auto-include server name to prevent self-echo

2. **Hash maps for persistence**:
    - `saveMap(key, id, data)` - Store entity
    - `loadMap(key, id)` - Retrieve entity
    - Batch operations: `saveMapEntries()`, `loadMapEntries()`

3. **`SyncService<T>`** for entity synchronization:
    - Automatically broadcasts create/update/delete events
    - Other servers listen and update their local caches
    - Implements eventual consistency across servers

### Configuration System

**Two config types**:

1. **Static YAML configs** (`Config` class):
    - Type-safe accessors: `.int("path")`, `.string("path")`, `.component("path")`
    - Duration parsing: `"30s"`, `"5m"` → `Duration`
    - Auto-save on modification
    - Managed by `ConfigManager.of(dataPath, "file.yml")`

2. **Typed config objects** (e.g., `FarmConfig`, `BoardConfig`):
    - Parse and validate YAML into strongly-typed data classes
    - Support hot-reload via `reload()` method

### Event and Task DSLs

**Type-safe Kotlin DSLs** for common Bukkit operations:

```kotlin
// Events
on<PlayerJoinEvent> { event -> /* handle */ }
once<PlayerQuitEvent> { /* fires once, then unregisters */ }

// Tasks
delayed(20.ticks) { /* runs after 1 second */ }
repeating(60.ticks, delay = 20.ticks) { /* repeats every 3 seconds */ }
async { /* async work */ }
```

These DSLs use abstraction interfaces (`EventBus`, `TaskScheduler`) for testability. Tests use `TestEventBus` and
`TestTaskScheduler` instead of real Bukkit APIs.

### GUI System

The plugin uses a custom GUI DSL built on top of InventoryUI framework (shadowed as `arc.arc.libs.inventoryframework`):

- **`GuiDsl.kt`**: Fluent DSL for building GUIs
- **`ConfigGui.kt`**: YAML-driven GUI definitions
- Pattern: GUIs are defined in `src/main/resources/guis/*.yml`

### Java/Kotlin Interoperability

**Strategic layering**:

- **Java**: Plugin entry point (`ARC.java`), Bukkit APIs, third-party hooks
- **Kotlin**: All framework code (repository, modules, DSLs), domain features
- **Bridge**: Kotlin companion objects with `@JvmStatic` for Java access

**Guidelines**:

- New features should be written in Kotlin (see `.cursor/rules/rule1.mdc`)
- Only use Java for public APIs exposed to other plugins
- Use `@JvmStatic`, `@JvmOverloads` for Java interop when needed

## Testing Philosophy

From `.cursor/rules/rule1.mdc`:

- **NEVER use `@Ignore` or `@Disabled`** - if a test doesn't work, refactor the code to be testable
- If mocking is difficult, it indicates architectural issues - extract interfaces, simplify dependencies
- Use AAA pattern (Arrange, Act, Assert)
- Test naming: `should_ExpectedBehavior_When_Condition` (Java) or `` `should do something when condition` `` (Kotlin)
- Minimum 80% coverage for business logic
- **Java tests**: JUnit 5 + Mockito + MockBukkit
- **Kotlin tests**: **MUST use Kotest + MockK** (never JUnit assertions or Mockito in Kotlin)

### Kotest + MockK Pattern (Required for Kotlin)

```kotlin
class MyServiceTest : FreeSpec({
    "MyService" - {
        "should load data when requested" {
            // Arrange
            val repository = mockk<Repository>()
            val service = MyService(repository)
            every { repository.load(any()) } returns Result.success(testData)

            // Act
            val result = service.getData("test-id")

            // Assert
            result shouldBe Success(testData)
            verify { repository.load("test-id") }
        }

        "should handle errors gracefully" {
            val repository = mockk<Repository>()
            every { repository.load(any()) } throws IOException()

            val service = MyService(repository)

            shouldThrow<ServiceException> {
                service.getData("test-id")
            }
        }
    }
})
```

**When refactoring existing code**:

1. Write tests for OLD behavior first (characterization tests)
2. Refactor the code
3. Ensure old tests still pass
4. Add new tests for new behavior
5. Never remove passing tests

**Testability abstractions**:

- `TimeProvider` - Control time in tests
- `TaskScheduler` - Mock Bukkit scheduler
- `EventBus` - Fire events without Bukkit
- All core classes accept dependencies via constructor injection

## Key Architectural Patterns

### Avoid Bukkit Static API Calls

Do NOT call `Bukkit.getPlayer()`, `Bukkit.getServer()`, etc. directly in business logic:

**Bad**:

```kotlin
val player = Bukkit.getPlayer(uuid)
```

**Good**:

```kotlin
class MyService(private val playerProvider: PlayerProvider) {
    fun doSomething(uuid: UUID) {
        val player = playerProvider.getPlayer(uuid)
    }
}
```

This enables unit testing without MockBukkit server setup.

### Thread Safety and Async

- **Never call Bukkit API from async threads** - it will throw exceptions
- Use `BukkitScheduler` to sync back to main thread
- Kotlin coroutines: Use appropriate dispatchers (`Dispatchers.IO` for I/O, `Dispatchers.Default` for CPU)
- Pattern: Load data async, apply changes on main thread

### Code Organization Principles

From `.cursor/rules/rule1.mdc`:

1. One class = one responsibility (SOLID)
2. Prefer composition over inheritance
3. Maximum method length: 30 lines
4. Maximum class complexity: 200 lines
5. Create interfaces for all services and managers
6. Use Kotlin features: data classes, sealed classes, extension functions, coroutines
7. Use dependency injection through constructors (no static dependencies)

## Common Patterns in This Codebase

### Creating a New Feature Module

1. Create a package under `ru.arc.{feature}` in **Kotlin** (src/main/kotlin/)
2. Create config class with `get()` accessors (see Core Context)
3. Implement `PluginModule` interface in Kotlin
4. Define initialization priority
5. Register in `ModuleRegistry` (in `ARC.java`)
6. Use `CachedRepository` for data persistence
7. **Write tests FIRST** (TDD) using Kotest + MockK
8. Use DSLs where applicable (EventDsl, TaskDsl, GuiDsl)

Example module structure:

```
ru.arc.myfeature/
├── MyFeatureModule.kt          # PluginModule implementation
├── MyFeatureConfig.kt           # Config with get() accessors
├── MyFeatureService.kt          # Business logic
├── MyFeatureRepository.kt       # Data persistence
├── MyFeatureEntity.kt           # Data class
└── gui/
    └── MyFeatureGui.kt          # GUI using GuiDsl
```

### Adding a New Entity Type with Persistence

1. Create data class implementing `Entity` interface (requires `id(): String`)
2. Create `Storage<T>` implementation (usually `RedisStorage`)
3. Create `CachedRepository<T>` instance with config
4. Optional: Use `SyncService<T>` for cross-server sync
5. Register save/load in module's `init()` and `shutdown()`

### Adding Cross-Server Communication

1. Define a channel name (e.g., `"my_feature_updates"`)
2. Publish: `RedisManager.publish(channel, message)`
3. Subscribe: `RedisManager.registerChannelUnique(channel) { message -> /* handle */ }`
4. Message format: JSON serialized data (Gson is available)
5. RedisManager automatically filters self-echo (messages from same server)

### Working with Configuration

**ALWAYS create a config class with get() accessors** (see Core Context section):

```kotlin
// 1. Create config class
open class MyFeatureConfig(private val config: Config) {
    open val enabled: Boolean
        get() = config.bool("enabled", true)

    open val timeout: Long
        get() = config.durationTicks("timeout", 20)

    open val message: Component
        get() = config.component("messages.welcome") ?: Component.text("Welcome")

    open val redisHost: String
        get() = config.string("redis.host", "localhost")

    companion object {
        fun load(dataPath: Path): MyFeatureConfig {
            return MyFeatureConfig(ConfigManager.of(dataPath, "myfeature.yml"))
        }
    }
}

// 2. Create test config
class TestMyFeatureConfig(
    override val enabled: Boolean = true,
    override val timeout: Long = 20,
    override val message: Component = Component.text("Test"),
    override val redisHost: String = "localhost"
) : MyFeatureConfig(EmptyConfig)

// 3. Use in production
val config = MyFeatureConfig.load(plugin.dataPath)
if (config.enabled) { /* ... */
}

// 4. Use in tests
val testConfig = TestMyFeatureConfig(enabled = false, timeout = 100)
```

**Why not direct Config access?** The get() pattern enables hot-reload, type safety, and easy testing.

## Important Dependencies

**Runtime dependencies** (must be on server):

- Paper/Spigot API (1.21+)
- Vault (economy)
- Redis (data storage and cross-server communication)
- WorldEdit/WorldGuard (for building system)

**Optional integrations** (hooks):

- Citizens (NPCs)
- ItemsAdder (custom items)
- Jobs Reborn (job boosts)
- BetterStructures (chest generation)
- EliteMobs (loot system)
- PlaceholderAPI (placeholders)

**Shaded into JAR**:

- InventoryUI framework (GUI system)
- NBT-API (item manipulation)
- CustomBlockData (block metadata)
- Kotlin stdlib + coroutines
- Log4j2 (logging)
- Gson (JSON)

## Migration from Java to Kotlin (CRITICAL)

**ALWAYS suggest migration when touching Java code. This is a project requirement.**

### Migration Checklist

1. **Before migration**: Write characterization tests for existing Java behavior
2. **Data classes**: Replace POJOs with `data class`
3. **Null safety**: Replace null checks with Kotlin's `?` and `?.` operators
4. **Coroutines**: Replace `CompletableFuture` with `suspend fun`
5. **Collections**: Use Kotlin collection operations (`map`, `filter`, etc.)
6. **Extension functions**: Replace utility classes with extensions
7. **Sealed classes**: Use for state/result types instead of enums
8. **DSLs**: Identify repetitive patterns and create DSLs
9. **Config classes**: Convert to `get()` accessor pattern
10. **Tests**: Convert to Kotest + MockK
11. **After migration**: Ensure all tests pass and add new Kotest tests
12. **Keep Java compatibility**: Use `@JvmStatic`, `@JvmOverloads` where needed for public APIs

### Example Migration

**Before (Java)**:

```java
public class EconomyService {
    private final EconomyRepository repository;

    public EconomyService(EconomyRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public CompletableFuture<BigDecimal> addMoney(UUID playerId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        return repository.updateBalance(playerId, balance -> balance.add(amount));
    }
}
```

**After (Kotlin)**:

```kotlin
class EconomyService(
    private val repository: EconomyRepository
) {
    suspend fun addMoney(playerId: UUID, amount: BigDecimal): BigDecimal {
        require(amount > BigDecimal.ZERO) { "Amount must be positive" }
        return repository.updateBalance(playerId) { it + amount }
    }
}
```

**Tests Before (JUnit + Mockito)**:

```java

@Test
public void should_AddMoney_When_AmountIsPositive() {
    // Arrange
    EconomyRepository repository = mock(EconomyRepository.class);
    when(repository.updateBalance(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(new BigDecimal("150")));

    // Act
    BigDecimal result = service.addMoney(playerId, new BigDecimal("50")).get();

    // Assert
    assertEquals(new BigDecimal("150"), result);
}
```

**Tests After (Kotest + MockK)**:

```kotlin
class EconomyServiceTest : FreeSpec({
    "EconomyService" - {
        "should add money when amount is positive" {
            // Arrange
            val repository = mockk<EconomyRepository>()
            val service = EconomyService(repository)
            coEvery { repository.updateBalance(any(), any()) } returns BigDecimal("150")

            // Act
            val result = service.addMoney(playerId, BigDecimal("50"))

            // Assert
            result shouldBe BigDecimal("150")
            coVerify { repository.updateBalance(playerId, any()) }
        }

        "should throw when amount is negative" {
            val service = EconomyService(mockk())

            shouldThrow<IllegalArgumentException> {
                service.addMoney(playerId, BigDecimal("-10"))
            }
        }
    }
})
```

## Core Context: Essential Patterns and Utilities

**ALWAYS refer to these files when working on this codebase. These define the architectural patterns:**

### 1. DSL Systems (src/main/kotlin/ru/arc/core/)

- **`EventDsl.kt`**: Type-safe event registration with builders, scopes, and awaitable events
  ```kotlin
  on<PlayerJoinEvent> { event -> /* handler */ }
  once<PlayerQuitEvent> { /* fires once */ }
  awaitEvent<PlayerInteractEvent>().timeout(600).then { /* ... */ }
  ```

- **`TaskDsl.kt`**: Task scheduling with durations, chains, countdown, debounce, throttle
  ```kotlin
  delayed(20.ticks) { /* ... */ }
  repeating(60.ticks) { /* ... */ }
  countdown(from = 30, period = 1.seconds) { /* ... */ }
  ```

### 2. GUI System (src/main/kotlin/ru/arc/gui/)

- **`GuiDsl.kt`**: Declarative GUI building with pagination, navigation, item builders
- **`GuiDslExtensions.kt`**: Click context helpers, cooldowns, economy integration
- **`ConfigGui.kt`**: YAML-driven GUI definitions

Example:

```kotlin
gui("Shop", 6, player) {
    pagination(0 until 5) {
        items(products) { product ->
            material(product.material)
            display(product.name)
            onClick { /* ... */ }
        }
    }
    navBar { back(); prevPage(); nextPage() }
}
```

### 3. ItemStack DSL (src/main/kotlin/ru/arc/util/)

- **`ItemStackDsl.kt`**: Fluent item creation with display, lore, enchants
- **`ItemStackExtensions.kt`**: Utility extensions for ItemStack manipulation

Example:

```kotlin
itemStack(Material.DIAMOND_SWORD) {
    display("<gold>Legendary")
    lore {
        +"<gray>Damage: <red>+50"
        +"<gray>Owner: ${player.name}"
    }
    modelData(1001)
    enchant(Enchantment.SHARPNESS, 5)
}
```

### 4. Configuration Pattern (src/main/kotlin/ru/arc/configs/)

**CRITICAL**: Always use the `get()` accessor pattern for configuration:

```kotlin
// Config base class (Config.kt)
open class MyModuleConfig(private val config: Config) {
    // Lazy getters enable auto-reload
    open val saveInterval: Long
        get() = config.integer("save-interval", 20).toLong()

    open val maxPlayers: Int
        get() = config.integer("max-players", 100)

    open val message: String
        get() = config.string("message", "Default")

    companion object {
        fun load(dataPath: Path): MyModuleConfig {
            val config = ConfigManager.of(dataPath, "mymodule.yml")
            return MyModuleConfig(config)
        }
    }
}

// Test implementation - explicit values
class TestMyModuleConfig(
    override val saveInterval: Long = 20,
    override val maxPlayers: Int = 100,
    override val message: String = "Test"
) : MyModuleConfig(EmptyConfig) {

    fun copy(
        saveInterval: Long = this.saveInterval,
        maxPlayers: Int = this.maxPlayers,
        message: String = this.message
    ) = TestMyModuleConfig(saveInterval, maxPlayers, message)
}
```

**Why this pattern?**

- Getters enable hot-reload (values re-read on access)
- Test configs use explicit constructor parameters
- Type-safe with default values
- Easy to mock in tests

### 5. Extension Functions (src/main/kotlin/ru/arc/util/)

- **`EntityExtensions.kt`**: Entity queries, cleanup utilities
- **`PlayerExtensions.kt`**: Messaging, permissions, effects
- **`ItemStackExtensions.kt`**: Item manipulation
- **`SoundUtils.kt`**: Sound playing utilities

Example usage:

```kotlin
player.sendMM("<gold>Hello, ${player.name}!")
player.showTitleMM("Welcome", "Enjoy your stay")
location.nearbyPlayers(10.0).forEach { /* ... */ }
itemStack.withDisplayName("<gold>Rare Item")
```

### 6. Logging System (src/main/kotlin/ru/arc/util/Logging.kt)

**ALWAYS use Logging.kt, never use println() or plugin.logger directly:**

```kotlin
Logging.info("Player {} joined at {}", player.name, timestamp)
Logging.debug("Cache hit for {}", key)
Logging.error("Failed to save: {}", exception)
Logging.warn("Config missing key: {}", path)

// In tests
Logging.quietMode = true
```

Features: format strings with `{}`, automatic exception extraction, Loki integration, level control.

### 7. Testing Utilities (src/test/kotlin/ru/arc/)

- **`KotestTestBase.kt`**: Base class for Kotest tests with common setup
- **`TestBase.kt`**: Legacy JUnit base (migrate to Kotest)
- Test implementations of core interfaces: `TestEventBus`, `TestTaskScheduler`, `TestTimeProvider`

### When to Create New DSLs

If you see patterns like:

- Repetitive builder code
- Multiple method calls to achieve simple tasks
- Unclear APIs that need documentation

**Create a DSL:**

1. Use `@DslMarker` annotation for type safety
2. Use inline reified generics for type parameters
3. Provide builder pattern with `apply { }`
4. Add convenience extensions

## Common Gotchas

1. **Java Version Requirement**: **MUST use Java 25 or higher**. Set JAVA_HOME before running Gradle commands.
2. **Gradle wrapper version**: Uses Gradle 9.2.1 with Java 25 toolchain
3. **Shadow plugin**: Dependencies are relocated to avoid conflicts (check `relocate` in build.gradle.kts)
3. **Log4j2**: Custom configuration in `src/main/resources/log4j2.properties`, includes optional Loki integration
4. **Redis connection**: Must be initialized before other modules (priority 10)
5. **Module initialization order matters**: Check priorities if features depend on each other
6. **Background jobs**: Repository save jobs use Bukkit scheduler, ensure proper shutdown to prevent data loss
7. **Cross-server sync**: Messages are async, expect eventual consistency not immediate consistency
8. **Kotest tests**: Use `FreeSpec` or `StringSpec`, never use JUnit's `@Test` in Kotlin
