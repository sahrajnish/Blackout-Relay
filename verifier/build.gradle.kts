plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("org.blackoutrelay.verify.CliKt")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-crypto"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
