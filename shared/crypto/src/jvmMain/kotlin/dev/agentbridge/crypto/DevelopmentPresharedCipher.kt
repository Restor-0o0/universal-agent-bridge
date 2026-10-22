package dev.agentbridge.crypto

import dev.agentbridge.protocol.EventEnvelope
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Loopback integration cipher using an explicitly supplied 256-bit test key.
 * It is not a pairing or key-storage implementation and must not be enabled by production config.
 */
class DevelopmentPresharedCipher(
    key: ByteArray,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) : EnvelopeCipher {
    private val secret = SecretKeySpec(key.copyOf(), "AES")
    private val seen = object : LinkedHashMap<String, Unit>(MAX_REPLAY_IDS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > MAX_REPLAY_IDS
    }

    init {
        require(key.size == 32) { "Development AES key must be 32 bytes" }
    }

    override suspend fun encrypt(context: EnvelopeContext, plaintext: ByteArray): EventEnvelope {
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT_BYTES)
        require(context.sequence > 0 && context.keyEpoch > 0)
        val nonce = ByteArray(12).also(random::nextBytes)
        val createdAt = Instant.now(clock).toString()
        val envelope = EventEnvelope(
            eventId = context.eventId,
            kind = "session.event",
            senderDeviceId = context.senderDeviceId,
            recipientDeviceId = context.recipientDeviceId,
            sessionId = context.sessionId,
            seq = context.sequence,
            createdAt = createdAt,
            ciphertext = "pending",
            nonce = encoder.encodeToString(nonce),
            keyEpoch = context.keyEpoch,
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(envelope))
        return envelope.copy(ciphertext = encoder.encodeToString(cipher.doFinal(plaintext)))
    }

    override suspend fun decrypt(envelope: EventEnvelope): ByteArray {
        envelope.validate()
        synchronized(seen) {
            require(!seen.containsKey(envelope.eventId)) { "Replayed envelope" }
        }
        val nonce = decoder.decode(envelope.nonce)
        require(nonce.size == 12) { "Invalid GCM nonce" }
        val ciphertext = decoder.decode(envelope.ciphertext)
        require(ciphertext.size <= MAX_PLAINTEXT_BYTES + 16) { "Ciphertext too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(envelope))
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Envelope authentication failed")
        }
        synchronized(seen) {
            require(seen.putIfAbsent(envelope.eventId, Unit) == null) { "Replayed envelope" }
        }
        return plaintext
    }

    private fun aad(envelope: EventEnvelope): ByteArray = listOf(
        envelope.v.toString(),
        envelope.eventId,
        envelope.kind,
        envelope.senderDeviceId,
        envelope.recipientDeviceId,
        envelope.sessionId,
        envelope.seq.toString(),
        envelope.createdAt,
        envelope.keyEpoch.toString(),
    ).joinToString("\u001f").toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val MAX_PLAINTEXT_BYTES = 1_000_000
        const val MAX_REPLAY_IDS = 4_096
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val decoder: Base64.Decoder = Base64.getUrlDecoder()
    }
}
