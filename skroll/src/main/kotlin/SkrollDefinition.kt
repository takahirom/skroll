// File: io/github/takahirom/skroll/SkrollCore.kt
package io.github.takahirom.skroll

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


/**
 * Represents a single, self-contained skroll test definition.
 * It includes the command template, local parameters, and metrics evaluation logic.
 *
 * @property name An optional name for this skroll definition, used for reporting.
 * @property commandTemplate The curl command template string with placeholders.
 * @property localParameters A list of [Parameter] specific to this skroll definition.
 *                           These can override or add to the default parameters from the parent [SkrollSet].
 * @property metrics A function that takes an [ApiResponse] and returns an [EvaluationOutput].
 * @property curlOptions Options for executing the curl command for this specific skroll.
 */
data class SkrollDefinition(
    val name: String?,
    val commandTemplate: String,
    val localParameters: List<Parameter>,
    val metrics: (ApiResponse) -> EvaluationOutput,
    val curlOptions: CurlExecutionOptions = CurlExecutionOptions() // Added for skroll-specific options
)

/**
 * A builder class for creating [SkrollDefinition] instances using a DSL.
 *
 * @param name An optional name for the skroll definition being built.
 */
class SkrollDefinitionBuilder(private val name: String?) {
    var commandTemplate: String = ""
    private val localParamsInternal = mutableListOf<Parameter>()
    private var metricsFunction: (ApiResponse) -> EvaluationOutput =
        { EvaluationOutput(0.0, mapOf("warning" to "Metrics function not defined")) }
    private var curlOptionsInternal: CurlExecutionOptions = CurlExecutionOptions()

    /**
     * Defines the parameters specific to this skroll definition.
     * These will be merged with or override default parameters from the parent [SkrollSet].
     */
    fun parameters(block: () -> List<Parameter>) {
        localParamsInternal.addAll(block())
    }

    /**
     * Defines the metrics evaluation logic for this skroll.
     * This function will be called with the [ApiResponse] from the executed curl command.
     * It should return an [EvaluationOutput] containing a primary score and optional details.
     */
    fun metrics(block: (ApiResponse) -> EvaluationOutput) {
        this.metricsFunction = block
    }

    /**
     * Configures [CurlExecutionOptions] for this specific skroll definition.
     * These options will override any default curl options set at the [SkrollSet] level for this skroll.
     */
    fun curlOptions(block: CurlExecutionOptionsBuilder.() -> Unit) {
        val builder = CurlExecutionOptionsBuilder()
        builder.block()
        this.curlOptionsInternal = builder.build()
    }

    internal fun build(): SkrollDefinition {
        require(commandTemplate.isNotBlank()) { "Command template must be set for a skroll definition." }
        return SkrollDefinition(
            name = name,
            commandTemplate = commandTemplate,
            localParameters = localParamsInternal.toList(),
            metrics = metricsFunction,
            curlOptions = curlOptionsInternal
        )
    }
}

/**
 * Builder for [CurlExecutionOptions].
 */
class CurlExecutionOptionsBuilder {
    var timeout: Duration = 30.seconds
    var followRedirects: Boolean = true
    var insecure: Boolean = false

    fun build(): CurlExecutionOptions {
        return CurlExecutionOptions(timeout, followRedirects, insecure)
    }
}


/**
 * Represents a set or group of related [SkrollDefinition]s.
 * It can hold default parameters that apply to all skrolls within it and provides
 * methods to execute them and optimize parameters.
 *
 * @param description An optional description for this set of skrolls.
 */
class SkrollSet(val description: String?) {
    private val defaultParamsInternal = mutableListOf<Parameter>()
    private val skrollDefinitionsInternal = mutableListOf<SkrollDefinition>()

    // Expose read-only views for introspection if needed
    val defaultParameters: List<Parameter> get() = defaultParamsInternal.toList()
    val skrollDefinitions: List<SkrollDefinition> get() = skrollDefinitionsInternal.toList()

