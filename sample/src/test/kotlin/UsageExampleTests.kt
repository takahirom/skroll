package io.github.takahirom.skroll.example

import com.google.common.truth.Truth.assertThat
import io.github.takahirom.skroll.* // Import all skroll classes
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("skroll-examples")
class UsageExampleTests {
  @Test
  @DisplayName("Real dummy API test")
  fun realDummyApiTest() = runBlocking<Unit> {
    val skrollSet = skrollSet("Dummy API Test") {
      defaultParameters {
        listOf(
          Parameter("BASE_URL", "https://jsonplaceholder.typicode.com"),
        )
      }
      skroll("Fetch Todo") {
        commandTemplate = "curl {{BASE_URL}}/todos/1"
        passFailMetrics { response ->
          assertThat(response.statusCode).isEqualTo(200)
          assertThat(response.body).contains("delectus aut autem")
        }
      }
    }
    skrollSet.executeAll().forEach { result ->
      println(
        "Test: ${result.definitionName}, Score: ${result.evaluation?.primaryScore}, Success: ${
          result.isSuccessful(
            0.9
          )
        }"
      )
      assertThat(result.isSuccessful(threshold = 0.9)).isTrue()
    }
  }

  private val testApiKey = System.getenv("SKROLL_TEST_API_KEY") ?: "dummy_key"
  private val testApiBaseUrl = System.getenv("SKROLL_TEST_API_URL") ?: "https://refactored-api.skroll.test"

  @Test
  @DisplayName("FAQ SkrollSet should meet evaluation criteria")
  fun faqSkrollSetEvaluation() = runBlocking<Unit> {
    val faqSet = skrollSet("FAQ Handling") {
      defaultParameters {
        listOf(
          Parameter("BASE_URL", testApiBaseUrl),
          Parameter("API_TOKEN", testApiKey),
          Parameter("COMMON_SYSTEM_PROMPT", "You are an extremely helpful and accurate FAQ bot.")
        )
      }

      skroll("Capital City Question") {
        commandTemplate =
          "curl {{BASE_URL}}/faq -d '{\"q\":\"Capital of France?\", \"prompt\":\"{{COMMON_SYSTEM_PROMPT}}\"}'"
        metrics { response ->
          EvaluationOutput(
            if (response.body.contains("Paris", ignoreCase = true)) 1.0 else 0.1,
            mapOf("body" to response.body.take(100))
          )
        }
      }

      skroll("Simple Math Question") {
        commandTemplate = "curl {{BASE_URL}}/calculate -d '{\"q\":\"17+25?\", \"prompt\":\"{{COMMON_SYSTEM_PROMPT}}\"}'"
        metrics { response ->
          EvaluationOutput(if (response.body.contains("42")) 1.0 else 0.2, mapOf("body" to response.body.take(100)))
        }
      }
    }

    // Create an executor instance (can be configured with different CurlExecutor or TemplateResolver)
    val executor = SkrollSetExecutor(
      apiExecutor = DummyApiExecutor(),
    ) // Uses DefaultCurlExecutor and SimpleTemplateResolver by default
    val results: List<SkrollRunResult> = faqSet.executeAll(executor) // Use extension function

    assertThat(results).hasSize(2)
    results.forEach { result ->
      println(
        "Test: ${result.definitionName}, Score: ${result.evaluation?.primaryScore}, Success: ${
          result.isSuccessful(
            0.9
          )
        }"
      )
      assertThat(result.isSuccessful(threshold = 0.9)).isTrue()
    }
  }

  @Test
  @DisplayName("Optimize COMMON_SYSTEM_PROMPT for FAQs (refactored)")
  @Tag("optimization-example")
  fun optimizeFaqSystemPromptRefactored() = runBlocking<Unit> {
    val faqSetForOptimization = skrollSet("FAQ Prompt Optimization") {
      defaultParameters {
        listOf(
          Parameter("BASE_URL", testApiBaseUrl),
          Parameter("API_TOKEN", testApiKey),
          Parameter("COMMON_SYSTEM_PROMPT", "You answer questions.")
        )
      }
      skroll("Capital City Question (for opt)") {
        commandTemplate =
          "curl {{BASE_URL}}/faq -d '{\"q\":\"Capital of France?\", \"prompt\":\"{{COMMON_SYSTEM_PROMPT}}\"}'"
        metrics { response -> EvaluationOutput(if (response.body.contains("Paris", ignoreCase = true)) 1.0 else 0.1) }
      }
      skroll("Math Question (for opt)") {
        commandTemplate = "curl {{BASE_URL}}/faq -d '{\"q\":\"17+25?\", \"prompt\":\"{{COMMON_SYSTEM_PROMPT}}\"}'"
        metrics { response -> EvaluationOutput(if (response.body.contains("42")) 1.0 else 0.2) }
      }
    }

    // Use the extension function for optimization
    val optimizationResult = faqSetForOptimization.optimizeParameterWithSimpleOptimizer(
      parameterKeyToOptimize = "COMMON_SYSTEM_PROMPT",
      initialValue = "Tell me the answer.",
      skrollSetExecutor = SkrollSetExecutor(apiExecutor = DummyApiExecutor()),
      // Default evaluator (AveragePrimaryScoreEvaluator) and optimizer (SimpleParameterOptimizer) will be used
      // Can also pass custom instances:
      // evaluator = MyCustomEvaluator(),
      // optimizer = MyAdvancedOptimizer(),
      // skrollSetExecutor = SkrollSetExecutor(customCurlExecutor),
      optimizationConfig = SimpleParameterOptimizer.OptimizationConfig(maxIterations = 3)
    )

    println("Optimization Result (Refactored):")
    println("  Best Prompt: \"${optimizationResult.bestValue}\"")
    println("  Best Aggregated Score: ${optimizationResult.bestScore}")

    assertThat(optimizationResult.bestScore).isAtLeast(0.5)
  }
}


/**
 * A dummy implementation of CurlExecutor for demonstration and testing purposes.
 * In a real scenario, this would use ProcessBuilder or a similar mechanism to run curl.
 */
class DummyApiExecutor : ApiExecutor<CurlApiExecutor.Input> {
  override suspend fun execute(input: CurlApiExecutor.Input): ApiResponse {
    val command = input.command
    val options = input.options
    println("  [DummyCurlExecutor] Executing: $command (Timeout: ${options.timeout}s, Redirects: ${options.followRedirects}, Insecure: ${options.insecure})")
    // Simulate a successful API call
    val simulatedStatusCode = if (command.contains("error_case")) 400 else 200
    val simulatedBody = if (simulatedStatusCode == 200) {
      when {
        command.lowercase()
          .contains("france") -> "{\"answer\":\"Paris is the capital!\", \"source\":\"knowledge_base\"}"

        command.lowercase().contains("17") -> "{\"answer\":\"The answer is 42.\", \"certainty\":0.99}"
        command.lowercase()
          .contains("joke") -> "{\"joke\":\"Why did the scarecrow win an award? Because he was outstanding in his field!\", \"type\":\"pun\"}"

        else -> "{\"message\":\"Dummy success response for command: ${command.take(50)}...\"}"
      }
    } else {
      "{\"error\":\"Simulated error for command: ${command.take(50)}...\"}"
    }
    return ApiResponse(
      statusCode = simulatedStatusCode,
      bodyByteArray = simulatedBody.toByteArray(),
      headers = mapOf("Content-Type" to listOf("application/json"), "X-Executed-By" to listOf("DummyCurlExecutor"))
    )
  }
}
