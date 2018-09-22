package slak.fanfictionstories.data.fetchers

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import kotlinx.android.parcel.RawValue
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.Dispatchers
import org.jsoup.Jsoup
import slak.fanfictionstories.*
import slak.fanfictionstories.Notifications.Companion.defaultIntent
import slak.fanfictionstories.data.Cache
import slak.fanfictionstories.data.fetchers.ParserUtils.authorIdFromAuthor
import slak.fanfictionstories.data.fetchers.ParserUtils.getPageCountFromNav
import slak.fanfictionstories.data.fetchers.ParserUtils.parseStoryMetadata
import slak.fanfictionstories.data.fetchers.ParserUtils.unescape
import slak.fanfictionstories.utility.*
import java.io.Serializable
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

/** Describes ffnet sort options. */
enum class Sort(private val ffnetValue: String) {
  UPDATE_DATE("1"), PUBLISH_DATE("2"),
  REVIEWS("3"), FAVORITES("4"), FOLLOWS("5");

  fun queryParam(): String = "srt=$ffnetValue"
}

/** Describes publish time/update time filters. */
enum class TimeRange(private val ffnetValue: String) {
  ALL("0"),
  UPD_LAST_DAY("1"), UPD_LAST_WEEK("2"), UPD_LAST_MONTH("3"), UPD_LAST_6_MONTHS("4"),
  UPD_LAST_YEAR("5"),

  PUB_LAST_DAY("11"), PUB_LAST_WEEK("12"), PUB_LAST_MONTH("13"), PUB_LAST_6_MONTHS("14"),
  PUB_LAST_YEAR("15");

  fun queryParam(): String = "t=$ffnetValue"
}

/** Describes language filters */
enum class Language(private val ffnetValue: String) {
  ALL(""), ENGLISH("1"), SPANISH("2"), FRENCH("3"), GERMAN("4"), CHINESE("5"), DUTCH("7"),
  PORTUGUESE("8"), RUSSIAN("10"), ITALIAN("11"), POLISH("13"), HUNGARIAN("14"), FINNISH("20"),
  CZECH("31"), UKRAINIAN("44"), SWEDISH("17"), INDONESIAN("32"),
  DANISH("19"), TURKISH("30"), NORWEGIAN("18"), HEBREW("15"), CATALAN("34"),
  FILIPINO("21"), GREEK("26"), JAPANESE("6"), ROMANIAN("27"), BULGARIAN("12"),
  KOREAN("36"), CROATIAN("33"), VIETNAMESE("37"), ARABIC("16"), LATIN("35"),
  AFRIKAANS("45"), ESTONIAN("41"), MALAYSIAN("42"), ESPERANTO("22"), ALBANIAN("28"),
  SLOVAK("43"), ICELANDIC("40"), SERBIAN("29"), PERSIAN("25"), HINDI("23");

  fun queryParam(): String = "lan=$ffnetValue"
}

/** Describes genres filters. */
enum class Genre(private val ffnetValue: String) {
  ALL("0"), ADVENTURE("6"), ANGST("10"), CRIME("18"), DRAMA("4"), FAMILY("19"), FANTASY("14"),
  FRIENDSHIP("21"), GENERAL("1"), HORROR("8"), HUMOR("3"), HURT_COMFORT("20"), MYSTERY("7"),
  PARODY("9"), POETRY("5"), ROMANCE("2"), SCI_FI("13"), SPIRITUAL("15"), SUPERNATURAL("11"),
  SUSPENSE("12"), TRAGEDY("16"), WESTERN("17");

  companion object {
    fun fromString(s: String): Genre = when (s) {
      "Adventure" -> ADVENTURE
      "Angst" -> ANGST
      "Crime" -> CRIME
      "Drama" -> DRAMA
      "Family" -> FAMILY
      "Fantasy" -> FANTASY
      "Friendship" -> FRIENDSHIP
      "General" -> GENERAL
      "Horror" -> HORROR
      "Humor" -> HUMOR
      "Hurt/Comfort" -> HURT_COMFORT
      "Mystery" -> MYSTERY
      "Parody" -> PARODY
      "Poetry" -> POETRY
      "Romance" -> ROMANCE
      "Sci-Fi" -> SCI_FI
      "Spiritual" -> SPIRITUAL
      "Supernatural" -> SUPERNATURAL
      "Suspense" -> SUSPENSE
      "Tragedy" -> TRAGEDY
      "Western" -> WESTERN
      else -> throw IllegalArgumentException("No such genre $s")
    }
  }

