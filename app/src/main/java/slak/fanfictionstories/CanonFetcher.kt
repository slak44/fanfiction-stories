package slak.fanfictionstories

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.sync.withLock
import java.net.URL

class CanonFetcher(private val ctx: Context, private val canonUrlComponent: String,
                   private val canonTitle: String) : Fetcher() {
  private fun fetchPage(page: Int, n: Notifications): Deferred<String> = async(CommonPool) {
    return@async DOWNLOAD_MUTEX.withLock {
      delay(Fetcher.RATE_LIMIT_MILLISECONDS)
      try {
        return@withLock URL("https://www.fanfiction.net/$canonUrlComponent").readText()
      } catch (t: Throwable) {
        // Something happened; retry
        n.show(MainActivity.res.getString(R.string.error_with_canon_stories, canonTitle))
        Log.e(TAG, "CanonFetcher: retry", t)
        delay(Fetcher.RATE_LIMIT_MILLISECONDS)
        return@withLock fetchPage(page, n).await()
      }
    }
  }

  private fun parseHtml(html: String): List<Long> {
    val ids = Regex("<a {2}class=stitle href=\"/s/(\\d+)/1/.*?\">",
        regexOpts).findAll(html)
    return ids.map { it.groupValues[1].toLong() }.toList()
  }

  fun get(page: Int): Channel<StoryModel> {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val n = Notifications(ctx, Notifications.Kind.OTHER)
    val channel = Channel<StoryModel>(25)
    checkNetworkState(ctx, cm, n, { _ ->
      val html = fetchPage(page, n).await()
      val deferredStories = parseHtml(html).map { StoryFetcher(it, ctx).fetchMetadata(n) }
      deferredStories.map { channel.send(it.await()) }
      channel.close()
    })
    return channel
  }
}
