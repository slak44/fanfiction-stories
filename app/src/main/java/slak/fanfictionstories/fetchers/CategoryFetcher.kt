package slak.fanfictionstories.fetchers

import android.annotation.SuppressLint
import android.os.Parcelable
import android.util.Log
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import org.jsoup.Jsoup
import slak.fanfictionstories.R
import slak.fanfictionstories.activities.categoryUrl
import slak.fanfictionstories.fetchers.Fetcher.TAG
import slak.fanfictionstories.utility.*
import java.io.Serializable
import java.util.concurrent.TimeUnit

@Parcelize @SuppressLint("ParcelCreator")
data class CategoryLink(val text: String,
                        val urlComponent: String,
                        val storyCount: String) : Parcelable, Serializable {
  fun isTargetCrossover(): Boolean =
      urlComponent.contains(Regex("crossovers", RegexOption.IGNORE_CASE))
  fun isTargetCategory(): Boolean {
    val pieces = urlComponent.split("/")
    if (pieces.size == 2 && categoryUrl.contains(pieces[1])) return true
    if (pieces[1] == "crossovers") return true
    return false
  }
  val displayName: String
    get() = if (isTargetCrossover()) str(R.string.title_crossover, text) else text
}

val categoryCache = Cache<Array<CategoryLink>>("Category", TimeUnit.DAYS.toMillis(7))

fun fetchCategoryData(categoryUrlComponent: String): Deferred<Array<CategoryLink>>
    = async2(CommonPool) {
  categoryCache.hit(categoryUrlComponent).ifPresent2 { return@async2 it }
  val html = patientlyFetchURL("https://www.fanfiction.net/$categoryUrlComponent/") {
    Notifications.show(Notifications.Kind.OTHER,
        R.string.error_with_categories, categoryUrlComponent)
    Log.e(TAG, "Category fetch fail: $categoryUrlComponent", it)
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
