plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)

}
kotlin {
    jvm()
    android {
        namespace = "dev.agentbridge.clientcore"
        compileSdk = 37
        minSdk = 30
    }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { api(project(":protocol")); api(project(":domain")); implementation(libs.ktor.client.core); implementation(libs.coroutines.core) }
        jvmMain.dependencies {
            implementation(project(":crypto"))
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
        }
        androidMain.dependencies {
            implementation(project(":crypto"))
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        commonTest.dependencies { implementation(libs.ktor.client.mock); implementation(libs.coroutines.test) }
    }
}
