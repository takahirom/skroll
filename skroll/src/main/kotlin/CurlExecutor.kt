package io.github.takahirom.skroll

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Interface for executing a curl command.
 */
interface CurlExecutor {
    /**
     * Executes the given curl command.
     * @param command The fully resolved curl command string to execute.
     * @param options Options for executing the curl command.
     * @return The ApiResponse from the command execution.
     * @throws Exception if the command execution fails at a low level.
     */
    suspend fun execute(command: String, options: CurlExecutionOptions): ApiResponse
}

/**
 * Default implementation of [CurlExecutor] that executes curl commands
 * as system processes.
 * It attempts to capture status code, headers, and body from stdout.
 */
class DefaultCurlExecutor(private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO) : CurlExecutor {

    /**
     * Executes a given curl command string.
     * This version attempts to capture status code, headers, and body.
     * It uses `curl -s -i -w "\nCURL_CUSTOM_HTTP_STATUS_CODE:%{http_code}"` to get all info in stdout.
     *
     * @param command The complete curl command string to execute.
     * @param options Options for the command execution.
     * @return An [ApiResponse] containing the status code, body, and headers.
     */
    override suspend fun execute(command: String, options: CurlExecutionOptions): ApiResponse {
        return withContext(coroutineDispatcher) {
            executeCurlCommand(command, options)
        }
    }

    private fun executeCurlCommand(command: String, options: CurlExecutionOptions): ApiResponse {
        // Append curl options to get status code and headers along with the body.
        // -s: silent mode
        // -i: include HTTP response headers in the output
        // -w "\nCURL_CUSTOM_HTTP_STATUS_CODE:%{http_code}": write out HTTP status code after a newline and marker.
        // The marker helps in parsing.
        val fullCommandWithOptions = "$command -s -i -w \"\\nCURL_CUSTOM_HTTP_STATUS_CODE:%{http_code}\""
        val commandParts = listOf("sh", "-c", fullCommandWithOptions)

        val processBuilder = ProcessBuilder(commandParts)
        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            // Failed to start the process (e.g., curl not found)
            return ApiResponse(-1, "Failed to start curl process: ${e.message}", emptyMap())
        }


        val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

        val outputLines = mutableListOf<String>()
        // It's important to read stdout fully before stderr in some cases, or use separate threads.
        // For simplicity, reading stdout first.
        try {
            stdoutReader.useLines { lines -> lines.forEach { outputLines.add(it) } }
        } catch (e: Exception) {
            // Error reading stdout
            // This block might be too late if waitFor already timed out.
        }

        val errorOutput = try {
            stderrReader.use { it.readText() }
        } catch (e: Exception) {
            "" // Error reading stderr
        }


        val exited = try {
            process.waitFor(options.timeout.inWholeSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt() // Restore interruption status
            process.destroyForcibly()
            return ApiResponse(-1, "Curl command timed out (interrupted) after ${options.timeout.inWholeSeconds}s. Stderr: $errorOutput", emptyMap())
        }


        if (!exited) {
            process.destroyForcibly()
            return ApiResponse(-1, "Curl command timed out after ${options.timeout.inWholeSeconds}s. Stderr: $errorOutput", emptyMap())
        }

        // Check curl's own exit code. A non-zero exit code often indicates a curl-specific error
        // (e.g., network issue before HTTP transaction, malformed URL, option error).
        if (process.exitValue() != 0) {
            // Even if curl fails, it might have printed something to stdout (like headers if -i was processed before error)
            // or an error message. We still try to parse what we got.
            // The status code from parseCurlOutput might still be -1 if no HTTP transaction occurred.
            val partiallyParsedResponse = parseCurlOutput(outputLines)
            return ApiResponse(
                // If parseCurlOutput found an HTTP status, use it, otherwise indicate curl error with -1 or similar.
                statusCode = if (partiallyParsedResponse.statusCode != -1) partiallyParsedResponse.statusCode else -process.exitValue(), // Or a specific negative code
                body = if (partiallyParsedResponse.body.isNotBlank()) partiallyParsedResponse.body else errorOutput, // Prioritize error output if body is empty
                headers = partiallyParsedResponse.headers
            )
        }
        return parseCurlOutput(outputLines)
    }

    /**
     * Parses the combined output from curl (-i -w "...") to extract headers, body, and status code.
     */
    private fun parseCurlOutput(outputLines: List<String>): ApiResponse {
        var statusCode = -1
        val headers = mutableMapOf<String, MutableList<String>>()
        val bodyLines = mutableListOf<String>()
        var parsingHeaders = true
        var httpStatusLineProcessed = false

        // Find the status code marker from -w option (most reliable for HTTP status)
        val statusMarkerLineIndex = outputLines.indexOfLast { it.startsWith("CURL_CUSTOM_HTTP_STATUS_CODE:") }
        if (statusMarkerLineIndex != -1) {
            statusCode = outputLines[statusMarkerLineIndex].substringAfter("CURL_CUSTOM_HTTP_STATUS_CODE:").trim().toIntOrNull() ?: -1
        }

        // Determine the lines that contain headers and body (excluding the status marker line)
        val contentLines = if (statusMarkerLineIndex != -1) outputLines.subList(0, statusMarkerLineIndex) else outputLines

        for (line in contentLines) {
            if (parsingHeaders) {
                // Check for HTTP status line (e.g., "HTTP/1.1 200 OK")
                // This might appear multiple times if there are redirects and -L is used,
                // curl -i shows headers for all responses in a redirect chain.
                // We are interested in the *last* set of headers and body.
                // This simple parser takes the first set. A more robust one would handle multiple sets.
                if (line.matches(Regex("^HTTP/[\\d.]+ \\d+ .*$"))) {
                    if (!httpStatusLineProcessed || statusCode == -1) { // Prioritize -w, but take first HTTP status if -w fails
                        val parts = line.split(" ", limit = 3)
                        if (parts.size > 1) {
                            if(statusCode == -1) statusCode = parts[1].toIntOrNull() ?: -1
                        }
                    }
                    httpStatusLineProcessed = true
                    continue // Skip the HTTP status line from header processing
                }

                if (line.isBlank()) { // Blank line separates headers from body
                    if(httpStatusLineProcessed){ // only switch to body if we've actually seen HTTP headers
                        parsingHeaders = false
                    } else {
                        // This might be a blank line before any headers (e.g. if curl failed early)
                        // or part of a multi-part response. For simplicity, assume first blank line after headers is separator.
                    }
                    continue
                }
                // Parse header lines
                val headerParts = line.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val headerName = headerParts[0].trim()
                    val headerValue = headerParts[1].trim()
                    headers.computeIfAbsent(headerName.lowercase()) { mutableListOf() }.add(headerValue) // Store lowercase for consistency
                } else {
                    // Potentially malformed header or continuation line, ignore for simplicity
                }
            } else {
                // Once headers are done, the rest is body
                bodyLines.add(line)
            }
        }
        return ApiResponse(statusCode, bodyLines.joinToString("\n"), headers.toMap())
    }
}
