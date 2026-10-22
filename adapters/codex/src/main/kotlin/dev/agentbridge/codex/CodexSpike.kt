package dev.agentbridge.codex

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only initializes a fresh child process. Does not read histories or start inference. */
fun probeCodex(executable: String, workingDirectory: Path): Boolean {
    val process = ProcessBuilder(executable, "app-server", "--listen", "stdio://")
        .directory(workingDirectory.toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "codex-spike-reader").apply { isDaemon = true }
    }
    try {
        val result = executor.submit<Boolean> {
            val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
            val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
            writer.write(CodexProtocol.initialize().toString()); writer.newLine(); writer.flush()
            while (true) {
                val line = reader.readLine() ?: error("Codex closed stdout before initialize response")
                require(line.length <= 1_048_576) { "Oversized Codex response" }
                val message = Json.parseToJsonElement(line).jsonObject
                if (message["id"] == JsonPrimitive(1)) {
                    check(message["error"] == null && message["result"] is JsonObject) { "Initialize rejected" }
                    writer.write(CodexProtocol.initialized().toString()); writer.newLine(); writer.flush()
                    return@submit true
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        return result.get(20, TimeUnit.SECONDS)
    } finally {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
        executor.shutdownNow()
    }
}
