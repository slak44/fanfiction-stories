package slak.fanfictionstories.data.fetchers

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import slak.fanfictionstories.Notifications
import slak.fanfictionstories.Notifications.Companion.defaultIntent
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.StoryProgress
import slak.fanfictionstories.StoryStatus
import slak.fanfictionstories.data.Cache
import slak.fanfictionstories.data.fetchers.ParserUtils.authorIdFromAuthor
import slak.fanfictionstories.data.fetchers.ParserUtils.convertImageUrl
import slak.fanfictionstories.data.fetchers.ParserUtils.parseStoryMetadata
import slak.fanfictionstories.data.fetchers.ParserUtils.unescape
import java.util.concurrent.TimeUnit

val authorCache = Cache<Author>("Author", TimeUnit.DAYS.toMillis(1))

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
suspend fun getAuthor(authorId: Long): Author {
  authorCache.hit(authorId.toString()).ifPresent { return it }
  val html = patientlyFetchURL("https://www.fanfiction.net/u/$authorId/") {
    Notifications.ERROR.show(defaultIntent(),
        R.string.error_fetching_author_data, authorId.toString())
  }
  val doc = Jsoup.parse(html)
  val authorName = doc.selectFirst("#content_wrapper_inner > span")!!.text()
  val stories = doc.getElementById("st_inside")!!.children().map {
    parseStoryElement(it, authorName, authorId)
  }
  // Drop a COMPLETELY FUCKING RANDOM SCRIPT TAG
  val favStories = doc.getElementById("fs_inside")?.children()?.drop(1)?.map {
    parseStoryElement(it, null, null)
  } ?: listOf()
  val favAuthors = doc.getElementById("fa")!!.select("dl").map {
    val authorElement = it.select("a").first()!!
    return@map Pair(authorIdFromAuthor(authorElement), authorElement.text())
  }
  // USING TABLES FOR ALIGNMENT IN 2019 GOD DAMMIT
  val retardedTableCell =
      doc.select("#content_wrapper_inner > table table[cellpadding=\"4\"] td[colspan=\"2\"]").last()!!
  val timeSpans = retardedTableCell.select("span")
  val author = Author(
      authorName,
      authorId,
      // Joined date, seconds
      timeSpans[0].attr("data-xutime").toLong(),
      // Updated date, seconds
      if (timeSpans.size > 1) timeSpans[1].attr("data-xutime").toLong() else 0,
      // Country name
      retardedTableCell.selectFirst("img")?.attr("title"),
      // Image url
      doc.selectFirst("#bio > img")?.attr("data-original"),
      // User bio (first child is image)
      Elements(doc.getElementById("bio")!!.children().drop(1)).outerHtml(),
      stories,
      favStories,
      favAuthors
  )
  authorCache.update(authorId.toString(), author)
  return author
}

private fun parseStoryElement(it: Element, authorName: String?, authorId: Long?): StoryModel {
  val authorAnchor = it.select("a:not(.reviews)").last()!!
  val stitle = it.selectFirst("a.stitle")!!
  return StoryModel(
      storyId = it.attr("data-storyid").toLong(),
      fragment = parseStoryMetadata(it.children().last()!!.children().last()!!, 3),
      progress = StoryProgress(),
      status = StoryStatus.TRANSIENT,
      // FFnet category is our canon
      canon = unescape(it.attr("data-category")),
      // Category info is unavailable here!
      category = null,
      summary = it.children().last()!!.textNodes().first().text(),
      author = authorName ?: authorAnchor.text(),
      authorId = authorId ?: authorIdFromAuthor(authorAnchor),
      title = stitle.textNodes().last().text(),
      imageUrl = convertImageUrl(stitle.children().first()?.attr("data-original")),
      serializedChapterTitles = null,
      addedTime = null,
      lastReadTime = null
  )
}
