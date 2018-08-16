package slak.fanfictionstories

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.support.v4.app.ActivityCompat
import android.util.Log
import android.view.View
import android.widget.EditText
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.jetbrains.anko.db.MapRowParser
import org.jetbrains.anko.db.dropTable
import org.jetbrains.anko.db.select
import org.jetbrains.anko.db.update
import slak.fanfictionstories.activities.MainActivity
import slak.fanfictionstories.fetchers.fetchAndWriteStory
import slak.fanfictionstories.fetchers.fetchStoryModel
import slak.fanfictionstories.utility.Empty
import slak.fanfictionstories.utility.Static
import java.io.File

private const val TAG = "FFStoriesDebug"

fun injectDebugButtons(activity: MainActivity) {
  activity.debugButtons.visibility = View.VISIBLE
  activity.debugButtons.setOnClickListener {
    AlertDialog.Builder(activity)
        .setItems(debugActions.keys.toTypedArray()) { _, which: Int ->
          debugActions.values.toTypedArray()[which]()
        }
        .setTitle(R.string.debug_dialog_title)
        .create()
        .show()
  }
}

/** Convenience method for debugging. (sometimes printf debugging is king) */
@Suppress("unused")
fun printAll(vararg stuff: Any?) = stuff.forEach { println(it) }

@SuppressLint("SdCardPath")
val debugActions = mapOf(
    "Regen stories table" to {
      Static.database.use { dropTable("stories", true) }
      Log.i(TAG, "DROPPED STORIES TABLE")
      Static.database.onCreate(Static.database.writableDatabase)
      Log.i(TAG, "REINITED STORIES TABLE")
    },
    "Wipe disk data" to {
      val deleted = File(getStorageDir(Static.currentCtx).get(), "storiesData")
          .deleteRecursively()
      if (deleted) Log.i(TAG, "SUCCESSFULLY DELETED")
      else Log.e(TAG, "DELETE FAILED")
    },
    "Wipe Settings" to { Prefs.useImmediate { it.clear() } },
    "Add 3 stories" to {
      launch(CommonPool) {
        fetchAndWriteStory(12129863L).await()
        fetchAndWriteStory(11953822L).await()
        fetchAndWriteStory(12295826L).await()
      }
    },
    "Test notification" to {
      AlertDialog.Builder(Static.currentCtx).setItems(
          Notifications.Kind.values().map { it.toString() }.toTypedArray()) { _, which ->
        val picked = Notifications.Kind.values()[which]
        Notifications.show(picked, Notifications.defaultIntent(), "TEST")
        launch(UI) {
          delay(2500)
          Notifications.cancel(picked)
        }
      }.create().show()
    },
    "Dump stories table to stdout" to {
      val r = Static.database.readableDatabase.select("stories")
          .parseList(object : MapRowParser<String> {
            override fun parseRow(columns: Map<String, Any?>): String {
              return columns.entries.joinToString(", ") {
                return@joinToString if (it.key != "summary" && it.key != "chapterTitles")
                  "(${it.key}) ${it.value.toString()}"
                else ""
              }
            }
          }).joinToString("\n")
      println(r)
    },
    "Import CSV" to {
      ActivityCompat.requestPermissions(Static.currentActivity!!, arrayOf(
          Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
      ), 123)
      Log.d(TAG, "LOADING library.csv")
      Log.d(TAG, "storyId,currentChapter,addedTime,lastReadTime,scrollAbsolute,scrollProgress")
      File("/sdcard/Download/library.csv")
          .readText()
          .split('\n')
          .drop(1)
          .map { it.split(',') }
          .filter { it[0].isNotEmpty() }
          .forEach {
            runBlocking {
              Log.d(TAG, it.joinToString(","))
              val model = fetchStoryModel(it[0].toLong()).await()
              if (model is Empty) return@runBlocking
              Static.database.upsertStory(model.get()).await()
              Log.d(TAG, "Inserted")
              Static.database.updateInStory(model.get().storyId,
                  "status" to "remote", "currentChapter" to it[1].toLong(),
                  "scrollProgress" to it[5].toDouble(), "scrollAbsolute" to it[4].toDouble(),
                  "lastReadTime" to it[3].toLong() / 1000, "addedTime" to it[2].toLong() / 1000)
              Static.database.setMarker(model.get().storyId, -6697984)
              Log.d(TAG, "Fixed")
            }
          }
    },
//     FIXME this has to be rewritten
//    "Purge untagged stories" to {
//
//      Static.database.writableDatabase.delete("stories", "markerColor = 0")
//    },
    "Run SQL" to {
      val editText = EditText(Static.currentCtx)
      val dialog = AlertDialog.Builder(Static.currentCtx)
          .setPositiveButton("run") { _, _ ->
            Static.database.writableDatabase.execSQL(editText.text.toString())
          }
          .create()
      dialog.setView(editText)
      dialog.show()
    },
    "Normalize progress" to {
      launch {
        Static.database.getStories().await().forEach {
          if (it.progressAsPercentage() > 75.0 || it.progress.scrollAbsolute == 999999.0)
            Static.database.updateInStory(it.storyId,
                "scrollAbsolute" to 999999.0, "scrollProgress" to 100.0)
        }
      }
    },
    "Import List" to {
      launch(CommonPool) {
        File("/sdcard/Download/storyId.list")
            .readText()
            .split('\n')
            .forEach { fetchAndWriteStory(it.toLong()).await() }
      }
    },
    "Tag all stories" to {
      Static.database.writableDatabase.update("markerColors",
          "markerColor" to -6697984).whereSimple("markerColor = ?", "0").exec()
    },
    "Download all stories" to {
      launch {
        Static.database.getStories().await().filter { it.status != StoryStatus.LOCAL }.forEach {
          fetchAndWriteStory(it.storyId).await()
        }
      }
    },
    "Load existing sqlite db" to {
      val db = Static.currentCtx.getDatabasePath(Static.database.databaseName)
      db.writeBytes(File("/sdcard/Download/FFStories").readBytes())
      Notifications.show(Notifications.Kind.DONE_UPDATING, Notifications.defaultIntent(), "Done")
    }
)
