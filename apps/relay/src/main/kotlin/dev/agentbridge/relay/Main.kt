package dev.agentbridge.relay

fun main(args: Array<String>) {
    require(args.size <= 2) { "Usage: relayHeadless [--dev] [port]" }
    val development = "--dev" in args
    val port = args.firstOrNull { it != "--dev" }?.toInt() ?: 8787
    val config = RelayConfig(port = port)
    val access = if (development) {
        RelayDevelopmentAccess(
            bearerToken = requireNotNull(System.getenv("AGENT_BRIDGE_DEV_TOKEN")) {
                "AGENT_BRIDGE_DEV_TOKEN is required for --dev"
            },
            allowedDeviceIds = setOf("host", "desktop", "android"),
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
