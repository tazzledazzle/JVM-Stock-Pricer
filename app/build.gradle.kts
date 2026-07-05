plugins {
    java
    kotlin("jvm") version "2.1.20"
    application
    kotlin("plugin.serialization") version "2.1.20"
}

group = "com.tazzledazzle.stockagg"
version = "0.1.0"


repositories {
    mavenCentral()
    gradlePluginPortal()
}
val ktorVersion = "2.3.12"
val coroutinesVersion = "1.8.1"

dependencies {
    // --- Server ---
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")

    // --- HTTP client (used by the providers to hit Yahoo Finance / Stooq) ---
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-trest")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.tazzledazzle.stockagg.AppKt")
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}