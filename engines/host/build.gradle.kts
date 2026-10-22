plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":agent-spi"))
    api(project(":protocol"))
    implementation(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(project(":relay-engine"))
    testImplementation(project(":client-core"))
    testImplementation(project(":crypto"))
    testImplementation(libs.ktor.server.test)
    testImplementation(libs.ktor.client.websockets)
}
