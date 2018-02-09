package slak.fanfictionstories

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.utility.*
import java.io.File
import java.util.*

fun haveExternalStorage() = Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()

/**
 * @returns a File representing the external storage dir, or Optional.empty() if it's unavailable
 */
fun getStorageDir(ctx: Context): Optional<File> =
    if (haveExternalStorage()) ctx.getExternalFilesDir(null).opt() else Optional.empty()

/**
 * @returns a File representing the stories dir, or Optional.empty() if it's unavailable
 */
fun storyDir(ctx: Context, storyId: Long): Optional<File> {
  val storage = getStorageDir(ctx).orElse {
    Log.e("StoryWriter#storyDir", "no ext storage")
    errorDialog(R.string.ext_store_unavailable, R.string.ext_store_unavailable_tip)
    return Optional.empty()
  }
  val storiesDir = File(storage, "storiesData")
  return File(storiesDir, storyId.toString()).opt()
}

/**
 * Writes received story data to disk asynchronously
 * Note that this function only suspends if it's actually writing; it immediately returns on failure
 * @returns true if we started writing data to disk, false otherwise
 */
fun writeChapters(ctx: Context, storyId: Long,
                  chapters: Channel<String>): Deferred<Boolean> = async2(CommonPool) {
  val targetDir = storyDir(ctx, storyId).orElse { return@async2 false }
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
    var idx = 1
    chapters.consumeEach { chapterText: String ->
      File(targetDir, "$idx.html").overwritePrintWriter().use { it.print(chapterText) }
      idx++
    }
    return@innerAsync true
  }.await()
}

/**
 * Deletes the story chapter data directory
 */
fun deleteLocalStory(ctx: Context, storyId: Long) = launch(CommonPool) {
  val targetDir = storyDir(ctx, storyId).orElseThrow(IllegalStateException("Storage missing"))
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
