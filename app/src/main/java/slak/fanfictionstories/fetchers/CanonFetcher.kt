package slak.fanfictionstories.fetchers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import slak.fanfictionstories.*
import slak.fanfictionstories.activities.MainActivity
import slak.fanfictionstories.utility.Notifications
import slak.fanfictionstories.utility.async2
import slak.fanfictionstories.utility.waitForNetwork
import java.net.URL

enum class Sorts(val ffnetValue: String) {
  UPDATE_DATE("1"), PUBLISH_DATE("2"),
  REVIEWS("3"), FAVORITES("4"), FOLLOWS("5");

  fun queryParamName(): String = "srt"
}

enum class TimeRanges(val ffnetValue: String) {
  ALL("0"),
  UPD_LAST_DAY("1"), UPD_LAST_WEEK("2"), UPD_LAST_MONTH("3"), UPD_LAST_6_MONTHS("4"),
  UPD_LAST_YEAR("5"),

  PUB_LAST_DAY("11"), PUB_LAST_WEEK("12"), PUB_LAST_MONTH("13"), PUB_LAST_6_MONTHS("14"),
  PUB_LAST_YEAR("15");

  fun queryParamName(): String = "t"
}

enum class Languages(val ffnetValue: String) {
  ALL(""), ENGLISH("1"), SPANISH("2"), FRENCH("3"), GERMAN("4"), CHINESE("5"), DUTCH("7"),
  PORTUGUESE("8"), RUSSIAN("10"), ITALIAN("11"), POLISH("13"), HUNGARIAN("14"), FINNISH("20"),
  CZECH("31"), UKRAINIAN("44");

  fun queryParamName(): String = "lan"
}

enum class Genres(val ffnetValue: String) {
  ALL("0"), ADVENTURE("6"), ANGST("10"), CRIME("18"), DRAMA("4"), FAMILY("19"), FANTASY("14"),
  FRIENDSHIP("21"), GENERAL("1"), HORROR("8"), HUMOR("3"), HURT_COMFORT("20"), MYSTERY("7"),
  PARODY("9"), POETRY("5"), ROMANCE("2"), SCI_FI("13"), SPIRITUAL("15"), SUPERNATURAL("11"),
  SUSPENSE("12"), TRAGEDY("16"), WESTERN("17");

  fun queryParamName(which: Int): String = "g$which"
}

enum class Rating(val ffnetValue: String) {
  ALL("10"),
  K_TO_T("103"), K_TO_K_PLUS("102"), K("1"), K_PLUS("2"), T("3"), M("4");

  fun queryParamName(): String = "r"
}

enum class Statuses(val ffnetValue: String) {
  ALL("0"), IN_PROGRESS("1"), COMPLETE("2");

  fun queryParamName(): String = "s"
}

enum class WordCounts(val ffnetValue: String) {
  ALL("0"),
  UNDER_1K("11"), UNDER_5K("51"), OVER_1K("1"), OVER_5K("5"), OVER_10K("10"), OVER_20K("20"),
  OVER_40K("40"), OVER_60K("60"), OVER_100K("100");

  fun queryParamName(): String = "len"
}

class CanonFetcher(private val ctx: Context, private val canonUrlComponent: String,
                   private val canonTitle: String, private val srcCategory: String) : Fetcher() {

  fun get(page: Int): Deferred<List<StoryModel>> = async2(CommonPool) {
    val n = Notifications(ctx, Notifications.Kind.OTHER)
    val html = fetchPage(page, n).await()
    return@async2 parseHtml(html)
  }

  private fun fetchPage(page: Int, n: Notifications): Deferred<String> = async2(CommonPool) {
    return@async2 DOWNLOAD_MUTEX.withLock {
      delay(RATE_LIMIT_MILLISECONDS)
      waitForNetwork(n).await()
      try {
        return@withLock URL("https://www.fanfiction.net/$canonUrlComponent/?p=$page").readText()
      } catch (t: Throwable) {
        // Something happened; retry
        n.show(MainActivity.res.getString(R.string.error_with_canon_stories, canonTitle))
        Log.e(TAG, "CanonFetcher: retry", t)
        delay(RATE_LIMIT_MILLISECONDS)
        return@withLock fetchPage(page, n).await()
      }
    }
  }

  private fun parseHtml(html: String): List<StoryModel> {
    val doc = Jsoup.parse(html)
    val list = mutableListOf<StoryModel>()
    // FIXME use parallel map for this instead of foreach
    doc.select("#content_wrapper_inner > div.z-list.zhover.zpointer").forEach {
      // Looks like /s/12656819/1/For-the-Motherland, pick the id
      val storyId = it.child(0).attr("href").split('/')[2].toLong()
      // The one and only text node there is the title
      val title = it.child(0).textNodes()[0].toString()

      // The author 'a' element is the second last before the reviews
      val authorAnchor = it.select("a:not(.reviews)").last()
      // Href looks like /u/6772732/Gnaoh-El-Nart, pick the id
      val authorId = authorAnchor.attr("href").split('/')[2].toLong()
      val authorName = authorAnchor.textNodes()[0].toString()

      val canon = canonTitle
      val category = srcCategory

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

      list.add(StoryModel(mutableMapOf(
          "storyId" to storyId,
          "authorid" to authorId,
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
          "status" to "remote",
          "canon" to canon,
          "category" to category,
          "summary" to summary,
          "author" to authorName,
          "title" to title,
          "chapterTitles" to ""
      ), false))
    }
    return list
  }
}
