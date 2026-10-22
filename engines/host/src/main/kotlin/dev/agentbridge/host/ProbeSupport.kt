package dev.agentbridge.host

suspend fun CommandRunner.requireSuccess(
    executable: String,
    arguments: List<String>,
    description: String,
): String {
    val result = run(executable, arguments)
    check(result.exitCode == 0) {
        "$description failed (${result.exitCode}): ${(result.stderr.ifBlank { result.stdout }).trim().take(200)}"
    }
    return (result.stdout.ifBlank { result.stderr }).trim()
}

fun firstVersionLine(output: String): String = output.lineSequence()
    .map(String::trim)
    .firstOrNull(String::isNotBlank)
    ?.take(120)
    ?: error("CLI returned an empty version")
