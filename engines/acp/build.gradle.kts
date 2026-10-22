plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
