plugins {
    alias(kei.plugins.library)
}

android {
    sourceSets {
        named("test") {
            java.directories.clear()
            java.directories.add("test")
            kotlin.directories.clear()
            kotlin.directories.add("test")
        }
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
}
