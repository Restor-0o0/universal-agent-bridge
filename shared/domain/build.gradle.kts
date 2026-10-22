plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)

}
kotlin {
    jvm()
    android {
        namespace = "dev.agentbridge.domain"
        compileSdk = 37
        minSdk = 30
    }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {  }
        commonTest.dependencies { implementation(kotlin("test")) }

    }
}
