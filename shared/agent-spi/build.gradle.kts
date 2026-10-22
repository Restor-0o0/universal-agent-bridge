plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)

}
kotlin {
    jvm()
    android {
        namespace = "dev.agentbridge.agentspi"
        compileSdk = 37
        minSdk = 30
    }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { api(project(":domain")); api(libs.coroutines.core) }
        commonTest.dependencies { implementation(kotlin("test")) }

    }
}
