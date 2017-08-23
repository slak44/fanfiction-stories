package slak.fanfictionstories

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import org.jetbrains.anko.db.insertOrThrow
import org.jetbrains.anko.db.replaceOrThrow
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

fun getFullStory(ctx: Context, storyId: Long, n: Notifications) = async(CommonPool) {
  val fetcher = StoryFetcher(storyId, ctx)
  val model = fetcher.fetchMetadata(n).await()
  val isWriting: Boolean = writeStory(ctx, storyId, fetcher.fetchChapters(n)).await()
  if (isWriting) {
    model.status = StoryStatus.LOCAL
    try {
      ctx.database.use {
        insertOrThrow("stories", *model.toKvPairs())
        Notifications.downloadedStory(ctx, model.title)
      }
    } catch (ex: SQLiteConstraintException) {
      Log.e("getFullStory", "", ex)
      errorDialog(ctx, R.string.unique_constraint_violated, R.string.unique_constraint_violated_tip)
    }
  }
}

class StoryFetcher(private val storyId: Long, val ctx: Context) {
  companion object {
    // Regen DB if you change this separator
    const val CHAPTER_TITLE_SEPARATOR = "^^^%!@#__PLACEHOLDER__%!@#~~~"
    const val RATE_LIMIT_SECONDS = 1L
    const val CONNECTION_WAIT_DELAY_SECONDS = 3L
    const val CONNECTION_MISSING_DELAY_SECONDS = 5L
    const val STORAGE_WAIT_DELAY_SECONDS = 5L

    private val ffnetMutex: Mutex = Mutex()
    private val regexOpts: Set<RegexOption> = hashSetOf(
        RegexOption.MULTILINE,
        RegexOption.UNIX_LINES,
        RegexOption.DOT_MATCHES_ALL
    )
    private val TAG = "StoryFetcher"
  }

  private var metadata: Optional<MutableMap<String, Any>> = Optional.empty()
  private var metadataChapter: Optional<String> = Optional.empty()

