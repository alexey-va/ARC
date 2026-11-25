import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
}

group = "ru.ruscrafting"
version = "0.1.3"

repositories {
    mavenCentral()
    maven("https://repo.rus-crafting.ru/grocermc/") { content { includeGroup("ru.ruscrafting.arc") } }
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-public/") { content { includeGroup("com.github.retrooper") } }
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
kotlin { jvmToolchain(25) }

val packetEvents = "com.github.retrooper:packetevents-spigot:2.12.1"

configurations.configureEach {
    // arc-core-paper exposes these optional consumers on its compile classpath.
    exclude(group = "ru.ruscrafting.arc", module = "arc-core-redis")
    exclude(group = "ru.ruscrafting.arc", module = "arc-core-sql")
}

dependencies {
    implementation("ru.ruscrafting.arc:arc-core:2.7.83")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.83")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.83")

    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.83")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(packetEvents) { exclude(group = "io.netty") }

    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.83")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.83")
    testImplementation(packetEvents) { exclude(group = "io.netty") }
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.jar { archiveClassifier.set("plain") }

tasks.shadowJar {
    archiveClassifier.set("")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    filesMatching("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
    transform(Log4j2PluginsCacheFileTransformer())

    // ArcCore owns the shared APIs and provider classes across Bukkit loaders.
    exclude("ru/arc/paper/api/**", "ru/arc/paper/sidebar/**", "ru/arc/metrics/paper/**")
    exclude("ru/arc/paper/sql/**", "ru/arc/paper/redis/**", "ru/arc/paper/metrics/PaperArcMetricsProvider*.class")
    exclude("ru/arc/paper/runtime/PaperNativeCleanupServiceProvider*.class")
    exclude("ru/arc/paper/packet/PaperVisualPacketRuntime*.class")
    exclude("ru/arc/paper/display/PaperDisplayPassengerServiceProvider*.class")
    exclude("ru/arc/paper/scene/PaperSceneObservationServiceProvider*.class")
    exclude("ru/arc/paper/scene/PaperSceneEntityInfoServiceProvider*.class")
    exclude("org/bukkit/**", "io/papermc/**")
}

tasks.assemble { dependsOn(tasks.shadowJar) }
