import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":ui"))
    implementation(project(":relay-engine"))
    implementation(project(":host-engine"))
    implementation(project(":codex-adapter"))
    implementation(project(":claude-adapter"))
    implementation(project(":opencode-adapter"))
    implementation(project(":client-core"))
    implementation(project(":crypto"))
    implementation(compose.desktop.currentOs)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
}
compose.desktop {
    application {
        mainClass = "dev.agentbridge.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AgentBridge"
            packageVersion = "0.1.0"
            windows {
                perUserInstall = true
                shortcut = true
                menu = true
            }
        }
    }
}
