package slak.fanfictionstories

import android.content.Context
import android.util.Log
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.sync.withLock
import org.jsoup.Jsoup
import java.net.URL

class CanonFetcher(private val ctx: Context, private val canonUrlComponent: String,
                   private val canonTitle: String, private val srcCategory: String) : Fetcher() {
  private fun fetchPage(page: Int, n: Notifications): Deferred<String> = async2(CommonPool) {
    return@async2 DOWNLOAD_MUTEX.withLock {
      delay(Fetcher.RATE_LIMIT_MILLISECONDS)
      waitForNetwork(n).await()
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

  private fun parseHtml(html: String): List<StoryModel> {
    val doc = Jsoup.parse(html)
    val list = mutableListOf<StoryModel>()
    // FIXME use parallel map for this instead of foreach
    doc.select("#content_wrapper_inner > div.z-list.zhover.zpointer").forEach {
      // Looks like /s/12656819/1/For-the-Motherland, pick the id
      val storyId = it.child(0).attr("href").split('/')[2].toLong()
      // The one and only text node there is the title
      val title = it.child(0).textNodes()[0].toString()

      // The author 'a' element is the second last before the reviews
      val authorAnchor = it.select("a:not(.reviews)").last()
      // Href looks like /u/6772732/Gnaoh-El-Nart, pick the id
      val authorId = authorAnchor.attr("href").split('/')[2].toLong()
      val authorName = authorAnchor.textNodes()[0].toString()

      val canon = canonTitle
      val category = srcCategory

      // There is only one such div
      val summaryMetaDiv = it.select("div.z-indent.z-padtop")[0]
      val summary = summaryMetaDiv.textNodes()[0].toString()
      val metaSection = summaryMetaDiv.child(0).html()
      val meta = parseStoryMetadata(metaSection)
      val isCompleted = metaSection.indexOf("Complete") > -1

      val publishTime = publishedTimeStoryMeta(html)
      val updateTime = updatedTimeStoryMeta(html)

      list.add(StoryModel(mutableMapOf(
          "storyId" to storyId,
          "authorid" to authorId,
          "rating" to meta["rating"]!!,
          "language" to meta["language"]!!,
          "genres" to meta["genres"]!!,
          "characters" to meta["characters"]!!,
          "chapters" to if (meta["chapters"] != null) meta["chapters"]!!.toLong() else 1L,
          "wordCount" to meta["words"]!!.replace(",", "").toLong(),
          "reviews" to if (meta["reviews"] != null) meta["reviews"]!!.toLong() else 0L,
          "favorites" to if (meta["favs"] != null) meta["favs"]!!.toLong() else 0L,
          "follows" to if (meta["follows"] != null) meta["follows"]!!.toLong() else 0L,
          "publishDate" to (publishTime?.toLong() ?: 0L),
          "updateDate" to (updateTime?.toLong() ?: 0L),
          "isCompleted" to if (isCompleted) 1L else 0L,
          "scrollProgress" to 0.0,
          "scrollAbsolute" to 0L,
          "currentChapter" to 0L,
          "status" to "remote",
          "canon" to canon,
          "category" to category,
          "summary" to summary,
          "author" to authorName,
          "title" to title,
          "chapterTitles" to ""
      ), false))
    }
    return list
  }

  fun get(page: Int): Deferred<List<StoryModel>> = async2(CommonPool) {
    val n = Notifications(ctx, Notifications.Kind.OTHER)
    val html = fetchPage(page, n).await()
    return@async2 parseHtml(html)
  }
}
