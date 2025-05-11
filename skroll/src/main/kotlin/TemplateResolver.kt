package io.github.takahirom.skroll

/**
 * Interface for resolving placeholders in a command template.
 */
interface TemplateResolver {
    /**
     * Resolves placeholders in the command template using the provided parameters.
     * @param commandTemplate The template string with placeholders (e.g., "{key}").
     * @param parameters A map of parameter keys to their string values.
     * @return The command string with all placeholders resolved.
     */
    fun resolve(commandTemplate: String, parameters: Map<String, String>): String
}

/**
 * A simple implementation of TemplateResolver that does basic string replacement.
 * More sophisticated implementations might handle escaping, typed parameters, etc.
 */
class SimpleTemplateResolver : TemplateResolver {
    override fun resolve(commandTemplate: String, parameters: Map<String, String>): String {
        var resolvedCommand = commandTemplate
        parameters.forEach { (key, value) ->
            // Basic placeholder replacement.
            // In a real implementation, consider proper escaping for shell commands, JSON, etc.
            resolvedCommand = resolvedCommand.replace("{$key}", value)
        }
        return resolvedCommand
    }
}
