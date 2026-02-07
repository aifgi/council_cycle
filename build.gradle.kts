plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
}

group = "com.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    implementation("io.insert-koin:koin-core:4.0.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.charleskorn.kaml:kaml:0.67.0")

    implementation("com.anthropic:anthropic-java:2.13.0")

    implementation("org.jsoup:jsoup:1.18.3")

    implementation("org.apache.pdfbox:pdfbox:3.0.4")

    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")

    testImplementation("io.ktor:ktor-client-mock:3.0.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform {
        excludeTags("real-llm")
    }
}

tasks.register<Test>("realLlmTest") {
    useJUnitPlatform {
        includeTags("real-llm")
    }
    environment("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"))
}

application {
    mainClass.set("MainKt")
}
