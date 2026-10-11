package dev.solvit.core

data class WebPageLink(val url: String, val title: String, val snippet: String = "")
data class WebPage(val url: String, val title: String, val text: String,
    val links: List<WebPageLink> = emptyList(), val truncated: Boolean = false)

/** Anonymous page observation. Browsing does not grant file, shell or credential access. */
interface WebPageService { suspend fun visit(url: String): WebPage }
val WebPageServiceKey = ServiceKey("web.pages", WebPageService::class)
