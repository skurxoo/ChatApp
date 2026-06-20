plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")
    implementation("ch.qos.logback:logback-classic:1.4.14")
}

application {
    mainClass.set("MainKt")
}