plugins { `java-library`; `maven-publish` }
group = "ru.ruscrafting.arc"
version = "0.1.1"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }; withSourcesJar() }
publishing {
    repositories { maven { name = "ApiStaging"; url = rootProject.layout.buildDirectory.dir("api-release-repository").get().asFile.toURI() } }
    publications { create<MavenPublication>("mavenJava") { from(components["java"]); artifactId = "arc-onboarding-api" } }
}
