package io.github.takahirom.skroll

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface Skroll<API_INPUT> {
    val name: String
    val metrics: (ApiResponse) -> EvaluationOutput
    fun createInput(defaultParameters: Map<String, String>): API_INPUT
}

/**
 * Represents a single, self-contained skroll test definition.
 * It includes the command template, local parameters, metrics evaluation logic, and curl options.
 *
 * @property name An optional name for this skroll definition, used for reporting.
 * @property commandTemplate The curl command template string with placeholders.
 * @property localParameters A list of [Parameter] specific to this skroll definition.
 *                           These can override or add to the default parameters from the parent [SkrollSet].
 * @property metrics A function that takes an [ApiResponse] and returns an [EvaluationOutput].
 * @property curlOptions Options for executing the curl command for this specific skroll.
 */
data class CurlSkroll(
    override val name: String,
    val commandTemplate: String,
    val localParameters: List<Parameter>,
    override val metrics: (ApiResponse) -> EvaluationOutput,
    val curlOptions: CurlExecutionOptions,
    val templateResolver: TemplateResolver = SimpleTemplateResolver(),
): Skroll<CurlApiExecutor.Input> {
    override fun createInput(defaultParameters: Map<String, String>): CurlApiExecutor.Input {
        val finalParameters = defaultParameters + localParameters.associate { it.key to it.value }
        val resolvedCommand = templateResolver.resolve(commandTemplate, finalParameters)
        return CurlApiExecutor.Input(resolvedCommand, curlOptions)
    }
}

/**
 * A builder class for creating [CurlSkroll] instances using a DSL.
 * This builder is used within the `skroll { ... }` block inside a `SkrollSet`.
 *
 * @param name An optional name for the skroll definition being built.
 * @see SkrollSet.skroll
 */
class CurlSkrollBuilder internal constructor(private val name: String) {
    var commandTemplate: String = ""
        set(value) {
            if(value.contains("Content-Length")) {
                println("Warning: An incorrect Content-Length header value may cause issues, such as JSON parse errors, when executing curl commands.")
            }
            field = value.trimIndent() // Automatically trim indent for multi-line strings
        }

    private val localParamsInternal = mutableListOf<Parameter>()
    private var metricsFunction: (ApiResponse) -> EvaluationOutput =
        { defaultMetricsFunction(it) } // Provide a sensible default
    private var curlOptionsInternal: CurlExecutionOptions = CurlExecutionOptions() // Default options
    private var templateResolver: TemplateResolver = SimpleTemplateResolver()

    /**
     * Defines the parameters specific to this skroll definition.
     * These will be merged with or override default parameters from the parent [SkrollSet]
     * during command resolution.
     *
     * Example:
     * ```
     * parameters {
     *     listOf(
     *         Parameter("USER_ID", "123"),
     *         Parameter("MODE", "test")
     *     )
     * }
     * ```
     */
    fun parameters(block: () -> List<Parameter>) {
        localParamsInternal.addAll(block())
    }

    /**
     * Defines the metrics evaluation logic for this skroll.
     * This function will be called with the [ApiResponse] from the executed curl command.
     * It should return an [EvaluationOutput] containing a primary score and optional details.
     *
     * Example:
     * ```
     * metrics { response ->
     *     val score = if (response.statusCode == 200 && response.body.contains("success")) 1.0 else 0.0
     *     EvaluationOutput(score, mapOf("body_preview" to response.body.take(50)))
     * }
     * ```
     */
    fun metrics(block: (ApiResponse) -> EvaluationOutput) {
        this.metricsFunction = block
    }

    fun passFailMetrics(block: (ApiResponse) -> Unit){
        this.metricsFunction = { response ->
            var exception: Exception? = null
            val score = try {
                block(response)
                1.0
            } catch (e: Exception) {
                exception = e
                0.0
            }
            EvaluationOutput(
                primaryScore = score,
                details = mapOf(
                    "status_code" to response.statusCode,
                    "pass_fail_metrics_used" to true,
                    "body_preview_pass_fail" to response.body.take(30),
                    "exception" to (exception?.message ?: ""),
                    "exception_stack_trace" to (exception?.stackTraceToString() ?: "")
                )
            )
        }
    }

    /**
     * Configures [CurlExecutionOptions] for this specific skroll definition.
     * These options will be used when executing the curl command for this skroll.
     *
     * Example:
     * ```
     * curlOptions {
     *     timeoutSeconds = 60
     *     followRedirects = false
     * }
     * ```
     */
    fun curlOptions(block: CurlExecutionOptionsBuilder.() -> Unit) {
        val builder = CurlExecutionOptionsBuilder()
        builder.block()
        this.curlOptionsInternal = builder.build()
    }

    internal fun build(): CurlSkroll {
        require(commandTemplate.isNotBlank()) { "Command template must be set for a skroll definition (name: ${name ?: "unnamed"})." }
        return CurlSkroll(
            name = name,
            commandTemplate = commandTemplate,
            localParameters = localParamsInternal.toList(),
            metrics = metricsFunction,
            templateResolver = templateResolver,
            curlOptions = curlOptionsInternal,
        )
    }

    companion object {
        // Default metrics function if none is provided by the user.
        private fun defaultMetricsFunction(response: ApiResponse): EvaluationOutput {
            val score = if (response.statusCode in 200..299) 1.0 else 0.0
            return EvaluationOutput(
                primaryScore = score,
                details = mapOf(
                    "status_code" to response.statusCode,
                    "default_metric_used" to true,
                    "body_preview_default" to response.body.take(30)
                )
            )
        }
    }
}

