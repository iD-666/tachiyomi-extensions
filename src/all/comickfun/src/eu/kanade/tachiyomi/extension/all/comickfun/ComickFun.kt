package eu.kanade.tachiyomi.extension.all.comickfun

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

abstract class ComickFun(
    override val lang: String,
    private val comickFunLang: String
) : HttpSource() {

    companion object {
        const val prefixIdSearch = "id:"
        private const val limit = 20
        val dateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
        val markdownLinksRegex = "\\[([^]]+)\\]\\(([^)]+)\\)".toRegex()
        val markdownItalicBoldRegex = "\\*+\\s*([^\\*]*)\\s*\\*+".toRegex()
        val markdownItalicRegex = "_+\\s*([^_]*)\\s*_+".toRegex()
    }

    override val name = "Comick"
    override val baseUrl = "https://comick.app"
    private val apiUrl = "https://api.comick.fun"
    override val supportsLatest = true

    override fun getFilterList() = getFilters()

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T
    
    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString(body.string())
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = true
    }

    override fun headersBuilder() = Headers.Builder().apply {
        add("Referer", "$baseUrl/")
        add("User-Agent", "Tachiyomi ${System.getProperty("http.agent")}")
    }

    override val client = network.client.newBuilder()
        .addInterceptor(::thumbnailIntercept)
        .rateLimit(3, 1)
        .build()

    /** Popular Manga **/
    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/v1.0/search?sort=user_follow_count&limit=$limit&page=$page&tachiyomi=true"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<List<SearchManga>>()
        return MangasPage(
            result.map(SearchManga::toSManga),
            hasNextPage = result.size >= limit
        )
    }

    /** Latest Manga **/
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/v1.0/search?sort=uploaded&limit=$limit&page=$page&tachiyomi=true"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    /** Manga Search **/
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val popularNewComics = filters.find { it is PopularNewComicsFilter } as PopularNewComicsFilter
        val mostViewed = filters.find { it is MostViewedFilter } as MostViewedFilter
        val sortFilter = filters.find { it is SortFilter } as SortFilter
        
        return when {
            //url deep link
            query.startsWith(prefixIdSearch) -> {
                val slug = query.removePrefix(prefixIdSearch)
                client.newCall(GET("$apiUrl/$slug?tachiyomi=true", headers))
                    .asObservableSuccess()
                    .map(::searchMangaParse)
            }
            sortFilter.getValue() == "hot" -> {
                client.newCall(popularNewComicsRequest(page, filters))
                    .asObservableSuccess()
                    .map(::popularNewComicsParse)
            }
            popularNewComics.state > 0 -> {
            }
            mostViewed.state > 0 -> {
            }
            else -> client.newCall(searchMangaRequest(page, query.trim(), filters))
                    .asObservableSuccess()
                    .map(::searchMangaParse)
        }
    }

    private fun popularNewComicsRequest(page: Int, filters: FilterList): Request {
        val url = "$apiUrl/chapter?order=hot&accept_erotic_content=true&page=$page&tachiyomi=true".toHttpUrl().newBuilder().apply {
            filters.forEach { filter ->
                if (filter is TypeFilter) {
                    filter.state.filter { it.state }.forEach { typeFilter ->
                        val type = when (typeFilter.value) {
                            "jp" -> "manga"
                            "cn" -> "manhua"
                            "kr" -> "manhwa"
                            else -> null
                        }
                        if (type != null) addQueryParameter("comic_types", type)
                    }
                }
            }
            if (comickFunLang != "all") addQueryParameter("lang", comickFunLang)
        }.build()
    }

    private fun popularNewComicsParse(response: Response): MangasPage {
        val result = response.parseAs<List<SearchComic>>()
        return MangasPage(
            result.map(SearchComic::toSManga),
            hasNextPage = result.size >= limit
        )
    }
    
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val textSearchWithFilter = filterList.findInstance<TextSearchFilter>()?.state ?: false
        val noFilterUrl = "$apiUrl/v1.0/search?q=$query&limit=$limit&page=$page&tachiyomi=true".toHttpUrl()
        val url = "$apiUrl/v1.0/search".toHttpUrl().newBuilder().apply {
            filters.forEach { it ->
                when (it) {
                    is CompletedFilter -> if (it.state) addQueryParameter("completed", "true")
                    is GenreFilter -> {
                        it.state.filter { it.isIncluded() }.forEach { addQueryParameter("genres", it.value) }
                        it.state.filter { it.isExcluded() }.forEach { addQueryParameter("excludes", it.value) }
                    }
                    is DemographicFilter -> it.state.filter { it.isIncluded() }.forEach { addQueryParameter("demographic", it.value) }
                    is TypeFilter -> it.state.filter { it.state }.forEach { addQueryParameter("country", it.value) }
                    is SortFilter -> if (it.getValue() != "hot") addQueryParameter("sort", it.getValue())
                    is StatusFilter -> if (it.state > 0) addQueryParameter("status", it.getValue())
                    is CreatedAtFilter -> if (it.state > 0) addQueryParameter("time", it.getValue())
                    is MinimumFilter -> if (it.state.isNotEmpty()) addQueryParameter("minimum", it.state)
                    is FromYearFilter -> if (it.state.isNotEmpty()) addQueryParameter("from", it.state)
                    is ToYearFilter -> if (it.state.isNotEmpty()) addQueryParameter("to", it.state)
                    is TagFilter -> if (it.state.isNotEmpty()) it.state.toTagSlug().forEach { addQueryParameter("tags", it) }
                    else -> {}
                }
            }
            addQueryParameter("q", query)
            addQueryParameter("tachiyomi", "true")
            addQueryParameter("limit", "$limit")
            addQueryParameter("page", "$page")
        }.build()

        return GET(
            if (!textSearchWithFilter) url else noFilterUrl,
            headers
        )
    }

    private fun String.toTagSlug(): List<String> {
        return this.trim().lowercase()
            .replace(Regex("(?m) \\(\\d+\\)$"), "")
            .replace(Regex("(?m)'s$"), "-s")
            .replace(Regex("[^a-z0-9&'’ -/\n]"), "")
            .replace("/", "-")
            .replace(" ", "-")
            .replace("&", "-and-amp-")
            .replace("'", "-and-039-")
            .replace("’", "-and-039-")
            .replace("--", "-")
            .replace(Regex("(?m)\n+"), ",")
            .split(",")
            .map { it.removePrefix("-") }
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)
        
    /** Manga Details **/
    override fun mangaDetailsRequest(manga: SManga): Request {
        /**
         * This exception will be shown if the manga URL does not end with '#'.
         * Please do not append a '#' suffix if the manga URL is a slug.
         */
        if (!manga.url.endsWith("#")) {
            throw Exception("Migrate from Comick to Comick")
        }

        val mangaUrl = manga.url.removeSuffix("#")
        return GET("$apiUrl$mangaUrl?tachiyomi=true", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaData = response.parseAs<Manga>()
        return mangaData.toSManga()
    }

    override fun getMangaUrl(manga: SManga): String {
        return "$baseUrl${manga.url.removeSuffix("#")}"
    }

    /** Manga Chapter List **/
    override fun chapterListRequest(manga: SManga): Request {
        /**
         * This exception will be shown if the manga URL does not end with '#'.
         * Please do not append a '#' suffix if the manga URL is a slug.
         */
        if (!manga.url.endsWith("#")) {
            throw Exception("Migrate from Comick to Comick")
        }
        
        return paginatedChapterListRequest(manga.url.removeSuffix("#"), 1)
    }

    private fun paginatedChapterListRequest(mangaUrl: String, page: Int): Request {
        return GET(
            "$apiUrl$mangaUrl".toHttpUrl().newBuilder().apply {
                addPathSegment("chapters")
                if (comickFunLang != "all") addQueryParameter("lang", comickFunLang)
                addQueryParameter("tachiyomi", "true")
                addQueryParameter("page", "$page")
            }.build(),
            headers,
        )
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapterListResponse = response.parseAs<ChapterList>()

        val mangaUrl = response.request.url.toString()
            .substringBefore("/chapters")
            .substringAfter(apiUrl)

        var resultSize = chapterListResponse.chapters.size
        var page = 2

        while (chapterListResponse.total > resultSize) {
            val newRequest = paginatedChapterListRequest(mangaUrl, page)
            val newResponse = client.newCall(newRequest).execute()
            val newChapterListResponse = newResponse.parseAs<ChapterList>()

            chapterListResponse.chapters += newChapterListResponse.chapters

            resultSize += newChapterListResponse.chapters.size
            page += 1
        }

        return chapterListResponse.chapters.map { it.toSChapter(mangaUrl) }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return "$baseUrl${chapter.url}"
    }

    /** Chapter Pages **/
    override fun pageListRequest(chapter: SChapter): Request {
        val chapterHid = chapter.url.substringAfterLast("/").substringBefore("-")
        return GET("$apiUrl/chapter/$chapterHid?tachiyomi=true", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<PageList>()
        return result.chapter.images.mapIndexedNotNull { index, data ->
            if (data.url == null) null else Page(index = index, imageUrl = data.url)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }
}
