package slak.fanfictionstories.fetchers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import kotlinx.android.parcel.IgnoredOnParcel
import kotlinx.android.parcel.Parceler
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.fetchers.Fetcher.TAG
import slak.fanfictionstories.fetchers.Fetcher.authorIdFromAuthor
import slak.fanfictionstories.fetchers.Fetcher.parseStoryMetadata
import slak.fanfictionstories.fetchers.Fetcher.publishedTimeStoryMeta
import slak.fanfictionstories.fetchers.Fetcher.updatedTimeStoryMeta
import slak.fanfictionstories.utility.*
import java.util.*
import java.util.stream.Collectors

enum class Sort(val ffnetValue: String) {
  UPDATE_DATE("1"), PUBLISH_DATE("2"),
  REVIEWS("3"), FAVORITES("4"), FOLLOWS("5");

  fun queryParam(): String = "srt=$ffnetValue"
}

enum class TimeRange(val ffnetValue: String) {
  ALL("0"),
  UPD_LAST_DAY("1"), UPD_LAST_WEEK("2"), UPD_LAST_MONTH("3"), UPD_LAST_6_MONTHS("4"),
  UPD_LAST_YEAR("5"),

  PUB_LAST_DAY("11"), PUB_LAST_WEEK("12"), PUB_LAST_MONTH("13"), PUB_LAST_6_MONTHS("14"),
  PUB_LAST_YEAR("15");

  fun queryParam(): String = "t=$ffnetValue"
}

enum class Language(val ffnetValue: String) {
  ALL(""), ENGLISH("1"), SPANISH("2"), FRENCH("3"), GERMAN("4"), CHINESE("5"), DUTCH("7"),
  PORTUGUESE("8"), RUSSIAN("10"), ITALIAN("11"), POLISH("13"), HUNGARIAN("14"), FINNISH("20"),
  CZECH("31"), UKRAINIAN("44");

  fun queryParam(): String = "lan=$ffnetValue"
}

enum class Genre(val ffnetValue: String) {
  ALL("0"), ADVENTURE("6"), ANGST("10"), CRIME("18"), DRAMA("4"), FAMILY("19"), FANTASY("14"),
  FRIENDSHIP("21"), GENERAL("1"), HORROR("8"), HUMOR("3"), HURT_COMFORT("20"), MYSTERY("7"),
  PARODY("9"), POETRY("5"), ROMANCE("2"), SCI_FI("13"), SPIRITUAL("15"), SUPERNATURAL("11"),
  SUSPENSE("12"), TRAGEDY("16"), WESTERN("17");

  fun queryParam(which: Int): String = "g$which=$ffnetValue"
}

enum class Rating(val ffnetValue: String) {
  ALL("10"),
  K_TO_T("103"), K_TO_K_PLUS("102"), K("1"), K_PLUS("2"), T("3"), M("4");

  fun queryParam(): String = "r=$ffnetValue"
}

enum class Status(val ffnetValue: String) {
  ALL("0"), IN_PROGRESS("1"), COMPLETE("2");

  fun queryParam(): String = "s=$ffnetValue"
}

enum class WordCount(val ffnetValue: String) {
  ALL("0"),
  UNDER_1K("11"), UNDER_5K("51"), OVER_1K("1"), OVER_5K("5"), OVER_10K("10"), OVER_20K("20"),
  OVER_40K("40"), OVER_60K("60"), OVER_100K("100");

  fun queryParam(): String = "len=$ffnetValue"
}

@Parcelize @SuppressLint("ParcelCreator")
class CanonFetcher(val details: Details) : Parcelable {
  @Parcelize @SuppressLint("ParcelCreator")
  data class Details(
      val parentLink: CategoryLink,

      var sort: Sort = Sort.UPDATE_DATE,
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

      var genreWithout: Genre? = null,
      var worldWithout: String? = null,
      var char1Without: String? = null,
      var char2Without: String? = null
  ) : Parcelable

  @Parcelize @SuppressLint("ParcelCreator")
  data class World(val name: String, val id: String) : Parcelable
  @Parcelize @SuppressLint("ParcelCreator")
  data class Character(val name: String, val id: String) : Parcelable

  @IgnoredOnParcel
  var worldList: Optional<List<World>> = listOf<World>().opt()
    private set
  @IgnoredOnParcel
  var charList: List<Character> = listOf()
    private set
  @IgnoredOnParcel
  var unfilteredStories: Optional<String> = Optional.empty()
    private set
  @IgnoredOnParcel
  var canonTitle: Optional<String> = Optional.empty()
    private set

  companion object : Parceler<CanonFetcher> {
    override fun create(parcel: Parcel): CanonFetcher {
      val c = CanonFetcher(parcel.readParcelable(Details::class.java.classLoader) as Details)
      @Suppress("unchecked_cast")
      val worldArray = parcel.readArray(Array<World>::class.java.classLoader) as Array<World>?
      c.worldList = worldArray?.toList()?.opt() ?: Optional.empty()

      @Suppress("unchecked_cast")
      val charArray = parcel.readArray(Array<Character>::class.java.classLoader) as Array<Character>
      c.charList = charArray.toList()

      val str = parcel.readString()
      c.unfilteredStories = str?.opt() ?: Optional.empty()

      val title = parcel.readString()
      c.canonTitle = title?.opt() ?: Optional.empty()

      return c
    }

    override fun CanonFetcher.write(parcel: Parcel, flags: Int) {
      parcel.writeParcelable(details, 0)
      parcel.writeArray(worldList.orElse(listOf())?.toTypedArray())
      parcel.writeArray(charList.toTypedArray())
      parcel.writeString(unfilteredStories.orElse(null))
      parcel.writeString(canonTitle.orElse(null))
    }
  }

