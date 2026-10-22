package dev.agentbridge.host

import dev.agentbridge.spi.AgentInstallation
import dev.agentbridge.spi.AgentAdapter
import dev.agentbridge.spi.HarnessProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

fun interface CommandRunner {
    suspend fun run(executable: String, arguments: List<String>): CommandResult
}

class ProcessCommandRunner(
    private val timeout: Duration = Duration.ofSeconds(10),
    private val outputLimit: Int = 256 * 1024,
) : CommandRunner {
    init {
        require(!timeout.isNegative && !timeout.isZero)
        require(outputLimit > 0)
    }

    override suspend fun run(executable: String, arguments: List<String>): CommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(executable) + arguments).start()
        val readers = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "agent-bridge-probe-output").apply { isDaemon = true }
        }
        val stdout = readers.submit<String> { process.inputStream.readBounded(outputLimit) }
        val stderr = readers.submit<String> { process.errorStream.readBounded(outputLimit) }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
                error("CLI probe timed out after ${timeout.seconds}s")
            }
            CommandResult(process.exitValue(), stdout.get(1, TimeUnit.SECONDS), stderr.get(1, TimeUnit.SECONDS))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            readers.shutdownNow()
        }
    }

    private fun java.io.InputStream.readBounded(limit: Int): String {
        val buffer = ByteArray(4096)
        val output = java.io.ByteArrayOutputStream()
        use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= limit) { "CLI probe output exceeded $limit bytes" }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8)
    }
}

fun interface ExecutableResolver {
    fun resolve(candidates: List<String>): String?
}

class PathExecutableResolver(
    path: String? = System.getenv("PATH"),
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    userHome: Path = Path.of(System.getProperty("user.home")),
) : ExecutableResolver {
    private val roots: List<Path> = buildList {
        path.orEmpty().split(java.io.File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .mapTo(this) { Path.of(it.trim('"')) }
        if (windows) {
            add(userHome.resolve("AppData/Roaming/npm"))
            add(userHome.resolve("AppData/Local/Programs/OpenAI/Codex/bin"))
        } else {
            add(userHome.resolve(".local/bin"))
        }
    }.distinct()

    override fun resolve(candidates: List<String>): String? {
        for (root in roots) {
            for (candidate in candidates) {
                val candidatePath = Path.of(candidate)
                if (candidatePath.isAbsolute || candidatePath.any { it.toString() == ".." }) continue
                for (variant in variants(candidate)) {
                    val resolved = root.resolve(variant).normalize()
                    if (
                        resolved.startsWith(root.normalize()) &&
                        Files.isRegularFile(resolved) &&
                        (!windows || resolved.toString().endsWith(".exe", true))
                    ) {
                        return resolved.toAbsolutePath().toString()
                    }
                }
            }
        }
        return null
    }

    private fun variants(candidate: String): List<String> = if (windows && !candidate.endsWith(".exe", true)) {
        listOf("$candidate.exe", candidate)
    } else {
        listOf(candidate)
    }
}

sealed interface HarnessDiscovery {
    val agentId: String
    val displayName: String

    data class Available(val installation: AgentInstallation) : HarnessDiscovery {
        override val agentId = installation.agentId
        override val displayName = installation.displayName
    }

    data class Unavailable(
        override val agentId: String,
        override val displayName: String,
        val reason: String,
    ) : HarnessDiscovery
}

class HarnessRegistry(
    private val providers: List<HarnessProvider>,
    private val resolver: ExecutableResolver = PathExecutableResolver(),
) {
    init {
        require(providers.isNotEmpty())
        require(providers.map { it.agentId }.distinct().size == providers.size) { "Duplicate agent provider" }
    }

    suspend fun discover(): List<HarnessDiscovery> = coroutineScope {
        providers.map { provider ->
            async {
                val executable = resolver.resolve(provider.executableNames)
                    ?: return@async HarnessDiscovery.Unavailable(
                        provider.agentId,
                        provider.displayName,
                        "CLI not found",
                    )
                runCatching { provider.probe(executable) }
                    .fold(
                        onSuccess = { HarnessDiscovery.Available(it) },
                        onFailure = {
                            HarnessDiscovery.Unavailable(
                                provider.agentId,
                                provider.displayName,
                                it.message?.take(200) ?: "Probe failed",
                            )
                        },
                    )
            }
        }.awaitAll()
    }

    /** Creates the normalized app-facing adapter without exposing a CLI command to the app. */
    fun createAdapter(installation: AgentInstallation): AgentAdapter {
        val provider = providers.singleOrNull { it.agentId == installation.agentId }
            ?: error("No provider for ${installation.agentId}")
        return provider.createAdapter(installation)
    }
}
