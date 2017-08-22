package slak.fanfictionstories

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.support.annotation.StringRes
import android.support.v4.app.NotificationCompat
import android.support.v4.app.TaskStackBuilder
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

class Notifications(val context: Context, val kind: Kind) {
  companion object {
    const val DOWNLOAD_CHANNEL = "download_channel"
    const val NOTIF_PENDING_INTENT_REQ_CODE = 0xD00D
    const val DOWNLOAD_NOTIFICATION_ID = 0xDA010AD
    const val UPDATE_NOTIFICATION_ID = 0x04DA7E
  }

  val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  init {
    if (Build.VERSION.SDK_INT >= 26) {
      val title = context.resources.getString(R.string.download_notification_channel)
      val channel =
          NotificationChannel(DOWNLOAD_CHANNEL, title, NotificationManager.IMPORTANCE_DEFAULT)
      notificationManager.createNotificationChannel(channel)
    }
  }

  enum class Kind(@StringRes val titleStringId: Int, val reqId: Int) {
    DOWNLOADING(R.string.downloading_story, DOWNLOAD_NOTIFICATION_ID),
    UPDATING(R.string.updating_story, UPDATE_NOTIFICATION_ID)
  }

  fun show(content: String) {
    val builder = NotificationCompat.Builder(context, Notifications.DOWNLOAD_CHANNEL)
        .setSmallIcon(R.drawable.ic_file_download_black_24dp)
        .setContentTitle(context.resources.getString(kind.titleStringId))
        .setContentText(content)
        .setOngoing(true)
        .setChannelId(DOWNLOAD_CHANNEL)
    val intent = Intent(context, StoryListActivity::class.java)
    val stack = TaskStackBuilder.create(context)
    stack.addParentStack(StoryListActivity::class.java)
    stack.addNextIntent(intent)
    val pendingIntent = stack.getPendingIntent(
        NOTIF_PENDING_INTENT_REQ_CODE, PendingIntent.FLAG_UPDATE_CURRENT)
    builder.setContentIntent(pendingIntent)
    notificationManager.notify(kind.reqId, builder.build())
  }

  fun cancel() {
    Log.i("Notifications", "killed $kind")
    notificationManager.cancel(kind.reqId)
  }
}

fun <T> checkNetworkState(
    context: Context,
    cm: ConnectivityManager,
    n: Notifications,
    onNetConnected: suspend (context: Context) -> T
): Deferred<T> = async(CommonPool) {
  val activeNetwork = cm.activeNetworkInfo
  if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting) {
    // No connection; wait
    n.show(context.resources.getString(R.string.waiting_for_connection))
    Log.e("checkNetworkState", "No connection")
    delay(5, TimeUnit.SECONDS)
    return@async checkNetworkState(context, cm, n, onNetConnected).await()
  } else if (activeNetwork.isConnectedOrConnecting && !activeNetwork.isConnected) {
    // We're connecting; wait
    n.show(context.resources.getString(R.string.waiting_for_connection))
    Log.e("checkNetworkState", "Connecting...")
    delay(3, TimeUnit.SECONDS)
    return@async checkNetworkState(context, cm, n, onNetConnected).await()
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
        val notifs = Notifications(this, Notifications.Kind.DOWNLOADING)
        getFullStory(this, 12129863L, notifs)
        getFullStory(this, 11953822L, notifs)
        getFullStory(this, 12295826L, notifs)
      }
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
