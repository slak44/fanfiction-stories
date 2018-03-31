package slak.fanfictionstories.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.Parcelable
import android.support.v4.app.ActivityCompat
import android.text.Html
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.jetbrains.anko.db.*
import org.jetbrains.anko.startActivity
import slak.fanfictionstories.BuildConfig
import slak.fanfictionstories.R
import slak.fanfictionstories.fetchers.*
import slak.fanfictionstories.getStorageDir
import slak.fanfictionstories.utility.*
import slak.fanfictionstories.utility.Notifications.defaultIntent
import java.io.File

class MainActivity : ActivityWithStatic() {
  companion object {
    private const val TAG = "MainActivity"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(toolbar)

    storyListButton.setOnClickListener { startActivity<StoryListActivity>() }
    storyBrowseButton.setOnClickListener { startActivity<SelectCategoryActivity>() }

    // Debug menu
    if (BuildConfig.DEBUG) hookDebug()
  }

  @SuppressLint("SdCardPath")
  private fun hookDebug() {
    debugButtons.visibility = View.VISIBLE
    mapOf(
        "Regen stories table" to {
          database.use { dropTable("stories", true) }
          Log.i(TAG, "DROPPED STORIES TABLE")
          database.onCreate(database.writableDatabase)
          Log.i(TAG, "REINITED STORIES TABLE")
        },
        "Wipe disk data" to {
          val deleted = File(getStorageDir(this@MainActivity).get(), "storiesData")
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
          AlertDialog.Builder(this).setItems(
              Notifications.Kind.values().map { it.toString() }.toTypedArray(), { _, which ->
            val picked = Notifications.Kind.values()[which]
            Notifications.show(picked, defaultIntent(), "TEST")
            launch(UI) {
              delay(2500)
              Notifications.cancel(picked)
            }
          }).create().show()
        },
        "Dump stories table to stdout" to {
          val r = database.readableDatabase.select("stories")
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
          ActivityCompat.requestPermissions(this, arrayOf(
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
                  database.upsertStory(model.get()).await()
                  Log.d(TAG, "Inserted")
                  database.updateInStory(model.get().storyId,
                      "status" to "remote", "currentChapter" to it[1].toLong(),
                      "scrollProgress" to it[5].toDouble(), "scrollAbsolute" to it[4].toDouble(),
                      "lastReadTime" to it[3].toLong() / 1000, "addedTime" to it[2].toLong() / 1000,
                      "markerColor" to -6697984)
                  Log.d(TAG, "Fixed")
                }
              }
        },
        "Purge untagged stories" to {
          database.writableDatabase.delete("stories", "markerColor = 0")
        },
        "Run SQL" to {
          val editText = EditText(this)
          val dialog = AlertDialog.Builder(this)
              .setPositiveButton("run", { _, _ ->
                database.writableDatabase.execSQL(editText.text.toString())
              })
              .create()
          dialog.setView(editText)
          dialog.show()
        },
        "Normalize progress" to {
          launch {
            database.getStories().await().forEach {
              if (it.progressAsPercentage() > 75.0 || it.progress.scrollAbsolute == 999999.0)
                database.updateInStory(it.storyId,
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
        }
    ).entries.forEach { kv ->
      val b = Button(this)
      b.text = kv.key
      b.setOnClickListener { kv.value() }
      debugButtons.addView(b)
    }
  }

  override fun onResume() {
    super.onResume()
    val storyId = Static.prefs.getLong(Prefs.RESUME_STORY_ID, -1)
    if (storyId == -1L) {
      resumeButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
      resumeButton.text = str(R.string.nothing_to_resume)
      resumeButton.setOnClickListener {}
      return
    }
    val model = runBlocking { database.storyById(storyId).await() }.orElse { return@onResume }
    resumeButton.text = Html.fromHtml(str(R.string.resume_story, model.title, model.author,
        model.progress.currentChapter, model.fragment.chapterCount), Html.FROM_HTML_MODE_COMPACT)
    resumeButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_restore_black_24dp, 0, 0, 0)
    resumeButton.drawableTint(R.color.white, theme, Direction.LEFT)
    resumeButton.setOnClickListener {
      startActivity<StoryReaderActivity>(
          StoryReaderActivity.INTENT_STORY_MODEL to model as Parcelable)
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.actionSettings -> startActivity<SettingsActivity>()
      R.id.clearAllCaches -> {
        Log.d(TAG, "Clearing all caches")
        categoryCache.clear()
        storyCache.clear()
        canonListCache.clear()
        authorCache.clear()
        reviewCache.clear()
      }
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}
