package slak.fanfictionstories.fetchers

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import org.jetbrains.anko.db.insertOrThrow
import org.jetbrains.anko.db.replaceOrThrow
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.Fetcher.DOWNLOAD_MUTEX
import slak.fanfictionstories.fetchers.Fetcher.RATE_LIMIT_MILLISECONDS
import slak.fanfictionstories.fetchers.Fetcher.STORAGE_WAIT_DELAY_SECONDS
import slak.fanfictionstories.fetchers.Fetcher.TAG
import slak.fanfictionstories.fetchers.Fetcher.parseMetadata
import slak.fanfictionstories.fetchers.Fetcher.regexOpts
import slak.fanfictionstories.utility.*
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

fun getFullStory(ctx: Context, storyId: Long,
                 n: Notifications): Deferred<Optional<StoryModel>> = async2(CommonPool) {
  val fetcher = StoryFetcher(storyId, ctx)
  val model = fetcher.fetchMetadata(n).await()
  model.status = StoryStatus.LOCAL
  try {
    ctx.database.use {
      insertOrThrow("stories", *model.toKvPairs())
    }
  } catch (ex: SQLiteConstraintException) {
    Log.e("getFullStory", "", ex)
    errorDialog(ctx, R.string.unique_constraint_violated, R.string.unique_constraint_violated_tip)
    return@async2 Optional.empty<StoryModel>()
  }
  val isWriting: Boolean = writeStory(ctx, storyId, fetcher.fetchChapters(n)).await()
  if (isWriting) {
    Notifications.downloadedStory(ctx, model.title)
  } else {
    ctx.database.updateInStory(storyId, "status" to "remote")
  }
  return@async2 model.opt()
}

object Fetcher {
  const val RATE_LIMIT_MILLISECONDS = 300L
  const val CONNECTION_WAIT_DELAY_SECONDS = 3L
  const val CONNECTION_MISSING_DELAY_SECONDS = 5L
  const val STORAGE_WAIT_DELAY_SECONDS = 5L
  @JvmStatic
  val DOWNLOAD_MUTEX = Mutex()
  @JvmStatic
  val TAG = "Fetcher"
  @JvmStatic
  val regexOpts: Set<RegexOption> = hashSetOf(
      RegexOption.MULTILINE,
      RegexOption.UNIX_LINES,
      RegexOption.DOT_MATCHES_ALL
  )

  // Regen DB if you change this separator
  const val CHAPTER_TITLE_SEPARATOR = "^^^%!@#__PLACEHOLDER__%!@#~~~"

  private fun cleanNrMatch(res: MatchResult?): String? =
      res?.groupValues?.get(1)?.replace(",", "")?.trim()

  /**
   * Parses story metadata from the metadata div.
   */
  fun parseStoryMetadata(metadata: String): Map<String, String?> {
    val ratingLang = Regex("Rated: (?:<a .*?>Fiction[ ]{2})?(.*?)(?:</a>)? - (.*?) -", regexOpts)
        .find(metadata) ?: {
      val ex = IllegalStateException("Can't match rating/language")
      Log.e("parseStoryMetadata", "", ex)
      throw ex
    }()

    val words = Regex("Words: ([0-9,]+)", regexOpts).find(metadata) ?: {
      val ex = IllegalStateException("Can't match word count")
      Log.e("parseStoryMetadata", "", ex)
      throw ex
    }()

    val chapters = Regex("Chapters: ([0-9,]+)", regexOpts).find(metadata)
    val favs = Regex("Favs: ([0-9,]+)", regexOpts).find(metadata)
    val follows = Regex("Follows: ([0-9,]+)", regexOpts).find(metadata)
    val reviews = Regex("Reviews: (?:<a.*?>)?([0-9,]+)(?:</a>)?", regexOpts).find(metadata)

    // Disambiguate genres/characters
    val split = ArrayList(metadata.split(" - "))
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

    return mapOf(
        "rating" to ratingLang.groupValues[1],
        "language" to ratingLang.groupValues[2],
        "words" to words.groupValues[1],
        "chapters" to cleanNrMatch(chapters),
        "favs" to cleanNrMatch(favs),
        "follows" to cleanNrMatch(follows),
        "reviews" to cleanNrMatch(reviews),
        "genres" to genres,
        "characters" to characters.trim()
    )
  }

