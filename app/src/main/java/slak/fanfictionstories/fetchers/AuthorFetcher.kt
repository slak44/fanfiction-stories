package slak.fanfictionstories.fetchers

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
import slak.fanfictionstories.StoryProgress
import slak.fanfictionstories.StoryStatus
import slak.fanfictionstories.fetchers.FetcherUtils.authorIdFromAuthor
import slak.fanfictionstories.fetchers.FetcherUtils.parseStoryMetadata
import slak.fanfictionstories.fetchers.FetcherUtils.unescape
import slak.fanfictionstories.utility.Cache
import slak.fanfictionstories.Notifications
import slak.fanfictionstories.Notifications.defaultIntent
import slak.fanfictionstories.utility.async2
import slak.fanfictionstories.utility.patientlyFetchURL
import java.util.concurrent.TimeUnit

val authorCache = Cache<Author>("Author", TimeUnit.DAYS.toMillis(1))

private const val TAG = "AuthorFetcher"

/**
 * A data class representing an author's metadata.
 * @see getAuthor
 */
@Parcelize
data class Author(val name: String,
                  val id: Long,
                  val joinedDateSeconds: Long,
                  val updatedDateSeconds: Long,
                  val countryName: String?,
                  val imageUrl: String?,
                  val bioHtml: String,
                  val userStories: List<StoryModel>,
                  val favoriteStories: List<StoryModel>,
                  val favoriteAuthors: List<Pair<Long, String>>) : Parcelable, java.io.Serializable

/**
 * Get author data for specified id.
 * @see Author
 */
fun getAuthor(authorId: Long): Deferred<Author> = async2(CommonPool) {
  authorCache.hit(authorId.toString()).ifPresent { return@async2 it }
  val html = patientlyFetchURL("https://www.fanfiction.net/u/$authorId/") {
    Notifications.show(Notifications.Kind.ERROR, defaultIntent(),
        R.string.error_fetching_author_data, authorId.toString())
  }.await()
  val doc = Jsoup.parse(html)
  val authorName = doc.select("#content_wrapper_inner > span").first().text()
  val stories = doc.getElementById("st_inside").children().map {
    parseStoryElement(it, authorName, authorId)
  }
  // Drop a COMPLETELY FUCKING RANDOM SCRIPT TAG
  val favStories = doc.getElementById("fs_inside")?.children()?.drop(1)?.map {
    parseStoryElement(it, null, null)
  } ?: listOf()
  val favAuthors = doc.getElementById("fa").select("dl").map {
    val authorElement = it.select("a").first()
    return@map Pair(authorIdFromAuthor(authorElement), authorElement.text())
  }
  // USING TABLES FOR ALIGNMENT IN 2018 GOD DAMMIT
  val retardedTableCell =
      doc.select("#content_wrapper_inner > table table[cellpadding=\"4\"] td[colspan=\"2\"]").last()
  val timeSpans = retardedTableCell.select("span")
  val author = Author(
      authorName,
      authorId,
      // Joined date, seconds
      timeSpans[0].attr("data-xutime").toLong(),
      // Updated date, seconds
      if (timeSpans.size > 1) timeSpans[1].attr("data-xutime").toLong() else 0,
      // Country name
      retardedTableCell.select("img").first()?.attr("title"),
      // Image url
      doc.select("#bio > img").first()?.attr("src"),
      // User bio (first child is image)
      Elements(doc.getElementById("bio").children().drop(1)).outerHtml(),
      stories,
      favStories,
      favAuthors
  )
  authorCache.update(authorId.toString(), author)
  return@async2 author
}

private fun parseStoryElement(it: Element, authorName: String?, authorId: Long?): StoryModel {
  val meta = it.children().last().children().last()
  val authorAnchor = it.select("a:not(.reviews)").last()
  return StoryModel(
      storyId = it.attr("data-storyid").toLong(),
      fragment = parseStoryMetadata(meta.html(), meta),
      progress = StoryProgress(),
      status = StoryStatus.TRANSIENT,
      // FFnet category is our canon
      canon = unescape(it.attr("data-category")),
      // Category info is unavailable here!
      category = null,
      summary = it.children().last().textNodes().first().text(),
      author = authorName ?: authorAnchor.text(),
      authorId = authorId ?: authorIdFromAuthor(authorAnchor),
      title = it.select("a.stitle").first().textNodes().last().text(),
      serializedChapterTitles = null,
      addedTime = null,
      lastReadTime = null
  )
}
