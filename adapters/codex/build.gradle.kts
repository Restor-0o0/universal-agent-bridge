plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":agent-spi"))
    implementation(project(":acp-client"))
    implementation(project(":host-engine"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