    private var curlExecutor: CurlExecutor = DummyCurlExecutor() // Default executor
    private var templateResolver: TemplateResolver = SimpleTemplateResolver() // Default resolver

    /**
     * Sets the [CurlExecutor] to be used by this SkrollSet.
     * Allows for injecting different execution strategies (e.g., real vs. fake).
     */
    fun setCurlExecutor(executor: CurlExecutor) {
        this.curlExecutor = executor
    }

    /**
     * Sets the [TemplateResolver] to be used by this SkrollSet.
     */
    fun setTemplateResolver(resolver: TemplateResolver) {
        this.templateResolver = resolver
    }

    /**
     * Defines default parameters that will be available to all [SkrollDefinition]s within this set.
     * Skroll-specific parameters can override these defaults.
     */
    fun defaultParameters(block: () -> List<Parameter>) {
        defaultParamsInternal.addAll(block())
    }

    /**
     * Defines a new [SkrollDefinition] within this set.
     *
     * @param name An optional name for the skroll definition.
     * @param block A DSL block to configure the [SkrollDefinition].
     */
    fun skroll(name: String? = null, block: SkrollDefinitionBuilder.() -> Unit) {
        val builder = SkrollDefinitionBuilder(name)
        builder.block()
        skrollDefinitionsInternal.add(builder.build())
    }

    /**
     * Executes all [SkrollDefinition]s within this set.
     *
     * @return A list of [SkrollRunResult] for each executed skroll.
     */
    fun executeAll(): List<SkrollRunResult> {
        println("Executing SkrollSet: ${description ?: "Untitled Skroll Set"}")
        val results = mutableListOf<SkrollRunResult>()

        val defaultParamsMap = defaultParamsInternal.associate { it.key to it.value }

        for (definition in skrollDefinitionsInternal) {
            val skrollSpecificParamsMap = definition.localParameters.associate { it.key to it.value }
            // Skroll-specific parameters override default parameters
            val finalParameters = defaultParamsMap + skrollSpecificParamsMap

            val resolvedCommand = templateResolver.resolve(definition.commandTemplate, finalParameters)
            var apiResponse: ApiResponse? = null
            var evaluationOutput: EvaluationOutput? = null
            var error: Throwable? = null

            val skrollFullName = definition.name ?: "Unnamed Skroll (#${results.size + 1})"
            println("  Running Skroll: $skrollFullName")

            try {
                apiResponse = curlExecutor.execute(resolvedCommand, definition.curlOptions)
                evaluationOutput = definition.metrics(apiResponse)
                println("    Metrics for '$skrollFullName': PrimaryScore=${evaluationOutput.primaryScore}, Details=${evaluationOutput.details}")
            } catch (e: Throwable) {
                error = e
                println("    ERROR during execution or metrics for '$skrollFullName': ${e.message}")
            }
            results.add(SkrollRunResult(definition.name, evaluationOutput, apiResponse, error))
        }
        return results
    }

