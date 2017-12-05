package slak.fanfictionstories.activities

import android.content.Intent
import android.os.Bundle
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
import org.jetbrains.anko.db.dropTable
import org.jetbrains.anko.db.select
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.getFullStory
import slak.fanfictionstories.utility.*
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ActivityWithStatic() {
  companion object {
    private const val TAG = "MainActivity"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(toolbar)

    // Update alarm
    initAlarm(this)

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
        val notifs = Notifications(this@MainActivity, Notifications.Kind.DOWNLOADING)
        getFullStory(this@MainActivity, 12129863L, notifs).await()
        getFullStory(this@MainActivity, 11953822L, notifs).await()
        getFullStory(this@MainActivity, 12295826L, notifs).await()
        notifs.cancel()
      } }
      wipeDiskDataBtn.setOnClickListener {
        val deleted = File(getStorageDir(this@MainActivity).get(), "storiesData")
            .deleteRecursively()
        if (deleted) Log.i(TAG, "SUCCESSFULLY DELETED")
        else Log.e(TAG, "DELETE FAILED")
      }
      downloadNotifBtn.setOnClickListener {
        val notifs = Notifications(this, Notifications.Kind.DOWNLOADING)
        notifs.show(resources.getString(R.string.waiting_for_connection))
        launch(UI) {
          delay(2500)
          notifs.cancel()
        }
      }
      updateStoriesBtn.setOnClickListener { launch(CommonPool) {
        delay(3, TimeUnit.SECONDS)
        val intent = Intent()
        intent.action = "slak.fanfictionstories.StoryUpdateReceiver"
        sendBroadcast(intent)
      } }
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
    resumeButton.text = Html.fromHtml(getString(R.string.resume_story, model.title,
        model.authorRaw, model.currentChapter, model.chapterCount), Html.FROM_HTML_MODE_COMPACT)
    resumeButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_restore_black_24dp, 0, 0, 0)
    resumeButton.drawableTint(android.R.color.white, theme, Direction.LEFT)
    resumeButton.setOnClickListener {
      val intent = Intent(this@MainActivity, StoryReaderActivity::class.java)
      intent.putExtra(StoryReaderActivity.INTENT_STORY_MODEL, model)
      startActivity(intent)
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
    R.id.action_settings -> {
      startActivity(Intent(this, SettingsActivity::class.java))
      true
    }
    else -> super.onOptionsItemSelected(item)
  }
}
