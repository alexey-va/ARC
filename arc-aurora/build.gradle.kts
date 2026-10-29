plugins { kotlin("jvm"); id("com.gradleup.shadow"); `maven-publish` }
group = "ru.ruscrafting.arc"
version = "0.1.5"
base.archivesName.set("ArcAurora")
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }; withSourcesJar() }
kotlin { jvmToolchain(25) }
repositories {
    maven("https://repo.rus-crafting.ru/grocermc/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-public/")
    mavenCentral()
}
dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.69")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.69")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.69")
    implementation(project(":arc-aurora-api"))
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.69")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(libs.com.github.retrooper.packetevents.spigot) { exclude(group = "io.netty") }
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.69")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.69")
    testImplementation(libs.com.github.retrooper.packetevents.spigot) { exclude(group = "io.netty") }
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform(); maxHeapSize = "1g"; jvmArgs("-XX:+EnableDynamicAgentLoading") }
tasks.processResources { inputs.property("version", project.version); filesMatching("plugin.yml") { expand("version" to project.version) } }
tasks.jar { archiveClassifier.set("plain") }
tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("ru/arc/paper/api/**", "ru/arc/paper/runtime/PaperNativeCleanupServiceProvider*.class")
    exclude("ru/arc/paper/packet/PaperVisualPacketRuntime*.class")
    exclude("ru/arc/paper/redis/PaperRedisServiceProvider*", "ru/arc/paper/sql/PaperSqlServiceProvider*", "ru/arc/paper/metrics/PaperArcMetricsProvider*")
    exclude("ru/arc/paper/redis/SharedPaperRedisService*", "ru/arc/paper/sql/SharedPaperSqlService*")
}
tasks.build { dependsOn(tasks.shadowJar) }
publishing {
    repositories { maven { name = "ApiStaging"; url = rootProject.layout.buildDirectory.dir("api-release-repository").get().asFile.toURI() } }
    publications { create<MavenPublication>("plugin") {
        artifactId = "arc-aurora"
        artifact(tasks.shadowJar)
        artifact(tasks.named("sourcesJar"))
    } }
}
