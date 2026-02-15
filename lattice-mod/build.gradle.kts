plugins {
    id("fabric-loom") version "1.7.4"
    id("maven-publish")
}

val minecraftVersion: String = project.property("minecraft_version") as String
val parchmentMinecraftVersion: String = project.property("parchment_minecraft_version") as String
val parchmentMappingsVersion: String = project.property("parchment_mappings_version") as String
val fabricLoaderVersion: String = project.property("fabric_loader_version") as String
val fabricApiVersion: String = project.property("fabric_api_version") as String
val javaVersion: String = project.property("java_version") as String
val mavenGroup: String = project.property("maven_group") as String
val archivesBaseNameProp: String = project.property("archives_base_name") as String
val modId: String = project.property("mod_id") as String
val modName: String = project.property("mod_name") as String
val modDescription: String = project.property("mod_description") as String
val modAuthor: String = project.property("mod_author") as String

base {
    archivesName.set(archivesBaseNameProp)
}

group = mavenGroup

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/")
    maven("https://api.modrinth.com/maven")
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion}")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-${parchmentMinecraftVersion}:${parchmentMappingsVersion}@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:${fabricLoaderVersion}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${fabricApiVersion}")

    implementation("com.google.code.gson:gson:2.11.0")
    include("com.google.code.gson:gson:2.11.0")

    implementation("org.tomlj:tomlj:1.1.1")
    include("org.tomlj:tomlj:1.1.1")

    implementation("org.antlr:antlr4-runtime:4.13.1")
    include("org.antlr:antlr4-runtime:4.13.1")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    withSourcesJar()
}

loom {
    mixin {
        defaultRefmapName.set("${modId}.refmap.json")
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("fabric_loader_version", fabricLoaderVersion)
    inputs.property("fabric_api_version", fabricApiVersion)
    inputs.property("java_version", javaVersion)
    inputs.property("mod_id", modId)
    inputs.property("mod_name", modName)
    inputs.property("mod_description", modDescription)
    inputs.property("mod_author", modAuthor)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "fabric_loader_version" to fabricLoaderVersion,
            "fabric_api_version" to fabricApiVersion,
            "java_version" to javaVersion,
            "mod_id" to modId,
            "mod_name" to modName,
            "mod_description" to modDescription,
            "mod_author" to modAuthor
        )
    }
}
