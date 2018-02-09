package slak.fanfictionstories.fetchers

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.db.insertOrThrow
import org.jetbrains.anko.db.replaceOrThrow
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.StoryStatus
import slak.fanfictionstories.fetchers.Fetcher.TAG
import slak.fanfictionstories.fetchers.Fetcher.parseMetadata
import slak.fanfictionstories.fetchers.Fetcher.regexOpts
import slak.fanfictionstories.utility.*
import slak.fanfictionstories.writeChapters
import java.util.*

fun getFullStory(ctx: Context, storyId: Long): Deferred<Optional<StoryModel>> = async2(CommonPool) {
  val fetcher = StoryFetcher(storyId, ctx)
  val model = fetcher.fetchMetadata().await()
  model.status = StoryStatus.LOCAL
  try {
    ctx.database.use {
      insertOrThrow("stories", *model.toKvPairs())
    }
  } catch (ex: SQLiteConstraintException) {
    Log.e("getFullStory", "", ex)
    errorDialog(R.string.unique_constraint_violated, R.string.unique_constraint_violated_tip)
    return@async2 Optional.empty<StoryModel>()
  }
  val isWriting: Boolean =
      writeChapters(ctx, storyId, fetcher.fetchChapters(Notifications.Kind.DOWNLOADING)).await()
  if (isWriting) {
    Notifications.downloadedStory(model.title)
    Notifications.cancel(Notifications.Kind.DOWNLOADING)
  } else {
    ctx.database.updateInStory(storyId, "status" to "remote")
  }
  return@async2 model.opt()
}

// FIXME this class is poorly designed. perhaps it should be just a bunch of stateless functions
class StoryFetcher(private val storyId: Long, private val ctx: Context) {
  private var metadata: Optional<MutableMap<String, Any>> = Optional.empty()
  private var metadataChapter: Optional<String> = Optional.empty()

  fun setMetadata(model: StoryModel) {
    if (model.storyIdRaw != storyId) throw IllegalArgumentException("Arg storyId does not match")
    metadata = model.src.opt()
  }

  fun fetchMetadata(): Deferred<StoryModel> = async2(CommonPool) {
    val html = fetchChapter(1).await()
    metadataChapter = parseChapter(html).opt()
    val meta = parseMetadata(html, storyId)
    metadata = meta.opt()
    return@async2 StoryModel(metadata.get())
  }

  /**
   * @returns whether or not the update was done
   */
  fun update(oldModel: StoryModel): Deferred<Boolean> = async2(CommonPool) {
    val meta = metadata.orElseThrow(IllegalStateException("Cannot update before fetching metadata"))
    Notifications.show(Notifications.Kind.UPDATING, R.string.checking_story, oldModel.title)
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
      Notifications.show(Notifications.Kind.UPDATING, R.string.fetching_chapter, 1, newModel.title)
      val isWriting = writeChapters(ctx, storyId, c).await()
      if (!isWriting) return@async2 revertUpdate()
      return@async2 true
    }
    // Chapters have been added
    if (newModel.chapterCount > oldModel.chapterCount) {
      val chapters = fetchChapters(
          Notifications.Kind.UPDATING, oldModel.chapterCount + 1, newModel.chapterCount)
      val isWriting = writeChapters(ctx, storyId, chapters).await()
      if (!isWriting) return@async2 revertUpdate()
      return@async2 true
    }
    val isWriting = writeChapters(ctx, storyId, fetchChapters(Notifications.Kind.UPDATING)).await()
    if (!isWriting) return@async2 revertUpdate()
    Notifications.cancel(Notifications.Kind.UPDATING)
    return@async2 true
  }

  fun fetchChapter(chapter: Int): Deferred<String> =
      patientlyFetchURL("https://www.fanfiction.net/s/$storyId/$chapter/") {
        Notifications.show(Notifications.Kind.OTHER,
            R.string.error_fetching_story_data, storyId.toString())
        Log.e(TAG, "fetchChapter", it)
      }

  fun parseChapter(fromHtml: String): String {
    val story = Regex("id='storytext'>(.*?)</div>", regexOpts).find(fromHtml) ?:
        throw IllegalStateException("Cannot find story")
    return story.groupValues[1]
  }

  fun fetchChapters(kind: Notifications.Kind, from: Int = 1, to: Int = -1): Channel<String>  {
    if (!metadata.isPresent && to == -1)
      throw IllegalArgumentException("Specify 'to' chapter if metadata is missing")
    val target = if (to == -1) (metadata.get()["chapters"] as Long).toInt() else to
    val storyName = if (metadata.isPresent) metadata.get()["title"]!! else ""
    // The buffer size is completely arbitrary
    val channel = Channel<String>(10)
    launch(CommonPool) {
      for (chapterNr in from..target) {
        Notifications.show(kind, R.string.fetching_chapter, chapterNr, storyName)
        channel.send(parseChapter(fetchChapter(chapterNr).await()))
      }
      channel.close()
    }
    return channel
  }

}
