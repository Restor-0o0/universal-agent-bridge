package dev.agentbridge.client

/** In-memory draft queue for M0; caller serializes access.
 * Only text drafts can be queued. Approvals have no offline representation.
 */
data class TextDraft(val idempotencyKey: String, val sessionId: String, val text: String)
class DraftOutbox(private val capacity: Int = 100) {
    init { require(capacity > 0) }
    private val pending = linkedMapOf<String, TextDraft>()
    fun enqueue(draft: TextDraft) {
        require(draft.idempotencyKey.isNotBlank() && draft.sessionId.isNotBlank())
        require(draft.text.isNotBlank() && draft.text.length <= 32_768)
        pending[draft.idempotencyKey]?.let {
            require(it == draft) { "Idempotency key reused for a different draft" }
            return
        }
        check(pending.size < capacity) { "Outbox full" }
        pending[draft.idempotencyKey] = draft
    }
    fun snapshot(): List<TextDraft> = pending.values.toList()
    fun acknowledge(idempotencyKey: String) { pending.remove(idempotencyKey) }
}
enum class SequenceResult { ACCEPTED, DUPLICATE, GAP }
class SequenceCursor {
    var lastSequence: Long = 0
        private set
    fun accept(sequence: Long): SequenceResult {
        require(sequence > 0)
        return when {
            sequence <= lastSequence -> SequenceResult.DUPLICATE
            sequence != lastSequence + 1 -> SequenceResult.GAP
            else -> { lastSequence = sequence; SequenceResult.ACCEPTED }
        }
    }
}
