package ru.arc.configs

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.Particle

// Test enum for enum parsing tests
enum class TestPriority { LOW, MEDIUM, HIGH }

/**
 * Test helper backed by the real SnakeYAML Engine v2 node tree.
 * Values are injected via [setValue] (exposed as [set]) rather than direct map mutation.
 */
class TestConfig(
    data: Map<String, Any> = emptyMap(),
) : Config(
        java.nio.file.Files
            .createTempDirectory("arc-test-config-"),
        "test-config.yml",
    ) {
    init {
        // Inject seed data into the node tree via the internal setValue path.
        // We flatten one level here — nested paths use dot notation.
        data.forEach { (k, v) -> injectValue(k, v) }
    }

    private fun injectValue(
        path: String,
        value: Any,
    ) {
        when (value) {
            is Int -> {
                setInt(path, value)
            }

            is Boolean -> {
                setBoolean(path, value)
            }

            is Double -> {
                setDouble(path, value)
            }

            is Long -> {
                setLong(path, value)
            }

            is String -> {
                setString(path, value)
            }

            is List<*> -> {
                setStringList(path, value.map { it.toString() })
            }

            is Map<*, *> -> {
                value.forEach { (k, v) ->
                    if (k != null && v != null) injectValue("$path.$k", v)
                }
            }

            else -> {
                setString(path, value.toString())
            }
        }
    }

    /** Allow tests to mutate a single top-level key after construction. */
    operator fun set(
        path: String,
        value: Any,
    ) = injectValue(path, value)

    override fun reload() { // no-op: test data lives in the node tree
    }

    override fun save() { // no-op
    }
}

