// TemplateUtils.kt
package io.github.takahirom.skroll

import java.io.File
import java.nio.charset.Charset

internal object TemplateUtils {

    /**
     * Loads a string content from a resource file.
     *
     * @param resourcePath The path to the resource file (e.g., "case1.txt").
     * @return The content of the file as a String.
     * @throwsIllegalArgumentException if the resource is not found.
     */
    fun loadFromResources(resourcePath: String, classLoader: ClassLoader = Thread.currentThread().contextClassLoader): String {
        val resourceStream = classLoader.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath. Ensure it's in your resources directory.")
        return resourceStream.bufferedReader(Charset.defaultCharset()).use { it.readText() }
    }

    /**
     * Replaces placeholders in a template string with values from a map.
     * Placeholders should be in the format {KEY}.
     *
     * @param template The template string.
     * @param variables A map of placeholder keys to their values.
     * @return The string with placeholders replaced.
     */
    fun replacePlaceholders(template: String, variables: Map<String, String>): String {
        var result = template
        variables.forEach { (key, value) ->
            result = result.replace("{$key}", value, ignoreCase = false) // Consider case sensitivity
        }
        return result
    }
}
