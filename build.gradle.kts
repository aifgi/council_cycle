plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "uk.co.councilcycle"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val coroutinesVersion = "1.8.1"
val ktorVersion = "2.3.12"
val slf4jVersion = "2.0.13"
val logbackVersion = "1.5.6"

dependencies {
    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Kotlin serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Ktor client (HTTP)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // HTML parsing
    implementation("org.jsoup:jsoup:1.17.2")

    // Logging
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // Configuration
    implementation("com.sksamuel.hoplite:hoplite-core:2.7.5")
    implementation("com.sksamuel.hoplite:hoplite-yaml:2.7.5")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("uk.co.councilcycle.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
