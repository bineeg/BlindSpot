package com.blindspot.modules.engine // 🎯 Updated package declaration

object UrlModule {
    fun normalizePath(rawUrl: String): String {
        if (rawUrl.isBlank()) return "/"

        var clean = rawUrl.trim().substringBefore("?").substringBefore("#")

        if (clean.contains("://")) {
            clean = "/" + clean.substringAfter("://").substringAfter("/", "")
        }

        if (!clean.startsWith("/")) clean = "/$clean"
        if (clean.endsWith("/") && clean.length > 1) clean = clean.dropLast(1)

        return clean
    }
}