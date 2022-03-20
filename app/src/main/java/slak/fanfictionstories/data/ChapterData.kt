package slak.fanfictionstories.data

import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryId
import slak.fanfictionstories.utility.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

private const val TAG = "ChapterData"

/** @returns whether or not we have external storage available */
private fun haveExternalStorage() = Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()

/** @returns a [File] representing the external storage dir, or [Empty] if it's unavailable */
private fun getStorageDir(): Optional<File> =
    if (haveExternalStorage()) Static.currentCtx.getExternalFilesDir(null).opt() else Empty()

/** @returns a [File] representing the stories dir, or [Empty] if it's unavailable */
private fun storyDir(storyId: StoryId): File {
  val storage = getStorageDir().orElse {
    errorDialog(R.string.ext_store_unavailable, R.string.ext_store_unavailable_tip)
    val err = IllegalStateException("Missing external storage?")
    Log.e(TAG, "haveExternalStorage() == false", err)
    throw err
  }
  val storyDir = File(storage, "storiesData").resolve(storyId.toString())
  if (!storyDir.exists()) {
    val madeDirs = storyDir.mkdirs()
    if (!madeDirs) {
      errorDialog(str(R.string.failed_making_dirs),
          str(R.string.failed_making_dirs_tip, storyDir.absolutePath))
      val err = FileSystemException(storyDir, null, "Can't create dirs")
      Log.e(TAG, "file: ${storyDir.absolutePath}", err)
      throw err
    }
  }
  return storyDir
}

/** @returns the [File] of a certain story's chapter */
private fun chapterFile(storyDir: File, chapter: Long) = storyDir.resolve("$chapter.html.deflated")

/** @returns how many chapters of a story were downloaded locally */
fun chapterCount(storyId: StoryId) = storyDir(storyId).list()!!.size

/**
 * Gets chapter text from disk.
 * @returns the text if successful, or [Empty] if not
 */
fun readChapter(storyId: StoryId, chapter: Long): Optional<String> {
  val chapterFile = chapterFile(storyDir(storyId), chapter)
  if (!chapterFile.exists()) return Empty()
  return InflaterInputStream(chapterFile.inputStream()).bufferedReader().readText().opt()
}

/** Write a (compressed) chapter to disk. */
private fun writeChapterImpl(storyDir: File, chapter: Long, chapterText: String) {
  DeflaterOutputStream(FileOutputStream(chapterFile(storyDir, chapter), false)).use {
    it.write(chapterText.toByteArray())
  }
}

/** Write a (compressed) chapter to disk. */
suspend fun writeChapter(storyId: StoryId, chapter: Long, chapterText: String) =
    withContext(Dispatchers.IO) { writeChapterImpl(storyDir(storyId), chapter, chapterText) }

/**
 * Writes received chapter data to disk asynchronously.
 *
 * [chapters] contains the story html and its respective chapter number.
 */
suspend fun writeChapters(storyId: StoryId, chapters: Flow<Pair<String, Long>>) = withContext(Dispatchers.IO) {
  val storyDir = storyDir(storyId)
  chapters.collect { (chapter, chapterNr) ->
    writeChapterImpl(storyDir, chapterNr, chapter)
  }
}

/** Deletes the chapter data directory for a story. */
suspend fun deleteStory(storyId: StoryId) = withContext(Dispatchers.IO) {
  val targetDir = storyDir(storyId)
  if (!targetDir.exists()) {
    Log.w(TAG, "Tried to delete a story that does not exist")
    // Our job here is done ¯\_(ツ)_/¯
    return@withContext
  }
  val wasDeleted = targetDir.deleteRecursively()
  if (!wasDeleted) {
    Log.e(TAG, "Failed to delete story dir")
    return@withContext
  }
}