    /**
     * Optimizes a default parameter (typically a prompt string) used within this [SkrollSet].
     * It iteratively tries different values for the specified parameter, executes all skrolls
     * in the set, and uses an aggregator function to evaluate the overall performance.
     *
     * @param parameterKeyToOptimize The key of the default parameter to optimize (e.g., "COMMON_SYSTEM_PROMPT").
     * @param initialValue The starting value for the parameter being optimized.
     * @param evaluationAggregator A function that takes a list of [SkrollRunResult] (from executing all
     *                             skrolls in the set with a candidate parameter value) and returns a single
     *                             [Double] score representing the overall performance.
     * @param optimizationConfig Configuration for the optimization process.
     * @return A [PromptOptimizationResult] containing the best parameter value found and its score.
     */
    fun optimizeDefaultParameter(
        parameterKeyToOptimize: String,
        initialValue: String,
        evaluationAggregator: (List<SkrollRunResult>) -> Double,
        optimizationConfig: OptimizationConfig = OptimizationConfig()
        // strategy: PromptOptimizationStrategy - can be added later
    ): PromptOptimizationResult {
        println("Optimizing default parameter '$parameterKeyToOptimize' for SkrollSet: ${description ?: "Untitled"}")
        println("  Initial value: \"$initialValue\"")
        println("  Max iterations: ${optimizationConfig.maxIterations}")

        var bestValue = initialValue
        var bestScore = Double.NEGATIVE_INFINITY // Assuming higher is better for the aggregator

        // Store the original value of the parameter if it exists in defaultParamsInternal
        val originalParamIndex = defaultParamsInternal.indexOfFirst { it.key == parameterKeyToOptimize }
        val originalParamValue = if (originalParamIndex != -1) defaultParamsInternal[originalParamIndex].value else null

        val optimizationHistory = mutableListOf<Pair<String, Double>>()

        // --- Simplified Optimization Loop (Placeholder for actual COPRO-like logic) ---
        // In a real scenario, a more sophisticated strategy (e.g., LLM-based suggestions,
        // evolutionary algorithms) would generate candidate prompts.
        // This dummy loop just tries a few variations.
        val candidateValues = listOf(
            initialValue,
            "Slightly different: $initialValue",
            "Another variation of $initialValue with more detail.",
            "Concise version: ${initialValue.take(10)}",
            "VERY EMPHATIC VERSION OF $initialValue!!!"
        ).take(optimizationConfig.maxIterations)


        for (currentCandidateValue in candidateValues) {
            println("    Trying value: \"$currentCandidateValue\"")

            // Temporarily set the default parameter to the candidate value
            if (originalParamIndex != -1) {
                defaultParamsInternal[originalParamIndex] = Parameter(parameterKeyToOptimize, currentCandidateValue)
            } else {
                // If the key wasn't in default params, add it temporarily.
                // This might not be ideal; typically, the key should exist.
                defaultParamsInternal.add(Parameter(parameterKeyToOptimize, currentCandidateValue))
            }

            val iterationResults = this.executeAll() // Execute all skrolls with the current candidate
            val aggregatedScore = evaluationAggregator(iterationResults)
            optimizationHistory.add(currentCandidateValue to aggregatedScore)
            println("      Aggregated score for \"$currentCandidateValue\": $aggregatedScore")

            if (aggregatedScore > bestScore) {
                bestScore = aggregatedScore
                bestValue = currentCandidateValue
                println("      New best score found!")
            }

            // Restore original default parameter state for the next iteration (if it was added temporarily)
            if (originalParamIndex == -1) {
                defaultParamsInternal.removeIf { it.key == parameterKeyToOptimize && it.value == currentCandidateValue }
            }
        }

        // Restore the original default parameter value if it was modified
        originalParamValue?.let {
            if (originalParamIndex != -1) defaultParamsInternal[originalParamIndex] = Parameter(parameterKeyToOptimize, it)
        } ?: run {
            // If it was added temporarily and was not the best, ensure it's removed.
            // If it was the best, it might still be there - this part needs careful state management.
            // For simplicity, this dummy doesn't perfectly restore if the best was a new temporary add.
        }
        // --- End of Simplified Optimization Loop ---

        return PromptOptimizationResult(
            optimizedParameterKey = parameterKeyToOptimize,
            bestValue = bestValue,
            bestScore = bestScore,
            history = optimizationHistory
        )
    }
}

/**
 * Top-level DSL function to create and configure a [SkrollSet].
 *
 * @param description An optional description for the set of skrolls.
 * @param block A DSL block to configure the [SkrollSet] (define default parameters and skrolls).
 * @return The configured [SkrollSet] instance.
 */
fun skrollSet(description: String? = null, block: SkrollSet.() -> Unit): SkrollSet {
    val set = SkrollSet(description)
    set.block()
    return set
}
