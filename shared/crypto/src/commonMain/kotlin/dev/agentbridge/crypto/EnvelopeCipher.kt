package dev.agentbridge.crypto

import dev.agentbridge.protocol.EventEnvelope

/** Reserved boundary. No plaintext fallback and no production cipher in M0.
 * Implementations must authenticate routing metadata as AEAD associated data,
 * enforce nonce uniqueness and replay protection, and use paired device keys.
 */
interface EnvelopeCipher {
    suspend fun encrypt(context: EnvelopeContext, plaintext: ByteArray): EventEnvelope
    suspend fun decrypt(envelope: EventEnvelope): ByteArray
}
data class EnvelopeContext(
    val eventId: String,
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val sessionId: String,
    val sequence: Long,
    val keyEpoch: Int,
)
