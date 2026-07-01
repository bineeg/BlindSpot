package com.blindspot.modules.engine

import java.util.regex.Pattern

object JsScannerModule {

    private val endpointRegex = Pattern.compile("(?<=\\\"|\\'|\\`)(/[a-zA-Z0-9_\\-\\.\\/\\{\\}]+)(?=\\\"|\\'|\\`)")
    private val parameterRegex = Pattern.compile("\\{[a-zA-Z0-9_\\-]+\\}|:[a-zA-Z0-9_\\-]+")

    /**
     * Parses raw JavaScript, normalizes structural paths, and classifies them.
     * Pure extraction only — deduplicated within this call but with no storage
     * coupling. Exclusion filtering and host-scoped registration are the
     * caller's responsibility so excluded paths never enter the store.
     *
     * @return distinct (cleaned path, inferred type) pairs found in [jsCode].
     */
    fun extractEnrichedEndpoints(jsCode: String): List<Pair<String, String>> {
        if (jsCode.isBlank()) return emptyList()

        val seen = HashSet<String>()
        val discoveredPairs = mutableListOf<Pair<String, String>>()
        val matcher = endpointRegex.matcher(jsCode)

        while (matcher.find()) {
            val rawMatch = matcher.group(1)

            if (rawMatch.endsWith(".png") || rawMatch.endsWith(".jpg") ||
                rawMatch.endsWith(".js") || rawMatch.startsWith("//") || rawMatch.length <= 2) {
                continue
            }

            val cleanPath = UrlModule.normalizePath(rawMatch)

            if (seen.add(cleanPath)) {
                discoveredPairs.add(Pair(cleanPath, inferRouteType(cleanPath)))
            }
        }
        return discoveredPairs
    }

    private fun inferRouteType(path: String): String {
        val lowerPath = path.lowercase()
        return when {
            parameterRegex.matcher(path).find() -> "⚙️ Dynamic Endpoint"
            lowerPath.contains("/config") || lowerPath.contains("/init") || lowerPath.contains("/setup") -> "🛠️ Configuration API"
            lowerPath.contains("auth") || lowerPath.contains("login") || lowerPath.contains("session") || lowerPath.contains("token") -> "🔑 Auth / Session"
            lowerPath.contains("/download") || lowerPath.contains("/upload") || lowerPath.contains("/file") -> "📁 File Handling"
            lowerPath.contains("/api/") || lowerPath.contains("/v0/") || lowerPath.contains("/v1/") -> "⚡ Core API"
            else -> "🔗 General Route"
        }
    }
}
