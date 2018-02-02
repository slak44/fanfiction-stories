package slak.fanfictionstories.fetchers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Parcelable
import android.util.Log
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.utility.*
import java.util.*

@Parcelize @SuppressLint("ParcelCreator")
data class Author(val name: String,
                  val id: Long,
                  val joinedDateSeconds: Long,
                  val updatedDateSeconds: Long,
                  val countryName: String,
                  val imageUrl: String,
                  val bioHtml: String,
                  val userStories: List<StoryModel>,
                  val favoriteStories: List<StoryModel>,
                  val favoriteAuthors: List<Pair<Long, String>>) : Parcelable

/**
 * Get author data for specified id.
 * @see Author
 */
fun getAuthor(context: Context, authorId: Long): Deferred<Author> = async2(CommonPool) {
  val n = Notifications(context, Notifications.Kind.OTHER)
  val html = fetchAuthorPage(authorId, n).await()
  val doc = Jsoup.parse(html)
  // USING TABLES FOR ALIGNMENT IN 2018 GOD DAMMIT
  val retardedTableCell =
      doc.select("#content_wrapper_inner > table[cellpadding=\"4\"] td[colspan=\"2\"]")
  val authorName = doc.select("#content_wrapper_inner > span").first().text()
  val stories = doc.getElementById("st_inside").children().map {
    parseStoryElement(it, Pair(authorId, authorName).opt())
  }
  // Drop a COMPLETELY FUCKING RANDOM SCRIPT TAG
  val favStories = doc.getElementById("fs_inside")?.children()?.drop(1)?.map {
    parseStoryElement(it)
  } ?: listOf()
  val favAuthors = doc.getElementById("fa").select("dl").map {
    val authorElement = it.select("a").first()
    return@map Pair(Fetcher.authorIdFromAuthor(authorElement), authorElement.text())
  }
  return@async2 Author(
      authorName,
      authorId,
      // Joined date, seconds
      retardedTableCell.select("span")[0].attr("data-xutime").toLong(),
      // Updated date, seconds
      retardedTableCell.select("span")[1].attr("data-xutime").toLong(),
      // Country name
      retardedTableCell.select("img").first().attr("title"),
      // Image url
      doc.select("#bio > img").first().attr("src"),
      // User bio (first child is image)
      Elements(doc.getElementById("bio").children().drop(1)).outerHtml(),
      stories,
      favStories,
      favAuthors
  )
}

private fun parseStoryElement(it: Element,
                              a: Optional<Pair<Long, String>> = Optional.empty()): StoryModel {
  val metaHtml = it.children().last().children().last().html()
  val meta = Fetcher.parseStoryMetadata(metaHtml)
  return StoryModel(mutableMapOf(
      "storyId" to it.attr("data-storyid").toLong(),
      "rating" to meta["rating"]!!,
      "language" to meta["language"]!!,
      "genres" to meta["genres"]!!,
      "characters" to meta["characters"]!!,
      "chapters" to if (meta["chapters"] != null) meta["chapters"]!!.toLong() else 1L,
      "wordCount" to meta["words"]!!.replace(",", "").toLong(),
      "reviews" to if (meta["reviews"] != null) meta["reviews"]!!.toLong() else 0L,
      "favorites" to if (meta["favs"] != null) meta["favs"]!!.toLong() else 0L,
      "follows" to if (meta["follows"] != null) meta["follows"]!!.toLong() else 0L,
      "publishDate" to (Fetcher.publishedTimeStoryMeta(metaHtml)?.toLong() ?: 0L),
      "updateDate" to (Fetcher.updatedTimeStoryMeta(metaHtml)?.toLong() ?: 0L),
      "isCompleted" to Fetcher.isComplete(metaHtml),
      "scrollProgress" to 0.0,
      "scrollAbsolute" to 0L,
      "currentChapter" to 0L,
      "status" to "transient",
      // FFnet category is our canon
      "canon" to it.attr("data-category"),
      // Category info is unavailable here!
      "category" to "",
      "summary" to it.children().last().textNodes().first().text(),
      "author" to if (a.isPresent) a.get().second else it.children()[2].text(),
      "authorid" to if (a.isPresent) a.get().first else Fetcher.authorIdFromAuthor(it.children()[2]),
      "title" to it.select("a.stitle").first().textNodes().last().text(),
      "chapterTitles" to ""
  ))
}

private fun fetchAuthorPage(authorId: Long, n: Notifications): Deferred<String> =
    patientlyFetchURL("https://www.fanfiction.net/u/$authorId/", n) {
      n.show(Static.res.getString(R.string.error_fetching_author_data, authorId.toString()))
      Log.e(Fetcher.TAG, "fetchAuthorPage", it)
    }
