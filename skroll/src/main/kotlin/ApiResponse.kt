// Models.kt
package io.github.takahirom.skroll

/**
 * Represents the raw response from an API call made via curl.
 *
 * @property statusCode The HTTP status code of the response.
 * @property body The body of the response as a String.
 * @property headers A map of header names to their values. May be empty if headers are not captured.
 * @property errorOutput Any error output from the curl command execution itself.
 */
data class ApiResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
    val errorOutput: String? = null
)

/**
 * Represents a test fixture, containing key-value data to be used in curl commands.
 *
 * @property name A descriptive name for the fixture (optional).
 * @property data A map of placeholder keys to their corresponding values.
 *             Example: mapOf("API_KEY" to "your_actual_key", "SYSTEM_PROMPT" to "You are helpful.")
 */
data class Fixture(
    val name: String? = null,
    val data: Map<String, String>
)

/**
 * Represents a single test case based on a curl command.
 *
 * @property name A descriptive name for this test case.
 * @property curlCommandTemplate The curl command string, possibly with placeholders like {PLACEHOLDER}.
 *                               This will be processed with fixture data.
 * @property assertionBlock A lambda function that takes an [ApiResponse] and performs assertions.
 */
data class CurlTestCase(
    val name: String,
    private val curlCommandTemplate: String, // Can be loaded from file or direct string
    private val assertionBlock: (ApiResponse) -> Unit
) {
    fun assertResponse(response: ApiResponse) {
        assertionBlock(response)
    }
}
