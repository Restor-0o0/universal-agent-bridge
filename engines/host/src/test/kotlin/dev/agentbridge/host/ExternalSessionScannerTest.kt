package dev.agentbridge.host

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalSessionScannerTest {
    @Test
    fun discoversAndMirrorsCodexAndClaudeHistoriesWithoutWritingThem() {
        val home = Files.createTempDirectory("agent-bridge-external")
        try {
            val codex = home.resolve(".codex/sessions/2026/10/21/rollout-test.jsonl")
            codex.parent.createDirectories()
            codex.writeLines(
                listOf(
                    """{"type":"session_meta","payload":{"id":"codex-1","cwd":"C:\\work\\alpha"}}""",
                    """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"check alpha"}]}}""",
                    """{"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"alpha ok"}]}}""",
                ),
            )
            val claude = home.resolve(".claude/projects/C--work-beta/claude-1.jsonl")
            claude.parent.createDirectories()
            claude.writeLines(
                listOf(
                    """{"type":"user","sessionId":"claude-1","cwd":"C:\\work\\beta","message":{"role":"user","content":"check beta"}}""",
                    """{"type":"assistant","sessionId":"claude-1","cwd":"C:\\work\\beta","message":{"role":"assistant","content":[{"type":"text","text":"beta ok"}]}}""",
                ),
            )
            val modified = FileTime.fromMillis(99_000)
            Files.setLastModifiedTime(codex, modified)
            Files.setLastModifiedTime(claude, modified)

            val scanner = ExternalSessionScanner(home) { 100_000 }
            val sessions = scanner.scan()

            assertEquals(setOf("codex-1", "claude-1"), sessions.map { it.wire.id }.toSet())
            assertTrue(sessions.all { it.wire.active })
            val codexText = scanner.transcript(sessions.single { it.wire.id == "codex-1" })
            val claudeText = scanner.transcript(sessions.single { it.wire.id == "claude-1" })
            assertTrue("YOU\ncheck alpha" in codexText)
            assertTrue("AGENT\nalpha ok" in codexText)
            assertTrue("YOU\ncheck beta" in claudeText)
            assertTrue("AGENT\nbeta ok" in claudeText)
        } finally {
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
