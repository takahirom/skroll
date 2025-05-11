// File: io/github/takahirom/skroll/example/UsageExampleTests.kt
// Using a sub-package for examples

import com.google.common.truth.Truth.assertThat
import io.github.takahirom.skroll.* // Import all skroll classes
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

// Helper to format Double for readability in output
fun Double.format(digits: Int) = "%.${digits}f".format(this)

@Tag("skroll-examples")
class UsageExampleTests {

    private val testApiKey = System.getenv("SKROLL_TEST_API_KEY") ?: "dummy_test_key_for_examples"
    private val testApiBaseUrl = System.getenv("SKROLL_TEST_API_URL") ?: "https://dummy-api.skroll.test"

    @Test
    @DisplayName("FAQ SkrollSet should meet evaluation criteria")
    fun faqSkrollSetEvaluation() {
        val faqSet = skrollSet("FAQ Handling Examples") {
            // Use a real executor if you want to make actual API calls (configure it)
            // setCurlExecutor(RealCurlExecutor()) 
            // For this example, the default DummyCurlExecutor is used.

            defaultParameters {
                listOf(
                    Parameter("BASE_URL", testApiBaseUrl),
                    Parameter("API_TOKEN", testApiKey),
                    Parameter("COMMON_SYSTEM_PROMPT", "You are an extremely helpful and accurate FAQ bot.")
                )
            }

            skroll("Capital City Question") {
                commandTemplate = """
                    curl -X POST "{BASE_URL}/faq" \
                    -H "Authorization: Bearer {API_TOKEN}" \
                    -H "Content-Type: application/json" \
                    -d '{
                        "model": "text-davinci-003", 
                        "messages": [
                            {"role": "system", "content": "{COMMON_SYSTEM_PROMPT}"},
                            {"role": "user", "content": "What is the capital of France?"}
                        ]
                    }'
                """.trimIndent()
                metrics { response ->
                    val body = response.body.lowercase()
                    var score = 0.0
                    val details = mutableMapOf<String, Any>()
                    details["response_body_preview"] = body.take(100)
                    if (response.statusCode == 200) score += 0.5
                    if (body.contains("paris")) score += 0.5 else details["missing_keyword"] = "paris"
                    EvaluationOutput(score.coerceIn(0.0, 1.0), details)
                }
            }

            skroll("Simple Math Question") {
                parameters { // Skroll-specific parameters (can override defaults or add new ones)
                    listOf(
                        Parameter("MATH_MODEL", "gpt-4") // Using a different model for this skroll
                    )
                }
                commandTemplate = """
                    curl -X POST "{BASE_URL}/calculate" \
                    -H "Authorization: Bearer {API_TOKEN}" \
                    -H "Content-Type: application/json" \
                    -d '{
                        "model": "{MATH_MODEL}", 
                        "messages": [
                            {"role": "system", "content": "{COMMON_SYSTEM_PROMPT} You are also good at math."},
                            {"role": "user", "content": "What is 17 + 25?"}
                        ]
                    }'
                """.trimIndent()
                metrics { response ->
                    val body = response.body.lowercase()
                    var score = 0.0
                    val details = mutableMapOf<String, Any>()
                    details["response_body_preview"] = body.take(100)
                    if (response.statusCode == 200) score += 0.5
                    if (body.contains("42")) score += 0.5 else details["calculation_error_or_missing"] = "42"
                    EvaluationOutput(score.coerceIn(0.0, 1.0), details)
                }
            }
        }

        val results: List<SkrollRunResult> = faqSet.executeAll()

        assertThat(results).hasSize(2)

        val capitalResult = results.find { it.definitionName == "Capital City Question" }
        assertThat(capitalResult).isNotNull()
        assertThat(capitalResult!!.isSuccessful(threshold = 0.9)).isTrue() // Expecting a high score
        assertThat(capitalResult.evaluation?.details?.get("missing_keyword")).isNull()

        val mathResult = results.find { it.definitionName == "Simple Math Question" }
        assertThat(mathResult).isNotNull()
        assertThat(mathResult!!.isSuccessful(threshold = 0.9)).isTrue()
        assertThat(mathResult.evaluation?.details?.get("calculation_error_or_missing")).isNull()

        // Print results for manual review
        results.forEach {
            println("Test: ${it.definitionName}, Score: ${it.evaluation?.primaryScore?.format(2)}, Success: ${it.isSuccessful(0.9)}")
            it.evaluation?.details?.forEach { (k, v) -> println("  $k: $v")}
        }
    }

    @Test
    @DisplayName("Optimize COMMON_SYSTEM_PROMPT for FAQ SkrollSet")
    @Tag("optimization-example")
    @Disabled("This is a conceptual test for optimization API; dummy executor doesn't truly optimize.")
    fun optimizeFaqSystemPrompt() {
        val faqSetForOptimization = skrollSet("FAQ Prompt Optimization") {
            defaultParameters {
                listOf(
                    Parameter("BASE_URL", testApiBaseUrl),
                    Parameter("API_TOKEN", testApiKey),
                    Parameter("COMMON_SYSTEM_PROMPT", "You answer questions.") // Initial prompt
                )
            }
            skroll("Capital City Question (for opt)") {
                commandTemplate = "curl {BASE_URL}/faq -d '{\"q\":\"Capital of France?\", \"prompt\":\"{COMMON_SYSTEM_PROMPT}\"}'"
                metrics { response -> EvaluationOutput(if (response.body.contains("Paris")) 1.0 else 0.1) }
            }
            skroll("Math Question (for opt)") {
                commandTemplate = "curl {BASE_URL}/faq -d '{\"q\":\"17+25?\", \"prompt\":\"{COMMON_SYSTEM_PROMPT}\"}'"
                metrics { response -> EvaluationOutput(if (response.body.contains("42")) 1.0 else 0.2) }
            }
        }

        val optimizationResult = faqSetForOptimization.optimizeDefaultParameter(
            parameterKeyToOptimize = "COMMON_SYSTEM_PROMPT",
            initialValue = "Tell me the answer.",
            evaluationAggregator = { skrollResults ->
                // Aggregate score: average primary score of all skrolls in the set
                val averageScore = skrollResults.mapNotNull { it.evaluation?.primaryScore }.average()
                if (averageScore.isNaN()) 0.0 else averageScore
            },
            optimizationConfig = OptimizationConfig(maxIterations = 3) // Keep low for example
        )

        println("Optimization Result:")
        println("  Best Prompt: \"${optimizationResult.bestValue}\"")
        println("  Best Aggregated Score: ${optimizationResult.bestScore.format(3)}")
        println("  History:")
        optimizationResult.history.forEach { (prompt, score) ->
            println("    \"$prompt\" -> Score: ${score.format(3)}")
        }

        // Assertions on the optimization outcome
        assertThat(optimizationResult.bestScore).isAtLeast(0.5) // Example threshold for a good optimization
        // assertThat(optimizationResult.bestValue).contains("accurate") // Example assertion on the prompt content
    }
}
