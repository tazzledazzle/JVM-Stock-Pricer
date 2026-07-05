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

val ktorVersion = "2.3.7"
val coroutinesVersion = "1.7.3"

dependencies {
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