## Skroll: Test and Optimize Your APIs with Curl Fidelity

**Skroll** is a Kotlin-based library designed for defining, executing, and evaluating tests against APIs, particularly those involving Large Language Models (LLMs). It emphasizes `curl` command fidelity to ensure your tests accurately reflect real-world API interactions.

## The Challenge: API Brittleness and Testing Fidelity

When interacting with complex APIs, especially LLMs where prompt engineering is key, ensuring consistent behavior and preventing regressions can be challenging. Prompts or API request structures might evolve, leading to unexpected changes in responses. Traditional testing methods might not always capture the exact nuances of the API calls your application makes, including specific headers or intricate JSON payloads. This discrepancy can reduce confidence in test results.

## The Skroll Solution: Curl-Centric Testing with Rich Metrics

Skroll addresses these challenges by:

1.  **Defining Tests with `curl` Templates**: You define your API interactions using `curl` command templates. This ensures high fidelity between your tests and the actual requests your application would send.
2.  **Evaluating with Rich Metrics**: Instead of simple pass/fail assertions, Skroll allows you to define custom `metrics` functions that return an `EvaluationOutput`. This object includes a `primaryScore` (a `Double`) and a `details` map (`Map`), enabling nuanced and multi-faceted evaluation of API responses.

This approach provides a robust way to test API behavior, catch regressions early, and iterate on designs (like LLM prompts) with greater confidence.

## Core Concepts & DSL

Skroll provides an intuitive Kotlin-based DSL to define your tests:

*   **`skrollSet { ... }`**: The top-level block to group related test definitions (`skroll`s). You can provide an optional `description` for the set.
    *   **`defaultParameters { ... }`**: Within a `skrollSet`, you can define a list of common `Parameter`s (e.g., base URLs, API tokens, default prompts). These are available to all `skroll` definitions within the set.
        ```kotlin
        defaultParameters {
            listOf(
                Parameter("BASE_URL", "https://api.example.com"),
                Parameter("API_TOKEN", "your_secret_key")
            )
        }
        ```

*   **`skroll("Test Case Name") { ... }`**: Defines an individual test case (a `Skroll` definition).
    *   **`commandTemplate = "..."`**: The `curl` command string with placeholders (e.g., `{API_TOKEN}`, `{USER_INPUT}`). Placeholders are resolved using parameters.
        ```kotlin
        commandTemplate = "curl {BASE_URL}/chat -d '{\"query\":\"{USER_INPUT}\"}'"
        ```
    *   **`parameters { ... }`**: (Optional) Define a list of `Parameter`s specific to this `skroll` definition. These can override or add to the `defaultParameters` from the parent `SkrollSet`.
        ```kotlin
        parameters {
            listOf(Parameter("USER_INPUT", "Hello there!"))
        }
        ```
    *   **`metrics { apiResponse -> ... }`**: A lambda function that takes an `ApiResponse` (containing `statusCode`, `body`, `headers`) and returns an `EvaluationOutput`. This is where you define your custom logic to score the response and extract relevant details.
        ```kotlin
        metrics { response ->
            val score = if (response.body.contains("expected_text")) 1.0 else 0.0
            EvaluationOutput(score, mapOf("body_preview" to response.body.take(100)))
        }
        ```
    *   **`curlOptions { ... }`**: (Optional) Configure `CurlExecutionOptions` for this specific skroll, such as `timeout`, `followRedirects`, and `insecure` (for allowing insecure server connections, like `curl -k`).
        ```kotlin
        curlOptions {
            timeout = 60.seconds // kotlin.time.Duration
            followRedirects = false
            insecure = true
        }
        ```

## Execution

To run your defined skrolls, you use the `executeAllWith()` extension function on a `SkrollSet` instance.

```kotlin
val mySkrollSet = skrollSet("My API Tests") { /* ... definitions ... */ }

// Execute all skrolls
val results: List = mySkrollSet.executeAllWith()
// For custom execution (e.g., with a dummy executor for local tests):
// val customExecutor = SkrollSetExecutor(curlExecutor = MyDummyCurlExecutor())
// val results: List = mySkrollSet.executeAllWith(customExecutor)

results.forEach { result ->
    println("Skroll: ${result.definitionName}, Score: ${result.evaluation?.primaryScore}, Success: ${result.isSuccessful(0.9)}")
    if (result.error != null) {
        println("  Error: ${result.error.message}")
    }
    result.evaluation?.details?.forEach { (key, value) ->
        println("  Detail - $key: $value")
    }
}
```

*   `executeAllWith()` returns a list of `SkrollRunResult` objects.
*   Each `SkrollRunResult` contains the `definitionName`, the `evaluation` output (`EvaluationOutput?`), the raw `apiResponse` (`ApiResponse?`), and any `error` (`Throwable?`) that occurred.
*   The `isSuccessful(threshold: Double)` helper function on `SkrollRunResult` can be used to quickly check if a skroll met a certain score threshold and had no errors.
*   By default, `executeAllWith()` uses a `SkrollSetExecutor` configured with `DefaultCurlExecutor` (which runs actual curl commands) and `SimpleTemplateResolver`. You can provide your own `SkrollSetExecutor` instance if you need custom `CurlExecutor` or `TemplateResolver` implementations.

## Parameter Optimization

Skroll supports optimizing a default parameter within a `SkrollSet` to find a value that maximizes an aggregated score across all skrolls. This is particularly useful for tuning prompts for LLMs.

Use the `optimizeDefaultParameterWith()` extension function:

