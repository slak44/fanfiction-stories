package slak.fanfictionstories.fetchers

import android.support.design.widget.Snackbar
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.contentView
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.FetcherUtils.TAG
import slak.fanfictionstories.fetchers.FetcherUtils.parseStoryModel
import slak.fanfictionstories.utility.*
import slak.fanfictionstories.utility.Notifications.defaultIntent
import slak.fanfictionstories.utility.Notifications.readerIntent
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Download metadata and every chapter, then store them in the database and on disk.
 * @returns the model we just fetched, or an empty optional if the story already exists
 */
fun fetchAndWriteStory(storyId: Long): Deferred<Optional<StoryModel>> = async2(CommonPool) {
  val model = fetchStoryModel(storyId).await().orElse { return@async2 Optional.empty<StoryModel>() }
  model.status = StoryStatus.LOCAL
  Static.database.upsertStory(model)
  val isWriting: Boolean =
      writeChapters(storyId, fetchChapterRange(Notifications.Kind.DOWNLOADING, model)).await()
  if (isWriting) {
    Notifications.downloadedStory(model.title, model.storyId)
    Notifications.cancel(Notifications.Kind.DOWNLOADING)
  } else {
    // FIXME show something saying we failed
    Static.database.updateInStory(storyId, "status" to "remote")
  }
  return@async2 model.opt()
}

// It is unlikely that an update would invalidate the cache within 15 minutes
val storyCache = Cache<String>("StoryChapter", TimeUnit.MINUTES.toMillis(15))

/**
 * Fetch the html for a story's chapter.
 * @param storyId what story
 * @param chapter what chapter
 */
fun fetchChapter(storyId: Long, chapter: Long): Deferred<String> = async2(CommonPool) {
  val cacheKey = "id$storyId-ch$chapter"
  storyCache.hit(cacheKey).ifPresent2 { return@async2 it }
  val html = patientlyFetchURL("https://www.fanfiction.net/s/$storyId/$chapter/") {
    Notifications.show(Notifications.Kind.ERROR, defaultIntent(),
        R.string.error_fetching_story_data, storyId.toString())
    Log.e(TAG, "fetchChapter", it)
  }.await()
  storyCache.update(cacheKey, html)
  return@async2 html
}

fun extractChapterText(doc: Document): String {
  return doc.getElementById("storytext")?.html()
      ?: throw IllegalArgumentException("No story text in given document")
}

fun fetchStoryModel(storyId: Long): Deferred<Optional<StoryModel>> = async2(CommonPool) {
  val chapterHtml = fetchChapter(storyId, 1).await()
  if (chapterHtml.contains("Story Not Found")) return@async2 Optional.empty<StoryModel>()
  return@async2 parseStoryModel(chapterHtml, storyId).opt()
}

/**
 * Get html of story chapters in given range.
 * @param kind what [Notifications.Kind] to show for each chapter that's fetched
 * @param from start index of chapters to fetch (1-indexed)
 * @param to end index of chapters to fetch (1-indexed). If -1, the end idx is the chapter count
 * @returns a [Channel] that supplies the html text
 */
fun fetchChapterRange(kind: Notifications.Kind, model: StoryModel,
                      from: Long = 1, to: Long = -1): Channel<String> {
  val target = if (to != -1L) to else model.fragment.chapterCount
  // The buffer size is completely arbitrary
  val channel = Channel<String>(10)
  launch(CommonPool) {
    for (chapterNr in from..target) {
      Notifications.show(kind, readerIntent(model.storyId),
          R.string.fetching_chapter, chapterNr, model.fragment.chapterCount,
          chapterNr * 100F / model.fragment.chapterCount, model.title)
      val chapterHtml = fetchChapter(model.storyId, chapterNr).await()
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
  Notifications.show(
      Notifications.Kind.UPDATING, defaultIntent(), R.string.checking_story, oldModel.title)
  val newModel = fetchStoryModel(oldModel.storyId).await().orElse { return@async2 false }
  // Skip non-locals from updates, since the operation does not make sense for them
  if (oldModel.status != StoryStatus.LOCAL) return@async2 false
  // Stories can't get un-updated
  if (oldModel.fragment.updateTime != 0L && newModel.fragment.updateTime == 0L)
    throw IllegalStateException("The old model had updates; the new one doesn't")
  // Story has never received an update, our job here is done
  if (newModel.fragment.updateTime == 0L) return@async2 false
  // Update time is identical, nothing to do again
  if (oldModel.fragment.updateTime == newModel.fragment.updateTime) return@async2 false

  newModel.progress = if (oldModel.progress.currentChapter > newModel.fragment.chapterCount) {
    StoryProgress(currentChapter = newModel.fragment.chapterCount)
  } else {
    oldModel.progress
  }

  Static.database.replaceStory(newModel)

  val channel: Channel<String> = when {
  // Special case when there is only one chapter
    newModel.fragment.chapterCount == 1L -> {
      Notifications.show(Notifications.Kind.UPDATING,
          defaultIntent(), R.string.fetching_chapter, 1, 1, 0F, newModel.title)
      val channel = Channel<String>(1)
      channel.send(fetchChapter(newModel.storyId, 1).await())
      channel.close()
      channel
    }
    // Try being smart, and only download delta when chapters were added
    newModel.fragment.chapterCount > oldModel.fragment.chapterCount ->
      fetchChapterRange(Notifications.Kind.UPDATING, oldModel,
        oldModel.fragment.chapterCount + 1, newModel.fragment.chapterCount)
    // If nothing else, just re-download everything
    else -> fetchChapterRange(Notifications.Kind.UPDATING, oldModel)
  }
  val isWriting = writeChapters(newModel.storyId, channel).await()
  if (!isWriting) {
    // Revert model to old values
    Static.database.replaceStory(oldModel)
    Log.e(TAG, "Had to revert update to ${oldModel.storyId}")
    return@async2 false
  }
  Notifications.cancel(Notifications.Kind.UPDATING)
  return@async2 true
}