  fun publishedTimeStoryMeta(html: String): String? {
    val time = Regex("Published: <span data-xutime='([0-9]+)'>", regexOpts).find(html)
    return if (time == null) null else time.groupValues[1]
  }

  fun updatedTimeStoryMeta(html: String): String? {
    val time = Regex("Updated: <span data-xutime='([0-9]+)'>", regexOpts).find(html)
    return if (time == null) null else time.groupValues[1]
  }

  fun authorIdFromAuthor(author: Element): Long {
    // The `href` on the author element looks like /u/6772732/Gnaoh-El-Nart, so pick the id at pos 2
    return author.attr("href").split("/")[2].toLong()
  }

  /**
   * Parses all required metadata for a [StoryModel].
   * @param html html string of any chapter of the story
   */
  fun parseMetadata(html: String, storyId: Long): MutableMap<String, Any> {
    // The raw html is completely insane
    // I mean really, using ' for attributes?
    // Sometimes not using any quotes at all?
    // Mixing lower case and upper case for tags?
    // Inline css/js?
    // Having a tag soup because line breaks appear at random?
    // Not closing tags that should have been?
    // Are the standards too permissive, or browser implementations...
    // Thank god for html parsers

    val doc = Jsoup.parse(html)

    val author = doc.select("#profile_top > a.xcontrast_txt")[0]
    val title = doc.select("#profile_top > b.xcontrast_txt")[0].text()
    val summary = doc.select("#profile_top > div.xcontrast_txt")[0].text()
    val categories = doc.select("#pre_story_links > span.lc-left > a.xcontrast_txt")
    val metaString = doc.select("#profile_top > span.xgray").html()
    val meta = parseStoryMetadata(metaString)

    // Parse chapter titles only if there are any chapters to name
    val chapterTitles: Optional<String> = if (meta["chapters"] == null) {
      Optional.empty()
    } else {
      doc.select("#chap_select > option").joinToString(CHAPTER_TITLE_SEPARATOR) {
        // The actual chapter title is preceded by the chapter nr, a dot, and a space:
        it.text().replace(Regex("\\d+\\. ", regexOpts), "")
      }.opt()
    }

    val publishTime = publishedTimeStoryMeta(html)
    val updateTime = updatedTimeStoryMeta(html)

    return mutableMapOf(
        "storyId" to storyId,
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
        "isCompleted" to if (metaString.indexOf("Complete") > -1) 1L else 0L,
        "scrollProgress" to 0.0,
        "scrollAbsolute" to 0L,
        "currentChapter" to 0L,
        "status" to "remote",
        "canon" to categories[0].text(),
        "category" to categories[1].text(),
        "summary" to summary,
        "authorid" to authorIdFromAuthor(author),
        "author" to author.text(),
        "title" to title,
        "chapterTitles" to chapterTitles.orElse("")
    )
  }
  // TODO: consider a general cache for all fetchers (with different cache times obv)
}

class StoryFetcher(private val storyId: Long, private val ctx: Context) {
  private var metadata: Optional<MutableMap<String, Any>> = Optional.empty()
  private var metadataChapter: Optional<String> = Optional.empty()

  fun setMetadata(model: StoryModel) {
    if (model.storyIdRaw != storyId) throw IllegalArgumentException("Arg storyId does not match")
    metadata = model.src.opt()
  }

  fun fetchMetadata(n: Notifications): Deferred<StoryModel> = async2(CommonPool) {
    DOWNLOAD_MUTEX.lock()
    delay(RATE_LIMIT_MILLISECONDS)
    val html: String = fetchChapter(1, n).await()
    DOWNLOAD_MUTEX.unlock()
    metadataChapter = parseChapter(html).opt()
    val meta = parseMetadata(html, storyId)
    metadata = meta.opt()
    return@async2 StoryModel(metadata.get(), fromDb = false)
  }