```kotlin
val optimizationResult: PromptOptimizationResult = mySkrollSet.optimizeDefaultParameterWith(
    parameterKeyToOptimize = "COMMON_SYSTEM_PROMPT",
    initialValue = "You are a helpful assistant.",
    optimizationConfig = OptimizationConfig(maxIterations = 5)
    // Optionally provide custom evaluator, optimizer, or skrollSetExecutor
)

println("Optimization Complete:")
println("  Best Prompt: \"${optimizationResult.bestValue}\"")
println("  Best Score: ${optimizationResult.bestScore}")
```

*   **`parameterKeyToOptimize`**: The key of the `defaultParameters` entry to optimize.
*   **`initialValue`**: The starting value for the parameter.
*   **`evaluator`**: A `SkrollSetEvaluator` (defaults to `AveragePrimaryScoreEvaluator`) that defines how to calculate an aggregate score from a list of `SkrollRunResult`s.
*   **`optimizer`**: A `ParameterOptimizer` (defaults to `SimpleParameterOptimizer`) that implements the strategy for generating and testing new parameter values.
*   **`skrollSetExecutor`**: The executor to run skrolls during each optimization iteration.
*   **`optimizationConfig`**: Configuration like `maxIterations`.
*   The function returns a `PromptOptimizationResult` containing the `bestValue`, `bestScore`, and history.

## Why Skroll?

*   **High Fidelity**: `curl` templates ensure tests accurately mirror real-world API calls, vital for complex requests (e.g., intricate JSON payloads, function calling).
*   **Prevent Regressions**: Catch API behavior changes and prompt-related issues early in the development cycle.
*   **Developer-Friendly DSL**: An intuitive and expressive Kotlin-based DSL for defining test sets and individual test cases.
*   **Flexible & Rich Metrics**: Go beyond simple pass/fail. Define custom, multi-faceted evaluation logic to score responses based on various criteria and capture detailed output.
*   **Prompt Optimization**: Built-in support for systematically finding better-performing prompt variations.

## Usage Example

Here's a basic example of how to define and run a `SkrollSet`. This example uses a `DummyCurlExecutor` for self-contained execution without hitting a real API. In a real test, you'd typically use the default `DefaultCurlExecutor`.

```kotlin
import io.github.takahirom.skroll.* // Import necessary Skroll classes
import kotlin.time.Duration.Companion.seconds

// Dummy executor for this example
class DummyCurlExecutor : CurlExecutor {
    override fun execute(command: String, options: CurlExecutionOptions): ApiResponse {
        println("  [DummyCurlExecutor] Would execute: $command")
        val body = when {
            command.contains("Capital of France") -> "{\"answer\":\"Paris is the capital!\"}"
            command.contains("17+25") -> "{\"answer\":\"The sum is 42.\"}"
            else -> "{\"message\":\"Dummy response\"}"
        }
        return ApiResponse(statusCode = 200, body = body)
    }
}

fun main() {
    val faqSet = skrollSet("FAQ Bot Tests") {
        defaultParameters {
            listOf(
                Parameter("BASE_URL", "https://api.example.com/test-faq"),
                Parameter("COMMON_SYSTEM_PROMPT", "You are a helpful FAQ bot.")
            )
        }

        skroll("Capital City Question") {
            commandTemplate = """
                curl {BASE_URL}/query \
                -H "Content-Type: application/json" \
                -d '{"question":"Capital of France?", "system_prompt":"{COMMON_SYSTEM_PROMPT}"}'
            """.trimIndent()

            metrics { response ->
                val isCorrect = response.body.contains("Paris", ignoreCase = true)
                EvaluationOutput(
                    primaryScore = if (isCorrect) 1.0 else 0.1,
                    details = mapOf("body_preview" to response.body.take(50))
                )
            }
            curlOptions { timeout = 10.seconds }
        }

        skroll("Simple Math Question") {
            commandTemplate = "curl {BASE_URL}/calculate -d '{\"q\":\"17+25?\", \"prompt\":\"{COMMON_SYSTEM_PROMPT}\"}'"
            metrics { response ->
                EvaluationOutput(
                    primaryScore = if (response.body.contains("42")) 1.0 else 0.2,
                    details = mapOf("body_preview" to response.body.take(50))
                )
            }
        }
    }

    // Execute using the DummyCurlExecutor for this example
    // In a real test, you might just call faqSet.executeAllWith() to use the DefaultCurlExecutor
    val executor = SkrollSetExecutor(curlExecutor = DummyCurlExecutor())
    val results: List = faqSet.executeAllWith(executor)

    println("\n--- Test Results ---")
    results.forEach { result ->
        println("Test: ${result.definitionName}")
        println("  Score: ${result.evaluation?.primaryScore}")
        println("  Successful (>=0.9): ${result.isSuccessful(0.9)}")
        result.evaluation?.details?.forEach { (k, v) -> println("    $k: $v") }
        if (result.error != null) {
            println("  Error: ${result.error.localizedMessage}")
        }
        println("---")
    }

    // Example of checking results (e.g., in a JUnit test)
    // import com.google.common.truth.Truth.assertThat
    // assertThat(results.all { it.isSuccessful(0.9) }).isTrue()
}
```

This example demonstrates defining a `skrollSet` with default parameters, individual `skroll`s with command templates and metrics, and then executing them. You would typically integrate these executions into your preferred testing framework (like JUnit 5) and use assertion libraries (like Truth or AssertK) to verify the `SkrollRunResult`s.
