plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":protocol"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.json)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.ktor.server.test)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(kotlin("test"))
}
