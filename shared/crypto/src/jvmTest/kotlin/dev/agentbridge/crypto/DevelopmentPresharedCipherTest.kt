package dev.agentbridge.crypto

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class DevelopmentPresharedCipherTest {
    @Test
    fun encryptsAuthenticatesAndRejectsReplay() {
        runBlocking {
            val sender = DevelopmentPresharedCipher(ByteArray(32) { it.toByte() })
            val receiver = DevelopmentPresharedCipher(ByteArray(32) { it.toByte() })
            val envelope = sender.encrypt(
                EnvelopeContext("event-1", "desktop", "host", "control", 1, 1),
                "secret prompt".encodeToByteArray(),
            )
            assertContentEquals("secret prompt".encodeToByteArray(), receiver.decrypt(envelope))
            assertFailsWith<IllegalArgumentException> { receiver.decrypt(envelope) }
        }
    }

    @Test
    fun routingMetadataIsAuthenticated() {
        runBlocking {
            val key = ByteArray(32) { (it + 1).toByte() }
            val envelope = DevelopmentPresharedCipher(key).encrypt(
                EnvelopeContext("event-2", "desktop", "host", "control", 1, 1),
                "payload".encodeToByteArray(),
            )
            assertFailsWith<IllegalArgumentException> {
                DevelopmentPresharedCipher(key).decrypt(envelope.copy(recipientDeviceId = "attacker"))
            }
        }
    }
}
