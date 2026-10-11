package dev.solvit.provider

import dev.solvit.core.*
import okhttp3.HttpUrl
import okhttp3.Response

/** Only response data, never request headers/body. Bound reads even for an endless error stream. */
internal class ResponseDiagnostics(private val config: ModelConfig, private val url: HttpUrl) {
    private val secrets = buildList {
        if (config.apiKey.isNotBlank()) add(config.apiKey)
        url.queryParameterNames.forEach { name -> addAll(url.queryParameterValues(name).filterNotNull().filter(String::isNotEmpty)) }
    }.sortedByDescending(String::length)
    private fun redact(value: String): String {
        var text = value
        secrets.forEach { text = text.replace(it, "[已隐藏]") }
        // A response may echo credentials from its own upstream, too.
        text = text.replace(Regex("(?i)Bearer\\s+[^\\s\\\"'<>]+"), "Bearer [已隐藏]")
        text = text.replace(Regex("(?i)([?&](?:[^=\\s]+)=)[^&\\s\\\"<>]+"), "$1[已隐藏]")
        return text.take(LIMIT)
    }
    fun details(response: Response? = null, body: String = ""): ModelErrorDetails = ModelErrorDetails(
        // Subscription query parameters and URL credentials never appear in diagnostics.
        endpoint = redact(url.newBuilder().query(null).fragment(null).username("").password("").build().toString()),
        httpStatus = response?.code,
        response = redact(body),
        requestId = (response?.header("x-request-id") ?: response?.header("request-id"))
            ?.take(256)?.let(::redact),
    )
    fun httpFailure(response: Response, summary: String): ModelException {
        val body = response.peekBody(LIMIT.toLong() + 1).string()
        return ModelException(summary, details(response, body.take(LIMIT) + if (body.length > LIMIT) "\n[响应已截断]" else ""))
    }
    companion object { const val LIMIT = 32_768 }
}
