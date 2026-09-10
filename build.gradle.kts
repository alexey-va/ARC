import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.internal.os.OperatingSystem
import ru.arc.testing.containers.RedisTestService
import java.util.Properties
import java.net.Socket
import java.security.MessageDigest

buildscript {
    repositories {
        maven("https://repo.rus-crafting.ru/grocermc/")
        mavenCentral()
    }
    dependencies {
        classpath("ru.ruscrafting.arc:arc-core-integration-testing:2.7.5")
    }
}

abstract class E2eRedisService : BuildService<E2eRedisService.Parameters>, AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val image: Property<String>
    }

    private var redis: RedisTestService? = null

    private fun service(): RedisTestService = synchronized(this) {
        redis ?: RedisTestService.Companion.start(parameters.image.get()).also { redis = it }
    }

    val endpoint get() = service().endpoint

    override fun close() {
        synchronized(this) {
            redis?.close()
            redis = null
        }
    }
}

plugins {
    id("io.github.drownek.plugwright") version "2.0.4"
    java
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
}

group = "ARC"
version = "1.4.43"
description = "ARC"
val pluginVersion = version.toString()
val arcCoreVersion = "2.7.6"
val landsJar = providers.gradleProperty("landsJar").orNull?.let(::file)
if (landsJar != null) require(landsJar.isFile) { "Lands JAR does not exist: $landsJar" }
val landsCompileDependency: Any = landsJar?.let { files(it) } ?: libs.com.github.angeschossen.landsapi

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
kotlin.target.compilations.getByName("integrationTest")
    .associateWith(kotlin.target.compilations.getByName("main"))

repositories {
    mavenLocal()
    maven("https://repo.rus-crafting.ru/grocermc/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://repo.alessiodp.com/releases/")
    exclusiveContent {
        forRepository { maven("https://maven.enginehub.org/repo/") }
        filter {
            includeGroupByRegex("com\\.sk89q(\\..*)?")
            includeGroupByRegex("org\\.enginehub(\\..*)?")
        }
    }
    maven("https://maven.citizensnpcs.co/repo")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.william278.net/releases/")
    maven("https://repo.viaversion.com")
    maven("https://jitpack.io")
    maven("https://mvn-repo.arim.space/lesser-gpl3/")
    maven("https://repo.magmaguy.com/releases")
    maven("https://repo.bluecolored.de/releases")
    mavenCentral()
}

