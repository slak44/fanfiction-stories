package slak.fanfictionstories

import android.os.Environment
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.consumeEachIndexed
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.utility.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.DeflaterOutputStream

/** @returns whether or not we have external storage available */
fun haveExternalStorage() = Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()

/** @returns a [File] representing the external storage dir, or [Empty] if it's unavailable */
fun getStorageDir(): Optional<File> =
    if (haveExternalStorage()) Static.currentCtx.getExternalFilesDir(null).opt() else Empty()

/** @returns a [File] representing the stories dir, or [Empty] if it's unavailable */
fun storyDir(storyId: Long): Optional<File> {
  val storage = getStorageDir().orElse {
    Log.e("StoryWriter#storyDir", "no ext storage")
    errorDialog(R.string.ext_store_unavailable, R.string.ext_store_unavailable_tip)
    return Empty()
  }
  val storiesDir = File(storage, "storiesData")
  return File(storiesDir, storyId.toString()).opt()
}

/**
 * Writes received story data to disk asynchronously
 * Note that this function only suspends if it's actually writing; it immediately returns on failure
 * @returns true if we started writing data to disk, false otherwise
 */
fun writeChapters(storyId: StoryId,
                  chapters: Channel<String>): Deferred<Boolean> = async2(CommonPool) {
  val targetDir = storyDir(storyId).orElse { return@async2 false }
  if (targetDir.exists()) {
    Log.i("StoryWriter#writeChapters", "Target dir already exists")
  } else {
    val madeDirs = targetDir.mkdirs()
    if (!madeDirs) {
      Log.e("StoryWriter#writeChapters", "Can't make dirs")
      errorDialog(str(R.string.failed_making_dirs),
          str(R.string.failed_making_dirs_tip, targetDir.absolutePath))
      return@async2 false
    }
  }
  innerAsync@ async2(CommonPool) {
    chapters.consumeEachIndexed { chapterText ->
      DeflaterOutputStream(
          FileOutputStream(File(targetDir, "${chapterText.index + 1}.html.deflated"), false)).use {
        it.write(chapterText.value.toByteArray())
      }
    }
    return@innerAsync true
  }.await()
}

/** Deletes the story chapter data directory. */
fun deleteLocalStory(storyId: StoryId) = launch(CommonPool) {
  val targetDir = storyDir(storyId).orElseThrow(IllegalStateException("Storage missing"))
  if (!targetDir.exists()) {
    Log.w("StoryWriter#deleteLocalStory", "Tried to delete a story that does not exist")
    // Our job here is done ¯\_(ツ)_/¯
    return@launch
  }
  val deleted = targetDir.deleteRecursively()
  if (!deleted) {
    Log.e("StoryWriter#deleteLocalStory", "Failed to delete story dir")
    return@launch
  }
}
