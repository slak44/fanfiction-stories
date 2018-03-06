package slak.fanfictionstories.activities

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.text.Html
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.db.MapRowParser
import org.jetbrains.anko.db.RowParser
import org.jetbrains.anko.db.dropTable
import org.jetbrains.anko.db.select
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.fetchAndWriteStory
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

    storyListButton.setOnClickListener {
      val intent = Intent(this, StoryListActivity::class.java)
      startActivity(intent)
    }

    storyBrowseButton.setOnClickListener {
      val intent = Intent(this, SelectCategoryActivity::class.java)
      startActivity(intent)
    }

    // Debug menu
    if (BuildConfig.DEBUG) {
      debugButtons.visibility = View.VISIBLE
      regenTableBtn.setOnClickListener {
        database.use { dropTable("stories", true) }
        Log.i(TAG, "DROPPED STORIES TABLE")
        database.onCreate(database.writableDatabase)
        Log.i(TAG, "REINITED STORIES TABLE")
      }
      addStoryBtn.setOnClickListener { launch(CommonPool) {
        fetchAndWriteStory(12129863L).await()
        fetchAndWriteStory(11953822L).await()
        fetchAndWriteStory(12295826L).await()
      } }
      wipeDiskDataBtn.setOnClickListener {
        val deleted = File(getStorageDir(this@MainActivity).get(), "storiesData")
            .deleteRecursively()
        if (deleted) Log.i(TAG, "SUCCESSFULLY DELETED")
        else Log.e(TAG, "DELETE FAILED")
      }
      downloadNotifBtn.setOnClickListener {
        AlertDialog.Builder(this).setItems(
            Notifications.Kind.values().map { it.toString() }.toTypedArray(), { _, which ->
              val picked = Notifications.Kind.values()[which]
              Notifications.show(picked, defaultIntent(), "TEST")
              launch(UI) {
                delay(2500)
                Notifications.cancel(picked)
              }
            }).create().show()
      }
      wipeSettings.setOnClickListener {
        Prefs.useImmediate { it.clear() }
      }
      dumpDb.setOnClickListener {
        println(database.readableDatabase.select("stories").parseList(object : MapRowParser<String> {
          override fun parseRow(columns: Map<String, Any?>): String {
            return columns.entries.joinToString(", ") { "(${it.key}) ${it.value.toString()}" }
          }
        }).joinToString("\n"))
      }
    }
  }

  override fun onResume() {
    super.onResume()
    val storyId = Static.prefs.getLong(Prefs.RESUME_STORY_ID, -1)
    if (storyId == -1L) {
      resumeButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
      return
    }
    val model = database.storyById(storyId).orElse { return@onResume }
    resumeButton.text = Html.fromHtml(str(R.string.resume_story, model.title, model.author,
        model.progress.currentChapter, model.fragment.chapterCount), Html.FROM_HTML_MODE_COMPACT)
    resumeButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_restore_black_24dp, 0, 0, 0)
    resumeButton.drawableTint(R.color.white, theme, Direction.LEFT)
    resumeButton.setOnClickListener {
      val intent = Intent(this@MainActivity, StoryReaderActivity::class.java)
      intent.putExtra(StoryReaderActivity.INTENT_STORY_MODEL, model as Parcelable)
      startActivity(intent)
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}
