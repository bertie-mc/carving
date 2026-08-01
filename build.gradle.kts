import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.testing.Test

plugins {
    `java-library`
    idea
    id("net.neoforged.moddev") version "2.0.134"
}

val minecraft_version: String by project
val minecraft_version_range: String by project
val neo_version: String by project
val neo_version_range: String by project
val loader_version_range: String by project
val parchment_mappings_version: String by project
val parchment_minecraft_version: String by project
val mod_id: String by project
val mod_name: String by project
val mod_license: String by project
val mod_version: String by project
val mod_group_id: String by project
val mod_authors: String by project
val mod_description: String by project

version = mod_version
group = mod_group_id

repositories {
    mavenLocal()
    maven { url = uri("https://api.modrinth.com/maven") }
}

base {
    archivesName = mod_id
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

val gameTest = sourceSets.create("gameTest")

neoForge {
    version = neo_version

    parchment {
        mappingsVersion = parchment_mappings_version
        minecraftVersion = parchment_minecraft_version
    }

    runs {
        register("client") {
            client()
        }
        register("server") {
            server()
        }
        register("gameTestServer") {
            type = "gameTestServer"
            systemProperty("neoforge.enabledGameTestNamespaces", "minecraft")
        }
        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        register(mod_id) {
            sourceSet(sourceSets.main.get())
            sourceSet(gameTest)
        }
    }

    addModdingDependenciesTo(gameTest)

    unitTest {
        enable()
        testedMod = mods.getByName(mod_id)
    }
}

gameTest.compileClasspath += sourceSets.main.get().output
gameTest.runtimeClasspath += sourceSets.main.get().output

dependencies {
    // Optional integrations are guarded at runtime.
    compileOnly("maven.modrinth:slag-n-embers:1.1a")
    compileOnly("maven.modrinth:emi:1.1.24+1.21.1+neoforge")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    systemProperty("bertie.projectDir", layout.projectDirectory.asFile.absolutePath)
}

val generateModMetadata = tasks.register<ProcessResources>("generateModMetadata") {
    val replaceProperties = mapOf(
        "minecraft_version" to minecraft_version,
        "minecraft_version_range" to minecraft_version_range,
        "neo_version" to neo_version,
        "neo_version_range" to neo_version_range,
        "loader_version_range" to loader_version_range,
        "mod_id" to mod_id,
        "mod_name" to mod_name,
        "mod_license" to mod_license,
        "mod_version" to mod_version,
        "mod_authors" to mod_authors,
        "mod_description" to mod_description,
    )
    inputs.properties(replaceProperties)
    expand(replaceProperties)
    from("src/main/templates")
    into("build/generated/sources/modMetadata")
}
sourceSets.main.get().resources.srcDir(generateModMetadata)
neoForge.ideSyncTask(generateModMetadata)
