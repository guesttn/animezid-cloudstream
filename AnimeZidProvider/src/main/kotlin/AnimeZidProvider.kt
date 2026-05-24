package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeZidProvider : MainAPI() {
    override var mainUrl = "https://animezid.cam"
    override var name = "انمي زد"
    override val lang = "ar"
    override val hasMainPage = true
    override val hasSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries,
    )

    // ============================
    // الصفحة الرئيسية
    // ============================
    override val mainPage = mainPageOf(
        "$mainUrl/newvideos.php" to "أحدث الإضافات",
        "$mainUrl/category.php?cat=new-anime-eps" to "أحدث حلقات الأنمي",
        "$mainUrl/category.php?cat=new-series-eps" to "أحدث حلقات الكرتون",
        "$mainUrl/category.php?cat=new-movies" to "أحدث الأفلام",
        "$mainUrl/category.php?cat=disney-masr" to "ديزني بالمصري",
        "$mainUrl/category.php?cat=anime" to "الأنمي",
        "$mainUrl/category.php?cat=dubbed-anime" to "أنمي مدبلج",
        "$mainUrl/category.php?cat=spacetoon" to "سبيستون",
        "$mainUrl/topvideos.php" to "الأكثر مشاهدة",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}&page=$page" else request.data
        val doc = app.get(url).document
        val items = doc.select("div.video-block, div.col-video, .video-item, a[href*='watch.php']")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ============================
    // تحويل عنصر HTML إلى نتيجة بحث
    // ============================
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a[href*='watch.php']") ?: return null
        val href = anchor.attr("href").let {
            if (it.startsWith("http")) it else "$mainUrl/$it".replace("//", "/").replace(":/", "://")
        }
        val title = anchor.attr("title").ifEmpty {
            anchor.text()
        }.trim()
        if (title.isEmpty()) return null
        val poster = this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        val isMovie = title.contains("فيلم", ignoreCase = true)
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    // ============================
    // البحث
    // ============================
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search.php?search_text=${query.encodeUrl()}").document
        return doc.select("a[href*='watch.php']").mapNotNull { anchor ->
            val href = anchor.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl/$it"
            }
            val title = anchor.attr("title").ifEmpty { anchor.text() }.trim()
            if (title.isEmpty()) return@mapNotNull null
            val poster = anchor.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
            val isMovie = title.contains("فيلم", ignoreCase = true)
            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            }
        }.distinctBy { it.url }
    }

    // ============================
    // صفحة تفاصيل المحتوى
    // ============================
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1, h2")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".video-thumbnail img, .thumb img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
        val description = doc.selectFirst(".video-description, .description, p")?.text()

        // استخرج معرف الفيديو من الرابط
        val vidId = Regex("vid=([a-zA-Z0-9]+)").find(url)?.groupValues?.get(1) ?: return null

        // تحقق إذا كان فيلم أو مسلسل
        val isMovie = title.contains("فيلم", ignoreCase = true)

        // جمع روابط الحلقات (إن وجدت)
        val episodeLinks = doc.select("a[href*='watch.php?vid=']").map {
            it.attr("href").let { href ->
                if (href.startsWith("http")) href else "$mainUrl/$href"
            }
        }.distinct()

        return if (isMovie || episodeLinks.size <= 1) {
            // فيلم
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // مسلسل/أنمي - استخرج الحلقات
            val episodes = episodeLinks.mapIndexed { index, epUrl ->
                val epDoc = app.get(epUrl).document
                val epTitle = epDoc.selectFirst("h1, h2")?.text() ?: "الحلقة ${index + 1}"
                Episode(epUrl, epTitle, episode = index + 1)
            }
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        }
    }

    // ============================
    // استخراج روابط التشغيل
    // ============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vidId = Regex("vid=([a-zA-Z0-9]+)").find(data)?.groupValues?.get(1) ?: return false
        val playUrl = "$mainUrl/play.php?vid=$vidId"
        val doc = app.get(playUrl).document

        // جرب استخراج iframe أو روابط مباشرة
        val iframes = doc.select("iframe[src]").map { it.attr("src") }
        val sources = doc.select("source[src]").map { it.attr("src") }

        // معالجة iframes
        iframes.forEach { iframeSrc ->
            val src = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl/$iframeSrc"
            loadExtractor(src, data, subtitleCallback, callback)
        }

        // معالجة روابط مباشرة
        sources.forEach { src ->
            if (src.contains(".m3u8") || src.contains(".mp4")) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = src,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = src.contains(".m3u8")
                    )
                )
            }
        }

        // جرب أيضاً صفحة embed
        val embedUrl = "$mainUrl/embed.php?vid=$vidId"
        val embedDoc = app.get(embedUrl, referer = mainUrl).document
        val embedIframes = embedDoc.select("iframe[src]").map { it.attr("src") }
        val embedSources = embedDoc.select("source[src]").map { it.attr("src") }

        embedIframes.forEach { iframeSrc ->
            val src = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl/$iframeSrc"
            loadExtractor(src, mainUrl, subtitleCallback, callback)
        }

        embedSources.forEach { src ->
            if (src.contains(".m3u8") || src.contains(".mp4")) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = src,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = src.contains(".m3u8")
                    )
                )
            }
        }

        return true
    }

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
