package dev.agentbridge.relay

fun main(args: Array<String>) {
    require(args.size <= 2) { "Usage: relayHeadless [--dev] [port]" }
    val development = "--dev" in args
    val port = args.firstOrNull { it != "--dev" }?.toInt() ?: 8787
    val config = RelayConfig(port = port)
    val access = if (development) {
        val configured = System.getenv("AGENT_BRIDGE_WORKSPACES")
            ?.split(';')
            ?.filter(String::isNotBlank)
            ?.associate { entry ->
                val separator = entry.indexOf('=')
                require(separator in 1 until entry.lastIndex) {
                    "AGENT_BRIDGE_WORKSPACES format: workspace=password;workspace2=password2"
                }
                entry.substring(0, separator).trim() to RelayWorkspaceAccess(entry.substring(separator + 1).trim())
            }
            .orEmpty()
        RelayDevelopmentAccess(
            configured.ifEmpty {
                mapOf(
                    "default" to RelayWorkspaceAccess(
                        requireNotNull(System.getenv("AGENT_BRIDGE_DEV_TOKEN")) {
                            "AGENT_BRIDGE_DEV_TOKEN or AGENT_BRIDGE_WORKSPACES is required for --dev"
                        },
                    ),
                )
            },
        )
    } else null
    val engine = RelayEngine(config, access)
    Runtime.getRuntime().addShutdownHook(Thread { engine.close() })
    println(
        if (development) "Agent Bridge local encrypted dev Relay: http://${config.bindHost}:${config.port}"
        else "Agent Bridge diagnostics: http://${config.bindHost}:${config.port}; enrollment disabled",
    )
    engine.start()
    java.util.concurrent.CountDownLatch(1).await()
}
