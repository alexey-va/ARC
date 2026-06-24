import org.gradle.internal.os.OperatingSystem
import java.util.Properties

plugins {
    java
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
}

group = "ARC"
version = "1.0"
description = "ARC"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

repositories {
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://repo.alessiodp.com/releases/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://maven.citizensnpcs.co/repo")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://redempt.dev")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.rus-crafting.ru/repository/grocermc/")
    maven("https://repo.viaversion.com")
    maven("https://jitpack.io")
    maven("https://artifactory.cronapp.io/public-release/")
    maven("https://mvn-repo.arim.space/lesser-gpl3/")
    maven("https://repo.magmaguy.com/releases")
    mavenCentral()
}

configurations {
    // libs we shade go in implementation
    // server-provided jars go in compileOnly
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")

    implementation(kotlin("stdlib"))
    implementation("ru.arc:arc-core:1.0-SNAPSHOT")
    implementation("org.snakeyaml:snakeyaml-engine:3.0.1")

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
    compileOnly(libs.com.github.retrooper.packetevents.spigot)
    compileOnly(libs.com.magmaguy.elitemobs)
    compileOnly(libs.com.github.slimefun.slimefun4)
    compileOnly(libs.com.magmaguy.betterstructures)
    compileOnly(libs.betterrtp.betterrtp)
    compileOnly(libs.org.jsoup.jsoup)
    compileOnly(libs.dev.espi.protectionstones)
    compileOnly(libs.com.alessiodp.parties.parties.api)
    compileOnly(libs.me.clip.placeholderapi)
    compileOnly(libs.com.github.gypopo.economyshopgui.api)
    compileOnly(libs.net.william278.huskhomes)
    compileOnly(libs.com.github.angeschossen.landsapi)
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
    compileOnly(libs.com.viaversion.viaversion.api)
    compileOnly(libs.org.eclipse.jetty.websocket.websocket.client)
    compileOnly(libs.io.josemmo.yamipa)
    compileOnly(libs.net.luckperms.api)
    compileOnly(libs.fr.black.eyes.lootchest)
    compileOnly(libs.fr.maxlego08.zauctionhouse)
    compileOnly(libs.com.github.zrips.jobs)
    compileOnly(libs.bank.bank)
    compileOnly(libs.com.github.emibergo02.rediseconomy)
    compileOnly(libs.io.lettuce.lettuce.core)
    compileOnly(libs.dev.aurelium.auraskills.api.bukkit)
    compileOnly(libs.com.github.zrips.cmilib)
    // These are optional runtime plugins - provided by server at runtime
    // compileOnly("com.meteordevelopments:duels-api:1.0.0")
    // compileOnly("me.ulrich:uclans-api:1.0.0")
    compileOnly("commons-lang:commons-lang:2.6")

    // tests
    testImplementation(libs.org.junit.jupiter.junit.jupiter.api)
    testImplementation(libs.org.junit.jupiter.junit.jupiter.engine)
    testImplementation(libs.org.junit.jupiter.junit.jupiter.params)
    testImplementation(libs.com.thedeanda.lorem)
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.98.0")
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
    "integrationTestImplementation"("org.testcontainers:testcontainers:2.0.2")
    "integrationTestImplementation"("org.testcontainers:testcontainers-junit-jupiter:2.0.2")
    // Integration tests reuse all test dependencies (Kotest, MockK, Paper API, etc.)
    "integrationTestImplementation"(sourceSets.test.get().output)
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])

    // Add compileOnly dependencies to test classpath so tests can run
    // These are server-provided at runtime, but needed for testing
    // Use a compatible Paper API version for tests to avoid service loader issues
    testImplementation("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testImplementation(libs.com.github.milkbowl.vaultapi) {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testImplementation(libs.redis.clients.jedis)
    testImplementation(libs.org.jsoup.jsoup)
    testImplementation("commons-lang:commons-lang:2.6")
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

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand(
                "version" to project.version,
                "project" to mapOf("version" to project.version),
            )
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
    withType<Javadoc> { options.encoding = "UTF-8" }
    test {
        useJUnitPlatform()
        systemProperty("arc.test.unit", "true")
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
