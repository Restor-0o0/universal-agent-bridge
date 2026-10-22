plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
}
allprojects { group = "dev.agentbridge"; version = "0.1.0-dev" }

// Keeps WSL/Linux packaging outputs separate from Windows build products.
providers.gradleProperty("agentBridgeBuildRoot").orNull?.let { isolatedRoot ->
    subprojects {
        val moduleDirectory = path.removePrefix(":").replace(':', '/')
        layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("$isolatedRoot/$moduleDirectory"))
    }
}
