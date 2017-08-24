package slak.fanfictionstories

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.experimental.*
import java.io.*
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit

data class Canon(val title: String, val url: String, val stories: String) : Serializable

// Unix timestamp + canon list
private typealias CategoryCanons = Pair<Long, List<Canon>>

class CategoryFetcher(private val ctx: Context) : Fetcher() {
  object Cache {
    // Cache categoryIdx's result
    private var cache = Array<CategoryCanons?>(CATEGORIES.size, { null })
    private val cacheMapFile = File(MainActivity.cacheDirectory, "category_canons.array")
    private val TAG = "CategoryCache"

    fun deserialize() {
      if (!cacheMapFile.exists()) {
        return
      }
      val objIn = ObjectInputStream(FileInputStream(cacheMapFile))
      // I serialize it however I like, I deserialize it however I like, so stfu
      @Suppress("Unchecked_Cast")
      val array = objIn.readObject() as Array<CategoryCanons?>
      cache = array
      objIn.close()
    }

    fun serialize() = launch(CommonPool) {
      val objOut = ObjectOutputStream(FileOutputStream(cacheMapFile))
      objOut.writeObject(cache)
      objOut.close()
    }

    fun update(categoryIdx: Int, canons: List<Canon>) {
      cache[categoryIdx] = Pair(System.currentTimeMillis(), canons)
      serialize()
    }

    fun hit(categoryIdx: Int): Optional<List<Canon>> {
      if (cache[categoryIdx] == null) {
        Log.d(TAG, "Cache miss: $categoryIdx")
        return Optional.empty()
      }
      // 7 days
      if (System.currentTimeMillis() - cache[categoryIdx]!!.first > 1000 * 60 * 60 * 24 * 7) {
        // Cache expired; remove and return nothing
        Log.d(TAG, "Cache expired: $categoryIdx")
        cache[categoryIdx] = null
        serialize()
        return Optional.empty()
      }
      Log.d(TAG, "Cache hit: $categoryIdx")
      return Optional.of(cache[categoryIdx]!!.second)
    }

    fun clear(categoryIdx: Int) {
      cache[categoryIdx] = null
      serialize()
    }
  }

  private fun fetchCategory(categoryIdx: Int,
                            n: Notifications): Deferred<String> = async(CommonPool) {
    delay(StoryFetcher.RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
    try {
      return@async URL("https://www.fanfiction.net/${URL_COMPONENTS[categoryIdx]}").readText()
    } catch (t: Throwable) {
      // Something happened; retry
      n.show(MainActivity.res.getString(R.string.error_with_categories, CATEGORIES[categoryIdx]))
      Log.e(TAG, "getCanonsForCategory${CATEGORIES[categoryIdx]}", t)
      delay(StoryFetcher.RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
      return@async fetchCategory(categoryIdx, n).await()
    }
  }

  fun get(categoryIdx: Int): Deferred<List<Canon>> = async(CommonPool) {
    val cachedValue = Cache.hit(categoryIdx)
    if (cachedValue.isPresent) return@async cachedValue.get()
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val n = Notifications(ctx, Notifications.Kind.OTHER)
    return@async checkNetworkState(ctx, cm, n, { _ ->
      val html = fetchCategory(categoryIdx, n).await()
      val table =
          Regex("id='list_output'><TABLE WIDTH='100%'><TR>(.*?)</TR></TABLE>", regexOpts)
              .find(html) ?: throw IllegalStateException("Can't get category table")
      // Get rid of the td's so we're left with (regular) divs
      val divString =
          table.groupValues[1].replace(Regex("</?TD.*?>", regexOpts), "")
      val results = Regex(
          "<div><a href=\"(.*?)\" title=\"(.*?)\">.*?CLASS='gray'>\\((.*?)\\)</SPAN></div>",
          regexOpts).findAll(divString)
      val canons = results.map {
        Canon(it.groupValues[2], it.groupValues[1], it.groupValues[3])
      }.toList()
      Cache.update(categoryIdx, canons)
      return@checkNetworkState canons
    }).await()
  }
}
