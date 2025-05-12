package io.github.takahirom.skroll

/**
 * Interface for evaluating a set of SkrollRunResults to produce an aggregated score,
 * typically used during prompt optimization.
 */
interface SkrollSetEvaluator {
    /**
     * Evaluates a list of [SkrollRunResult] and returns a single [Double] score.
     * This score is used by a [ParameterOptimizer] to guide the optimization process.
     *
     * @param results The list of results from executing all skrolls in a set with a candidate prompt.
     * @return An aggregated score.
     */
    fun evaluate(results: List<SkrollRunResult>): Double
}

/**
 * A simple [SkrollSetEvaluator] that calculates the average primaryScore from all successful skroll executions.
 */
class AveragePrimaryScoreEvaluator : SkrollSetEvaluator {
    override fun evaluate(results: List<SkrollRunResult>): Double {
        val scores = results.mapNotNull { it.evaluation?.primaryScore }
        return if (scores.isEmpty()) 0.0 else scores.average().let { if(it.isNaN()) 0.0 else it }
    }
}

/**
 * Interface for a parameter optimization strategy.
 */
interface ParameterOptimizer {
    /**
     * Optimizes a specific parameter within a [SkrollSet].
     *
     * @param skrollSet The [SkrollSet] whose parameter is to be optimized.
     * @param parameterKeyToOptimize The key of the default parameter to optimize.
     * @param initialValue The starting value for the parameter.
     * @param evaluator The [SkrollSetEvaluator] to assess the performance of candidate parameter values.
     * @param skrollSetExecutor The [SkrollSetExecutor] used to run the skrolls with candidate parameters.
     * @param optimizationConfig Configuration for the optimization process.
     * @return A [PromptOptimizationResult].
     */
    suspend fun optimize(
        skrollSet: SkrollSet,
        parameterKeyToOptimize: String,
        initialValue: String,
        evaluator: SkrollSetEvaluator,
        skrollSetExecutor: SkrollSetExecutor, // Optimizer needs to run executions
        optimizationConfig: OptimizationConfig
    ): PromptOptimizationResult
}

/**
 * A simple parameter optimizer implementation.
 * This is a placeholder for more sophisticated strategies (e.g., COPRO-like).
 */
class SimpleParameterOptimizer : ParameterOptimizer {
    override suspend fun optimize(
        skrollSet: SkrollSet,
        parameterKeyToOptimize: String,
        initialValue: String,
        evaluator: SkrollSetEvaluator,
        skrollSetExecutor: SkrollSetExecutor,
        optimizationConfig: OptimizationConfig
    ): PromptOptimizationResult {
        println("  [SimpleParameterOptimizer] Optimizing '$parameterKeyToOptimize', initial: \"$initialValue\", max iterations: ${optimizationConfig.maxIterations}")

        var bestValue = initialValue
        var bestScore = Double.NEGATIVE_INFINITY // Assuming higher is better

        val optimizationHistory = mutableListOf<Pair<String, Double>>()

        // Simplified: Try a few hardcoded variations or slight modifications
        val candidateValues = listOf(
            initialValue,
            "Slightly improved: $initialValue",
            "A completely different take for $parameterKeyToOptimize",
            initialValue.uppercase(),
            "${initialValue} Be very concise."
        ).take(optimizationConfig.maxIterations)


        for (currentCandidateValue in candidateValues) {
            println("    Trying value: \"$currentCandidateValue\"")

            // Create a temporary SkrollSet or modify defaultParameters for this iteration
            // This needs careful handling to not permanently alter the original skrollSet's defaults
            // or to pass the modified parameter down to the executor.
            // For this example, assume SkrollSet can be cloned or temporarily modified.
            // A cleaner way might be for the SkrollSetExecutor to accept override parameters.

            // Find the parameter to modify in the default parameters of the skrollSet
            val originalDefaultParams = skrollSet.defaultParameters.toMutableList()
            val paramIndex = originalDefaultParams.indexOfFirst { it.key == parameterKeyToOptimize }

            val tempDefaultParams: List<Parameter>
            if (paramIndex != -1) {
                val oldParam = originalDefaultParams[paramIndex]
                tempDefaultParams = originalDefaultParams.toMutableList().apply {
                    this[paramIndex] = oldParam.copy(value = currentCandidateValue)
                }
            } else {
                // If the parameter wasn't in defaults, add it for this run
                tempDefaultParams = originalDefaultParams + Parameter(parameterKeyToOptimize, currentCandidateValue)
            }

            // Create a temporary SkrollSet with the modified default parameter
            // This is a bit verbose; a helper to "execute SkrollSet with overridden default param" would be better.
            val tempSkrollSet = SkrollSet(skrollSet.description).apply {
                defaultParameters { tempDefaultParams }
                skrollSet.skrolls.forEach { addSkroll(it) } // Re-add definitions
            }


            val iterationResults = skrollSetExecutor.executeAll(tempSkrollSet)
            val aggregatedScore = evaluator.evaluate(iterationResults)
            optimizationHistory.add(currentCandidateValue to aggregatedScore)
            println("      Aggregated score for \"$currentCandidateValue\": $aggregatedScore")

            if (aggregatedScore > bestScore) {
                bestScore = aggregatedScore
                bestValue = currentCandidateValue
                println("      New best score found!")
            }
        }

        return PromptOptimizationResult(
            optimizedParameterKey = parameterKeyToOptimize,
            bestValue = bestValue,
            bestScore = bestScore,
            history = optimizationHistory
        )
    }
}
