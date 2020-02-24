package slak.fanfictionstories.data.fetchers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import slak.fanfictionstories.*
import slak.fanfictionstories.Notifications.Companion.defaultIntent
import slak.fanfictionstories.Notifications.Companion.readerIntent
import slak.fanfictionstories.data.Cache
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.ParserUtils.parseStoryModel
import slak.fanfictionstories.data.writeChapter
import slak.fanfictionstories.data.writeChapters
import slak.fanfictionstories.utility.Empty
import slak.fanfictionstories.utility.Optional
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.opt
import java.util.concurrent.TimeUnit

private const val TAG = "StoryFetcher"

/**
 * Download metadata and every chapter, then store them in the database and on disk.
 * @returns the model we just fetched, or an empty optional if that story doesn't exist
 */
suspend fun fetchAndWriteStory(storyId: StoryId): Optional<StoryModel> = withContext(Dispatchers.Default) {
  val model = fetchStoryModel(storyId).orElse { return@withContext Empty<StoryModel>() }
  model.status = StoryStatus.LOCAL
  Static.database.upsertModel(model)
  writeChapters(storyId, fetchChapterRange(Notifications.DOWNLOADING, model))
  Notifications.downloadedStory(model)
  Notifications.DOWNLOADING.cancel()
  return@withContext model.opt()
}

// It is unlikely that an update would invalidate the cache within 15 minutes
val storyCache = Cache<String>("StoryChapter", TimeUnit.MINUTES.toMillis(15))

/**
 * Fetch the html for a story's chapter.
 * @param storyId what story
 * @param chapter what chapter
 */
suspend fun fetchChapter(storyId: StoryId, chapter: Long): String {
  val cacheKey = "id$storyId-ch$chapter"
  storyCache.hit(cacheKey).ifPresent { return it }
  val html = patientlyFetchURL("https://www.fanfiction.net/s/$storyId/$chapter/") {
    Notifications.ERROR.show(defaultIntent(), R.string.error_fetching_story_data, storyId.toString())
  }
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
suspend fun fetchStoryModel(storyId: StoryId): Optional<StoryModel> {
  val chapterHtml = fetchChapter(storyId, 1)
  if (chapterHtml.contains("Story Not Found")) return Empty()
  return parseStoryModel(chapterHtml, storyId).opt()
}

/**
 * Get html of story chapters in given range.
 * @param notif what [Notifications] to show for each chapter that's fetched
 * @param from start index of chapters to fetch (1-indexed)
 * @param to end index of chapters to fetch (1-indexed). If -1, the end idx is the chapter count
 * @returns a [Flow] that supplies the story html text and the chapter number
 */
fun fetchChapterRange(
    notif: Notifications,
    model: StoryModel,
    from: Long = 1,
    to: Long = -1
): Flow<Pair<String, Long>> {
  val target = if (to != -1L) to else model.fragment.chapterCount
  return flow {
    for (chapterNr in from..target) {
      notif.show(
          readerIntent(model),
          R.string.fetching_chapter,
          chapterNr,
          model.fragment.chapterCount,
          chapterNr * 100F / model.fragment.chapterCount,
          model.title
      )
      val chapterHtml = fetchChapter(model.storyId, chapterNr)
      emit(extractChapterText(Jsoup.parse(chapterHtml)) to chapterNr)
    }
  }
}

/**
 * Tries to update story metadata and chapters. Only works on local stories.
 * @param oldModel the EXISTING [StoryModel], fetched from db
 * @returns if the update was done, the updated model, otherwise [Empty]
 */
suspend fun updateStory(oldModel: StoryModel): Optional<StoryModel> {
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

  withContext(Dispatchers.Default) {
    // Special case when there is only one chapter
    if (newModel.fragment.chapterCount == 1L) {
      Notifications.UPDATING.show(defaultIntent(), R.string.fetching_chapter, 1, 1, 0F, newModel.title)
      writeChapter(newModel.storyId, 1, fetchChapter(newModel.storyId, 1))
    } else {
      val chapterFlow = if (newModel.fragment.chapterCount > oldModel.fragment.chapterCount) {
        // Try being smart, and only download delta when chapters were added
        fetchChapterRange(Notifications.UPDATING, oldModel,
            oldModel.fragment.chapterCount + 1, newModel.fragment.chapterCount)
      } else {
        // Download everything otherwise
        fetchChapterRange(Notifications.UPDATING, oldModel)
      }
      writeChapters(newModel.storyId, chapterFlow)
    }
  }
  Notifications.UPDATING.cancel()
  return newModel.opt()
}

val imageCache = Cache<ByteArray>("Images", TimeUnit.DAYS.toMillis(3))

suspend fun fetchImage(imageUrl: String): Bitmap {
  imageCache.hit(imageUrl).ifPresent { return BitmapFactory.decodeByteArray(it, 0, it.size) }
  val bytes = patientlyFetchURLBytes("https:$imageUrl") {
    Notifications.ERROR.show(defaultIntent(), R.string.error_fetching_image_data)
  }
  imageCache.update(imageUrl, bytes)
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
