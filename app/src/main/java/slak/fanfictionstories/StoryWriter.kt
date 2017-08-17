package slak.fanfictionstories

import android.app.AlertDialog
import android.content.Context
import android.os.Environment
import android.support.annotation.StringRes
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch
import java.io.File
import java.util.*

fun errorDialog(ctx: Context, @StringRes title: Int, @StringRes msg: Int) {
  errorDialog(ctx, ctx.resources.getString(title), ctx.resources.getString(msg))
}

fun errorDialog(ctx: Context, title: String, msg: String) {
  AlertDialog.Builder(ctx)
      .setTitle(title)
      .setMessage(msg)
      .setPositiveButton(R.string.got_it, { dialogInterface, _ ->
        // User acknowledged error
        dialogInterface.dismiss()
      }).create()
}

fun haveExternalStorage() = Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()

fun getStorageDir(ctx: Context): Optional<File> =
    if (haveExternalStorage()) Optional.of(ctx.getExternalFilesDir(null)) else Optional.empty()

fun writeStory(ctx: Context, storyid: Long, chapters: Channel<String>) = async(CommonPool) {
  val storage = getStorageDir(ctx)
  if (!storage.isPresent) {
    errorDialog(ctx, R.string.ext_store_unavailable, R.string.ext_store_unavailable_tip)
    return@async
  }
  val storiesDir = File(storage.get(), "storiesData")
  val targetDir = File(storiesDir, storyid.toString())
  if (targetDir.exists()) {
    // FIXME maybe ask the user if he wants to overwrite or legitimize this by getting the metadata
    errorDialog(ctx, R.string.storyid_already_exists, R.string.storyid_already_exists_tip)
    return@async
  }
  val madeDirs = targetDir.mkdirs()
  if (!madeDirs) {
    launch(UI) {
      errorDialog(ctx,
          ctx.resources.getString(R.string.failed_making_dirs),
          ctx.resources.getString(R.string.failed_making_dirs_tip, targetDir.absolutePath))
    }
    return@async
  }
  var idx: Int = 1
  chapters.consumeEach { chapterText: String ->
    File(targetDir, "$idx.html").printWriter().use { it.print(chapterText) }
    idx++
  }
}
