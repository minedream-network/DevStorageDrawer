plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.3.2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.teenkung.devstoragedrawer"
version = providers.gradleProperty("version").orElse("1.0.0-SNAPSHOT").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://jitpack.io")
}

dependencies {
    implementation("io.github.projectunified:uni-item-all:2.7.1")
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("org.geysermc.cumulus:cumulus:1.1.2")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.17")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")

    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testImplementation("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    testImplementation("org.geysermc.cumulus:cumulus:1.1.2")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7.1")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("io.github.projectunified.uniitem", "com.teenkung.devstoragedrawer.libs.uniitem")
    }

    build {
        dependsOn(shadowJar)
    }

    withType<JavaCompile>().configureEach {
        options.release = 25
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion("26.1.2")
        jvmArgs("-Xms1G", "-Xmx2G")
    }

    processResources {
        val properties = mapOf("version" to project.version)
        inputs.properties(properties)
        filesMatching("plugin.yml") {
            expand(properties)
        }
    }
}
