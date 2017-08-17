package slak.fanfictionstories

import android.app.AlertDialog
import android.content.Context
import android.os.Environment
import java.io.File
import java.util.*

fun haveExternalStorage() = Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()

fun getStorageDir(ctx: Context): Optional<File> {
  if (!haveExternalStorage()) {
    AlertDialog.Builder(ctx)
        .setTitle(R.string.ext_store_unavailable)
        .setMessage(R.string.ext_store_unavailable_tip)
        .setPositiveButton(R.string.got_it, { dialogInterface, _ ->
          // User acknowledged we have no storage
          dialogInterface.dismiss()
        }).create()
    return Optional.empty()
  }
  return Optional.of(ctx.getExternalFilesDir(null))
}

