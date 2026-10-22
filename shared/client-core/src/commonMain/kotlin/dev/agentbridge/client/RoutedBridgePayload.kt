package dev.agentbridge.client

import dev.agentbridge.protocol.BridgePayload

data class RoutedBridgePayload(
    val senderDeviceId: String,
    val payload: BridgePayload,
)
