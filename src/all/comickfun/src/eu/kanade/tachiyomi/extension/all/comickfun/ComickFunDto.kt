package eu.kanade.tachiyomi.extension.all.comickfun

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.util.Locale

@Serializable
data class SearchManga(
    val hid: String,
    val title: String,
    @SerialName("md_covers") val mdCovers: List<MDcovers> = emptyList(),
    @SerialName("cover_url") val cover: String? = null,

) {
    fun toSManga() = SManga.create().apply {
        // appending # at end as part of migration from slug to hid
        url = "/comic/$hid#"
        title = this@SearchManga.title
        thumbnail_url = parseCover(cover, mdCovers)
    }
}

@Serializable
data class Manga(
    val comic: Comic,
    val artists: List<Name> = emptyList(),
    val authors: List<Name> = emptyList(),
    val genres: List<Name> = emptyList(),
    val demographic: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        // appennding # at end as part of migration from slug to hid
        url = "/comic/${comic.hid}#"
        title = comic.title
        description = comic.desc?.beautifyDescription()
        if (comic.relateFrom.isNotEmpty()) {
            description += if (description.isNullOrEmpty()) comic.relatedSeries else "\n\n\n" + comic.relatedSeries
        }
        if (comic.altTitles.isNotEmpty()) {
            description += when {
                description.isNullOrEmpty() -> ""
                comic.relateFrom.isEmpty() -> "\n\n\n"
                else -> "\n\n"
            }
            description += "Alternative Titles:\n"
            description += comic.alternativeTitles
        }
        description += if (description.isNullOrEmpty()) "" else "\n\n"
        description += "Published: ${comic.year ?: "N/A"}"
        description += "\nFollowed by: ${comic.formatFollow()}"
        status = comic.status.parseStatus(comic.translationComplete)
        thumbnail_url = parseCover(comic.cover, comic.mdCovers)
        artist = artists.joinToString { it.name.trim() }
        author = authors.joinToString { it.name.trim() }
        genre = comic.origination.map { it.name.trim() }
            .plus(listOfNotNull(demographic))
            .plus(genres.map { it.name.trim() })
            .plus("Content Rating: ${comic.content_rating.capitalize()}")
            .plus(comic.categories?.map { it.category.title?.trim() }?.filterNotNull())
            .distinct()
            .joinToString()
            
    }
}

@Serializable
data class Comic(
    val hid: String,
    val title: String,
    val country: String? = null,
    val year: Int? = null,
    val user_follow_count: Int = 0,
    val content_rating: String,
    @SerialName("mu_comic_categories") val categories: List<Category> = emptyList(),
    @SerialName("md_titles") val altTitles: List<Title> = emptyList(),
    val desc: String? = null,
    @SerialName("relate_from") val relateFrom: List<RelateFrom> = emptyList(),
    val status: Int? = 0,
    @SerialName("translation_completed") val translationComplete: Boolean? = true,
    @SerialName("md_covers") val mdCovers: List<MDcovers> = emptyList(),
    @SerialName("cover_url") val cover: String? = null,
) {
    val origination: List<Name> = when (country) {
        "jp" -> listOf(Name("Manga"))
        "kr" -> listOf(Name("Manhwa"))
        "cn" -> listOf(Name("Manhua"))
        "hk" -> listOf(Name("Manhua"), Name("Hong Kong"))
        "gb" -> listOf(Name("English"))
        else -> emptyList()
    }
    val relatedSeries = relateFrom.mapNotNull {
        it.relateTo.title?.let { title -> "${it.mdRelates.name}: $title" }
    }.joinToString("\n")
    val alternativeTitles = altTitles.mapNotNull {
        it.title?.let { "• $it" }
    }.joinToString("\n")
    fun formatFollow(): String {
        val numberFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.US)
        val followCount: String = numberFormat.format(user_follow_count)
        val user: String = if (user_follow_count == 0 || user_follow_count == 1) "user" else "users"
        return "$followCount $user"
    }
}

@Serializable
data class MDcovers(
    val b2key: String?,
)

@Serializable
data class Title(
    val title: String?,
    val slug: String? = null,
)

@Serializable
data class Name(
    val name: String,
    val slug: String? = null,
)

@Serializable
data class RelateFrom(
    @SerialName("relate_to") val relateTo: Title,
    @SerialName("md_relates") val mdRelates: Name,
)

@Serializable
data class Category(
    @SerialName("mu_categories") val category: Title,
)

@Serializable
data class ChapterList(
    val chapters: MutableList<Chapter>,
    val total: Int,
)

@Serializable
data class Chapter(
    val hid: String,
    val lang: String = "",
    val title: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val chap: String = "",
    val vol: String = "",
    @SerialName("group_name") val groups: List<String> = emptyList(),
) {
    fun toSChapter(mangaUrl: String) = SChapter.create().apply {
        url = "$mangaUrl/$hid-chapter-$chap-$lang"
        name = beautifyChapterName(vol, chap, title)
        date_upload = createdAt.parseDate()
        scanlator = groups.joinToString().takeUnless { it.isBlank() } ?: "Unknown"
    }
}

@Serializable
data class PageList(
    val chapter: ChapterPageData,
)

@Serializable
data class ChapterPageData(
    val images: List<Page>,
)

@Serializable
data class Page(
    val url: String? = null,
)

@Serializable
data class DayList(
    @SerialName("7") val oneWeek: List<SearchComic> = emptyList(),
    @SerialName("30") val oneMonth: List<SearchComic> = emptyList(),
    @SerialName("90") val threeMonths: List<SearchComic> = emptyList(),
    @SerialName("180") val sixMonths: List<SearchComic> = emptyList(),
    @SerialName("270") val nineMonths: List<SearchComic> = emptyList(),
    @SerialName("360") val oneYear: List<SearchComic> = emptyList(),
    @SerialName("720") val twoYears: List<SearchComic> = emptyList(),
)

@Serializable
data class SearchComic(
    val title: String,
    val slug: String,
    @SerialName("md_covers") val mdCovers: List<MDcovers> = emptyList(),
    @SerialName("cover_url") val cover: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "/comic/$slug"
        title = this@SearchComic.title
        thumbnail_url = parseCover(cover, mdCovers)
    }
}

@Serializable
data class HotUpdate(
    @SerialName("md_comics") val mdComics: SearchManga,
)
