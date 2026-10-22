plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    jvm()
    android {
        namespace = "dev.agentbridge.protocol"
        compileSdk = 37
        minSdk = 30
    }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { api(libs.serialization.json) }
        commonTest.dependencies { implementation(kotlin("test")) }

    }
}
