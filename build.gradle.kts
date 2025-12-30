plugins {
    java
    kotlin("jvm") version "2.0.20"
    id("com.gradleup.shadow") version "9.0.0"
}

group = "ARC"
version = "1.0"
description = "ARC"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
kotlin { jvmToolchain(21) }

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

    // shaded (was <scope>compile</scope> in POM)
    implementation(libs.com.github.stefvanschie.inventoryframework.`if`)
    implementation(libs.com.jeff.media.custom.block.data)
    implementation(libs.de.tr7zw.item.nbt.api)
    implementation(libs.org.apache.logging.log4j.log4j.api)
    implementation(libs.org.apache.logging.log4j.log4j.core)
    implementation(libs.com.google.code.gson.gson)
    implementation(libs.pl.tkowalcz.tjahzi.log4j2.appender.nodep) {
        exclude(group = "org.apache.logging.log4j")
    }

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
    compileOnly(libs.com.github.milkbowl.vaultapi)
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
    compileOnly(libs.com.github.dumbo.the.developer.duels.duels.api)
    compileOnly(libs.com.github.ulrichbr.uclans.api)

    // tests
    testImplementation(libs.org.junit.jupiter.junit.jupiter.api)
    testImplementation(libs.org.junit.jupiter.junit.jupiter.engine)
    testImplementation(libs.org.junit.jupiter.junit.jupiter.params)
    testImplementation(libs.com.thedeanda.lorem)
}

tasks {
    withType<JavaCompile> { options.encoding = "UTF-8" }
    withType<Javadoc> { options.encoding = "UTF-8" }
    test { useJUnitPlatform() }

    shadowJar {
        archiveClassifier.set("")

        mergeServiceFiles()
        transform(
            com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer()
        )

        exclude("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")

        relocate("com.jeff_media.customblockdata", "arc.arc.libs.customblockdata")
        relocate("com.github.stefvanschie.inventoryframework", "arc.arc.libs.inventoryframework")
        relocate("de.tr7zw.changeme.nbtapi", "arc.arc.libs.nbtapi")

        minimize()
    }

    build {
        dependsOn(shadowJar)
    }
}