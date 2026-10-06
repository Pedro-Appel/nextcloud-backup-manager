plugins {
    java
    application
    id("com.gradleup.shadow") version "9.3.0"
}

group = "dev.irattiz"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "dev.irattiz.backup.BackupApplication"
}

repositories {
    mavenCentral()
}

dependencies {
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // JSON parsing (for Restic snapshot output)
    implementation("jakarta.json:jakarta.json-api:2.1.3")
    implementation("org.eclipse.parsson:parsson:1.1.7")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.5")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = "verification"
    dependsOn(tasks.shadowJar)
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("applicationJar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

tasks.shadowJar {
    archiveClassifier = "all"
    manifest {
        attributes["Main-Class"] = application.mainClass
    }
}