  fun toUIString(): String = Static.res.getStringArray(R.array.genres)[ordinal]

  fun queryParam(which: Int): String = "g$which=$ffnetValue"
}

/** Describes ffnet ratings. */
enum class Rating(private val ffnetValue: String) {
  ALL("10"),
  K_TO_T("103"), K_TO_K_PLUS("102"), K("1"), K_PLUS("2"), T("3"), M("4");

  fun queryParam(): String = "r=$ffnetValue"
}

/** Describes story completion filters. */
enum class Status(private val ffnetValue: String) {
  ALL("0"), IN_PROGRESS("1"), COMPLETE("2");

  fun queryParam(): String = "s=$ffnetValue"
}

/** Describes word count filters. */
enum class WordCount(private val ffnetValue: String) {
  ALL("0"),
  UNDER_1K("11"), UNDER_5K("51"), OVER_1K("1"), OVER_5K("5"), OVER_10K("10"), OVER_20K("20"),
  OVER_40K("40"), OVER_60K("60"), OVER_100K("100");

  fun queryParam(): String = "len=$ffnetValue"
}

/** A class representing ffnet's filter dialog. Provides the same filtering options. */
@Parcelize
data class CanonFilters(var sort: Sort = Sort.UPDATE_DATE,
                        var timeRange: TimeRange = TimeRange.ALL,
                        var lang: Language = Language.ALL,
                        var genre1: Genre = Genre.ALL,
                        var genre2: Genre = Genre.ALL,
                        var rating: Rating = Rating.ALL,
                        var status: Status = Status.ALL,
                        var wordCount: WordCount = WordCount.ALL,
                        var worldId: String = "0",
                        var char1Id: String = "0",
                        var char2Id: String = "0",
                        var char3Id: String = "0",
                        var char4Id: String = "0",
                        var genreWithout: @RawValue Optional<Genre> = Empty(),
                        var worldWithout: @RawValue Optional<String> = Empty(),
                        var char1Without: @RawValue Optional<String> = Empty(),
                        var char2Without: @RawValue Optional<String> = Empty()) : Parcelable {
  /** Transforms these filters to query params compatible with ffnet. */
  fun queryParams() = listOf(
      sort.queryParam(),
      timeRange.queryParam(),
      lang.queryParam(),
      genre1.queryParam(1),
      genre2.queryParam(2),
      rating.queryParam(),
      status.queryParam(),
      wordCount.queryParam(),
      "v1=$worldId",
      "c1=$char1Id",
      "c2=$char2Id",
      "c3=$char3Id",
      "c4=$char4Id",
      if (genreWithout !is Empty) "_${genreWithout.get().queryParam(1)}" else "",
      if (worldWithout !is Empty) "_v1=${worldWithout.get()}" else "",
      if (char1Without !is Empty) "_c1=${char1Without.get()}" else "",
      if (char2Without !is Empty) "_c2=${char2Without.get()}" else ""
  ).asSequence().filter { it.isNotEmpty() }.joinToString("&")
}

@Parcelize
data class World(val name: String, val id: String) : Parcelable, Serializable

@Parcelize
data class Character(val name: String, val id: String) : Parcelable, Serializable

/** Metadata for a canon. Useful for filtering and showing various info about the canon. */
@Parcelize
data class CanonMetadata(val worldList: @RawValue Optional<List<World>> = Empty(),
                         val charList: @RawValue Optional<List<Character>> = Empty(),
                         val unfilteredStoryCount: @RawValue Optional<String> = Empty(),
                         val canonTitle: @RawValue Optional<String> = Empty(),
                         val pageCount: @RawValue Optional<Int> = Empty()
) : Parcelable, Serializable

/** The data contained in a canon page, which is [CanonMetadata] and the list of stories. */
@Parcelize
data class CanonPage(val storyList: List<StoryModel>,
                     val metadata: CanonMetadata) : Parcelable, Serializable

// It is unlikely that an update would invalidate the cache within 15 minutes
val canonListCache = Cache<CanonPage>("CanonPage", TimeUnit.MINUTES.toMillis(15))

