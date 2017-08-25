package slak.fanfictionstories

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.sync.withLock
import java.net.URL
import java.util.concurrent.TimeUnit

class CanonFetcher(private val ctx: Context, private val canonUrlComponent: String,
                   private val canonTitle: String) : Fetcher() {
  private fun fetchPage(page: Int, n: Notifications): Deferred<String> = async(CommonPool) {
    return@async DOWNLOAD_MUTEX.withLock {
      delay(StoryFetcher.RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
      try {
        return@withLock URL("https://www.fanfiction.net/$canonUrlComponent").readText()
      } catch (t: Throwable) {
        // Something happened; retry
        n.show(MainActivity.res.getString(R.string.error_with_canon_stories, canonTitle))
        Log.e(TAG, "CanonFetcher: retry", t)
        delay(StoryFetcher.RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
        return@withLock fetchPage(page, n).await()
      }
    }
  }

  private fun parseHtml(html: String): List<Long> {
    val ids = Regex("<a {2}class=stitle href=\"/s/(\\d+)/1/.*?\">",
        regexOpts).findAll(html)
    return ids.map { it.groupValues[1].toLong() }.toList()
  }

  fun get(page: Int): Deferred<List<StoryModel>> = async(CommonPool) {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val n = Notifications(ctx, Notifications.Kind.OTHER)
    return@async checkNetworkState(ctx, cm, n, { _ ->
      val html = fetchPage(page, n).await()
      val deferredStories = parseHtml(html).map { StoryFetcher(it, ctx).fetchMetadata(n) }
      return@checkNetworkState deferredStories.map { it.await() }
    }).await()
  }
}
