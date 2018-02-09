package slak.fanfictionstories.fetchers

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.db.insertOrThrow
import org.jetbrains.anko.db.replaceOrThrow
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.StoryStatus
import slak.fanfictionstories.fetchers.FetcherUtils.TAG
import slak.fanfictionstories.fetchers.FetcherUtils.parseMetadata
import slak.fanfictionstories.utility.*
import slak.fanfictionstories.writeChapters
import java.util.*

/**
 * Download metadata and every chapter, then store them in the database and on disk.
 * @returns the model we just fetched, or an empty optional if the story already exists
 */
fun fetchAndWriteStory(storyId: Long): Deferred<Optional<StoryModel>> = async2(CommonPool) {
  val model = fetchStoryModel(storyId).await()
  model.status = StoryStatus.LOCAL
  try {
    Static.currentCtx.database.use {
      insertOrThrow("stories", *model.toKvPairs())
    }
  } catch (ex: SQLiteConstraintException) {
    Log.e("fetchAndWriteStory", "", ex)
    errorDialog(R.string.unique_constraint_violated, R.string.unique_constraint_violated_tip)
    return@async2 Optional.empty<StoryModel>()
  }
  val isWriting: Boolean =
      writeChapters(storyId, fetchChapterRange(Notifications.Kind.DOWNLOADING, model)).await()
  if (isWriting) {
    Notifications.downloadedStory(model.title)
    Notifications.cancel(Notifications.Kind.DOWNLOADING)
  } else {
    // FIXME show something saying we failed
    Static.currentCtx.database.updateInStory(storyId, "status" to "remote")
  }
  return@async2 model.opt()
}

fun fetchChapter(storyId: Long, chapter: Int): Deferred<String> {
  return patientlyFetchURL("https://www.fanfiction.net/s/$storyId/$chapter/") {
    Notifications.show(Notifications.Kind.OTHER,
        R.string.error_fetching_story_data, storyId.toString())
    Log.e(TAG, "fetchChapter", it)
  }
}

fun extractChapterText(doc: Document): String {
  return doc.getElementById("storytext")?.text()
      ?: throw IllegalArgumentException("No story text in given document")
}

fun fetchStoryModel(storyId: Long): Deferred<StoryModel> = async2(CommonPool) {
  val chapterHtml = fetchChapter(storyId, 1).await()
  return@async2 StoryModel(parseMetadata(chapterHtml, storyId))
}

/**
 * Get html of story chapters in given range.
 * @param kind what [Notifications.Kind] to show for each chapter that's fetched
 * @param from start index of chapters to fetch (1-indexed)
 * @param to end index of chapters to fetch (1-indexed). If -1, the end idx is the chapter count
 * @returns a [Channel] that supplies the html text
 */
fun fetchChapterRange(kind: Notifications.Kind, model: StoryModel,
                      from: Int = 1, to: Int = -1): Channel<String> {
  val target = if (to != -1) to else model.chapterCount
  // The buffer size is completely arbitrary
  val channel = Channel<String>(10)
  launch(CommonPool) {
    for (chapterNr in from..target) {
      Notifications.show(kind, R.string.fetching_chapter, chapterNr, model.title)
      val chapterHtml = fetchChapter(model.storyIdRaw, chapterNr).await()
      channel.send(extractChapterText(Jsoup.parse(chapterHtml)))
    }
    channel.close()
  }
  return channel
}

/**
 * Tries to update story metadata and chapters. Only works on local stories.
 * @returns whether or not the update was done
 */
fun updateStory(oldModel: StoryModel): Deferred<Boolean> = async2(CommonPool) {
  Notifications.show(Notifications.Kind.UPDATING, R.string.checking_story, oldModel.title)
  var newModel = fetchStoryModel(oldModel.storyIdRaw).await()

  // Skip non-locals from updates, since the operation does not make sense for them
  if (oldModel.status != StoryStatus.LOCAL) return@async2 false
  // Stories can't get un-updated
  if (oldModel.updateDateSeconds != 0L && newModel.updateDateSeconds == 0L)
    throw IllegalStateException("The old model had updates; the new one doesn't")
  // Story has never received an update, our job here is done
  if (newModel.updateDateSeconds == 0L) return@async2 false
  // Update time is identical, nothing to do again
  if (oldModel.updateDateSeconds == newModel.updateDateSeconds) return@async2 false

  // Copy story progress from oldModel
  val meta = newModel.src
  if (oldModel.currentChapter > newModel.chapterCount) {
    meta["currentChapter"] = newModel.chapterCount
    meta["scrollProgress"] = 0.0
    meta["scrollAbsolute"] = 0.0
  } else {
    meta["currentChapter"] = oldModel.currentChapter.toLong()
    meta["scrollProgress"] = oldModel.src["scrollProgress"] as Double
    meta["scrollAbsolute"] = oldModel.src["scrollAbsolute"] as Double
  }
  // Update model after copying
  newModel = StoryModel(meta)

  Static.currentCtx.database.use {
    replaceOrThrow("stories", *newModel.toKvPairs())
  }

  val channel: Channel<String> = when {
  // Special case when there is only one chapter
    newModel.chapterCount == 1 -> {
      Notifications.show(Notifications.Kind.UPDATING, R.string.fetching_chapter, 1, newModel.title)
      val channel = Channel<String>(1)
      channel.send(fetchChapter(newModel.storyIdRaw, 1).await())
      channel.close()
      channel
    }
    // Try being smart, and only download delta when chapters were added
    newModel.chapterCount > oldModel.chapterCount ->
      fetchChapterRange(Notifications.Kind.UPDATING, oldModel,
        oldModel.chapterCount + 1, newModel.chapterCount)
    // If nothing else, just re-download everything
    else -> fetchChapterRange(Notifications.Kind.UPDATING, oldModel)
  }
  val isWriting = writeChapters(newModel.storyIdRaw, channel).await()
  if (!isWriting) {
    // Revert model to old values
    Static.currentCtx.database.use { replaceOrThrow("stories", *oldModel.toKvPairs()) }
    Log.e(TAG, "Had to revert update to ${oldModel.storyIdRaw}")
    return@async2 false
  }
  Notifications.cancel(Notifications.Kind.UPDATING)
  return@async2 true
}