/**
 * Fetches a [CanonPage] for the canon pointed at by [parentLink], using the filters provided by
 * [filters].
 * @see CanonFilters
 */
fun CoroutineScope.getCanonPage(parentLink: CategoryLink,
                 filters: CanonFilters, page: Int): Deferred<CanonPage> = async2(Dispatchers.Default) {
  val pathAndQuery = "${parentLink.urlComponent}/?p=$page&${filters.queryParams()}"
  canonListCache.hit(pathAndQuery).ifPresent { return@async2 it }
  val html = patientlyFetchURL("https://www.fanfiction.net/$pathAndQuery") {
    Notifications.ERROR.show(defaultIntent(),
        R.string.error_with_canon_stories, parentLink.displayName)
  }.await()
  val pageData = parseHtml(html)
  canonListCache.update(pathAndQuery, pageData)
  return@async2 pageData
}

private fun parseHtml(html: String): CanonPage {
  val doc = Jsoup.parse(html)

  val filtersDiv = doc.select("#filters > form > div.modal-body")
  val charsElement = filtersDiv.select("select[name=\"characterid1\"]")
  val charList = if (charsElement.size > 0) {
    charsElement[0].children().map { opt -> Character(opt.text(), opt.`val`()) }.opt()
  } else {
    Empty()
  }
  val worldsElement = filtersDiv.select("select[name=\"verseid1\"]")
  val worldList = if (worldsElement.size > 0) {
    worldsElement[0].children().map { opt -> World(opt.text(), opt.`val`()) }.opt()
  } else {
    Empty()
  }

  val div = doc.getElementById("content_wrapper_inner")
  val canonTitle = doc.title().replace(Regex("(?:FanFiction Archive)? \\| FanFiction"), "").opt()
  // That span only exists for normal canons
  val isCurrentlyCrossover = !div.child(0).`is`("span")
  val categoryTitle = if (isCurrentlyCrossover) canonTitle.get() else div.child(2).text()

  val list = doc.select("#content_wrapper_inner > div.z-list.zhover.zpointer").parallelStream().map {
    // Looks like /s/12656819/1/For-the-Motherland, pick the id
    val storyId = it.child(0).attr("href").split('/')[2].toLong()
    // The one and only text node there is the title
    val title = unescape(it.child(0).text())

    // The author 'a' element is the second last before the reviews
    val authorAnchor = it.select("a:not(.reviews)").last()
    val authorName = authorAnchor.textNodes()[0].toString()

    // There is only one such div
    val summaryMetaDiv = it.select("div.z-indent.z-padtop")[0]
    val summary = unescape(summaryMetaDiv.textNodes()[0].toString()).trim()
    val meta = parseStoryMetadata(summaryMetaDiv.child(0), 2)

    return@map StoryModel(
        storyId = storyId,
        fragment = meta,
        progress = StoryProgress(),
        authorId = authorIdFromAuthor(authorAnchor),
        author = authorName,
        status = StoryStatus.TRANSIENT,
        canon = canonTitle.get(),
        category = categoryTitle,
        summary = summary,
        title = title,
        serializedChapterTitles = null,
        addedTime = null,
        lastReadTime = null
    )
  }.collect(Collectors.toList())

  val centerElem = doc.select("#filters + center")
  val unfilteredStoryCount = if (centerElem.size == 0) {
    // If there is no element with the count there, it means there is only one page, so
    // we get how many stories were on the page
    list.size.toString().opt()
  } else {
    val text = centerElem[0].textNodes()[0].text().split('|')[0].trim()
    // If the text isn't a number (or at least look like a number), we have no stories unfiltered
    if (text[0].isDigit()) text.opt()
    else "0".opt()
  }

  val pageNav = doc.select("#content_wrapper_inner > center").last()
  val pageCount =
      if (pageNav != null && pageNav.text() != "Filters") getPageCountFromNav(pageNav).opt()
      else 1.opt()

  return CanonPage(
      storyList = list,
      metadata = CanonMetadata(
          canonTitle = canonTitle,
          charList = charList,
          worldList = worldList,
          pageCount = pageCount,
          unfilteredStoryCount = unfilteredStoryCount
      )
  )
}
