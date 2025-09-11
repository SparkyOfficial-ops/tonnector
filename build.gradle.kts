plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "dev.tonnector"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-status-pages:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.ton:ton-kotlin-contract:0.4.3")
    implementation("org.ton:ton-kotlin-crypto:0.4.3")
    implementation("org.purejava:tweetnacl-java:1.1.2")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.tonnector.MainKt")
}

tasks.test {
    useJUnitPlatform()
}