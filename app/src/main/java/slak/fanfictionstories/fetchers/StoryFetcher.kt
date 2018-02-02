package slak.fanfictionstories.fetchers

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.db.insertOrThrow
import org.jetbrains.anko.db.replaceOrThrow
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.Fetcher.RATE_LIMIT_MILLISECONDS
import slak.fanfictionstories.fetchers.Fetcher.STORAGE_WAIT_DELAY_SECONDS
import slak.fanfictionstories.fetchers.Fetcher.TAG
import slak.fanfictionstories.fetchers.Fetcher.parseMetadata
import slak.fanfictionstories.fetchers.Fetcher.regexOpts
import slak.fanfictionstories.utility.*
import java.util.*
import java.util.concurrent.TimeUnit

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

class StoryFetcher(private val storyId: Long, private val ctx: Context) {
  private var metadata: Optional<MutableMap<String, Any>> = Optional.empty()
  private var metadataChapter: Optional<String> = Optional.empty()

  fun setMetadata(model: StoryModel) {
    if (model.storyIdRaw != storyId) throw IllegalArgumentException("Arg storyId does not match")
    metadata = model.src.opt()
  }

  fun fetchMetadata(n: Notifications): Deferred<StoryModel> = async2(CommonPool) {
    val html = fetchChapter(1, n).await()
    metadataChapter = parseChapter(html).opt()
    val meta = parseMetadata(html, storyId)
    metadata = meta.opt()
    return@async2 StoryModel(metadata.get())
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
      meta["scrollAbsolute"] = 0.0
    } else {
      meta["currentChapter"] = oldModel.currentChapter.toLong()
      meta["scrollProgress"] = oldModel.src["scrollProgress"] as Double
      meta["scrollAbsolute"] = oldModel.src["scrollAbsolute"] as Double
    }
    meta["status"] = oldModel.status.toString()
    val newModel = StoryModel(meta)
    // Skip non-locals from global updates
    if (oldModel.status != StoryStatus.LOCAL) return@async2 false
    // Stories can't get un-updated
    if (oldModel.updateDateSeconds != 0L && newModel.updateDateSeconds == 0L)
      throw IllegalStateException("The old model had updates; the new one doesn't")
    // Story has never received an update, our job here is done
    if (newModel.updateDateSeconds == 0L) return@async2 false
    // Update time is identical, nothing to do again
    if (oldModel.updateDateSeconds == newModel.updateDateSeconds) return@async2 false

    ctx.database.use {
      replaceOrThrow("stories", *newModel.toKvPairs())
    }

    val revertUpdate: () -> Boolean = {
      // Revert model to old values
      ctx.database.use { replaceOrThrow("stories", *oldModel.toKvPairs()) }
      Log.e(TAG, "Had to revert update to $storyId")
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
    val isWriting = writeStory(ctx, storyId, fetchChapters(n)).await()
    if (!isWriting) return@async2 revertUpdate()
    return@async2 true
  }

  fun fetchChapter(chapter: Int, n: Notifications): Deferred<String> =
      patientlyFetchURL("https://www.fanfiction.net/s/$storyId/$chapter/", n) {
    n.show(ctx.resources.getString(R.string.error_fetching_story_data, storyId.toString()))
    Log.e(TAG, "fetchChapter", it)
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
    launch(CommonPool) {
      for (chapterNr in from..target) {
        n.show(ctx.resources.getString(R.string.fetching_chapter, chapterNr, storyName))
        delay(RATE_LIMIT_MILLISECONDS)
        channel.send(parseChapter(fetchChapter(chapterNr, n).await()))
      }
      channel.close()
      n.show(ctx.resources.getString(R.string.done_story, storyName))
    }
    return channel
  }

}
