pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "UniversalAgentBridge"
val modules = mapOf(
    ":androidApp" to "apps/android",
    ":desktopApp" to "apps/desktop",
    ":relayHeadless" to "apps/relay",
    ":hostApp" to "apps/host",
    ":protocol" to "shared/protocol",
    ":domain" to "shared/domain",
    ":crypto" to "shared/crypto",
    ":client-core" to "shared/client-core",
    ":agent-spi" to "shared/agent-spi",
    ":ui" to "shared/ui",
    ":acp-client" to "engines/acp",
    ":host-engine" to "engines/host",
    ":relay-engine" to "engines/relay",
    ":codex-adapter" to "adapters/codex",
    ":claude-adapter" to "adapters/claude",
    ":opencode-adapter" to "adapters/opencode"
)
modules.forEach { (name, path) -> include(name); project(name).projectDir = file(path) }
