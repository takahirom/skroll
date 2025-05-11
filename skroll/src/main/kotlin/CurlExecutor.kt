package io.github.takahirom.skroll

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.sequences.forEach

// File: io/github/takahirom/skroll/CurlExecutor.kt

/**
 * Interface for executing a curl command.
 * This can have different implementations (e.g., actual process execution, fake/mock executor).
 */
interface CurlExecutor {
    /**
     * Executes the given curl command.
     * @param command The fully resolved curl command string to execute.
     * @param options Options for executing the curl command.
     * @return The ApiResponse from the command execution.
     * @throws Exception if the command execution fails at a low level.
     */
    fun execute(command: String, options: CurlExecutionOptions): ApiResponse
}

/**
 * A dummy implementation of CurlExecutor for demonstration and testing purposes.
 * In a real scenario, this would use ProcessBuilder or a similar mechanism to run curl.
 */
class DummyCurlExecutor : CurlExecutor {
    override fun execute(command: String, options: CurlExecutionOptions): ApiResponse {
        println("  [DummyCurlExecutor] Executing: $command (Timeout: ${options.timeout}s, Redirects: ${options.followRedirects}, Insecure: ${options.insecure})")
        // Simulate a successful API call
        val simulatedStatusCode = if (command.contains("error_case")) 400 else 200
        val simulatedBody = if (simulatedStatusCode == 200) {
            when {
                command.lowercase().contains("france") -> "{\"answer\":\"Paris is the capital!\", \"source\":\"knowledge_base\"}"
                command.lowercase().contains("17") -> "{\"answer\":\"The answer is 42.\", \"certainty\":0.99}"
                command.lowercase().contains("joke") -> "{\"joke\":\"Why did the scarecrow win an award? Because he was outstanding in his field!\", \"type\":\"pun\"}"
                else -> "{\"message\":\"Dummy success response for command: ${command.take(50)}...\"}"
            }
        } else {
            "{\"error\":\"Simulated error for command: ${command.take(50)}...\"}"
        }
        return ApiResponse(
            statusCode = simulatedStatusCode,
            body = simulatedBody,
            headers = mapOf("Content-Type" to listOf("application/json"), "X-Executed-By" to listOf("DummyCurlExecutor"))
        )
    }
}

object DefaultCurlExecutor : CurlExecutor {

    const val DEFAULT_TIMEOUT_SECONDS = 30L

    /**
     * Executes a given curl command string.
     * This version attempts to capture status code, headers, and body.
     * It uses `curl -s -i -w "\n%{http_code}"` to get all info in stdout.
     *
     * @param command The complete curl command string to execute.
     * @param timeoutSeconds Timeout for the command execution.
     * @return An [ApiResponse] containing the status code, body, and headers.
     */
    override fun execute(command: String, options: CurlExecutionOptions): ApiResponse {
        // We add options to curl to get status code and headers along with the body.
        // -s: silent mode
        // -i: include HTTP response headers in the output
        // -w "\nHTTP_STATUS_CODE:%{http_code}": write out the HTTP status code after a newline and a specific marker.
        // The marker helps in parsing. We add the original command to this.
        // Splitting the command carefully to handle spaces in arguments (e.g., within headers or data).
        // A more robust solution might involve parsing the command into a list of arguments.
        // For simplicity, assuming `sh -c` can handle the full command string.
        // This is common for executing complex shell commands.

        val commandParts = listOf("sh", "-c", "$command -s -i -w \"\\nCURL_CUSTOM_HTTP_STATUS_CODE:%{http_code}\"")

        val processBuilder = ProcessBuilder(commandParts)
        val process = processBuilder.start()

        val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

        val outputLines = mutableListOf<String>()
        stdoutReader.useLines { lines -> lines.forEach { outputLines.add(it) } }

        val errorOutput = stderrReader.use { it.readText() }

        val exited = process.waitFor(options.timeout.inWholeSeconds, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            return ApiResponse(
                -1,
                "",
                emptyMap(),
            )
        }

        if (process.exitValue() != 0 && errorOutput.isNotBlank()) {
            // Non-zero exit code from curl itself, likely an issue with the command or network
            // Return early with error if curl command itself failed (not HTTP error)
            return ApiResponse(
                statusCode = -1, // Indicate curl execution failure rather than HTTP status
                body = outputLines.joinToString("\n"),
                headers = emptyMap(),
            )
        }

        return parseCurlOutput(outputLines, errorOutput)
    }

    /**
     * Parses the combined output from curl (-i -w "...") to extract headers, body, and status code.
     */
    private fun parseCurlOutput(outputLines: List<String>, stderr: String?): ApiResponse {
        var statusCode = -1
        val headers = mutableMapOf<String, MutableList<String>>()
        val bodyLines = mutableListOf<String>()
        var parsingHeaders = true

        // Find the status code marker first
        val statusLineIndex = outputLines.indexOfLast { it.startsWith("CURL_CUSTOM_HTTP_STATUS_CODE:") }
        if (statusLineIndex != -1) {
            statusCode =
                outputLines[statusLineIndex].substringAfter("CURL_CUSTOM_HTTP_STATUS_CODE:").trim().toIntOrNull() ?: -1
        }

        val contentLines = if (statusLineIndex != -1) outputLines.subList(0, statusLineIndex) else outputLines

        // HTTP Status line (e.g., HTTP/1.1 200 OK) is the first line of headers if present
        if (contentLines.isNotEmpty() && contentLines[0].matches(Regex("^HTTP/[\\d.]+ \\d+ .*"))) {
            // statusCode from HTTP line can be a fallback or cross-check
            if (statusCode == -1) { // If not found from -w option
                statusCode = contentLines[0].split(" ")[1].toIntOrNull() ?: -1
            }
        }


        for (line in contentLines) {
            if (parsingHeaders) {
                if (line.isBlank()) { // Blank line separates headers from body
                    parsingHeaders = false
                    continue
                }
                if (line.matches(Regex("^HTTP/[\\d.]+ \\d+ .*"))) { // Skip the HTTP status line itself
                    continue
                }
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    val headerName = parts[0].trim()
                    val headerValue = parts[1].trim()
                    headers.computeIfAbsent(headerName) { mutableListOf() }.add(headerValue)
                } else {
                    // Handle malformed headers or log them if necessary
                }
            } else {
                bodyLines.add(line)
            }
        }

        // If status code wasn't found via -w (e.g. curl couldn't connect), but we got an HTTP status line
        if (statusCode == -1 && contentLines.isNotEmpty() && contentLines[0].matches(Regex("^HTTP/[\\d.]+ \\d+ .*"))) {
            statusCode = contentLines[0].split(" ")[1].toIntOrNull() ?: -1
        }


        return ApiResponse(statusCode, bodyLines.joinToString("\n"), headers)
    }
}
