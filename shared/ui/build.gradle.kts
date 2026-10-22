plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}
kotlin {
    jvm()
    android {
        namespace = "dev.agentbridge.ui"
        compileSdk = 37
        minSdk = 30
    }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { api(project(":client-core")); implementation(compose.runtime); implementation(compose.foundation); implementation(compose.material3) }
        commonTest.dependencies { implementation(kotlin("test")) }

    }
}
