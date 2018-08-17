package slak.fanfictionstories.fetchers

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import org.jsoup.Jsoup
import slak.fanfictionstories.Notifications
import slak.fanfictionstories.Notifications.Companion.defaultIntent
import slak.fanfictionstories.R
import slak.fanfictionstories.activities.categoryUrl
import slak.fanfictionstories.utility.Cache
import slak.fanfictionstories.utility.async2
import slak.fanfictionstories.utility.patientlyFetchURL
import slak.fanfictionstories.utility.str
import java.io.Serializable
import java.util.concurrent.TimeUnit

/**
 * A link found in a category. Can point to a canon (to see with
 * [slak.fanfictionstories.activities.CanonStoryListActivity]), or to further navigation in
 * crossover categories (to see with [slak.fanfictionstories.activities.BrowseCategoryActivity]).
 * @see fetchCategoryData
 */
@Parcelize
data class CategoryLink(val text: String,
                        val urlComponent: String,
                        val storyCount: String) : Parcelable, Serializable {
  private fun isTargetCrossover(): Boolean =
      urlComponent.contains(Regex("crossovers", RegexOption.IGNORE_CASE))

  /** @returns if this link points to a category rather than a canon */
  fun isTargetCategory(): Boolean {
    val pieces = urlComponent.split("/")
    if (pieces.size == 2 && categoryUrl.contains(pieces[1])) return true
    if (pieces[1] == "crossovers") return true
    return false
  }

  /** How this link should be displayed in the UI. */
  val displayName: String
    get() = if (isTargetCrossover()) str(R.string.title_crossover, text) else text
}

val categoryCache = Cache<Array<CategoryLink>>("Category", TimeUnit.DAYS.toMillis(7))

/**
 * Fetches the list of [CategoryLink]s at the target [categoryUrlComponent].
 * @see CategoryLink
 */
fun fetchCategoryData(categoryUrlComponent: String):
    Deferred<Array<CategoryLink>> = async2(CommonPool) {
  categoryCache.hit(categoryUrlComponent).ifPresent { return@async2 it }
  val html = patientlyFetchURL("https://www.fanfiction.net/$categoryUrlComponent/") {
    Notifications.ERROR.show(defaultIntent(), R.string.error_with_categories, categoryUrlComponent)
  }.await()
  val doc = Jsoup.parse(html)
  val result = doc.select("#list_output div").map {
    val urlComponent = it.child(0).attr("href")
    val title = it.child(0).text()
    val storyCount = it.child(1).text().replace(Regex("[(),]"), "")
    CategoryLink(title, urlComponent, storyCount)
  }.toTypedArray()
  categoryCache.update(categoryUrlComponent, result)
  return@async2 result
}
