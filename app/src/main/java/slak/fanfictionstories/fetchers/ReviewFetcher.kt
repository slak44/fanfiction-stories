package slak.fanfictionstories.fetchers

import android.os.Parcelable
import android.util.Log
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import org.jsoup.Jsoup
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryId
import slak.fanfictionstories.fetchers.FetcherUtils.authorIdFromAuthor
import slak.fanfictionstories.fetchers.FetcherUtils.getPageCountFromNav
import slak.fanfictionstories.utility.*
import slak.fanfictionstories.utility.Notifications.defaultIntent
import java.io.Serializable
import java.util.concurrent.TimeUnit

@Parcelize
data class Review(
    val storyId: StoryId,
    val chapter: Int,
    val author: String,
    val authorId: Long,
    val unixTimeSeconds: Long,
    val content: String
) : Parcelable, Serializable

private const val TAG = "ReviewPage"

typealias ReviewPage = Triple<List<Review>, Int, Int>

val reviewCache = Cache<ReviewPage>("ReviewPage", TimeUnit.DAYS.toMillis(1))

/**
 * Get a particular page of reviews for the specified story, for the specified chapter.
 * @returns a [Triple] of the reviews, how many pages of reviews there are in total and how many
 * reviews are in total. If the particular page has no reviews (even if it is the first one),
 * returns `Triple(emptyList(), NO_PAGES, 0)`
 * @see NO_PAGES
 */
fun getReviews(storyId: StoryId,
               chapter: Int, page: Int): Deferred<ReviewPage> = async2(CommonPool) {
  reviewCache.hit("$storyId/$chapter/$page").ifPresent { return@async2 it }
  val html = patientlyFetchURL("https://www.fanfiction.net/r/$storyId/$chapter/$page/") {
    Notifications.show(Notifications.Kind.ERROR, defaultIntent(),
        R.string.error_fetching_review_data, storyId.toString())
    Log.e(TAG, "getReviews", it)
  }.await()
  Log.v(TAG, "storyId=($storyId), chapter=($chapter), page=($page)")
  val triple = parseReviewPage(storyId, html)
  Log.v(TAG, "pages=(${triple.second}), reviewCount=(${triple.third})")
  reviewCache.update("$storyId/$chapter/$page", triple)
  return@async2 triple
}

const val NO_PAGES: Int = -12

private fun parseReviewPage(storyId: StoryId, html: String): Triple<List<Review>, Int, Int> {
  val doc = Jsoup.parse(html)
  val list = doc.select("div.table-bordered > table > tbody > tr > td").map {
    if (it.text().contains("No Reviews found")) {
      return@parseReviewPage Triple(listOf(), NO_PAGES, 0)
    }
    val maybeAuthor = it.select("img + a")
    val author: String
    val authorId: Long
    if (maybeAuthor.isEmpty()) {
      author = str(R.string.guest)
      authorId = -1L
    } else {
      author = maybeAuthor[0].text().trim()
      authorId = authorIdFromAuthor(maybeAuthor[0])
    }
    val container = it.select("small")[0]
    val chapterText = container.textNodes()[0].text()
    val chapter = chapterText.replace(Regex("[^\\d]"), "").toInt()
    val unixTimeSeconds =
        container.select("span")[0].attr("data-xutime").toLong()
    val content = it.select("div")[0].text()
    return@map Review(storyId, chapter, author, authorId, unixTimeSeconds, content)
  }
  val pageNav = doc.select("#content_wrapper_inner > center")
  if (pageNav.size == 0) return Triple(list, 1, list.size)
  val pageCount = getPageCountFromNav(pageNav[0])
  val reviewCount = pageNav[0].textNodes()[0].toString()
      .split(" | ")[0]
      .trim()
      .replace(",", "")
      .toInt()
  return Triple(list, pageCount, reviewCount)
}