dependencies {
    // Keep the server API ahead of HuskHomes' bundled older Adventure classes.
    compileOnly("net.kyori:adventure-api:4.26.1")
    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")

    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-logging:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-metrics:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-redis:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-sql:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-paper:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-paper-api:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-menu:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-ai:$arcCoreVersion")

    // snakeyaml-engine comes transitively from arc-core

    implementation(libs.com.github.stefvanschie.inventoryframework.`if`)
    implementation(libs.com.jeff.media.custom.block.data)
    implementation(libs.de.tr7zw.item.nbt.api)
    implementation(libs.org.apache.logging.log4j.log4j.api)
    implementation(libs.org.apache.logging.log4j.log4j.core)
    implementation(libs.com.google.code.gson.gson)
    implementation(libs.pl.tkowalcz.tjahzi.log4j2.appender.nodep)

    // server-provided
    compileOnly(libs.io.papermc.paper.paper.api)
    compileOnly(libs.net.advancedplugins.advancedenchantments)
    compileOnly(libs.com.github.retrooper.packetevents.spigot) {
        // PacketEvents is server-provided; its legacy netty-all graph is not part of ARC compilation.
        exclude(group = "io.netty")
    }
    // Immutable private mirrors of the exact EliteMobs API and its mutable MagmaCore snapshot.
    // Both are provided by the shaded EliteMobs runtime and must never be shaded into ARC.
    compileOnly(libs.ru.ruscrafting.thirdparty.elitemobs.api)
    compileOnly(libs.ru.ruscrafting.thirdparty.magmacore)
    compileOnly(libs.com.denizenscript.denizen) { isTransitive = false }
    compileOnly(libs.com.github.slimefun.slimefun4)
    compileOnly(libs.com.magmaguy.betterstructures)
    compileOnly(libs.betterrtp.betterrtp)
    // Private mirror of the exact server-provided LeafRTP JAR; never shaded.
    compileOnly("ru.ruscrafting.thirdparty:leafrtp-lite:3.2.0")
    compileOnly(libs.dev.espi.protectionstones)
    compileOnly(libs.com.alessiodp.parties.parties.api)
    compileOnly(libs.me.clip.placeholderapi)
    // Exact optional API baseline used by the native Parkour presentation bridge.
    // Parkour remains server-provided and is never shaded into ARC.
    compileOnly("com.github.A5H73Y:Parkour:Parkour-7.2.8-RELEASE.136") { isTransitive = false }
    // Private mirror of the exact server-provided Premium JAR; never shaded.
    compileOnly("ru.ruscrafting.thirdparty:economyshopgui-premium:6.3.0")
    compileOnly(libs.net.william278.huskhomes)
    // CI uses the current public API. Release verification may supply the exact
    // active server JAR with -PlandsJar=/absolute/path/Lands.jar.
    compileOnly(landsCompileDependency)
    compileOnly(libs.com.github.milkbowl.vaultapi) {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly(libs.redis.clients.jedis)
    compileOnly(libs.com.sk89q.worldguard.worldguard.bukkit)
    compileOnly(libs.com.sk89q.worldedit.worldedit.bukkit)
    compileOnly(libs.com.zrips.cmi.api)
    compileOnly(libs.com.olziedev.playerwarps)
    compileOnly(libs.com.github.lonedev6.api.itemsadder)
    compileOnly(libs.net.citizensnpcs.citizens.main) { exclude(group = "*", module = "*") }
    compileOnly("de.bluecolored:bluemap-api:2.7.7")
    compileOnly(libs.com.viaversion.viaversion.api)
    compileOnly(libs.io.josemmo.yamipa)
    compileOnly(libs.net.luckperms.api)
    compileOnly(libs.fr.black.eyes.lootchest)
    // Exact API extracted from the active zAuctionHouse 4.0.1.3 release; the
    // upstream project does not publish this release to Maven repositories.
    // This remains compile-only and is never shaded into ARC.
    compileOnly(files("libs/zauctionhouse-api-4.0.1.3.jar"))
    compileOnly(libs.com.github.zrips.jobs)
    compileOnly(libs.bank.bank)
    // API-compatible compile baseline for the active 4.5.13 runtime; never
    // shaded. The private mirror currently publishes 4.5.12.
    compileOnly(libs.ru.ruscrafting.thirdparty.rediseconomy)
    compileOnly(libs.io.lettuce.lettuce.core)
    compileOnly(libs.dev.aurelium.auraskills.api.bukkit)
    compileOnly(libs.com.github.zrips.cmilib)
    // These are optional runtime plugins - provided by server at runtime
    // compileOnly("me.ulrich:uclans-api:1.0.0")
    compileOnly("commons-lang:commons-lang:2.6")

    // tests
    testImplementation(libs.org.junit.jupiter.junit.jupiter.api)
    testImplementation(libs.org.junit.jupiter.junit.jupiter.engine)
    testImplementation(libs.org.junit.jupiter.junit.jupiter.params)
    testImplementation(libs.com.thedeanda.lorem)
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:$arcCoreVersion")
    testImplementation(landsCompileDependency)
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    // Kotest
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.kotest:kotest-property:6.0.7")

    // MockK
    testImplementation("io.mockk:mockk:1.14.7")

    // Testcontainers — integration tests source set
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:$arcCoreVersion")
    "integrationTestImplementation"("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    // Integration tests reuse all test dependencies (Kotest, MockK, Paper API, etc.)
    "integrationTestImplementation"(sourceSets.test.get().output)
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])

    testImplementation(libs.com.github.milkbowl.vaultapi) {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testImplementation(libs.redis.clients.jedis)
    testImplementation(libs.ru.ruscrafting.thirdparty.rediseconomy)
    testImplementation(libs.io.lettuce.lettuce.core)
    testImplementation(libs.net.luckperms.api)
    testImplementation(libs.me.clip.placeholderapi)
    testImplementation("commons-lang:commons-lang:2.6")
    testImplementation(libs.com.zrips.cmi.api)
    testImplementation(libs.net.william278.huskhomes)
    testImplementation(libs.net.citizensnpcs.citizens.main) { exclude(group = "*", module = "*") }
    testImplementation(libs.com.denizenscript.denizen) { isTransitive = false }
    testImplementation(libs.ru.ruscrafting.thirdparty.elitemobs.api)
    testImplementation(libs.ru.ruscrafting.thirdparty.magmacore)
    testImplementation(libs.com.github.zrips.cmilib)
    testImplementation(libs.com.github.lonedev6.api.itemsadder)
    testImplementation("ru.ruscrafting.thirdparty:leafrtp-lite:3.2.0")
    testImplementation("ru.ruscrafting.thirdparty:economyshopgui-premium:6.3.0")
    // Jackson databind needed for Log4j JsonLayout used in Logging.addLokiAppender()
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    // Log4j dependencies needed for Logging class
    testImplementation(libs.org.apache.logging.log4j.log4j.api)
    testImplementation(libs.org.apache.logging.log4j.log4j.core)
    // Log4j layout template JSON needed for ThreadContextDataInjector
    testImplementation("org.apache.logging.log4j:log4j-layout-template-json:2.24.1")
    // Tjahzi Loki appender needed for Logging.addLokiAppender()
    testImplementation(libs.pl.tkowalcz.tjahzi.log4j2.appender.nodep) {
        exclude(group = "org.apache.logging.log4j")
    }
    // WorldEdit dependency needed for Building class tests
    testImplementation(libs.com.sk89q.worldedit.worldedit.bukkit)
}

val runtimeClasspathConfiguration = configurations.named("runtimeClasspath")
val loggingModuleResources =
    zipTree(
        runtimeClasspathConfiguration.map { configuration ->
            configuration.files.first { it.name.startsWith("arc-core-logging") }
        },
    )
val redisModuleResources =
    zipTree(
        runtimeClasspathConfiguration.map { configuration ->
            configuration.files.first { it.name.startsWith("arc-core-redis") }
        },
    )
val schedulingModuleResources =
    zipTree(
        runtimeClasspathConfiguration.map { configuration ->
            configuration.files.first {
                it.name.startsWith("arc-core-") &&
                    !it.name.startsWith("arc-core-logging") &&
                    !it.name.startsWith("arc-core-redis") &&
                    !it.name.startsWith("arc-core-paper") &&
                    !it.name.startsWith("arc-core-velocity")
            }
        },
    )

tasks {
    processResources {
        inputs.property("pluginVersion", pluginVersion)
        filesMatching("plugin.yml") {
            expand(
                "version" to pluginVersion,
                "project" to mapOf("version" to pluginVersion),
            )
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
    withType<Javadoc> { options.encoding = "UTF-8" }
    test {
        // Third-party plugin JARs (HuskHomes) bundle older unrelocated Adventure classes.
        // Prefer the resolved Paper/MockBukkit modules, as the real Paper classloader does.
        classpath = files(classpath.filter { it.name.startsWith("adventure-") }, classpath)
        useJUnitPlatform()
        systemProperty("arc.test.unit", "true")
        // MockK/ByteBuddy must self-attach inside the Java 25 test worker. Without
        // these test-only flags it can wait forever for an external attach helper.
        jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    }

    // Never let the plain archive race with or overwrite the deployable
    // dependency-complete shadow JAR at build/libs/ARC-1.0.jar.
    jar {
        archiveClassifier.set("plain")
    }

    register<Test>("integrationTest") {
        description = "Runs Redis/Testcontainers integration tests (requires Docker)."
        group = "verification"

        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()

        val os = OperatingSystem.current()
        if (os.isMacOsX) {
            environment(
                "DOCKER_HOST",
                "unix://${System.getProperty("user.home")}/.colima/default/docker.sock",
            )
            environment(
                "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE",
                "/var/run/docker.sock",
            )
        }

        shouldRunAfter("test")
    }

    shadowJar {
        archiveClassifier.set("")

        mergeServiceFiles()
        transform(
            com.github.jengelman.gradle.plugins.shadow.transformers
                .Log4j2PluginsCacheFileTransformer(),
        )

        exclude("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")

        // Canonical logging.yml / redis.yml live in arc-core-* (not duplicated in this repo).
        from(loggingModuleResources) {
            include("modules/logging.yml")
        }
        from(redisModuleResources) {
            include("modules/redis.yml")
        }
        from(schedulingModuleResources) {
            include("modules/scheduling.yml")
        }

        relocate("com.jeff_media.customblockdata", "arc.arc.libs.customblockdata")
        relocate("com.github.stefvanschie.inventoryframework", "arc.arc.libs.inventoryframework")
        relocate("de.tr7zw.changeme.nbtapi", "arc.arc.libs.nbtapi")
    }

    build {
        dependsOn(shadowJar)
    }

    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }

        classDirectories.setFrom(
            files(
                classDirectories.files.map {
                    fileTree(it) {
                        exclude(
                            "**/ARC.class",
                            "**/test/**",
                            "**/*Test*.class",
                            "**/*Mock*.class",
                        )
                    }
                },
            ),
        )
    }

    jacocoTestCoverageVerification {
        violationRules {
            rule {
                limit {
                    minimum = "0.70".toBigDecimal()
                }
            }
        }
    }

    register("publishPlugin") {
        group = "publishing"
        description = "Upload shadow JAR to remote server and run update script via SSH"
        dependsOn(shadowJar)

        doLast {
            val deployFile = rootProject.file("deploy.properties")
            require(deployFile.exists()) {
                "deploy.properties not found. Create it with SERVER_IP=<ip> and SERVER_USER=<user>"
            }

            val props = Properties().apply { deployFile.inputStream().use(::load) }
            val serverIp = requireNotNull(props.getProperty("SERVER_IP")?.trim()) {
                "SERVER_IP is missing in deploy.properties"
            }
            val serverUser = props.getProperty("SERVER_USER")?.trim() ?: "root"

            val jarFile = shadowJar.get().archiveFile.get().asFile
            val remotePath = "$serverUser@$serverIp:~/McFine/update/"

            fun run(vararg cmd: String) {
                val code = ProcessBuilder(*cmd)
                    .inheritIO()
                    .start()
                    .waitFor()
                check(code == 0) { "Command failed (exit $code): ${cmd.joinToString(" ")}" }
            }

            println("Uploading ${jarFile.name} → $remotePath")
            run("scp", jarFile.absolutePath, remotePath)

            println("Running ./update.sh on $serverIp")
            run("ssh", "$serverUser@$serverIp", "cd ~/McFine && ./update.sh")
        }
    }
}

val contractE2eFiles = layout.buildDirectory.dir("plugwright-e2e-generated")
val contractCrashFixture = sourceSets.create("contractCrashFixture") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[contractCrashFixture.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[contractCrashFixture.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
kotlin.target.compilations.getByName("contractCrashFixture")
    .associateWith(kotlin.target.compilations.getByName("main"))
val contractCrashFixtureJar = tasks.register<Jar>("contractCrashFixtureJar") {
    archiveBaseName.set("ARCContractCrashFixture")
    from(contractCrashFixture.output)
}
val cleanupE2e = providers.gradleProperty("cleanupE2e").map(String::toBoolean).orElse(false).get()
val dialogE2e = providers.gradleProperty("dialogE2e").map(String::toBoolean).orElse(false).get()
val parkourE2e = providers.gradleProperty("parkourE2e").map(String::toBoolean).orElse(false).get()
val contractCrashE2e = providers.gradleProperty("contractCrashE2e").map(String::toBoolean).orElse(false).get()

// Isolated real-Paper tests run separately from the fast JVM suite.
plugwright {
    minecraftVersion.set(providers.gradleProperty("e2eMinecraftVersion").orElse("26.1.2"))
    downloadPlugins {
        url("https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar")
        url("https://github.com/A5H73Y/Parkour/releases/download/Parkour-7.2.8-RELEASE.136/Parkour-7.2.8-RELEASE.jar")
    }
    runDir.set(layout.buildDirectory.dir("plugwright"))
    testsDir.set(layout.projectDirectory.dir("src/test/e2e"))
    downloadNode.set(true)
    nodeVersion.set("22.14.0")
    acceptEula.set(true)
    jvmArgs.set(listOf("-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=2"))
    writeFiles {
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        file("permissions.yml", projectDir.resolve("src/test/e2e/fixtures/permissions.yml"))
        file("plugins/ARC/modules/entity-cleanup.yml", projectDir.resolve("src/test/e2e/fixtures/entity-cleanup.yml"))
        file("plugins/ARC/modules/parkour.yml", projectDir.resolve("src/test/e2e/fixtures/parkour.yml"))
        file("plugins/Parkour/config.yml", projectDir.resolve("src/test/e2e/fixtures/parkour-config.yml"))
        if (cleanupE2e || parkourE2e || dialogE2e) {
            file("plugins/ARC/modules/redis.yml", projectDir.resolve("src/test/e2e/fixtures/entity-cleanup-redis.yml"))
        } else {
            file("plugins/ARC/modules/redis.yml", contractE2eFiles.get().file("redis.yml").asFile)
            file("plugins/ARC/modules/contracts.yml", contractE2eFiles.get().file("contracts.yml").asFile)
            file("plugins/RedisEconomy/config.yml", contractE2eFiles.get().file("rediseconomy.yml").asFile)
            file("plugins/RedisEconomy.jar", contractE2eFiles.get().file("RedisEconomy.jar").asFile)
            if (contractCrashE2e) {
                file("plugins/ARCContractCrashFixture.jar", contractCrashFixtureJar.get().archiveFile.get().asFile)
            }
        }
    }
}

// E2E owns a disposable Redis only for the lifetime of Paper. The service uses
// Testcontainers' mapped port, so the test never binds a shared host port.
val e2eRedis = gradle.sharedServices.registerIfAbsent("plugwrightRedis", E2eRedisService::class) {
    parameters.image.set("redis:7.4-alpine")
}
val e2eRedisEconomy = configurations.detachedConfiguration(
    dependencies.create("ru.ruscrafting.thirdparty:rediseconomy:4.5.12"),
).apply {
    isTransitive = false
}

val prepareContractE2e = tasks.register("prepareContractE2e") {
    usesService(e2eRedis)
    doLast {
        val endpoint = e2eRedis.get().endpoint
        Socket(endpoint.host, endpoint.port).use { }

        val generatedDir = contractE2eFiles.get().asFile
        generatedDir.mkdirs()
        generatedDir.resolve("redis.yml").apply {
            writeText(
                """
                enabled: true
                host: ${endpoint.host}
                port: ${endpoint.port}
                username: default
                password: ""
                server-name: e2e
                main-server: true
                """.trimIndent() + "\n",
            )
        }
        generatedDir.resolve("rediseconomy.yml").apply {
            writeText(
                """
                lang: en-US
                debug: false
                migrationEnabled: false
                redis:
                  host: ${endpoint.host}
                  port: ${endpoint.port}
                  user: default
                  password: ""
                  database: 0
                  timeout: 300
                  clientName: RedisEconomyE2E
                  ssl: false
                  poolSize: 2
                  tryAgainCount: 3
                clusterId: e2e
                defaultCurrencyName: vault
                payCooldown: 0
                minPayAmount: 0.01
                currencies:
                  - currencyName: vault
                    currencySingle: ' coin'
                    currencyPlural: ' coins'
                    decimalFormat: '#.##'
                    languageTag: en-US
                    startingBalance: 0.0
                    maxBalance: 1000000.0
                    payTax: 0.0
                    saveTransactions: true
                    transactionsTTL: 604800
                    bankEnabled: true
                    taxOnlyPay: false
                    executorThreads: 1
                """.trimIndent() + "\n",
            )
        }
        generatedDir.resolve("contracts.yml").apply {
            writeText(
                """
                enabled: true
                mode: enforce
                leader-server: e2e
                server-weekly-budget: '100000.00'
                orders:
                  e2e_stone:
                    enabled: true
                    kind: resource
                    group: spawn
                    display-name: E2E stone order
                    item: minecraft:stone
                    funding: server_envelope
                    window-starts-at: '2026-01-01T00:00:00Z'
                    window-ends-at: '2099-01-01T00:00:00Z'
                    payout-per-unit: '100.00'
                    budget: '100000.00'
                    target-quantity: 1000
                    per-player-quantity-cap: 64
                    min-submission-quantity: 1
                    max-submission-quantity: 2
                    dynamic-pricing: true
                """.trimIndent() + "\n",
            )
        }
        generatedDir.resolve("redis-test.json").writeText(
            "{\"host\":\"${endpoint.host}\",\"port\":${endpoint.port}}\n",
        )
        val jar = e2eRedisEconomy.resolve().single { it.name == "rediseconomy-4.5.12.jar" }
        check(MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
            .joinToString("") { "%02x".format(it) } ==
            "7ccd1c5fbc43ab1a3345f4c9705b71aa39fbbbd457647c1808629bc86884e51f") {
            "Unexpected RedisEconomy 4.5.12 artifact: ${jar.absolutePath}"
        }

        jar.copyTo(generatedDir.resolve("RedisEconomy.jar"), overwrite = true)
    }
}

tasks.named<me.drownek.plugwright.PlugwrightTestTask>("plugwrightTest") {
    if (dialogE2e) {
        testFiles.set("dialog-designs")
    } else if (cleanupE2e) {
        testFiles.set("entity-cleanup")
    } else if (parkourE2e) {
        testFiles.set("parkour-real-paper")
    } else {
        dependsOn(prepareContractE2e)
        usesService(e2eRedis)
        if (contractCrashE2e) {
            dependsOn(contractCrashFixtureJar)
            testFiles.set("contracts-crash")
            doFirst {
                check(System.getenv("ARC_CONTRACT_CRASH_FIXTURE") == "1") {
                    "The crash profile requires ARC_CONTRACT_CRASH_FIXTURE=1 in its isolated CI process"
                }
            }
        }
    }
}
