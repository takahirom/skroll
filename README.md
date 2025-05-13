# Skroll: Test and Optimize Your API/LLM Calls with Curl Fidelity

**Skroll** is a Kotlin-based library for defining, executing, and evaluating tests against APIs, particularly those involving Large Language Models (LLMs). It emphasizes `curl` command fidelity to ensure your tests accurately reflect real-world API interactions, helping you prevent regressions and optimize parameters effectively.

![banner](https://github.com/user-attachments/assets/58c70af7-ad7c-4c03-80d0-202563474ea2)

## The Problem: Ensuring API & LLM Reliability and Instruction Fidelity

Interacting with APIs, and particularly with Large Language Models (LLMs), presents unique and evolving challenges that can undermine reliability:

*   **General API Brittleness**: All APIs can be sensitive. Small, undocumented changes in request structure or unexpected API behavior can lead to silent failures or incorrect data, which are hard to catch without dedicated tests.
*   **LLM Prompt Complexity and "Instruction Creep"**:
    *   As you refine LLM interactions, prompts often grow in complexity. You might add multiple "do not do X," "always format Y this way," or "ensure Z" instructions.
    *   LLMs can struggle to maintain context and adhere to all constraints in very long or overly detailed prompts. This can lead to the model "forgetting" or ignoring earlier instructions, especially negative constraints (e.g., "do not speak as the user").
    *   The sheer volume of information or the number of distinct instructions can dilute the model's focus, causing it to overlook critical parts of your prompt.
*   **Context Window Limitations**: LLMs have finite context windows. As prompts, chat history, or retrieved documents grow, they might exceed these limits, forcing truncation or causing the model to lose track of initial instructions or crucial context.
*   **Testing Fidelity Gap**: Manually testing with `curl` in a terminal is common during debugging. However, translating these ad-hoc tests into automated, reliable regression tests that *exactly* replicate the problematic `curl` command (with all its headers, nuances, and payload intricacies) can be difficult. Without this fidelity, your tests might not catch the real issue.

These challenges mean that what worked yesterday might silently break today, and fine-tuning prompts to achieve desired behavior can feel like a constant battle against regression. You need a way to lock down expected behavior for specific, complex API calls and LLM prompts.

## The Skroll Solution: Curl-Centric Testing & Optimization

Skroll addresses these challenges by providing:
*   **High-Fidelity `curl` Templates**: Define tests using `curl` commands, ensuring what you test is what you run.
*   **Rich, Flexible Metrics**: Go beyond simple pass/fail. Evaluate responses with custom scoring and detailed output capture.
*   **Parameter Optimization**: Systematically tune parameters (like LLM prompts) to find optimal values while regression tests ensure stability.

## Why Skroll?

*   **High Fidelity**: `curl` templates ensure tests accurately mirror real-world API calls, vital for complex requests (e.g., intricate JSON payloads, LLM function calling).
*   **Prevent Regressions**: Easily create tests from observed `curl` commands to catch API behavior changes and prompt-related issues early. Your problematic API calls become your regression shield.
*   **Developer-Friendly DSL**: An intuitive and expressive Kotlin-based DSL for defining test sets and individual test cases.
*   **Flexible & Rich Metrics**: Define custom, multi-faceted evaluation logic. Score responses based on various criteria and capture detailed output beyond a simple pass/fail.
*   **Built-in Optimization**: Systematically find better-performing prompt variations or other API parameters.

## Core Workflow: From Problem to Regression Prevention (and Optimization)

Skroll is designed to fit naturally into your development and debugging process:

1.  **Identify an Issue**: You notice an unexpected behavior or bug in your application's interaction with an API (e.g., an LLM providing a suboptimal response).
2.  **Capture the `curl` Command**: Many applications or proxy tools can log the exact `curl` command that led to the problematic behavior. This command is your starting point.
3.  **Create a Skroll Test**: Embed this `curl` command directly into a Skroll test case. Define metrics (or use `passFailMetrics`) to verify the expected outcome or capture the undesirable one.
4.  **Prevent Regression**: This test now acts as a safeguard. As you fix the underlying issue or evolve your API/prompts, this Skroll test ensures the specific problem doesn't reappear.
5.  **Optimize with Confidence**: With a suite of regression tests in place, you can leverage Skroll's optimization features to improve prompts or other parameters, knowing that your existing functionality is protected.

## Quick Start Guide

This example demonstrates defining and executing an API test using Skroll's `passFailMetrics` for straightforward assertion-based testing.

```kotlin
import io.github.takahirom.skroll.*       // Core Skroll imports
import kotlinx.coroutines.runBlocking     // For running suspend functions
import org.junit.jupiter.api.Test        // Example with JUnit 5
import org.junit.jupiter.api.DisplayName // Example with JUnit 5
import org.assertj.core.api.Assertions.assertThat // Example with AssertJ

class MyApiTests {

    @Test
    @DisplayName("Fetch To-Do item and verify content")
    fun fetchToDoItemTest() = runBlocking {
        val todoApiTestSet = skrollSet("JSONPlaceholder ToDo API Test") {
            defaultParameters {
                listOf(
                    Parameter("BASE_URL", "https://jsonplaceholder.typicode.com")
                )
            }

            skroll("Fetch a single Todo") {
                commandTemplate = "curl {BASE_URL}/todos/1"

                passFailMetrics { response ->
                    assertThat(response.statusCode).isEqualTo(200)
                    assertThat(response.body).contains("delectus aut autem")
                    assertThat(response.headers["Content-Type"]).contains("application/json")
                }
                // curlOptions { timeout = 10.seconds } // Optional
            }
        }

        val results = todoApiTestSet.executeAll()

        results.forEach { result ->
            println(
                "Test: ${result.definitionName}, " +
                "Score: ${result.evaluation?.primaryScore}, " +
                "Success (threshold 0.9): ${result.isSuccessful(0.9)}"
            )
            assertThat(result.isSuccessful(threshold = 0.9))
                .`as`("Test '${result.definitionName}' should be successful")
                .isTrue()
        }
    }
}
```

## Installation (Comming Soon)

**Gradle (Kotlin DSL):**
```kotlin
// build.gradle.kts
dependencies {
    testImplementation("io.github.takahirom.skroll:skroll:")
}
```

**Maven:**
```xml


    io.github.takahirom.skroll
    skroll
    latest_version
    test

```
Replace `` with the actual latest version of Skroll.

## Core Concepts & DSL

Skroll uses a Kotlin-based DSL to define tests.

**`skrollSet("Set Description") { ... }`**
The top-level block to group related test definitions (`skroll`s).
*   **`defaultParameters { listOf(Parameter("KEY", "VALUE")) }`**:
    Define common parameters (e.g., base URLs, API tokens) shared by all `skroll`s within this set.
    ```kotlin
    defaultParameters {
        listOf(
            Parameter("BASE_URL", "https://api.example.com"),
            Parameter("API_TOKEN", "your_secret_key")
        )
    }
    ```

**`skroll("Test Case Name") { ... }`**
Defines an individual test case.
*   **`commandTemplate = "curl command with {PLACEHOLDERS}"`**:
    The `curl` command string. Placeholders are resolved from parameters.
    ```kotlin
    commandTemplate = "curl {BASE_URL}/chat -d '{\"query\":\"{USER_INPUT}\"}'"
    ```
*   **`parameters { listOf(Parameter("KEY", "VALUE")) }`**: (Optional)
    Define parameters specific to this `skroll`, overriding or adding to `defaultParameters`.
    ```kotlin
    parameters {
        listOf(Parameter("USER_INPUT", "Tell me a joke."))
    }
    ```
*   **`passFailMetrics { apiResponse -> ... }`**:
    For tests where outcome is binary (pass/fail). Use assertion libraries inside. If assertions pass, score is `1.0`; if any assertion throws an exception, score is `0.0`.
    ```kotlin
    passFailMetrics { response ->
        assertThat(response.statusCode).isEqualTo(200)
        // Further assertions
    }
    ```
*   **`metrics { apiResponse -> EvaluationOutput(score, detailsMap) }`**:
    For custom scoring logic. Return an `EvaluationOutput` with a `primaryScore` (Double) and a `details` map.
    ```kotlin
    metrics { response ->
        val score = if (response.body.contains("expected_text")) 1.0 else 0.0
        val details = mapOf("body_preview" to response.body.take(100))
        EvaluationOutput(primaryScore = score, details = details)
    }
    ```
*   **`curlOptions { ... }`**: (Optional)
    Configure `CurlExecutionOptions` like `timeout`, `followRedirects`, `insecure`.
    ```kotlin
    curlOptions {
        timeout = 30.seconds // Using kotlin.time.Duration
        followRedirects = true
    }
    ```

## Executing Tests

Use the `executeAll()` extension function on a `SkrollSet` instance. (Note: `executeAllWith` is available for more advanced executor customization).

```kotlin
val mySkrollSet = skrollSet("My API Tests") { /* ... skroll definitions ... */ }

// Execute all skrolls using the default curl executor
val results: List = mySkrollSet.executeAll()

results.forEach { result ->
    println("Skroll: ${result.definitionName}")
    println("  Score: ${result.evaluation?.primaryScore}")
    println("  Success (threshold 0.9): ${result.isSuccessful(0.9)}")
    if (result.error != null) {
        println("  Error: ${result.error.message}")
    }
    result.evaluation?.details?.forEach { (key, value) ->
        println("  Detail - $key: $value")
    }
}
```
*   `executeAll()` returns a list of `SkrollResult` objects.
*   Each `SkrollResult` contains the test's `definitionName`, the `evaluation` output, the raw `apiResponse`, and any `error`.
*   The `isSuccessful(threshold: Double)` helper on `SkrollResult` checks if the score meets the threshold and no errors occurred.
*   Integrate these executions into your testing framework (e.g., JUnit, Kotest ) and use assertion libraries to verify results.

## Parameter Optimization

Skroll can help optimize a parameter (e.g., an LLM system prompt) within a `SkrollSet` to find a value that maximizes an aggregated score across all tests in that set.

Use the `optimizeDefaultParameter()` extension function:
```kotlin
val optimizationResult: OptimizationOutcome = mySkrollSet.optimizeDefaultParameter(
    parameterKeyToOptimize = "COMMON_SYSTEM_PROMPT",
    initialValue = "You are a helpful assistant.",
    optimizationConfig = OptimizationConfig(maxIterations = 10)
    // Optionally provide custom evaluator, optimizer, or skrollSetExecutor
)

println("Optimization Complete:")
println("  Best Value for '{optimizationResult.parameterKey}': \"${optimizationResult.bestValue}\"")
println("  Best Score: ${optimizationResult.bestScore}")
// optimizationResult.history provides details of each iteration
```
*   **`parameterKeyToOptimize`**: The key of the `defaultParameters` entry to optimize.
*   **`initialValue`**: The starting value for the parameter.
*   **`optimizationConfig`**: Configuration like `maxIterations`.
*   Refer to the documentation for advanced options like custom evaluators and optimizers.

## Advanced: Custom Executors & Resolvers
For specialized scenarios, Skroll allows you to provide your own implementations:
*   **`CurlExecutor`**: Define how `curl` commands are actually executed. Useful for mocking, custom logging, or integrating with specific environments.
*   **`TemplateResolver`**: Customize how placeholders in `commandTemplate` are resolved.

Provide these via a custom `SkrollSetExecutor` when calling `executeAllWith(customExecutor)`.
