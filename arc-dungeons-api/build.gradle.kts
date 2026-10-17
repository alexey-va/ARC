plugins { `java-library` }
group = "ru.ruscrafting.arc"
version = "0.1.0"
base.archivesName.set("arc-dungeons-api")
repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.rus-crafting.ru/grocermc/")
}
dependencies {
    compileOnlyApi("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnlyApi("ru.ruscrafting.arc:arc-core-paper-api:2.7.33")
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(25) }
