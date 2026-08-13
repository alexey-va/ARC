plugins {
    java
}

group = "ru.ruscrafting"
version = "4.1.0-ruscrafting.1"
description = "Safety-hardened RusCrafting fork of Dartanboy Duels"

base {
    // Keep the deployed filename stable so Paper's update directory replaces
    // the currently active Duels Optimised JAR atomically.
    archivesName.set("Duels-Optimised-7.6")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // MockBukkit 4.98.0 currently targets this test API. Production compilation
    // remains pinned to the active 1.21.11 Paper API above.
    testImplementation("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.98.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveVersion.set("")
    from("LICENSE") {
        into("META-INF")
        rename { "LICENSE-Dartanboy-Duels.txt" }
    }
}

tasks.test {
    useJUnitPlatform()
}
