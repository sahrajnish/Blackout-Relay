pluginManagement {
    repositories {
        maven { url = uri("https://repo1.maven.org/maven2/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://repo1.maven.org/maven2/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        google()
    }
}

rootProject.name = "blackout-relay"

include(":core-crypto", ":core-model", ":verifier", ":app")