  fun fetchMetadata(n: Notifications): Deferred<StoryModel> = async(CommonPool) {
    return@async ffnetMutex.withLock {
    delay(RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
    val html: String = patientlyFetchChapter(1, n).await()

    // The regex are shit, because so is what we're trying to parse
    // I mean really, using ' for attributes?
    // Sometimes not using any quotes at all?
    // Mixing lower case and upper case for tags?
    // Inline css/js?
    // Tag soup?
    // Not closing tags that should be?

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
    var genres = "None"
    if (findGenres.isNotEmpty()) {
      genres = findGenres[0].trim()
      split.removeAll { findGenres.contains(it) }
    }
    val thingsAfterCharacters =
        Regex("Words|Chapters|Reviews|Favs|Follows|Published|Updated", regexOpts)
    val characters = if (split[2].contains(thingsAfterCharacters)) "None" else split[2]

    var chapterTitles: Optional<String> = Optional.empty()
    // Parsing chapter titles only if there are any chapters to name
    if (chapters != null) {
      val chapterTitlesRaw = Regex("id=chap_select.*?>(.*?)</select>", regexOpts).find(html)
          ?: throw IllegalStateException("Cannot find chapter titles")
      chapterTitles = Optional.of(chapterTitlesRaw.groupValues[1].replace(
          // The space at the end of this regex is intentional
          Regex("<option.*?>\\d+\\. ", regexOpts), CHAPTER_TITLE_SEPARATOR)
          // There is one at the beginning that we don't care about (because it's not a separator)
          .removePrefix(CHAPTER_TITLE_SEPARATOR))
    }

    metadata = Optional.of(mutableMapOf(
        "storyId" to storyId,
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
        "scrollProgress" to 0.0,
        "scrollAbsolute" to 0L,
        "currentChapter" to 0L,
        "status" to "remote",
        "canon" to categories.groupValues[2],
        "category" to categories.groupValues[1],
        "summary" to summary.groupValues[1],
        "author" to author.groupValues[2],
        "title" to title.groupValues[1],
        "chapterTitles" to if (chapterTitles.isPresent) chapterTitles.get() else ""
    ))

    metadataChapter = Optional.of(parseChapter(html))

    return@withLock StoryModel(metadata.get(), fromDb = false)
  } }

  /**
   * @returns whether or not the update was done
   */
  fun update(oldModel: StoryModel, n: Notifications): Deferred<Boolean> = async(CommonPool) {
    if (!metadata.isPresent) throw IllegalStateException("Cannot update before fetching metadata")
    n.show(ctx.resources.getString(R.string.checking_story, oldModel.title))
    val metaWithInitedValues = metadata.get()
    val newChapterCount = metadata.get()["chapters"] as Long
    if (oldModel.currentChapter > newChapterCount) {
      metaWithInitedValues["currentChapter"] = newChapterCount
      metaWithInitedValues["scrollProgress"] = 0.0
      metaWithInitedValues["scrollAbsolute"] = 0L
    } else {
      metaWithInitedValues["currentChapter"] = oldModel.currentChapter.toLong()
      metaWithInitedValues["scrollProgress"] = oldModel.src["scrollProgress"] as Double
      metaWithInitedValues["scrollAbsolute"] = oldModel.src["scrollAbsolute"] as Long
    }
    metaWithInitedValues["status"] = oldModel.status.toString()
    val newModel = StoryModel(metaWithInitedValues, fromDb = false)
    ctx.database.use {
      replaceOrThrow("stories", *newModel.toKvPairs())
    }
    // Skip non-locals from global updates
    if (oldModel.status != StoryStatus.LOCAL) return@async false
    // Stories can't get un-updated
    if (oldModel.updateDateSeconds != 0L && newModel.updateDateSeconds == 0L)
      throw IllegalStateException("The old model had updates; the new one doesn't")
    // Story has never received an update, our job here is done
    if (newModel.updateDateSeconds == 0L) return@async false
    // Update time is identical, nothing to do again
    if (oldModel.updateDateSeconds == newModel.updateDateSeconds) return@async false

    val revertUpdate: () -> Boolean = {
      // Revert model to old values
      ctx.database.use { replaceOrThrow("stories", *oldModel.toKvPairs()) }
      Log.e(TAG, "Had to revert update to $storyId")
      n.show(ctx.resources.getString(R.string.update_failed, oldModel.title))
      false
    }

    // If there is only one chapter, we already got it
    if (newModel.chapterCount == 1) {
      val c = Channel<String>(1)
      c.send(metadataChapter.get())
      c.close()
      n.show(ctx.resources.getString(R.string.fetching_chapter, 1, newModel.title))
      val isWriting = writeStory(ctx, storyId, c).await()
      if (!isWriting) return@async revertUpdate()
      return@async true
    }
    // Chapters have been added
    if (newModel.chapterCount > oldModel.chapterCount) {
      val chapters = fetchChapters(n, oldModel.chapterCount + 1, newModel.chapterCount)
      val isWriting = writeStory(ctx, storyId, chapters).await()
      if (!isWriting) return@async revertUpdate()
      return@async true
    }
    // At least one chapter has been changed/removed, redownload everything
    var dir = storyDir(ctx, storyId)
    // Keep trying if we can't get the story directory right now
    var i = 0
    while (!dir.isPresent) {
      // Give up after 5 minutes
      if (i == 60) return@async revertUpdate()
      delay(STORAGE_WAIT_DELAY_SECONDS, TimeUnit.SECONDS)
      dir = storyDir(ctx, storyId)
      i++
    }
    dir.get().deleteRecursively()
    val isWriting = writeStory(ctx, storyId, fetchChapters(n)).await()
    if (!isWriting) return@async revertUpdate()
    return@async true
  }

  private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  private fun patientlyFetchChapter(chapter: Int, n: Notifications): Deferred<String> = async(CommonPool) {
    return@async checkNetworkState(ctx, cm, n, { _ ->
      return@checkNetworkState fetchChapter(chapter, n).await()
    }).await()
  }

  @Suppress("LiftReturnOrAssignment")
  private fun fetchChapter(chapter: Int, n: Notifications): Deferred<String> = async(CommonPool) {
    try {
      return@async URL("https://www.fanfiction.net/s/$storyId/$chapter/").readText()
    } catch (t: Throwable) {
      // Something happened; retry
      n.show(ctx.resources.getString(R.string.error_fetching_something, storyId.toString()))
      Log.e(TAG, "fetchChapter", t)
      delay(RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
      return@async fetchChapter(chapter, n).await()
    }
  }

  private fun parseChapter(fromHtml: String): String {
    val story = Regex("id='storytext'>(.*?)</div>", regexOpts).find(fromHtml) ?:
        throw IllegalStateException("Cannot find story")
    return story.groupValues[1]
  }

  fun fetchChapters(n: Notifications, from: Int = 1, to: Int = -1): Channel<String>  {
    if (!metadata.isPresent && to == -1)
      throw IllegalArgumentException("Specify 'to' chapter if metadata is missing")
    val target = if (to == -1) (metadata.get()["chapters"] as Long).toInt() else to
    val storyName = if (metadata.isPresent) metadata.get()["title"] else ""
    // The buffer size is completely arbitrary
    val channel = Channel<String>(10)
    launch(CommonPool) { ffnetMutex.withLock {
      for (chapterNr in from..target) {
        n.show(ctx.resources.getString(R.string.fetching_chapter, chapterNr, storyName))
        delay(RATE_LIMIT_SECONDS, TimeUnit.SECONDS)
        channel.send(parseChapter(patientlyFetchChapter(chapterNr, n).await()))
      }
      channel.close()
      n.show(ctx.resources.getString(R.string.done_story, storyName))
    } }
    return channel
  }

}