class ConfigExtensionsTest :
    DescribeSpec({

        fun configWith(vararg pairs: Pair<String, Any>): Config = TestConfig(mapOf(*pairs))

        fun emptyConfig(): Config = TestConfig()

        describe("Nullable accessors") {

            describe("intOrNull") {
                it("should return null when path doesn't exist") {
                    emptyConfig().intOrNull("missing") shouldBe null
                }

                it("should return int when value is Number") {
                    configWith("count" to 42).intOrNull("count") shouldBe 42
                }

                it("should parse int from string") {
                    configWith("count" to "123").intOrNull("count") shouldBe 123
                }

                it("should return null for invalid string") {
                    configWith("count" to "not-a-number").intOrNull("count") shouldBe null
                }

                it("should handle Double as int") {
                    configWith("count" to 42.7).intOrNull("count") shouldBe 42
                }
            }

            describe("longOrNull") {
                it("should return null when path doesn't exist") {
                    emptyConfig().longOrNull("missing") shouldBe null
                }

                it("should return long when value is Number") {
                    configWith("big" to 9999999999L).longOrNull("big") shouldBe 9999999999L
                }

                it("should parse long from string") {
                    configWith("big" to "9999999999").longOrNull("big") shouldBe 9999999999L
                }
            }

            describe("doubleOrNull") {
                it("should return null when path doesn't exist") {
                    emptyConfig().doubleOrNull("missing") shouldBe null
                }

                it("should return double when value is Number") {
                    configWith("ratio" to 3.14).doubleOrNull("ratio") shouldBe 3.14
                }

                it("should parse double from string") {
                    configWith("ratio" to "2.5").doubleOrNull("ratio") shouldBe 2.5
                }
            }

            describe("booleanOrNull") {
                it("should return null when path doesn't exist") {
                    emptyConfig().booleanOrNull("missing") shouldBe null
                }

                it("should return boolean when value is Boolean") {
                    configWith("enabled" to true).booleanOrNull("enabled") shouldBe true
                }

                it("should parse 'yes' as true") {
                    configWith("enabled" to "yes").booleanOrNull("enabled") shouldBe true
                }

                it("should parse 'no' as false") {
                    configWith("enabled" to "no").booleanOrNull("enabled") shouldBe false
                }

                it("should parse '1' as true") {
                    configWith("enabled" to "1").booleanOrNull("enabled") shouldBe true
                }

                it("should return null for invalid string") {
                    configWith("enabled" to "maybe").booleanOrNull("enabled") shouldBe null
                }
            }

            describe("stringOrNull") {
                it("should return null when path doesn't exist") {
                    emptyConfig().stringOrNull("missing") shouldBe null
                }

                it("should return string when value is String") {
                    configWith("name" to "test").stringOrNull("name") shouldBe "test"
                }

                it("should convert number to string") {
                    configWith("name" to 123).stringOrNull("name") shouldBe "123"
                }
            }

            describe("stringListOrNull") {
                it("should return null when path doesn't exist") {
                    emptyConfig().stringListOrNull("missing") shouldBe null
                }

                it("should return list when value is List") {
                    configWith("items" to listOf("a", "b", "c")).stringListOrNull("items") shouldBe
                        listOf(
                            "a",
                            "b",
                            "c",
                        )
                }

                it("should wrap single string in list") {
                    configWith("items" to "single").stringListOrNull("items") shouldBe listOf("single")
                }
            }
        }

        describe("Consistent naming aliases") {

            it("int should delegate to integer") {
                configWith("value" to 42).int("value", 0) shouldBe 42
            }

            it("long should delegate to longValue") {
                configWith("value" to 999L).long("value", 0L) shouldBe 999L
            }

            it("double should delegate to real") {
                configWith("value" to 3.14).double("value", 0.0) shouldBe 3.14
            }

            it("boolean should delegate to bool") {
                configWith("value" to true).boolean("value", false) shouldBe true
            }
        }

        describe("Bukkit type accessors") {

            describe("materialOrNull") {
                it("should return null when missing") {
                    emptyConfig().materialOrNull("material") shouldBe null
                }

                it("should parse valid material") {
                    configWith("material" to "DIAMOND").materialOrNull("material") shouldBe Material.DIAMOND
                }

                it("should be case insensitive") {
                    configWith("material" to "diamond").materialOrNull("material") shouldBe Material.DIAMOND
                }

                it("should return null for invalid material") {
                    configWith("material" to "NOT_A_MATERIAL").materialOrNull("material") shouldBe null
                }
            }

            describe("particleOrNull") {
                it("should return null when missing") {
                    emptyConfig().particleOrNull("particle") shouldBe null
                }

                it("should parse valid particle") {
                    configWith("particle" to "FLAME").particleOrNull("particle") shouldBe Particle.FLAME
                }

                it("should return null for invalid particle") {
                    configWith("particle" to "NOT_A_PARTICLE").particleOrNull("particle") shouldBe null
                }
            }

            // Note: soundOrNull uses Registry API which requires MockBukkit environment

            describe("materials") {
                it("should return default when missing") {
                    val default = setOf(Material.STONE)
                    emptyConfig().materials("materials", default) shouldBe default
                }

                it("should parse list of materials") {
                    configWith("materials" to listOf("DIAMOND", "GOLD_INGOT", "IRON_INGOT"))
                        .materials("materials") shouldBe
                        setOf(
                            Material.DIAMOND,
                            Material.GOLD_INGOT,
                            Material.IRON_INGOT,
                        )
                }

                it("should skip invalid materials") {
                    configWith("materials" to listOf("DIAMOND", "INVALID", "GOLD_INGOT"))
                        .materials("materials") shouldBe setOf(Material.DIAMOND, Material.GOLD_INGOT)
                }
            }
        }

        describe("Enum support") {

            it("should parse enum value") {
                configWith("priority" to "HIGH").enumOrNull<TestPriority>("priority") shouldBe TestPriority.HIGH
            }

            it("should be case insensitive") {
                configWith("priority" to "low").enumOrNull<TestPriority>("priority") shouldBe TestPriority.LOW
            }

            it("should return null for invalid enum") {
                configWith("priority" to "INVALID").enumOrNull<TestPriority>("priority") shouldBe null
            }

            it("should return default for missing enum") {
                emptyConfig().enum("priority", TestPriority.MEDIUM) shouldBe TestPriority.MEDIUM
            }

            it("should parse enum set") {
                configWith("priorities" to listOf("HIGH", "LOW", "INVALID"))
                    .enumSet<TestPriority>("priorities") shouldBe setOf(TestPriority.HIGH, TestPriority.LOW)
            }
        }

        describe("Duration parsing") {

            it("should parse seconds") {
                configWith("timeout" to "30s").durationOrNull("timeout")?.toSeconds() shouldBe 30
            }

            it("should parse minutes") {
                configWith("timeout" to "5m").durationOrNull("timeout")?.toMinutes() shouldBe 5
            }

            it("should parse hours") {
                configWith("timeout" to "2h").durationOrNull("timeout")?.toHours() shouldBe 2
            }

            it("should parse days") {
                configWith("timeout" to "1d").durationOrNull("timeout")?.toDays() shouldBe 1
            }

            it("should parse milliseconds") {
                configWith("timeout" to "500ms").durationOrNull("timeout")?.toMillis() shouldBe 500
            }

            it("should parse combined duration") {
                configWith("timeout" to "1h30m").durationOrNull("timeout")?.toMinutes() shouldBe 90
            }

            it("should parse plain number as milliseconds") {
                configWith("timeout" to "1000").durationOrNull("timeout")?.toMillis() shouldBe 1000
            }

            it("should return null for missing duration") {
                emptyConfig().durationOrNull("timeout") shouldBe null
            }

            it("should return default millis for missing") {
                emptyConfig().durationMillis("timeout", 5000L) shouldBe 5000L
            }

            it("should return default ticks for missing") {
                emptyConfig().durationTicks("timeout", 20L) shouldBe 20L
            }

            it("should convert to ticks correctly") {
                // 1 second = 1000ms = 20 ticks
                configWith("timeout" to "1s").durationTicks("timeout", 0L) shouldBe 20L
            }
        }

        describe("Color parsing") {

            it("should parse hex color with hash") {
                val config = configWith("color" to "#FF5500")
                val color = config.colorOrNull("color")
                color.shouldNotBeNull()
                color.red() shouldBe 255
                color.green() shouldBe 85
                color.blue() shouldBe 0
            }

            it("should parse hex color without hash") {
                val color = configWith("color" to "00FF00").colorOrNull("color")
                color.shouldNotBeNull()
                color.green() shouldBe 255
            }

            it("should return null for invalid color") {
                configWith("color" to "not-a-color").colorOrNull("color") shouldBe null
            }
        }

        describe("Range validation") {

            it("should clamp int to range max") {
                configWith("value" to 150).intInRange("value", 50, 0..100) shouldBe 100
            }

            it("should clamp int to range min") {
                configWith("value" to -50).intInRange("value", 50, 0..100) shouldBe 0
            }

            it("should keep int in range") {
                configWith("value" to 75).intInRange("value", 50, 0..100) shouldBe 75
            }

            it("should clamp double to range") {
                configWith("value" to 1.5).doubleInRange("value", 0.5, 0.0, 1.0) shouldBe 1.0
            }
        }

        describe("String pattern matching") {

            it("should accept valid pattern") {
                val emailPattern = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
                configWith("email" to "test@example.com")
                    .stringMatching("email", "default@test.com", emailPattern) shouldBe "test@example.com"
            }

            it("should return default for invalid pattern") {
                val emailPattern = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
                configWith("email" to "not-an-email")
                    .stringMatching("email", "default@test.com", emailPattern) shouldBe "default@test.com"
            }
        }

        describe("TagResolverBuilder") {

            it("should build empty resolver") {
                val builder = TagResolverBuilder()
                val resolver = builder.build()
                resolver.shouldNotBeNull()
            }

            it("should add string tags") {
                val builder = TagResolverBuilder()
                builder.tag("name", "TestValue")
                val resolver = builder.build()
                resolver.shouldNotBeNull()
            }

            it("should add lazy tags") {
                var called = false
                val builder = TagResolverBuilder()
                builder.tag("lazy") {
                    called = true
                    "LazyValue"
                }

                // Build triggers lazy evaluation
                builder.build()
                called shouldBe true
            }
        }

        describe("ConfigSection") {

            it("should prefix all paths") {
                val config =
                    TestConfig(
                        mapOf(
                            "database" to
                                mapOf(
                                    "host" to "localhost",
                                    "port" to 5432,
                                ),
                        ),
                    )

                val section = config.section("database")
                section.string("host") shouldBe "localhost"
                section.int("port") shouldBe 5432
            }

            it("should support nested sections") {
                val config =
                    TestConfig(
                        mapOf(
                            "app" to
                                mapOf(
                                    "database" to
                                        mapOf(
                                            "primary" to
                                                mapOf(
                                                    "host" to "primary.db",
                                                ),
                                        ),
                                ),
                        ),
                    )

                val section = config.section("app").section("database").section("primary")
                section.string("host") shouldBe "primary.db"
            }

            it("should return null for missing nested path") {
                val config =
                    TestConfig(
                        mapOf(
                            "level1" to mapOf("value" to 1),
                        ),
                    )
                config.intOrNull("level1.missing.value").shouldBeNull()
            }

            it("should return null when intermediate is not a map") {
                val config =
                    TestConfig(
                        mapOf(
                            "level1" to "not-a-map",
                        ),
                    )
                config.intOrNull("level1.value").shouldBeNull()
            }
        }

        describe("Property delegation") {

            it("should delegate int property") {
                class MySettings(
                    config: Config,
                ) {
                    val maxPlayers by config.intProp("max-players", 10)
                }

                val config = configWith("max-players" to 50)
                val settings = MySettings(config)
                settings.maxPlayers shouldBe 50
            }

            it("should delegate string property") {
                class MySettings(
                    config: Config,
                ) {
                    val serverName by config.stringProp("server-name", "Default")
                }

                val config = configWith("server-name" to "MyServer")
                val settings = MySettings(config)
                settings.serverName shouldBe "MyServer"
            }

            it("should use default when missing") {
                class MySettings(
                    config: Config,
                ) {
                    val maxPlayers by config.intProp("max-players", 10)
                }

                val settings = MySettings(emptyConfig())
                settings.maxPlayers shouldBe 10
            }

            it("should re-read value on each access") {
                val config = TestConfig(mapOf("count" to 1))

                class MySettings(
                    config: Config,
                ) {
                    val count by config.intProp("count", 0)
                }

                val settings = MySettings(config)
                settings.count shouldBe 1

                // Modify the underlying data
                config["count"] = 999

                // Should read new value
                settings.count shouldBe 999
            }
        }
    })
