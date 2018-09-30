package slak.fanfictionstories.data.fetchers

import android.util.Log
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import slak.fanfictionstories.*
import slak.fanfictionstories.Notifications.Companion.defaultIntent
import slak.fanfictionstories.Notifications.Companion.readerIntent
import slak.fanfictionstories.data.Cache
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.ParserUtils.parseStoryModel
import slak.fanfictionstories.data.writeChapters
import slak.fanfictionstories.utility.*
import java.util.concurrent.TimeUnit

private const val TAG = "StoryFetcher"

/**
 * Download metadata and every chapter, then store them in the database and on disk.
 * @returns the model we just fetched, or an empty optional if that story doesn't exist
 */
suspend fun CoroutineScope.fetchAndWriteStory(storyId: StoryId): Optional<StoryModel> {
  val model = fetchStoryModel(storyId).orElse { return Empty() }
  model.status = StoryStatus.LOCAL
  val existingModel = Static.database.storyById(storyId).await().orNull()
  if (existingModel != null) {
    model.addedTime = existingModel.addedTime
    model.lastReadTime = existingModel.lastReadTime
    model.progress = existingModel.progress
  }
  Static.database.upsertStory(model).await()
  writeChapters(storyId, fetchChapterRange(Notifications.DOWNLOADING, model))
  Notifications.downloadedStory(model)
  Notifications.DOWNLOADING.cancel()
  return model.opt()
}

// It is unlikely that an update would invalidate the cache within 15 minutes
val storyCache = Cache<String>("StoryChapter", TimeUnit.MINUTES.toMillis(15))

/**
 * Fetch the html for a story's chapter.
 * @param storyId what story
 * @param chapter what chapter
 */
suspend fun CoroutineScope.fetchChapter(storyId: StoryId, chapter: Long): String {
  val cacheKey = "id$storyId-ch$chapter"
  storyCache.hit(cacheKey).ifPresent { return it }
  val html = patientlyFetchURL("https://www.fanfiction.net/s/$storyId/$chapter/") {
    Notifications.ERROR.show(defaultIntent(), R.string.error_fetching_story_data, storyId.toString())
  }.await()
  storyCache.update(cacheKey, html)
  return html
}

/**
 * Get the story chapter's html as a [String].
 * @param doc the document to search for the data
 * @throws IllegalArgumentException if the document does not contain story chapter data
 */
fun extractChapterText(doc: Document): String {
  return doc.getElementById("storytext")?.html()
      ?: throw IllegalArgumentException("No story text in given document")
}

/**
 * Just download metadata for the [storyId].
 * @returns the [StoryModel] if it was there, or [Empty] if the story was not found
 */
suspend fun CoroutineScope.fetchStoryModel(storyId: StoryId): Optional<StoryModel> {
  val chapterHtml = fetchChapter(storyId, 1)
  if (chapterHtml.contains("Story Not Found")) return Empty()
  return parseStoryModel(chapterHtml, storyId).opt()
}

/**
 * Get html of story chapters in given range.
 * @param notif what [Notifications] to show for each chapter that's fetched
 * @param from start index of chapters to fetch (1-indexed)
 * @param to end index of chapters to fetch (1-indexed). If -1, the end idx is the chapter count
 * @returns a [Channel] that supplies the html text
 */
fun CoroutineScope.fetchChapterRange(notif: Notifications, model: StoryModel,
                                     from: Long = 1, to: Long = -1): ReceiveChannel<String> {
  val target = if (to != -1L) to else model.fragment.chapterCount
  // The buffer size is completely arbitrary
  val channel = Channel<String>(10)
  launch(Dispatchers.Default) {
    for (chapterNr in from..target) {
      notif.show(
          readerIntent(model),
          R.string.fetching_chapter,
          chapterNr,
          model.fragment.chapterCount,
          chapterNr * 100F / model.fragment.chapterCount,
          model.title)
      val chapterHtml = fetchChapter(model.storyId, chapterNr)
      channel.send(extractChapterText(Jsoup.parse(chapterHtml)))
    }
    channel.close()
  }
  return channel
}

/**
 * Tries to update story metadata and chapters. Only works on local stories.
 * @param oldModel the EXISTING [StoryModel], fetched from db
 * @returns if the update was done, the updated model, otherwise [Empty]
 */
suspend fun CoroutineScope.updateStory(oldModel: StoryModel): Optional<StoryModel> {
  val newModel = fetchStoryModel(oldModel.storyId).orElse { return Empty() }
  Log.v(TAG, "Attempting update from\n   oldModel: $oldModel\nto newModel: $newModel")
  // Skip non-locals from updates, since the operation does not make sense for them
  if (oldModel.status != StoryStatus.LOCAL) return Empty()
  // Stories can't get un-updated
  if (oldModel.fragment.updateTime != 0L && newModel.fragment.updateTime == 0L)
    throw IllegalStateException("The old model had updates; the new one doesn't")
  // Story has never received an update, our job here is done
  if (newModel.fragment.updateTime == 0L) return Empty()
  // Update time is identical, nothing to do again
  if (oldModel.fragment.updateTime == newModel.fragment.updateTime) return Empty()

  newModel.progress = if (oldModel.progress.currentChapter > newModel.fragment.chapterCount) {
    Log.w(TAG, "Had to discard progress for id ${oldModel.storyId} because oldModel current chapter value" +
        "exceeds newModel chapter count")
    StoryProgress(currentChapter = newModel.fragment.chapterCount)
  } else {
    oldModel.progress
  }
  newModel.addedTime = oldModel.addedTime
  newModel.lastReadTime = oldModel.lastReadTime

  newModel.status = StoryStatus.LOCAL

  Log.v(TAG, "Replacing ${oldModel.storyId} in database")
  Static.database.upsertStory(newModel).await()

  val channel: ReceiveChannel<String> = when {
  // Special case when there is only one chapter
    newModel.fragment.chapterCount == 1L -> {
      Notifications.UPDATING.show(defaultIntent(), R.string.fetching_chapter, 1, 1, 0F, newModel.title)
      val channel = Channel<String>(1)
      channel.send(fetchChapter(newModel.storyId, 1))
      channel.close()
      channel
    }
  // Try being smart, and only download delta when chapters were added
    newModel.fragment.chapterCount > oldModel.fragment.chapterCount ->
      fetchChapterRange(Notifications.UPDATING, oldModel,
          oldModel.fragment.chapterCount + 1, newModel.fragment.chapterCount)
  // If nothing else, just re-download everything
    else -> fetchChapterRange(Notifications.UPDATING, oldModel)
  }
  writeChapters(newModel.storyId, channel)
  Notifications.UPDATING.cancel()
  return newModel.opt()
}
