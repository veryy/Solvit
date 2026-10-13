package dev.solvit.web

import dev.solvit.core.WebPage
import dev.solvit.core.WebPageLink
import org.json.JSONObject
import java.net.URI

internal object PageObservation {
    fun validateUrl(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull()
        require(value.length <= 8192 && uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "网页地址必须是不含账号的 HTTPS 地址"
        }
        return value
    }

    fun parse(value: String): WebPage {
        require(value.length <= 512 * 1024) { "网页观察结果超出限制" }
        val json = JSONObject(value)
        val url = validateUrl(json.getString("url"))
        val links = json.optJSONArray("links")
        val result = mutableListOf<WebPageLink>()
        val seen = mutableSetOf<String>()
        if (links != null) for (index in 0 until minOf(links.length(), 120)) {
            val link = links.optJSONObject(index) ?: continue
            val target = link.optString("url")
            if (target.length > 2048 || runCatching { validateUrl(target) }.isFailure || !seen.add(target)) continue
            result += WebPageLink(target, link.optString("title").take(160), link.optString("snippet").take(400))
            if (result.size == 60) break
        }
        val text = json.optString("text")
        return WebPage(url, json.optString("title").take(256), text.take(16_000), result,
            json.optBoolean("truncated") || text.length > 16_000, json.opt("searchResults") == true)
    }

    // Observe the DOM after page loading; no native JavaScript interface is installed.
    val script = """
        (() => {
          const root = document.querySelector('main,article,[role="main"]') || document.body;
          if (!root) return JSON.stringify({url:location.href,title:document.title,text:'',links:[]});
          // Mobile Bing nests the heading inside the anchor; desktop reverses it.
          const searchLinks = Array.from(root.querySelectorAll('.b_algo h2')).map(h =>
            h.closest('a[href]') || h.querySelector('a[href]')).filter(Boolean);
          searchLinks.push(...root.querySelectorAll('a.result__a[href]'));
          const links = Array.from(searchLinks.length ? searchLinks : root.querySelectorAll('a[href]')).slice(0,120).map(a => {
            const block = a.closest('.b_algo,.result,article,section') || a.parentElement;
            return {url:a.href,title:(a.innerText || a.textContent || '').trim().slice(0,160),
              snippet:(block?.innerText || block?.textContent || '').trim().slice(0,400)};
          });
          const clone = root.cloneNode(true);
          clone.querySelectorAll('script,style,noscript,svg,nav,header,footer,aside,iframe').forEach(n => n.remove());
          clone.querySelectorAll('p,div,li,h1,h2,h3,h4,h5,h6,br,tr').forEach(n => n.appendChild(document.createTextNode('\n')));
          const text = (clone.textContent || '').replace(/[ \t]+/g,' ').replace(/\n[ \t]*\n+/g,'\n').trim();
          return JSON.stringify({url:location.href,title:document.title,text:text.slice(0,16000),
            truncated:text.length>16000,links,searchResults:searchLinks.length>0});
        })()
    """.trimIndent()
}
