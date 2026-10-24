plugins {
    kotlin("jvm") version "2.0.21" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

allprojects {
    group = "org.blackoutrelay"
    version = "0.1.0"
}

subprojects {
    if (name == "app") return@subprojects
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

tasks.wrapper {
    gradleVersion = "8.10.2"
    distributionType = org.gradle.api.tasks.wrapper.Wrapper.DistributionType.BIN
    // This environment cannot validate the URL; the distribution is fetched on
    // first `./gradlew` run.
    validateDistributionUrl = false
}
