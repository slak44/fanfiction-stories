package slak.fanfictionstories

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.EditText
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import org.jetbrains.anko.db.MapRowParser
import org.jetbrains.anko.db.dropTable
import org.jetbrains.anko.db.select
import org.jetbrains.anko.db.update
import slak.fanfictionstories.Notifications.Companion.defaultIntent
import slak.fanfictionstories.activities.MainActivity
import slak.fanfictionstories.data.Prefs
import slak.fanfictionstories.data.chapterCount
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.fetchAndWriteStory
import slak.fanfictionstories.data.fetchers.fetchStoryModel
import slak.fanfictionstories.databinding.ActivityMainBinding
import slak.fanfictionstories.utility.Empty
import slak.fanfictionstories.utility.Static
import java.io.File
import java.util.zip.DeflaterOutputStream

private const val TAG = "FFStoriesDebug"

fun injectDebugButtons(activity: MainActivity, binding: ActivityMainBinding) {
  binding.debugButtons.visibility = View.VISIBLE
  binding.debugButtons.setOnClickListener {
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
      val deleted = File(Static.currentCtx.getExternalFilesDir(null), "storiesData")
          .deleteRecursively()
      if (deleted) Log.i(TAG, "SUCCESSFULLY DELETED")
      else Log.e(TAG, "DELETE FAILED")
    },
    "Wipe Settings" to { Prefs.useImmediate { it.clear() } },
    "Add 3 stories" to {
      GlobalScope.launch(Dispatchers.Default) {
        fetchAndWriteStory(12129863L)
        fetchAndWriteStory(11953822L)
        fetchAndWriteStory(12295826L)
      }
    },
    "Test notification" to {
      AlertDialog.Builder(Static.currentCtx).setItems(
          Notifications.values().map { it.toString() }.toTypedArray()) { _, which ->
        val picked = Notifications.values()[which]
        picked.show(defaultIntent(), "TEST")
        GlobalScope.launch(Main) {
          delay(2500)
          picked.cancel()
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
          .asSequence()
          .drop(1)
          .map { it.split(',') }
          .filter { it[0].isNotEmpty() }
          .toList()
          .forEach {
            runBlocking {
              Log.d(TAG, it.joinToString(","))
              val model = fetchStoryModel(it[0].toLong())
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
      GlobalScope.launch {
        Static.database.getStories().await().forEach {
          if (it.progressAsPercentage() > 75.0 || it.progress.scrollAbsolute == 999999.0)
            Static.database.updateInStory(it.storyId,
                "scrollAbsolute" to 999999.0, "scrollProgress" to 100.0)
        }
      }
    },
    "Import List" to {
      GlobalScope.launch(Dispatchers.Default) {
        File("/sdcard/Download/storyId.list")
            .readText()
            .split('\n')
            .forEach { fetchAndWriteStory(it.toLong()) }
      }
    },
    "Tag all stories" to {
      Static.database.writableDatabase.update("markerColors",
          "markerColor" to -6697984).whereSimple("markerColor = ?", "0").exec()
    },
    "Download all stories" to {
      GlobalScope.launch {
        Static.database.getStories().await().filter { it.status != StoryStatus.LOCAL }.forEach {
          fetchAndWriteStory(it.storyId)
        }
      }
    },
    "Load existing sqlite db" to {
      val db = Static.currentCtx.getDatabasePath(Static.database.databaseName)
      db.writeBytes(File("/sdcard/Download/FFStories").readBytes())
      Notifications.DONE_UPDATING.show(defaultIntent(), "Done")
    },
    "Delete legacy notification channel" to {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Static.notifManager.deleteNotificationChannel("download_channel")
      }
    },
    "Deflate all chapter text data" to {
      var initialByteCount = 0L
      var finalByteCount = 0L
      File(Static.currentCtx.getExternalFilesDir(null), "storiesData").listFiles()!!.forEach { story ->
        story.listFiles()!!.forEach { chapter ->
          initialByteCount += chapter.length()
          val outFile = File("${story.absolutePath}/${chapter.name.split('.')[0]}.html.deflated")
          val stream = DeflaterOutputStream(outFile.outputStream())
          stream.use {
            it.write(chapter.readBytes())
            it.flush()
          }
          finalByteCount += outFile.length()
          chapter.delete()
        }
      }
      println(initialByteCount)
      println(finalByteCount)
      println("${finalByteCount * 100 / initialByteCount}%")
    },
    "Fix chapter numbers" to {
      File(Static.currentCtx.getExternalFilesDir(null), "storiesData").listFiles()!!.forEach { story ->
        story.listFiles()!!.sortedBy { it.name.split('.')[0].toInt() }.reversed().forEach { chapter ->
          chapter.renameTo(File("${story.absolutePath}/${chapter.name.split('.')[0].toInt() + 1}.html.deflated"))
        }
      }
    },
    "Mark all downloaded remotes as local" to {
      GlobalScope.launch {
        for (story in Static.database.getStories().await()) {
          if (chapterCount(story.storyId) == story.fragment.chapterCount.toInt()) {
            Static.database.updateInStory(story.storyId, "status" to "local")
            println(story.storyId)
          }
        }
        println("done")
      }
    },
    "Search for light green marked stories that are not local" to {
      GlobalScope.launch {
        val stories = Static.database.getStories().await()
        val markers = Static.database.getMarkers(stories.map(StoryModel::storyId)).await()
        for ((storyId, markerColor) in markers) {
          if (markerColor == -6697984L && stories.first { it.storyId == storyId }.status != StoryStatus.LOCAL) {
            println("GREEN NOT UPDATED: $storyId")
            fetchAndWriteStory(storyId)
          }
        }
        println("done")
      }
    },
    "Fetch image URLs for old stories" to {
      GlobalScope.launch {
        val stories = Static.database
            .getStories()
            .await()
            .asSequence()
            .filter { it.imageUrl.isBlank() }
            .map { it.storyId }
        for (storyId in stories) {
          val newModel = fetchStoryModel(storyId)
          if (newModel is Empty) {
            println("skipping $storyId")
            continue
          }
          val url = newModel.get().imageUrl
          Static.database.updateInStory(storyId, "imageUrl" to url)
          println("dealt with $storyId")
        }
        Notifications.DONE_UPDATING.show(defaultIntent(), "Done")
      }
    },
    "Repair database from downloaded stories" to {
      GlobalScope.launch {
        val stories = Static.database.getLocalStories().await().map { it.storyId }
        val onLocal = File(Static.currentCtx.getExternalFilesDir(null), "storiesData").list()!!.map { it.toLong() }
        val delta = onLocal - stories
        if (delta.isEmpty()) {
          Log.e(TAG, "none found")
        }
        val markers = Static.database.getMarkers(delta).await()
        for (storyId in delta) {
          val model = fetchStoryModel(storyId)
          if (model is Empty) {
            Log.e(TAG, "Story not found: $storyId")
            continue
          }
          model.get().status = StoryStatus.LOCAL
          Static.database.upsertStory(model.get()).await()
          if (markers[storyId] == 0L) {
            Static.database.setMarker(storyId, -6697984)
          }
        }
      }
    },
    "Disable all stories of marker" to {
      Static.database.use {
        execSQL("update stories set enabled = 0 where storyId in " +
            "(select storyId from colorMarkers where markerColor = -13388315)")
      }
    },
)
