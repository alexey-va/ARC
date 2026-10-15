import org.gradle.api.tasks.testing.Test
plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}
group = "ru.ruscrafting"
version = "0.1.2"
base.archivesName.set("ArcDungeons")
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }
repositories {
    maven("https://repo.rus-crafting.ru/grocermc/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.magmaguy.com/releases")
    maven("https://jitpack.io")
    mavenCentral()
}
dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.23")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.23")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.23")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.23")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.23")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.7.23")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.7.23")
    implementation(libs.com.google.code.gson.gson)
    implementation(libs.de.tr7zw.item.nbt.api)
    compileOnly(project(":arc-dungeons-api"))
    compileOnly(project(":arc-staff-spells-api"))
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.23")
    compileOnly(libs.io.papermc.paper.paper.api)
    compileOnly(libs.ru.ruscrafting.thirdparty.elitemobs.api)
    compileOnly(libs.ru.ruscrafting.thirdparty.magmacore)
    compileOnly("com.magmaguy:FreeMinecraftModels:2.12.1") { isTransitive = false }
    compileOnly(libs.fr.black.eyes.lootchest)
    compileOnly(libs.com.zrips.cmi.api)
    compileOnly(libs.com.github.lonedev6.api.itemsadder)
    compileOnly(libs.net.luckperms.api)
    compileOnly(libs.com.github.retrooper.packetevents.spigot) { exclude(group = "io.netty") }

    testImplementation(project(":arc-dungeons-api"))
    testImplementation(project(":arc-staff-spells-api"))
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.23")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.23")
    testImplementation(libs.ru.ruscrafting.thirdparty.elitemobs.api)
    testImplementation(libs.ru.ruscrafting.thirdparty.magmacore)
    testImplementation("com.magmaguy:FreeMinecraftModels:2.12.1") { isTransitive = false }
    testImplementation(libs.com.github.retrooper.packetevents.spigot) { exclude(group = "io.netty") }
    testImplementation(libs.com.zrips.cmi.api)
    testImplementation(libs.com.github.lonedev6.api.itemsadder)
    testImplementation(libs.net.luckperms.api)
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.kotest:kotest-property:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}
val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
kotlin.target.compilations.getByName("integrationTest").associateWith(kotlin.target.compilations.getByName("main"))
configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
dependencies {
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.7.23")
}
tasks.withType<Test>().configureEach {
    classpath = files(classpath.filter { it.name.startsWith("adventure-") }, classpath)
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
}
tasks.register<Test>("integrationTest") {
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
}
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.jar { archiveClassifier.set("plain") }
tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    // These contracts and host implementations have a single runtime owner: ARC.
    exclude("ru/arc/paper/api/**", "ru/arc/dungeons/api/**", "ru/arc/staffspells/api/**")
    exclude("ru/arc/paper/redis/PaperRedisServiceProvider*", "ru/arc/paper/sql/PaperSqlServiceProvider*",
        "ru/arc/paper/metrics/PaperArcMetricsProvider*")
    relocate("de.tr7zw.changeme.nbtapi", "ru.arc.dungeons.libs.nbtapi")
}
tasks.build { dependsOn(tasks.shadowJar) }

// Offline textured preview uses the same cuboids as live spell displays.
tasks.register<JavaExec>("exportStaffRedesignPreview") {
    group = "verification"
    description = "Export native particle and display staff sequences"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.arc.staffspells.StaffRedesignPreviewExport")
    args(providers.gradleProperty("staffRedesignPreviewOutput").getOrElse(
        layout.buildDirectory.file("staff-spells-preview/redesign-sequences.json").get().asFile.absolutePath,
    ))
}

tasks.register<JavaExec>("exportStaffSpellPreview") {
    group = "verification"
    description = "Export staff display geometry and animation poses as JSON"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.arc.staffspells.StaffSpellPreviewExport")
    args(providers.gradleProperty("staffSpellPreviewOutput").getOrElse(
        layout.buildDirectory.file("staff-spells-preview/scene.json").get().asFile.absolutePath,
    ))
    providers.gradleProperty("staffSpellPreviewSpell").orNull?.let { args(it) }
}
