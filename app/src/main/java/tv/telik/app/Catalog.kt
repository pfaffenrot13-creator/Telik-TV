package tv.telik.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.TimeUnit

data class TvChannel(
    val id: String,
    val name: String,
    val category: String,
    val pageUrl: String? = null,
    val streamUrl: String? = null
)

/** Reads public Smotret.tv pages and an optional operator-maintained JSON feed. */
class SmotretCatalog(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun refresh(): List<TvChannel> {
        val result = linkedMapOf<String, TvChannel>()
        val feedUrl = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FEED, "").orEmpty()
        if (feedUrl.isNotBlank()) runCatching { readRemoteFeed(feedUrl) }.getOrNull()?.forEach { result[it.id] = it }
        runCatching {
            val home = Jsoup.parse(fetch("https://smotret.tv/"), "https://smotret.tv/")
            val categories = home.select("a[href]").mapNotNull { a ->
                val label = a.text().trim()
                val href = a.absUrl("href")
                if (label in CATEGORY_NAMES && href.startsWith("https://smotret.tv/")) label to href else null
            }.distinctBy { it.first }
            categories.forEach { (category, url) ->
                runCatching {
                    val doc = Jsoup.parse(fetch(url), url)
                    doc.select("a[href]").forEach { a ->
                        val name = a.text().trim()
                        val page = a.absUrl("href").substringBefore('#')
                        if (name.isNotBlank() && page.startsWith("https://smotret.tv/") && page != url &&
                            !page.trimEnd('/').equals("https://smotret.tv", true)) {
                            val id = page.trimEnd('/').substringAfterLast('/').ifBlank { page }
                            result.putIfAbsent(id, TvChannel(id, name, category, page))
                        }
                    }
                }
            }
        }
        return result.values.toList()
    }

    fun directMediaUrl(channel: TvChannel): String? {
        channel.streamUrl?.takeIf(::isAllowedMediaUrl)?.let { return it }
        val page = channel.pageUrl ?: return null
        val doc = Jsoup.parse(fetch(page), page)
        val candidate = doc.select("video source[src], video[src]").firstOrNull()?.absUrl("src")
        return candidate?.takeIf(::isAllowedMediaUrl)
    }

    private fun readRemoteFeed(url: String): List<TvChannel> {
        require(url.startsWith("https://")) { "Адрес каталога должен начинаться с https://" }
        val json = JSONObject(fetch(url))
        val array = json.optJSONArray("channels") ?: JSONArray()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("name").trim()
            val stream = item.optString("streamUrl").takeIf { it.isNotBlank() }
            val page = item.optString("pageUrl").takeIf { it.startsWith("https://") }
            if (name.isBlank() || (stream == null && page == null)) null else {
                val id = item.optString("id").ifBlank { name.lowercase() }
                TvChannel(id, name, item.optString("category", "Другие"), page, stream?.takeIf(::isAllowedMediaUrl))
            }
        }
    }

    private fun isAllowedMediaUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
        return listOf(".m3u8", ".mpd", ".mp4", ".mp3", ".aac").any { path.contains(it) }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Telik-TV/0.2 (Android TV)").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string() ?: error("Пустой ответ")
        }
    }

    companion object {
        const val PREFS = "telik_tv"
        const val KEY_FEED = "remote_feed_url"
        private val CATEGORY_NAMES = setOf("Основные", "Музыкальные", "Спортивные", "Новостные", "Детские", "Христианские", "Развлекательные", "Познавательные", "Региональные", "Релакс")
    }
}
