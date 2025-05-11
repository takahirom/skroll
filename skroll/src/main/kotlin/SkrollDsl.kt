// SkrollDsl.kt
package io.github.takahirom.skroll

// DSL Marker for better type safety and context control
@DslMarker
annotation class SkrollDsl

/**
 * Entry point for defining and running Skroll tests within a JUnit 5 test method.
 * Test failures (e.g., assertion errors) will propagate and be handled by JUnit.
 */
fun skrollTest(description: String = "Skroll Test Block", block: SkrollTestContext.() -> Unit) {
    println("Starting Skroll Test Block: $description")
    val context = SkrollTestContext(description)
    context.block() // Configure fixtures and test cases
    context.executeConfiguredTests() // Execute them
    println("Finished Skroll Test Block: $description")
}

@SkrollDsl
class SkrollTestContext(private val blockDescription: String) {
    private val fixtures = mutableListOf<Fixture>() // Stores globally defined fixtures for this context
    private val testRuns = mutableListOf<TestRun>() // Stores configured test runs

    // Internal class to hold a configured test run (fixture + case)
    internal data class TestRun(val fixtureToUse: Fixture, val testCase: CurlTestCase)

    private var defaultFixture: Fixture? = null
    private var curlExecutor: CurlExecutor = DefaultCurlExecutor

    /**
     * Defines a list of fixtures to be used globally within this `skrollTest` block
     * or for specific `curlCases` blocks that iterate over them.
     */
    fun fixtures(block: FixtureListBuilder.() -> Unit): List<Fixture> {
        val builder = FixtureListBuilder()
        builder.block()
        val definedFixtures = builder.build()
        this.fixtures.addAll(definedFixtures)
        return definedFixtures
    }

    /**
     * Defines a default fixture for test cases within this `skrollTest` block
     * that do not have a more specific fixture assigned.
     */
    fun defaultFixture(name: String? = "Default Fixture", data: Map<String, String>) {
        this.defaultFixture = Fixture(name, data)
    }

    /**
     * Defines a set of curl test cases.
     * If `fixtureForBlock` is null, it tries to use the `defaultFixture`.
     * If still null, an empty fixture is used (placeholders might not be replaced).
     */
    fun curlCases(fixtureForBlock: Fixture? = null, block: CurlCaseListBuilder.() -> Unit) {
        val effectiveFixture = fixtureForBlock ?: this.defaultFixture ?: Fixture(data = emptyMap())
        val builder = CurlCaseListBuilder(effectiveFixture)
        builder.block()
        builder.build().forEach { testCase ->
            this.testRuns.add(TestRun(effectiveFixture, testCase))
        }
    }

    /**
     * Defines curl test cases that will be run for each fixture in `fixturesToIterate`.
     */
    fun curlCases(fixturesToIterate: List<Fixture>, block: CurlCaseListBuilder.(currentFixture: Fixture) -> Unit) {
        if (fixturesToIterate.isEmpty()) {
            println("Warning: curlCases (iterator) called with an empty list of fixtures. No tests will be generated from this block.")
            return
        }
        fixturesToIterate.forEach { currentFixture ->
            val builder = CurlCaseListBuilder(currentFixture) // Each iteration gets its specific fixture
            builder.block(currentFixture)
            builder.build().forEach { testCase ->
                this.testRuns.add(TestRun(currentFixture, testCase))
            }
        }
    }

    fun setCurlExecutor(executor: CurlExecutor) {
        this.curlExecutor = executor
    }

    internal fun executeConfiguredTests() {
        if (testRuns.isEmpty()) {
            println("No Skroll curl test cases were configured in block: $blockDescription")
            return
        }

        var Succeeded = 0
        var Failed = 0

        testRuns.forEachIndexed { index, testRun ->
            val (fixtureToUse, testCase) = testRun
            println("\n--- Executing Skroll Case ${index + 1}: ${testCase.name} (Fixture: ${fixtureToUse.name ?: "Unnamed"}) ---")

            if (fixtureToUse.data.isEmpty() && testCase.curlCommandTemplate.contains("{")) {
                println("Warning: Fixture data for '${fixtureToUse.name ?: "Unnamed"}' is empty but command template '${testCase.name}' seems to contain placeholders.")
            }
            println("Using Fixture Data: ${fixtureToUse.data}")


            val commandWithPlaceholdersReplaced = TemplateUtils.replacePlaceholders(
                testCase.curlCommandTemplate,
                fixtureToUse.data
            )

            println("Executing Curl Command: $commandWithPlaceholdersReplaced")

            try {
                val apiResponse = curlExecutor.execute(commandWithPlaceholdersReplaced)
                // Log basic response info. Detailed logging can be done in assertion block if needed.
                println("Response Status Code: ${apiResponse.statusCode}")
                if (!apiResponse.errorOutput.isNullOrBlank()){
                    println("Curl STDERR: ${apiResponse.errorOutput}")
                }


                // Perform the assertion. If this throws an error, JUnit will catch it.
                testCase.assertResponse(apiResponse)
                println("✅ Skroll Case '${testCase.name}' PASSED")
                Succeeded++
            } catch (e: Throwable) { // Catch assertion errors or any other exception
                println("❌ Skroll Case '${testCase.name}' FAILED: ${e.message}")
                // e.printStackTrace(System.out) // JUnit will typically handle stack trace logging.
                Failed++
                // IMPORTANT: Re-throw the exception so JUnit knows the test failed.
                // If multiple cases are in one skrollTest block, this will stop the block.
                // Consider if each `skrollTest` should map to one logical JUnit test,
                // or if one `skrollTest` can contain many sub-tests that all must pass.
                // For now, any failure in a sub-case fails the entire JUnit @Test method.
                throw e
            }
        }
        println("\n--- Skroll Block Summary for '$blockDescription' ---")
        println("Total Skroll Cases Executed: ${testRuns.size}, Succeeded: $Succeeded, Failed: $Failed")
        if(Failed > 0) {
            // This message is more for when not re-throwing, but good for console visibility
            println("At least one Skroll case failed in this block.")
        }
    }
}

@SkrollDsl
class FixtureListBuilder {
    private val fixtures = mutableListOf<Fixture>()

    fun add(name: String? = null, data: Map<String, String>) {
        fixtures.add(Fixture(name, data))
    }

    fun add(fixture: Fixture) {
        fixtures.add(fixture)
    }

    internal fun build(): List<Fixture> = fixtures
}

@SkrollDsl
class CurlCaseListBuilder(private val fixtureForBlock: Fixture) {
    private val curlTestCases = mutableListOf<CurlTestCase>()

    fun case(
        name: String,
        curlCommandTemplate: String,
        assertionBlock: (ApiResponse) -> Unit
    ) {
        curlTestCases.add(CurlTestCase(name, curlCommandTemplate, assertionBlock))
    }

    fun caseFromResource(
        name: String,
        commandTemplateResourcePath: String,
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
        assertionBlock: (ApiResponse) -> Unit
    ) {
        val template = TemplateUtils.loadFromResources(commandTemplateResourcePath, classLoader)
        curlTestCases.add(CurlTestCase(name, template, assertionBlock))
    }

    internal fun build(): List<CurlTestCase> = curlTestCases
}
