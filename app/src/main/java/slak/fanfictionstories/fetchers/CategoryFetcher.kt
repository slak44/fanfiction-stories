package slak.fanfictionstories.fetchers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.Canon
import slak.fanfictionstories.R
import slak.fanfictionstories.activities.categories
import slak.fanfictionstories.activities.categoryUrl
import slak.fanfictionstories.utility.Notifications
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.async2
import slak.fanfictionstories.utility.waitForNetwork
import java.io.*
import java.net.URL
import java.util.*

// Unix timestamp + canon list
private typealias CategoryCanons = Pair<Long, List<Canon>>

class CategoryFetcher(private val ctx: Context) : Fetcher() {
  object Cache {
    // Cache categoryIdx's result
    private var cache = Array<CategoryCanons?>(categories.size, { null })
    private val cacheMapFile = File(Static.cacheDir, "category_canons.array")
    private const val TAG = "CategoryCache"
    // 7 days
    private const val CACHE_LIFE_MS = 1000 * 60 * 60 * 24 * 7

    fun deserialize() = async2(CommonPool) {
      if (!cacheMapFile.exists()) {
        return@async2
      }
      val objIn = ObjectInputStream(FileInputStream(cacheMapFile))
      try {
        // I serialize it however I like, I deserialize it however I like, so stfu
        @Suppress("Unchecked_Cast")
        val array = objIn.readObject() as Array<CategoryCanons?>
        cache = array
      } catch (ex: IOException) {
        // Ignore errors with the cache; don't crash the app because of it
        cacheMapFile.delete()
      } finally {
        objIn.close()
      }
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
      if (System.currentTimeMillis() - cache[categoryIdx]!!.first > CACHE_LIFE_MS) {
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
                            n: Notifications): Deferred<String> = async2(CommonPool) {
    delay(RATE_LIMIT_MILLISECONDS)
    waitForNetwork(n).await()
    try {
      return@async2 URL("https://www.fanfiction.net/${categoryUrl[categoryIdx]}").readText()
    } catch (t: Throwable) {
      // Something happened; retry
      n.show(Static.res.getString(R.string.error_with_categories, categories[categoryIdx]))
      Log.e(TAG, "getCanonsForCategory${categories[categoryIdx]}", t)
      delay(RATE_LIMIT_MILLISECONDS)
      return@async2 fetchCategory(categoryIdx, n).await()
    }
  }

  fun get(categoryIdx: Int): Deferred<List<Canon>> = async2(CommonPool) {
    val cachedValue = Cache.hit(categoryIdx)
    if (cachedValue.isPresent) return@async2 cachedValue.get()
    val n = Notifications(ctx, Notifications.Kind.OTHER)
    // FIXME be nice and show some spinny loady crap if we miss the cache
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
    return@async2 canons
  }
}
