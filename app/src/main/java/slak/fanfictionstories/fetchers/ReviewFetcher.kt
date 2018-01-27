package slak.fanfictionstories.fetchers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Parcelable
import android.util.Log
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import org.jsoup.Jsoup
import slak.fanfictionstories.R
import slak.fanfictionstories.fetchers.Fetcher.authorIdFromAuthor
import slak.fanfictionstories.utility.*

@Parcelize @SuppressLint("ParcelCreator")
data class Review(
    val storyId: Long,
    val chapter: Int,
    val author: String,
    val authorId: Long,
    val unixTimeSeconds: Long,
    val content: String
) : Parcelable

/**
 * Get a particular page of reviews for the specified story, for the specified chapter.
 * @returns the reviews, and how many pages of reviews there are in total, or an empty list and -1 if
 * there are no reviews
 */
fun getReviews(context: Context, storyId: Long,
               chapter: Int, page: Int): Deferred<Pair<List<Review>, Int>> = async2(CommonPool) {
  val n = Notifications(context, Notifications.Kind.OTHER)
  val html = fetchReviewPage(storyId, chapter, page, n).await()
  return@async2 parseReviewPage(storyId, html)
}

private fun parseReviewPage(storyId: Long, html: String): Pair<List<Review>, Int> {
  val doc = Jsoup.parse(html)
  val list = doc.select("div.table-bordered > table > tbody > tr > td").map {
    if (it.text().contains("No Reviews found")) return@parseReviewPage Pair(listOf(), -1)
    val reviewAuthor = it.select("img + a")[0]
    val authorId = authorIdFromAuthor(reviewAuthor)
    val author = reviewAuthor.text().trim()
    val container = it.select("small")[0]
    val chapterText = container.textNodes()[0].text()
    val chapter = chapterText.replace(Regex("[^\\d]"), "").toInt()
    val unixTimeSeconds =
        container.select("span")[0].attr("data-xutime").toLong()
    val content = it.select("div")[0].text()
    return@map Review(storyId, chapter, author, authorId, unixTimeSeconds, content)
  }
  val pageNav = doc.select("#content_wrapper_inner > center")
  if (pageNav.size == 0) return Pair(list, 1)
  val navLinks = pageNav[0].select("a")
  // The href is like: /r/9156000/0/245/, we want the page nr, which is the last nr
  val lastPageNr = navLinks[navLinks.size - 2].attr("href")
      .trim('/').split("/").last().toInt()
  return Pair(list, lastPageNr)
}

private fun fetchReviewPage(storyId: Long,
                            chapter: Int, page: Int, n: Notifications): Deferred<String> =
    patientlyFetchURL("https://www.fanfiction.net/r/$storyId/$chapter/$page/", n) {
  n.show(Static.res.getString(R.string.error_fetching_review_data, storyId.toString()))
  Log.e(Fetcher.TAG, "fetchReviewPage", it)
}
