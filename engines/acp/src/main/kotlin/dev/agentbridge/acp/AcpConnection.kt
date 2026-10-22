package dev.agentbridge.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AcpRpcException(val code: Int?, message: String) : IllegalStateException(message)

interface AcpPeer {
    val incoming: Flow<JsonObject>
    suspend fun request(method: String, params: JsonElement = JsonObject(emptyMap())): JsonObject
    suspend fun notify(method: String, params: JsonElement = JsonObject(emptyMap()))
    suspend fun respond(id: JsonElement, result: JsonElement = JsonNull)
    suspend fun shutdown()
}

/** Long-lived ACP subprocess connection shared by every ACP-compatible harness. */
class AcpConnection private constructor(
    private val process: Process,
    private val writer: BufferedWriter,
    private val scope: CoroutineScope,
    private val timeout: Duration,
) : AcpPeer {
    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val incomingChannel = Channel<JsonObject>(capacity = 256)
    private val writeMutex = Mutex()
    private val readerJob: Job

    override val incoming: Flow<JsonObject> = incomingChannel.receiveAsFlow()

    init {
        readerJob = scope.launch(Dispatchers.IO) {
            val failure = runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.filter(String::isNotBlank).forEach { line ->
                        val message = json.parseToJsonElement(line).jsonObject
                        val id = message["id"]?.jsonPrimitive?.contentOrNull
                        if (id != null && ("result" in message || "error" in message)) {
                            pending.remove(id)?.complete(message) ?: incomingChannel.send(message)
                        } else {
                            incomingChannel.send(message)
                        }
                    }
                }
                error("ACP process closed stdout")
            }.exceptionOrNull() ?: error("ACP reader stopped")
            pending.values.forEach { it.completeExceptionally(failure) }
            pending.clear()
            incomingChannel.close(failure)
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

    suspend fun initialize(): AcpAgentInfo = AcpProtocol.parseInitializeResult(
        request("initialize", AcpProtocol.initializeParams()),
    )

    override suspend fun request(method: String, params: JsonElement): JsonObject {
        val id = nextId.getAndIncrement().toString()
        val response = CompletableDeferred<JsonObject>()
        check(pending.putIfAbsent(id, response) == null)
        try {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id.toLong())
                put("method", method)
                put("params", params)
            })
            val message = withTimeout(timeout.toMillis()) { response.await() }
            message["error"]?.jsonObject?.let { error ->
                throw AcpRpcException(
                    error["code"]?.jsonPrimitive?.intOrNull,
                    error["message"]?.jsonPrimitive?.contentOrNull ?: "ACP request failed",
                )
            }
            return message["result"]?.jsonObject ?: error("ACP response has no object result")
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun notify(method: String, params: JsonElement) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        })
    }

    override suspend fun respond(id: JsonElement, result: JsonElement) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        })
    }

    override suspend fun shutdown() {
        runCatching { writer.close() }
        if (process.isAlive) process.destroy()
        withContext(Dispatchers.IO) {
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        readerJob.cancel()
        scope.cancel()
    }

    private suspend fun send(message: JsonObject) = writeMutex.withLock {
        check(process.isAlive) { "ACP process is not running" }
        withContext(Dispatchers.IO) {
            writer.write(json.encodeToString(JsonElement.serializer(), message))
            writer.newLine()
            writer.flush()
        }
    }

    companion object {
        suspend fun start(
            executable: String,
            arguments: List<String>,
            workingDirectory: Path? = null,
            timeout: Duration = Duration.ofSeconds(10),
        ): AcpConnection = withContext(Dispatchers.IO) {
            require(executable.isNotBlank())
            require(!timeout.isNegative && !timeout.isZero)
            val builder = ProcessBuilder(listOf(executable) + arguments)
            workingDirectory?.let { builder.directory(it.toAbsolutePath().normalize().toFile()) }
            val process = builder.start()
            AcpConnection(
                process,
                process.outputStream.bufferedWriter(Charsets.UTF_8),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
                timeout,
            )
        }
    }
}

fun interface AcpConnectionFactory {
    suspend fun start(executable: String, arguments: List<String>, workingDirectory: Path?): AcpPeer
}

val ProcessAcpConnectionFactory = AcpConnectionFactory { executable, arguments, workingDirectory ->
    // A prompt request remains open for the whole model turn, including time spent
    // waiting for a human approval. Keep probes fail-fast, but allow managed turns
    // to outlive cold startup and the five-minute approval window.
    AcpConnection.start(
        executable,
        arguments,
        workingDirectory,
        timeout = Duration.ofMinutes(10),
    )
}

fun interface AcpProbe {
    suspend fun probe(executable: String, arguments: List<String>): AcpAgentInfo
}

class ProcessAcpProbe(private val timeout: Duration = Duration.ofSeconds(10)) : AcpProbe {
    override suspend fun probe(executable: String, arguments: List<String>): AcpAgentInfo {
        val connection = AcpConnection.start(executable, arguments, timeout = timeout)
        return try {
            connection.initialize()
        } finally {
            connection.shutdown()
        }
    }
}
