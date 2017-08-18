package slak.fanfictionstories

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class StoryFetcher(val storyid: Long, val ctx: Context) {
  companion object {
    private val ffnetMutex: Mutex = Mutex()
  }

  var metadata: Optional<MutableMap<String, Any>> = Optional.empty()
    private set

  private val regexOpts: Set<RegexOption> = hashSetOf(
      RegexOption.MULTILINE,
      RegexOption.UNIX_LINES,
      RegexOption.DOT_MATCHES_ALL
  )

  fun fetchMetadata(): Deferred<StoryModel> = async(CommonPool) { return@async ffnetMutex.withLock {
    val html: String = patientlyFetchChapter(1).await()

    val author =
        Regex("<a class='xcontrast_txt' href='/u/([0-9]+)/.*?'>(.*?)</a>", regexOpts)
        .find(html) ?: throw IllegalStateException("Can't match author")

    val title = Regex("<b class='xcontrast_txt'>(.*?)</b>", regexOpts).find(html) ?:
        throw IllegalStateException("Can't match title")

    val summary =
        Regex("<div style='margin-top:2px' class='xcontrast_txt'>(.*?)</div>", regexOpts)
        .find(html) ?: throw IllegalStateException("Can't match summary")

    val categories =
        Regex("id=pre_story_links>.*?<a .*?>(.*?)</a>.*?<a .*?>(.*?)</a>", regexOpts)
        .find(html) ?: throw IllegalStateException("Can't match categories")

    val metadataInnerHtml =
        Regex("<span class='xgray xcontrast_txt'>(.*?)</span>.*?</span>", regexOpts)
            .find(html) ?: throw IllegalStateException("Can't match metadata for FF.net chapter 1")
    val metadataStr = metadataInnerHtml.groupValues[1]
    val ratingLang = Regex("Rated: <a .*?>Fiction[ ]{2}(.*?)</a> - (.*?) -", regexOpts)
        .find(metadataStr) ?: throw IllegalStateException("Can't match rating/language")
    val words = Regex("Words: ([0-9,]+)", regexOpts).find(metadataStr) ?:
        throw IllegalStateException("Can't match word count")
    val chapters = Regex("Chapters: ([0-9]+)", regexOpts).find(metadataStr)
    val favs = Regex("Favs: ([0-9]+)", regexOpts).find(metadataStr)
    val follows = Regex("Follows: ([0-9]+)", regexOpts).find(metadataStr)
    val reviews = Regex("Reviews: <a.*?>([0-9]+)</a>", regexOpts).find(metadataStr)
    val published = Regex("Published: <span data-xutime='([0-9]+)'>", regexOpts).find(html)
    val updated = Regex("Updated: <span data-xutime='([0-9]+)'>", regexOpts).find(html)

    // Disambiguate genres/characters
    val split = ArrayList(metadataStr.split('-'))
    val findGenres = split.filter {
      it.contains(Regex("Adventure|Angst|Drama|Fantasy|Friendship|Humor|Hurt/Comfort|"+
          "Poetry|Romance|Sci-Fi|Supernatural|Tragedy"))
    }
    var genres: String = "None"
    if (findGenres.isNotEmpty()) {
      genres = findGenres[0].trim()
      split.removeAll { findGenres.contains(it) }
    }
    val thingsAfterCharacters =
        Regex("Words|Chapters|Reviews|Favs|Follows|Published|Updated", regexOpts)
    val characters = if (split[2].contains(thingsAfterCharacters)) "None" else split[2]

    metadata = Optional.of(mutableMapOf(
        "storyid" to storyid,
        "authorid" to author.groupValues[1].toLong(),
        "rating" to ratingLang.groupValues[1],
        "language" to ratingLang.groupValues[2],
        "genres" to genres,
        "characters" to characters.trim(),
        "chapters" to if (chapters != null) chapters.groupValues[1].toLong() else 1L,
        "wordCount" to words.groupValues[1].replace(",", "").toLong(),
        "reviews" to if (reviews != null) reviews.groupValues[1].toLong() else 0L,
        "favorites" to if (favs != null) favs.groupValues[1].toLong() else 0L,
        "follows" to if (follows != null) follows.groupValues[1].toLong() else 0L,
        "publishDate" to if (published != null) published.groupValues[1].toLong() else 0L,
        "updateDate" to if (updated != null) updated.groupValues[1].toLong() else 0L,
        "isCompleted" to if (metadataInnerHtml.groupValues[0].indexOf("Complete") > -1) 1L else 0L,
        "scrollProgress" to 0L,
        "currentChapter" to 0L,
        "status" to "remote",
        "canon" to categories.groupValues[2],
        "category" to categories.groupValues[1],
        "summary" to summary.groupValues[1],
        "author" to author.groupValues[2],
        "title" to title.groupValues[1]
    ))

    return@withLock StoryModel(metadata.get(), fromDb = false)
  } }

  private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  private fun patientlyFetchChapter(chapter: Int): Deferred<String> = async(CommonPool) {
    val activeNetwork = cm.activeNetworkInfo
    if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting) {
      // No connection; wait
      delay(5, TimeUnit.SECONDS)
      println("no connection") // FIXME update notification
      return@async patientlyFetchChapter(chapter).await()
    } else if (activeNetwork.isConnectedOrConnecting && !activeNetwork.isConnected) {
      // If we're connecting; wait
      delay(3, TimeUnit.SECONDS)
      println("connecting") // FIXME update notification
      return@async patientlyFetchChapter(chapter).await()
    }
    // We have a connection
    try {
      return@async URL("https://www.fanfiction.net/s/$storyid/$chapter/").readText()
      // FIXME update notification
    } catch (t: Throwable) {
      Log.e("StoryFetcher", "", t)
      // Something happened; retry
      // FIXME update notification
      delay(1, TimeUnit.SECONDS)
      return@async patientlyFetchChapter(chapter).await()
    }
  }

  private fun parseChapter(fromHtml: String): String {
    val story = Regex("id='storytext'>(.*?)</div>", regexOpts).find(fromHtml) ?:
        throw IllegalStateException("Cannot find story")
    return story.groupValues[1]
  }

  fun fetchChapters(from: Int = 1, to: Int = -1): Channel<String>  {
    if (!metadata.isPresent && to == -1)
      throw IllegalArgumentException("Specify 'to' chapter if metadata is missing")
    val target = if (to == -1) (metadata.get()["chapters"] as Long).toInt() else to
    // The buffer size is completely arbitrary
    val channel = Channel<String>(10)
    launch(CommonPool) { ffnetMutex.withLock {
      for (chapterNr in from..target) {
        delay(1, TimeUnit.SECONDS)
        channel.send(parseChapter(patientlyFetchChapter(chapterNr).await()))
        // FIXME update notification
      }
      channel.close()
    } }
    return channel
  }

}
