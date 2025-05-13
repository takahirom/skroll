package io.github.takahirom.skroll

/**
 * Responsible for executing a [SkrollSet].
 * Decouples execution logic from the SkrollSet data structure.
 */
class SkrollSetExecutor<API_INPUT>(
    private val apiExecutor: ApiExecutor<API_INPUT> = CurlApiExecutor() as ApiExecutor<API_INPUT>,
) {
    class ExecutorOptions(
    )
    /**
     * Executes all [CurlSkroll]s within the given [SkrollSet].
     *
     * @param skrollSet The [SkrollSet] containing the definitions to execute.
     * @return A list of [SkrollRunResult] for each executed skroll.
     */
    suspend fun executeAll(skrollSet: SkrollSet<API_INPUT>, executorOptions: ExecutorOptions = ExecutorOptions()): List<SkrollRunResult> {
        println("Executing SkrollSet: ${skrollSet.description ?: "Untitled Skroll Set"}")
        val results = mutableListOf<SkrollRunResult>()
        val defaultParamsMap: Map<String, String> = skrollSet.defaultParameters.associate { it.key to it.value }

        for (skroll in skrollSet.skrolls) {

            var apiResponse: ApiResponse? = null
            var evaluationOutput: EvaluationOutput? = null
            var error: Throwable? = null

            val skrollFullName = skroll.name
            println("  Running Skroll: $skrollFullName")

            try {
                val input = skroll.createInput(defaultParamsMap)
                apiResponse = apiExecutor.execute(input)
                evaluationOutput = skroll.metrics(apiResponse)
                println("    Metrics for '$skrollFullName': PrimaryScore=${evaluationOutput.primaryScore}, Details=${evaluationOutput.details}")
            } catch (e: Throwable) {
                error = e
                println("    ERROR during execution or metrics for '$skrollFullName': ${e.message}")
                // If curlExecutor throws, apiResponse might be null.
                // If metrics throws, apiResponse will be set.
            }
            results.add(SkrollRunResult(skroll.name, evaluationOutput, apiResponse, error))
        }
        return results
    }
}
