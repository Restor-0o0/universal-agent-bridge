package dev.agentbridge.host

import dev.agentbridge.protocol.BridgeJson
import dev.agentbridge.protocol.WireExternalSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

data class ExternalSessionSource(
    val wire: WireExternalSession,
    val file: Path,
)

/** Reads the append-only histories written by regular Codex and Claude Code terminals.
 * It never opens, signals, or writes to their processes. */
class ExternalSessionScanner(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    fun scan(limitPerAgent: Int = 12): List<ExternalSessionSource> =
        (scanCodex(limitPerAgent) + scanClaude(limitPerAgent))
            .sortedByDescending { it.wire.updatedAtMillis }

    fun refresh(source: ExternalSessionSource): ExternalSessionSource {
        val modified = runCatching { Files.getLastModifiedTime(source.file).toMillis() }
            .getOrDefault(source.wire.updatedAtMillis)
        return source.copy(
            wire = source.wire.copy(
                updatedAtMillis = modified,
                active = clockMillis() - modified <= ACTIVE_WINDOW_MILLIS,
            ),
        )
    }

    fun transcript(source: ExternalSessionSource): String {
        val lines = tailLines(source.file)
        val messages = lines.mapNotNull { line ->
            runCatching { parseMessage(source.wire.agentId, BridgeJson.parseToJsonElement(line).jsonObject) }
                .getOrNull()
        }.takeLast(MAX_MESSAGES)
        return messages.joinToString("\n\n") { (role, text) ->
            "${if (role == "user") "YOU" else "AGENT"}\n$text"
        }.takeLast(MAX_TRANSCRIPT_CHARS)
    }

    private fun scanCodex(limit: Int): List<ExternalSessionSource> =
        recentJsonl(userHome.resolve(".codex").resolve("sessions"), limit).mapNotNull { file ->
            val meta = firstObjects(file, 12).firstOrNull { it.string("type") == "session_meta" }
                ?: return@mapNotNull null
            val payload = meta["payload"] as? JsonObject ?: return@mapNotNull null
            val id = payload.string("id") ?: payload.string("session_id") ?: idFromCodexFilename(file)
            val cwd = payload.string("cwd").orEmpty()
            val modified = Files.getLastModifiedTime(file).toMillis()
            ExternalSessionSource(
                WireExternalSession(
                    id = id,
                    agentId = "codex",
                    title = title("Codex", cwd, id),
                    workingDirectory = cwd,
                    updatedAtMillis = modified,
                    active = clockMillis() - modified <= ACTIVE_WINDOW_MILLIS,
                ),
                file,
            )
        }

    private fun scanClaude(limit: Int): List<ExternalSessionSource> =
        recentJsonl(userHome.resolve(".claude").resolve("projects"), limit * 3)
            .filterNot { it.toString().contains("${java.io.File.separator}subagents${java.io.File.separator}") }
            .take(limit)
            .mapNotNull { file ->
                val objects = firstObjects(file, 30)
                val message = objects.firstOrNull { it.string("sessionId") != null && it.string("cwd") != null }
                    ?: return@mapNotNull null
                val id = message.string("sessionId") ?: file.nameWithoutExtension
                val cwd = message.string("cwd").orEmpty()
                val modified = Files.getLastModifiedTime(file).toMillis()
                ExternalSessionSource(
                    WireExternalSession(
                        id = id,
                        agentId = "claude",
                        title = title("Claude", cwd, id),
                        workingDirectory = cwd,
                        updatedAtMillis = modified,
                        active = clockMillis() - modified <= ACTIVE_WINDOW_MILLIS,
                    ),
                    file,
                )
            }

    private fun recentJsonl(root: Path, limit: Int): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension.equals("jsonl", ignoreCase = true) }
                .sorted(compareByDescending<Path> { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0) })
                .limit(limit.toLong())
                .toList()
        }
    }

    private fun firstObjects(file: Path, limit: Int): List<JsonObject> = runCatching {
        Files.newBufferedReader(file).useLines { lines ->
            lines.take(limit).mapNotNull { line ->
                runCatching { BridgeJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
            }.toList()
        }
    }.getOrDefault(emptyList())

    private fun tailLines(file: Path): List<String> = runCatching {
        RandomAccessFile(file.toFile(), "r").use { input ->
            val length = input.length()
            val start = (length - MAX_TAIL_BYTES).coerceAtLeast(0)
            input.seek(start)
            val bytes = ByteArray((length - start).toInt())
            input.readFully(bytes)
            val decoded = String(bytes, StandardCharsets.UTF_8)
            val complete = if (start == 0L) decoded else decoded.substringAfter('\n', "")
            complete.lineSequence().filter(String::isNotBlank).toList()
        }
    }.getOrDefault(emptyList())

    private fun parseMessage(agentId: String, value: JsonObject): Pair<String, String>? = when (agentId) {
        "codex" -> {
            if (value.string("type") != "response_item") return null
            val payload = value["payload"] as? JsonObject ?: return null
            if (payload.string("type") != "message") return null
            val role = payload.string("role") ?: return null
            if (role != "user" && role != "assistant") return null
            role to extractText(payload["content"])
        }
        "claude" -> {
            val role = value.string("type") ?: return null
            if (role != "user" && role != "assistant") return null
            val message = value["message"] as? JsonObject ?: return null
            role to extractText(message["content"])
        }
        else -> null
    }?.takeIf { it.second.isNotBlank() }

    private fun extractText(element: JsonElement?): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> item.string("text")
                else -> null
            }
        }.joinToString("\n")
        is JsonObject -> element.string("text").orEmpty()
        else -> ""
    }.trim()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun idFromCodexFilename(file: Path): String =
        file.nameWithoutExtension.substringAfterLast('-', file.nameWithoutExtension)

    private fun title(agent: String, cwd: String, id: String): String {
        val project = runCatching { Path.of(cwd).fileName?.toString() }.getOrNull()
        return "$agent · ${project?.takeIf(String::isNotBlank) ?: id.take(8)}"
    }

    private companion object {
        // A quiet tool call may not append to the history for several seconds. Keep resume
        // disabled until the source terminal has been quiet for a full minute.
        const val ACTIVE_WINDOW_MILLIS = 60_000L
        const val MAX_TAIL_BYTES = 512 * 1024L
        const val MAX_TRANSCRIPT_CHARS = 120_000
        const val MAX_MESSAGES = 80
    }
}
