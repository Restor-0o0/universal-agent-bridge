plugins { alias(libs.plugins.android.application); alias(libs.plugins.compose.compiler) }
android {
    namespace = "dev.agentbridge.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.agentbridge.android"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":ui"))
    implementation(project(":client-core"))
    implementation(project(":crypto"))
    implementation(project(":protocol"))
    implementation(libs.activity.compose)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.coroutines.core)
}
