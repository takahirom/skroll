package io.github.takahirom.skroll.example

import com.google.common.truth.Truth.assertThat
import io.github.takahirom.skroll.* // Import all skroll classes
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

// Helper to format Double for readability in output
 fun Double.format(digits: Int) = "%.${digits}f".format(this)

@Tag("skroll-examples")
class UsageExampleTests {

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
        commandTemplate = "curl {BASE_URL}/faq -d '{\"q\":\"Capital of France?\", \"prompt\":\"{COMMON_SYSTEM_PROMPT}\"}'"
        metrics { response ->
          EvaluationOutput(if (response.body.contains("Paris", ignoreCase = true)) 1.0 else 0.1, mapOf("body" to response.body.take(100)))
        }
      }

      skroll("Simple Math Question") {
        commandTemplate = "curl {BASE_URL}/calculate -d '{\"q\":\"17+25?\", \"prompt\":\"{COMMON_SYSTEM_PROMPT}\"}'"
        metrics { response ->
          EvaluationOutput(if (response.body.contains("42")) 1.0 else 0.2, mapOf("body" to response.body.take(100)))
        }
      }
    }

    // Create an executor instance (can be configured with different CurlExecutor or TemplateResolver)
    val executor = SkrollSetExecutor(
      curlExecutor = DummyCurlExecutor(),
    ) // Uses DefaultCurlExecutor and SimpleTemplateResolver by default
    val results: List<SkrollRunResult> = faqSet.executeAllWith(executor) // Use extension function

    assertThat(results).hasSize(2)
    results.forEach { result ->
      println("Test: ${result.definitionName}, Score: ${result.evaluation?.primaryScore}, Success: ${result.isSuccessful(0.9)}")
      assertThat(result.isSuccessful(threshold = 0.9)).isTrue()
    }
  }

  @Test
  @DisplayName("Optimize COMMON_SYSTEM_PROMPT for FAQs (refactored)")
  @Tag("optimization-refactored-example")
  @Disabled("Conceptual test for refactored optimization API")
  fun optimizeFaqSystemPromptRefactored() = runBlocking {
    val faqSetForOptimization = skrollSet("FAQ Prompt Optimization - Refactored") {
      defaultParameters {
        listOf(
          Parameter("BASE_URL", testApiBaseUrl),
          Parameter("API_TOKEN", testApiKey),
          Parameter("COMMON_SYSTEM_PROMPT", "You answer questions.")
        )
      }
      skroll("Capital City Question (for opt)") {
        commandTemplate = "curl {BASE_URL}/faq -d '{\"q\":\"Capital of France?\", \"prompt\":\"{COMMON_SYSTEM_PROMPT}\"}'"
        metrics { response -> EvaluationOutput(if (response.body.contains("Paris", ignoreCase = true)) 1.0 else 0.1) }
      }
      skroll("Math Question (for opt)") {
        commandTemplate = "curl {BASE_URL}/faq -d '{\"q\":\"17+25?\", \"prompt\":\"{COMMON_SYSTEM_PROMPT}\"}'"
        metrics { response -> EvaluationOutput(if (response.body.contains("42")) 1.0 else 0.2) }
      }
    }

    // Use the extension function for optimization
    val optimizationResult = faqSetForOptimization.optimizeDefaultParameterWith(
      parameterKeyToOptimize = "COMMON_SYSTEM_PROMPT",
      initialValue = "Tell me the answer.",
      // Default evaluator (AveragePrimaryScoreEvaluator) and optimizer (SimpleParameterOptimizer) will be used
      // Can also pass custom instances:
      // evaluator = MyCustomEvaluator(),
      // optimizer = MyAdvancedOptimizer(),
      // skrollSetExecutor = SkrollSetExecutor(customCurlExecutor),
      optimizationConfig = OptimizationConfig(maxIterations = 3)
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
class DummyCurlExecutor : CurlExecutor {
  override suspend fun execute(command: String, options: CurlExecutionOptions): ApiResponse {
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
