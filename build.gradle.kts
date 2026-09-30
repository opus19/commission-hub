plugins {
    kotlin("jvm") version "2.4.20"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "kim.opus"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.javalin:javalin:7.2.3")
    implementation("org.slf4j:slf4j-simple:2.0.20")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("kim.opus.hub.MainKt")
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveFileName.set("commission-hub.jar")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
