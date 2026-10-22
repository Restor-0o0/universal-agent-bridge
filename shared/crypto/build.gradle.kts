plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)

}
kotlin {
    jvm()
    android {
        namespace = "dev.agentbridge.crypto"
        compileSdk = 37
        minSdk = 30
    }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { api(project(":protocol")) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }

    }
}
