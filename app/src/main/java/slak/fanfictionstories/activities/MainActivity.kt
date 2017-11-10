package slak.fanfictionstories.activities

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.PersistableBundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.android.UI
import org.jetbrains.anko.db.dropTable
import java.io.File
import kotlinx.coroutines.experimental.*
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.CategoryFetcher
import slak.fanfictionstories.fetchers.getFullStory
import slak.fanfictionstories.utility.Notifications
import slak.fanfictionstories.utility.database
import java.util.concurrent.TimeUnit

object Static {
  var res: Resources? = null
    private set
  var cm: ConnectivityManager? = null
    private set
  var cacheDir: File? = null
    private set

  fun init(context: Context) {
    if (res == null) res = context.resources
    if (cm == null)
      cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (cacheDir == null) cacheDir = context.cacheDir
  }
}

abstract class ActivityWithStatic : AppCompatActivity() {
  private var cacheInited: Boolean = false
  override fun onCreate(savedInstanceState: Bundle?) {
    Static.init(this)
    // Re-create cache
    if (!cacheInited) {
      CategoryFetcher.Cache.deserialize()
      cacheInited = true
    }
    super.onCreate(savedInstanceState)
  }
}

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

    // FIXME: set drawable and text string for resume button if a story is available to be resumed
    // FIXME: do the above in onResume as well

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

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
    R.id.action_settings -> true
    else -> super.onOptionsItemSelected(item)
  }
}