  fun get(page: Int, context: Context): Deferred<List<StoryModel>> = async2(CommonPool) {
    val n = Notifications(context, Notifications.Kind.OTHER)
    val html = fetchPage(page, n).await()
    return@async2 parseHtml(html)
  }

  private fun fetchPage(page: Int, n: Notifications): Deferred<String> = async2(CommonPool) {
    val queryParams = listOf(
        details.sort.queryParam(),
        details.timeRange.queryParam(),
        details.lang.queryParam(),
        details.genre1.queryParam(1),
        details.genre2.queryParam(2),
        details.rating.queryParam(),
        details.status.queryParam(),
        details.wordCount.queryParam(),
        "v1=${details.worldId}",
        "c1=${details.char1Id}",
        "c2=${details.char2Id}",
        "c3=${details.char3Id}",
        "c4=${details.char4Id}",
        if (details.genreWithout != null) "_${details.genreWithout!!.queryParam(1)}" else "",
        if (details.worldWithout != null) "_v1=${details.worldWithout}" else "",
        if (details.char1Without != null) "_c1=${details.char1Without}" else "",
        if (details.char2Without != null) "_c2=${details.char2Without}" else ""
    ).joinToString("&")

    return@async2 patientlyFetchURL(
        "https://www.fanfiction.net/${details.parentLink.urlComponent}/?p=$page&$queryParams", n) {
      n.show(Static.res.getString(R.string.error_with_canon_stories, details.parentLink.displayName))
      Log.e(TAG, "CanonFetcher: retry", it)
    }.await()
  }

  private fun parseHtml(html: String): List<StoryModel> {
    val doc = Jsoup.parse(html)

    if (charList.isEmpty() || (worldList.isPresent && worldList.get().isEmpty())) {
      val filtersDiv = doc.select("#filters > form > div.modal-body")
      val charsElement = filtersDiv.select("select[name=\"characterid1\"]")[0]
      charList = charsElement.children().map { option -> Character(option.text(), option.`val`()) }
      val worldsElement = filtersDiv.select("select[name=\"verseid1\"]")
      worldList = if (worldsElement.size == 0) {
        Optional.empty()
      } else {
        worldsElement[0].children()
            .map {option -> World(option.text(), option.`val`()) }.opt()
      }
    }

    val div = doc.getElementById("content_wrapper_inner")
    canonTitle = doc.title().replace(Regex("(?:FanFiction Archive)? \\| FanFiction"), "").opt()
    // That span only exists for normal canons
    val isCurrentlyCrossover = !div.child(0).`is`("span")
    val categoryTitle = if (isCurrentlyCrossover) canonTitle.get() else div.child(2).text()

    val list = doc.select("#content_wrapper_inner > div.z-list.zhover.zpointer").parallelStream().map {
      // Looks like /s/12656819/1/For-the-Motherland, pick the id
      val storyId = it.child(0).attr("href").split('/')[2].toLong()
      // The one and only text node there is the title
      val title = it.child(0).textNodes()[0].toString()

      // The author 'a' element is the second last before the reviews
      val authorAnchor = it.select("a:not(.reviews)").last()
      val authorName = authorAnchor.textNodes()[0].toString()

      // There is only one such div
      val summaryMetaDiv = it.select("div.z-indent.z-padtop")[0]
      val summary = summaryMetaDiv.textNodes()[0].toString()
      val metaSection = summaryMetaDiv.child(0).html()
      val meta = parseStoryMetadata(metaSection)
      val isCompleted = metaSection.indexOf("Complete") > -1

      val publishTime = publishedTimeStoryMeta(html)
      val updateTime = updatedTimeStoryMeta(html)

      var characters = meta["characters"]!!
      val lastNode = summaryMetaDiv.child(0).childNodes().last()
      if (lastNode is TextNode) {
        val stripStatus = lastNode.text().replace(" - Complete", "")
        if (stripStatus.isNotBlank()) {
          characters = stripStatus.trimStart(' ', '-')
        }
      }

      return@map StoryModel(mutableMapOf(
          "storyId" to storyId,
          "authorid" to authorIdFromAuthor(authorAnchor),
          "rating" to meta["rating"]!!,
          "language" to meta["language"]!!,
          "genres" to meta["genres"]!!,
          "characters" to characters,
          "chapters" to if (meta["chapters"] != null) meta["chapters"]!!.toLong() else 1L,
          "wordCount" to meta["words"]!!.replace(",", "").toLong(),
          "reviews" to if (meta["reviews"] != null) meta["reviews"]!!.toLong() else 0L,
          "favorites" to if (meta["favs"] != null) meta["favs"]!!.toLong() else 0L,
          "follows" to if (meta["follows"] != null) meta["follows"]!!.toLong() else 0L,
          "publishDate" to (publishTime?.toLong() ?: 0L),
          "updateDate" to (updateTime?.toLong() ?: 0L),
          "isCompleted" to if (isCompleted) 1L else 0L,
          "scrollProgress" to 0.0,
          "scrollAbsolute" to 0L,
          "currentChapter" to 0L,
          "status" to "transient",
          "canon" to canonTitle.get(),
          "category" to categoryTitle,
          "summary" to summary,
          "author" to authorName,
          "title" to title,
          "chapterTitles" to ""
      ), false)
    }.collect(Collectors.toList())

    val centerElem = doc.select("#filters + center")
    unfilteredStories = if (centerElem.size == 0) {
      // If there is no element with the count there, it means there is only one page, so
      // we get how many stories were on the page
      list.size.toString().opt()
    } else {
      val text = centerElem[0].textNodes()[0].text().split('|')[0].trim()
      // If the text isn't a number (or at least look like a number), we have no stories unfiltered
      if (text[0].isDigit()) text.opt()
      else "0".opt()
    }

    return list
  }
}
