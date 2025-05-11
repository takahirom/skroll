// File: io/github/takahirom/skroll/SkrollData.kt
package io.github.takahirom.skroll

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Represents a simple key-value parameter.
 * @property key The name of the parameter, used as a placeholder in command templates (e.g., "{key}").
 * @property value The string value of the parameter.
 */
data class Parameter(val key: String, val value: String)

/**
 * Represents the response from an API call (e.g., a curl command execution).
 * @property statusCode The HTTP status code of the response.
 * @property body The body of the response as a string.
 * @property headers The headers of the response.
 */
data class ApiResponse(
  val statusCode: Int,
  val body: String,
  val headers: Map<String, List<String>> = emptyMap()
)

/**
 * Represents the output of a metrics evaluation for a skroll execution.
 * @property primaryScore The main score indicating the performance or success (e.g., 0.0 to 1.0).
 * @property details A map kematian additional information, sub-metrics, or debug data.
 *                 Values can be of any type (String, Double, Boolean, etc.).
 */
data class EvaluationOutput(
  val primaryScore: Double,
  val details: Map<String, Any> = emptyMap()
)

/**
 * Configuration options for executing a curl command.
 * @property timeout Timeout for the curl command in seconds.
 * @property followRedirects Whether curl should follow redirects (-L option).
 * @property insecure Whether curl should allow insecure server connections (-k option).
 */
data class CurlExecutionOptions(
  val timeout: Duration = 30.seconds,
  val followRedirects: Boolean = true,
  val insecure: Boolean = false
  // Add other relevant curl options here
)

/**
 * Configuration for the prompt optimization process.
 * @property maxIterations The maximum number of iterations the optimizer should run.
 * @property trainTags Tags to identify ParameterSets used for training/optimizing the prompt. (If ParameterSet tagging is used)
 * @property devTags Tags to identify ParameterSets used for validating prompts during optimization. (If ParameterSet tagging is used)
 */
data class OptimizationConfig(
  val maxIterations: Int = 10
  // Add other optimization-specific configurations like learning rate, specific strategy params, etc.
)

/**
 * Represents the result of a prompt optimization process.
 * @property optimizedParameterKey The name of the parameter that was optimized (e.g., "COMMON_SYSTEM_PROMPT").
 * @property bestValue The best value found for the optimized parameter (e.g., the best prompt string).
 * @property bestScore The highest aggregated score achieved with the bestValue.
 * @property history A list of pairs makanan (attemptedValue, score) representing the optimization journey.
 */
data class PromptOptimizationResult(
  val optimizedParameterKey: String,
  val bestValue: String,
  val bestScore: Double,
  val history: List<Pair<String, Double>> = emptyList()
)

/**
 * Represents the result of a single skroll execution (a SkrollDefinition).
 * @property definitionName The name of the SkrollDefinition that was executed, if provided.
 * @property evaluation The output from the metrics function for this execution. Null if metrics failed or execution errored before metrics.
 * @property apiResponse The ApiResponse received. Null if the command execution itself failed.
 * @property error Any throwable caught during the execution or metrics evaluation. Null if successful.
 */
data class SkrollRunResult(
  val definitionName: String?,
  val evaluation: EvaluationOutput?,
  val apiResponse: ApiResponse?,
  val error: Throwable? = null
) {
  /**
   * Helper function to determine if the execution is considered successful based on the primary score.
   * @param threshold The minimum primaryScore to be considered successful.
   * @return True if the primaryScore meets the threshold and no error occurred, false otherwise.
   */
  fun isSuccessful(threshold: Double = 0.8): Boolean {
    return error == null && evaluation?.primaryScore?.let { it >= threshold } ?: false
  }
}
