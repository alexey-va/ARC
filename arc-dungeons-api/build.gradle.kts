plugins {
    `java-library`
    `maven-publish`
}
group = "ru.ruscrafting.arc"
version = "0.1.2"
base.archivesName.set("arc-dungeons-api")
repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.rus-crafting.ru/grocermc/")
}
dependencies {
    compileOnlyApi("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnlyApi("ru.ruscrafting.arc:arc-core-paper-api:2.7.38")
}
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(25) }

publishing {
    repositories {
        maven {
            name = "ApiStaging"
            url = rootProject.layout.buildDirectory.dir("api-release-repository").get().asFile.toURI()
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "arc-dungeons-api"
            pom {
                name.set("ARC Dungeons API")
                description.set("Typed Bukkit service contracts for the ARC dungeon host and consumers.")
            }
        }
    }
}
