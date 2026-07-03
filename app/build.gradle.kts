plugins {
    kotlin("jvm") version "2.0.0"
}

group = "com.blindspot"
version = "1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    // Burp API remains compileOnly (Burp provides this itself)
    compileOnly("net.portswigger.burp.extensions:montoya-api:2024.7")
    
    // Explicitly pull the Kotlin standard library so we can force it inside our JAR
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    // 1. Pack our custom compiled extension code files
    from(sourceSets.main.get().output)
    
    // 2. MAGIC LINE: Open up the implementation dependencies (the Kotlin standard library), 
    // extract them, and merge them inside our final output JAR file.
    from(provider {
        configurations.runtimeClasspath.get()
            .filter { it.name.contains("kotlin-stdlib") } // Only grab the needed Kotlin files
            .map { if (it.isDirectory) it else zipTree(it) }
    })
}