/**
 * Builder for [CurlExecutionOptions].
 * Used within the `curlOptions { ... }` block in a [CurlSkrollBuilder].
 */
class CurlExecutionOptionsBuilder {
    var timeout: Duration = 30.seconds
    var followRedirects: Boolean = true
    var insecure: Boolean = false

    internal fun build(): CurlExecutionOptions {
        return CurlExecutionOptions(timeout, followRedirects, insecure)
    }
}

/**
 * Represents a set or group of related [CurlSkroll]s.
 * It holds default parameters and a list of skroll definitions.
 * Execution and optimization are handled by separate classes ([SkrollSetExecutor], [ParameterOptimizer]).
 *
 * @param description An optional description for this set of skrolls.
 */
class SkrollSet<API_INPUT>(val description: String) {
    private val defaultParamsInternal = mutableListOf<Parameter>()
    private val skrollInternal = mutableListOf<Skroll<API_INPUT>>()

    // Read-only access for Executor and Optimizer
    val defaultParameters: List<Parameter> get() = defaultParamsInternal.toList()
    val skrolls: List<Skroll<API_INPUT>> get() = skrollInternal.toList()

    /**
     * Defines default parameters that will be available to all [CurlSkroll]s within this set.
     * These parameters are merged with skroll-specific parameters, with skroll-specific ones taking precedence.
     *
     * Example:
     * ```
     * defaultParameters {
     *     listOf(
     *         Parameter("BASE_URL", "https://api.example.com"),
     *         Parameter("API_TOKEN", "your_secret_key")
     *     )
     * }
     * ```
     */
    fun defaultParameters(block: () -> List<Parameter>) {
        defaultParamsInternal.addAll(block())
    }

    fun addSkroll(skroll: Skroll<API_INPUT>) {
        skrollInternal.add(skroll)
    }
}

/**
 * Defines a new [CurlSkroll] within this set.
 *
 * @param name An optional name for the skroll definition, useful for reporting and identification.
 * @param block A DSL block to configure the [CurlSkroll] using [CurlSkrollBuilder].
 */
fun SkrollSet<CurlApiExecutor.Input>.skroll(
    name: String,
    block: CurlSkrollBuilder.() -> Unit
) {
    val builder = CurlSkrollBuilder(name)
    builder.block()
    this.addSkroll(builder.build())
}

// --- Extension functions to provide a fluent API for execution and optimization ---

/**
 * Executes all skrolls in this [SkrollSet] using the provided executor.
 * If no executor is provided, a [SkrollSetExecutor] with default [CurlApiExecutor] and [SimpleTemplateResolver] will be used.
 *
 * @param executor The [SkrollSetExecutor] instance to use for execution.
 * @return A list of [SkrollRunResult].
 */
suspend fun <API_INPUT>SkrollSet<API_INPUT>.executeAll(executor: SkrollSetExecutor<API_INPUT> = SkrollSetExecutor<API_INPUT>()): List<SkrollRunResult> {
    return executor.executeAll(this)
}

/**
 * Optimizes a default parameter for this [SkrollSet] using the provided components.
 *
 * @param parameterKeyToOptimize The key of the default parameter to be optimized.
 * @param initialValue The initial value for the parameter to start optimization from.
 * @param evaluator The [SkrollSetEvaluator] to assess the performance of each candidate parameter value. Defaults to [AveragePrimaryScoreEvaluator].
 * @param optimizer The [ParameterOptimizer] strategy to use. Defaults to [SimpleParameterOptimizer].
 * @param skrollSetExecutor The [SkrollSetExecutor] to run skrolls during optimization. Defaults to a new instance.
 * @param optimizationConfig Configuration for the optimization process.
 * @return A [PromptOptimizationResult] with the best parameter value found.
 */
suspend fun <API_INPUT>SkrollSet<API_INPUT>.optimizeParameterWithSimpleOptimizer(
  parameterKeyToOptimize: String,
  initialValue: String,
  evaluator: SkrollSetEvaluator = AveragePrimaryScoreEvaluator(),
  optimizer: ParameterOptimizer<SimpleParameterOptimizer.OptimizationConfig, API_INPUT> = SimpleParameterOptimizer(),
  skrollSetExecutor: SkrollSetExecutor<API_INPUT> = SkrollSetExecutor(),
  optimizationConfig: SimpleParameterOptimizer.OptimizationConfig = SimpleParameterOptimizer.OptimizationConfig()
): PromptOptimizationResult {
    return optimizer.optimize(
      skrollSet = this,
      parameterKeyToOptimize = parameterKeyToOptimize,
      initialValue = initialValue,
      evaluator = evaluator,
      skrollSetExecutor = skrollSetExecutor,
      optimizationConfig = optimizationConfig
    )
}

/**
 * Top-level DSL function to create and configure a [SkrollSet].
 * This is the main entry point for defining a collection of skroll tests.
 *
 * @param description An optional description for the set of skrolls.
 * @param block A DSL block to configure the [SkrollSet] (define default parameters and skrolls).
 * @return The configured [SkrollSet] instance.
 */
fun skrollSet(description: String, block: SkrollSet<CurlApiExecutor.Input>.() -> Unit): SkrollSet<CurlApiExecutor.Input> {
    val set = SkrollSet<CurlApiExecutor.Input>(description)
    set.block()
    return set
}

fun <T>customSkrollSet(description: String, block: SkrollSet<Any>.() -> Unit): SkrollSet<Any> {
  val set = SkrollSet<Any>(description)
  set.block()
  return set
}