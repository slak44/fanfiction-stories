package slak.fanfictionstories

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch
import java.io.File
import java.util.*

fun haveExternalStorage() = Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()

/**
 * @returns a File representing the external storage dir, or Optional.empty() if it's unavailable
 */
fun getStorageDir(ctx: Context): Optional<File> =
    if (haveExternalStorage()) Optional.of(ctx.getExternalFilesDir(null)) else Optional.empty()

/**
 * @returns a File representing the stories dir, or Optional.empty() if it's unavailable
 */
fun storyDir(ctx: Context, storyId: Long): Optional<File> {
  val storage = getStorageDir(ctx)
  if (!storage.isPresent) {
    Log.e("StoryWriter#storyDir", "no ext storage")
    errorDialog(ctx, R.string.ext_store_unavailable, R.string.ext_store_unavailable_tip)
    return Optional.empty()
  }
  val storiesDir = File(storage.get(), "storiesData")
  return Optional.of(File(storiesDir, storyId.toString()))
}

/**
 * Writes received story data to disk asynchronously
 * Note that this function only suspends if it's actually writing; it immediately returns on failure
 * @returns true if we started writing data to disk, false otherwise
 */
fun writeStory(ctx: Context, storyId: Long,
               chapters: Channel<String>): Deferred<Boolean> = async(CommonPool) {
  val targetDir = storyDir(ctx, storyId)
  if (!targetDir.isPresent) return@async false
  if (targetDir.get().exists()) {
    // FIXME maybe ask the user if he wants to overwrite or legitimize this by getting the metadata
    Log.e("StoryWriter", "targetDir exists")
    errorDialog(ctx, R.string.storyid_already_exists, R.string.storyid_already_exists_tip)
    return@async false
  }
  val madeDirs = targetDir.get().mkdirs()
  if (!madeDirs) {
    Log.e("StoryWriter", "can't make dirs")
    errorDialog(ctx,
        ctx.resources.getString(R.string.failed_making_dirs),
        ctx.resources.getString(R.string.failed_making_dirs_tip, targetDir.get().absolutePath))
    return@async false
  }
  innerAsync@async(CommonPool) {
    var idx = 1
    chapters.consumeEach { chapterText: String ->
      File(targetDir.get(), "$idx.html").printWriter().use { it.print(chapterText) }
      idx++
    }
    return@innerAsync true
  }.await()
}
