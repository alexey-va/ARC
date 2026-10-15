import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

group = "ru.ruscrafting"
version = providers.gradleProperty("arcCorePluginVersion").get()
base.archivesName.set("ArcCore")

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

val arcCoreVersion = "2.7.23"

repositories {
    maven("https://repo.rus-crafting.ru/grocermc/")
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core-paper-api:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-paper:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-redis:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-sql:$arcCoreVersion")

    // PaperArcMetricsProvider is hosted here; ARC keeps its own registry and exporter.
    implementation("io.micrometer:micrometer-core:1.14.5")
    implementation("io.micrometer:micrometer-commons:1.14.5")
    implementation("io.micrometer:micrometer-observation:1.14.5")

    compileOnly(libs.io.papermc.paper.paper.api)

    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:$arcCoreVersion")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.jar { archiveClassifier.set("plain") }

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")
}

tasks.build { dependsOn(tasks.shadowJar) }
