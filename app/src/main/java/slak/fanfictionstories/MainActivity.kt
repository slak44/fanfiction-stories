package slak.fanfictionstories

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.ConnectivityManager
import android.os.Bundle
import android.support.annotation.StringRes
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
import java.util.concurrent.TimeUnit

fun errorDialog(ctx: Context, @StringRes title: Int, @StringRes msg: Int) {
  errorDialog(ctx, ctx.resources.getString(title), ctx.resources.getString(msg))
}

fun errorDialog(ctx: Context, title: String, msg: String) = launch(UI) {
  AlertDialog.Builder(ctx)
      .setTitle(title)
      .setMessage(msg)
      .setPositiveButton(R.string.got_it, { dialogInterface, _ ->
        // User acknowledged error
        dialogInterface.dismiss()
      }).create().show()
}

fun waitForNetwork(n: Notifications) = async2(CommonPool) {
  while (true) {
    val activeNetwork = MainActivity.cm.activeNetworkInfo
    // FIXME figure out network status even when app is not focused
    if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting) {
      // No connection; wait
      n.show(MainActivity.res.getString(R.string.waiting_for_connection))
      Log.e("waitForNetwork", "No connection")
      delay(Fetcher.CONNECTION_MISSING_DELAY_SECONDS, TimeUnit.SECONDS)
    } else if (activeNetwork.isConnectedOrConnecting && !activeNetwork.isConnected) {
      // We're connecting; wait
      n.show(MainActivity.res.getString(R.string.waiting_for_connection))
      Log.e("waitForNetwork", "Connecting...")
      delay(Fetcher.CONNECTION_WAIT_DELAY_SECONDS, TimeUnit.SECONDS)
    } else {
      break
    }
  }
}

class MainActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "MainActivity"

    lateinit var res: Resources
      private set

    lateinit var cm: ConnectivityManager
      private set

    lateinit var cacheDirectory: File
      private set
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(toolbar)

    // Update alarm
    initAlarm(this)

    // Set static resources instance
    res = resources
    cacheDirectory = cacheDir
    cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Init this cache
    CategoryFetcher.Cache.deserialize()

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
        notifs.show(res.getString(R.string.waiting_for_connection))
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
