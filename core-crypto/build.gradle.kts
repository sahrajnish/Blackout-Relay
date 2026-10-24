plugins {
    kotlin("jvm")
}

dependencies {
    implementation("net.java.dev.jna:jna:5.12.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
