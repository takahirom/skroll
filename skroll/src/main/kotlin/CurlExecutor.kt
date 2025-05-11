// CurlExecutor.kt
package io.github.takahirom.skroll

import io.github.takahirom.skroll.DefaultCurlExecutor.DEFAULT_TIMEOUT_SECONDS
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

interface CurlExecutor {
  fun execute(fullCurlCommand: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): ApiResponse
}

object FakeCurlExecutor : CurlExecutor {
  override fun execute(fullCurlCommand: String, timeoutSeconds: Long): ApiResponse {
    // Simulate a successful curl execution with a dummy response
    return ApiResponse(
      statusCode = 200,
      body = JsonObject(
        mapOf(
          "choices" to JsonObject(
            mapOf(
              "message" to JsonObject(
                mapOf(
                  "role" to JsonPrimitive("assistant"),
                  "content" to JsonPrimitive("OK. Here is a joke: Why don't scientists trust atoms? Because they make up everything!"),
                )
              )
            )
          )
        )
      ).toString(),
      headers = mapOf("Content-Type" to "application/json"),
      errorOutput = null
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
   * @param fullCurlCommand The complete curl command string to execute.
   * @param timeoutSeconds Timeout for the command execution.
   * @return An [ApiResponse] containing the status code, body, and headers.
   */
  override fun execute(fullCurlCommand: String, timeoutSeconds: Long): ApiResponse {
    // We add options to curl to get status code and headers along with the body.
    // -s: silent mode
    // -i: include HTTP response headers in the output
    // -w "\nHTTP_STATUS_CODE:%{http_code}": write out the HTTP status code after a newline and a specific marker.
    // The marker helps in parsing. We add the original command to this.
    // Splitting the command carefully to handle spaces in arguments (e.g., within headers or data).
    // A more robust solution might involve parsing the command into a list of arguments.
    // For simplicity, assuming `sh -c` can handle the full command string.
    // This is common for executing complex shell commands.

    val commandParts = listOf("sh", "-c", "$fullCurlCommand -s -i -w \"\\nCURL_CUSTOM_HTTP_STATUS_CODE:%{http_code}\"")

    val processBuilder = ProcessBuilder(commandParts)
    val process = processBuilder.start()

    val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
    val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

    val outputLines = mutableListOf<String>()
    stdoutReader.useLines { lines -> lines.forEach { outputLines.add(it) } }

    val errorOutput = stderrReader.use { it.readText() }

    val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!exited) {
      process.destroyForcibly()
      return ApiResponse(
        -1,
        "",
        emptyMap(),
        "Curl command timed out after $timeoutSeconds seconds.\nError output: $errorOutput"
      )
    }

    if (process.exitValue() != 0 && errorOutput.isNotBlank()) {
      // Non-zero exit code from curl itself, likely an issue with the command or network
      // Return early with error if curl command itself failed (not HTTP error)
      return ApiResponse(
        statusCode = -1, // Indicate curl execution failure rather than HTTP status
        body = outputLines.joinToString("\n"),
        headers = emptyMap(),
        errorOutput = "Curl command failed with exit code ${process.exitValue()}.\nSTDERR: $errorOutput"
      )
    }

    return parseCurlOutput(outputLines, errorOutput)
  }

  /**
   * Parses the combined output from curl (-i -w "...") to extract headers, body, and status code.
   */
  private fun parseCurlOutput(outputLines: List<String>, stderr: String?): ApiResponse {
    var statusCode = -1
    val headers = mutableMapOf<String, String>()
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
          headers[parts[0].trim()] = parts[1].trim()
        }
      } else {
        bodyLines.add(line)
      }
    }

    // If status code wasn't found via -w (e.g. curl couldn't connect), but we got an HTTP status line
    if (statusCode == -1 && contentLines.isNotEmpty() && contentLines[0].matches(Regex("^HTTP/[\\d.]+ \\d+ .*"))) {
      statusCode = contentLines[0].split(" ")[1].toIntOrNull() ?: -1
    }


    return ApiResponse(statusCode, bodyLines.joinToString("\n"), headers, stderr)
  }
}
