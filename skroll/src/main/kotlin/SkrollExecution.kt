package io.github.takahirom.skroll

/**
 * Responsible for executing a [SkrollSet].
 * Decouples execution logic from the SkrollSet data structure.
 */
class SkrollSetExecutor(
    private val curlExecutor: CurlExecutor = DefaultCurlExecutor(),
    private val templateResolver: TemplateResolver = SimpleTemplateResolver()
) {
    class ExecutorOptions(
    )
    /**
     * Executes all [Skroll]s within the given [SkrollSet].
     *
     * @param skrollSet The [SkrollSet] containing the definitions to execute.
     * @return A list of [SkrollRunResult] for each executed skroll.
     */
    suspend fun executeAll(skrollSet: SkrollSet, executorOptions: ExecutorOptions = ExecutorOptions()): List<SkrollRunResult> {
        println("Executing SkrollSet: ${skrollSet.description ?: "Untitled Skroll Set"}")
        val results = mutableListOf<SkrollRunResult>()
        val defaultParamsMap = skrollSet.defaultParameters.associate { it.key to it.value }

        for (definition in skrollSet.skrolls) {
            val skrollSpecificParamsMap = definition.localParameters.associate { it.key to it.value }
            val finalParameters = defaultParamsMap + skrollSpecificParamsMap // Skroll-specific overrides defaults

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
                // If curlExecutor throws, apiResponse might be null.
                // If metrics throws, apiResponse will be set.
            }
            results.add(SkrollRunResult(definition.name, evaluationOutput, apiResponse, error))
        }
        return results
    }
}
