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

fun <T> checkNetworkState(
    context: Context,
    cm: ConnectivityManager,
    onNetConnected: suspend (context: Context) -> T
): Deferred<T> = async(CommonPool) {
  val activeNetwork = cm.activeNetworkInfo
  if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting) {
    // No connection; wait
    println("no connection") // FIXME set notification to 'no connection'
    delay(5, TimeUnit.SECONDS)
    return@async checkNetworkState(context, cm, onNetConnected).await()
  } else if (activeNetwork.isConnectedOrConnecting && !activeNetwork.isConnected) {
    // We're connecting; wait
    delay(3, TimeUnit.SECONDS)
    println("connecting") // FIXME update notification
    return@async checkNetworkState(context, cm, onNetConnected).await()
  } else {
    // We have connection
    return@async onNetConnected(context)
  }
}

class MainActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "MainActivity"

    lateinit var res: Resources
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

    // FIXME: set drawable and text string for resume button if a story is available to be resumed
    // FIXME: do the above in onResume as well

    storyListButton.setOnClickListener {
      val intent = Intent(this, StoryListActivity::class.java)
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
      addStoryBtn.setOnClickListener {
        getFullStory(this, 12129863L)
        getFullStory(this, 11953822L)
        getFullStory(this, 12295826L)
      }
      wipeDiskDataBtn.setOnClickListener {
        val deleted = File(getStorageDir(this@MainActivity).get(), "storiesData").deleteRecursively()
        if (deleted) Log.i(TAG, "SUCCESSFULLY DELETED")
        else Log.e(TAG, "DELETE FAILED")
      }
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    // Inflate the menu; this adds items to the action bar if it is present.
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    return when (item.itemId) {
      R.id.action_settings -> true
      else -> super.onOptionsItemSelected(item)
    }
  }
}
