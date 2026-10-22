package dev.agentbridge.claude

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedWriter
import java.nio.file.Path
import java.util.concurrent.TimeUnit

interface ClaudePeer {
    val incoming: Flow<JsonObject>
    suspend fun send(message: JsonObject)
    suspend fun shutdown()
}

fun interface ClaudeConnectionFactory {
    suspend fun start(executable: String, arguments: List<String>, workingDirectory: Path): ClaudePeer
}

internal class ClaudeConnection private constructor(
    private val process: Process,
    private val writer: BufferedWriter,
    private val scope: CoroutineScope,
) : ClaudePeer {
    private val json = Json { ignoreUnknownKeys = true }
    private val writes = Mutex()
    private val messages = Channel<JsonObject>(capacity = 256)

    override val incoming: Flow<JsonObject> = messages.receiveAsFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val failure = runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.filter(String::isNotBlank).forEach { line ->
                        messages.send(json.parseToJsonElement(line).jsonObject)
                    }
                }
                error("Claude process closed stdout")
            }.exceptionOrNull() ?: error("Claude reader stopped")
            messages.close(failure)
        }
        scope.launch(Dispatchers.IO) {
            process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (reader.read(buffer) >= 0) {
                    // Keep stderr drained so the subprocess cannot block.
                }
            }
        }
    }

    override suspend fun send(message: JsonObject) = writes.withLock {
        check(process.isAlive) { "Claude process is not running" }
        withContext(Dispatchers.IO) {
            writer.write(json.encodeToString(JsonElement.serializer(), message))
            writer.newLine()
            writer.flush()
        }
    }

    override suspend fun shutdown() {
        runCatching { writer.close() }
        if (process.isAlive) process.destroy()
        withContext(Dispatchers.IO) {
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        scope.cancel()
    }

    companion object {
        suspend fun start(
            executable: String,
            arguments: List<String>,
            workingDirectory: Path,
        ): ClaudeConnection = withContext(Dispatchers.IO) {
            require(executable.isNotBlank())
            val process = ProcessBuilder(listOf(executable) + arguments)
                .directory(workingDirectory.toFile())
                .start()
            ClaudeConnection(
                process,
                process.outputStream.bufferedWriter(Charsets.UTF_8),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        }
    }
}

val ProcessClaudeConnectionFactory = ClaudeConnectionFactory { executable, arguments, workingDirectory ->
    ClaudeConnection.start(executable, arguments, workingDirectory)
}
