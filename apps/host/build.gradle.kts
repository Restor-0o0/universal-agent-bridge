plugins { application; alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":acp-client"))
    implementation(project(":host-engine"))
    implementation(project(":codex-adapter"))
    implementation(project(":claude-adapter"))
    implementation(project(":opencode-adapter"))
    implementation(project(":client-core"))
    implementation(project(":crypto"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    testImplementation(kotlin("test"))
}

application { mainClass.set("dev.agentbridge.host.MainKt") }