  /**
   * @returns whether or not the update was done
   */
  fun update(oldModel: StoryModel, n: Notifications): Deferred<Boolean> = async2(CommonPool) {
    val meta = metadata.orElseThrow(IllegalStateException("Cannot update before fetching metadata"))
    n.show(ctx.resources.getString(R.string.checking_story, oldModel.title))
    val newChapterCount = metadata.get()["chapters"] as Long
    if (oldModel.currentChapter > newChapterCount) {
      meta["currentChapter"] = newChapterCount
      meta["scrollProgress"] = 0.0
      meta["scrollAbsolute"] = 0L
    } else {
      meta["currentChapter"] = oldModel.currentChapter.toLong()
      meta["scrollProgress"] = oldModel.src["scrollProgress"] as Double
      meta["scrollAbsolute"] = oldModel.src["scrollAbsolute"] as Long
    }
    meta["status"] = oldModel.status.toString()
    val newModel = StoryModel(meta, fromDb = false)
    ctx.database.use {
      replaceOrThrow("stories", *newModel.toKvPairs())
    }
    // Skip non-locals from global updates
    if (oldModel.status != StoryStatus.LOCAL) return@async2 false
    // Stories can't get un-updated
    if (oldModel.updateDateSeconds != 0L && newModel.updateDateSeconds == 0L)
      throw IllegalStateException("The old model had updates; the new one doesn't")
    // Story has never received an update, our job here is done
    if (newModel.updateDateSeconds == 0L) return@async2 false
    // Update time is identical, nothing to do again
    if (oldModel.updateDateSeconds == newModel.updateDateSeconds) return@async2 false

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
      if (!isWriting) return@async2 revertUpdate()
      return@async2 true
    }
    // Chapters have been added
    if (newModel.chapterCount > oldModel.chapterCount) {
      val chapters = fetchChapters(n, oldModel.chapterCount + 1, newModel.chapterCount)
      val isWriting = writeStory(ctx, storyId, chapters).await()
      if (!isWriting) return@async2 revertUpdate()
      return@async2 true
    }
    // At least one chapter has been changed/removed, redownload everything
    var dir = storyDir(ctx, storyId)
    // Keep trying if we can't get the story directory right now
    var i = 0
    while (!dir.isPresent) {
      // Give up after 5 minutes
      if (i == 60) return@async2 revertUpdate()
      delay(STORAGE_WAIT_DELAY_SECONDS, TimeUnit.SECONDS)
      dir = storyDir(ctx, storyId)
      i++
    }
    dir.get().deleteRecursively()
    val isWriting = writeStory(ctx, storyId, fetchChapters(n)).await()
    if (!isWriting) return@async2 revertUpdate()
    return@async2 true
  }

  fun fetchChapter(chapter: Int, n: Notifications): Deferred<String> = async2(CommonPool) {
    waitForNetwork(n).await()
    try {
      return@async2 URL("https://www.fanfiction.net/s/$storyId/$chapter/").readText()
    } catch (t: Throwable) {
      // Something happened; retry
      n.show(ctx.resources.getString(R.string.error_fetching_something, storyId.toString()))
      Log.e(TAG, "fetchChapter", t)
      delay(RATE_LIMIT_MILLISECONDS)
      return@async2 fetchChapter(chapter, n).await()
    }
  }

  fun parseChapter(fromHtml: String): String {
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
    launch(CommonPool) { DOWNLOAD_MUTEX.withLock {
      for (chapterNr in from..target) {
        n.show(ctx.resources.getString(R.string.fetching_chapter, chapterNr, storyName))
        delay(RATE_LIMIT_MILLISECONDS)
        channel.send(parseChapter(fetchChapter(chapterNr, n).await()))
      }
      channel.close()
      n.show(ctx.resources.getString(R.string.done_story, storyName))
    } }
    return channel
  }

}
