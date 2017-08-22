package slak.fanfictionstories

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.annotation.DrawableRes
import android.support.annotation.StringRes
import android.support.v4.app.NotificationCompat
import android.support.v4.app.TaskStackBuilder
import android.util.Log

class Notifications(val context: Context, val kind: Kind) {
  companion object {
    const val DOWNLOAD_CHANNEL = "download_channel"
    const val NOTIF_PENDING_INTENT_REQ_CODE = 0xD00D

    const val DOWNLOAD_NOTIFICATION_ID = 0xDA010AD
    const val UPDATE_NOTIFICATION_ID = 0x04DA7E
    const val DONE_UPDATING_NOTIFICATION_ID = 0x77704DA
    const val DONE_DOWNLOADING_NOTIFICATION_ID = 0x77704DB

    const val DOWNLOADED_STORIES_ID_BEGIN = 0xFFE000
    const val UPDATED_STORIES_REQ_ID_BEGIN = 0xFFF000
    const val NOTIFICATIONS_DOWNLOADED_STORIES_GROUP = "stories_list_downloaded"
    const val NOTIFICATIONS_UPDATED_STORIES_GROUP = "stories_list_updated"

    private var downloadedIds = DOWNLOADED_STORIES_ID_BEGIN
    fun downloadedStory(context: Context, titleOfStory: String) {
      val n = Notifications(context, Notifications.Kind.DONE_DOWNLOADING)
      n.show(n.create("").setGroupSummary(true)
          .setGroup(NOTIFICATIONS_DOWNLOADED_STORIES_GROUP))
      val notif = n.create(titleOfStory)
          .setContentTitle(n.context.resources.getString(R.string.downloaded_story))
          .setGroup(NOTIFICATIONS_DOWNLOADED_STORIES_GROUP)
          .build()
      n.notificationManager.notify(downloadedIds++, notif)
    }

    private var updatedIds = UPDATED_STORIES_REQ_ID_BEGIN
    fun updatedStories(context: Context, titles: List<String>) {
      val n = Notifications(context, Notifications.Kind.DONE_UPDATING)
      n.show(n.create(context.resources.getString(R.string.x_stories_updated, titles.size))
          .setGroupSummary(true)
          .setGroup(NOTIFICATIONS_UPDATED_STORIES_GROUP))
      titles.forEach {
        val notif = n.create(it)
            .setContentTitle(n.context.resources.getString(R.string.updated_story))
            .setGroup(NOTIFICATIONS_UPDATED_STORIES_GROUP)
            .build()
        n.notificationManager.notify(updatedIds++, notif)
      }
      updatedIds = UPDATED_STORIES_REQ_ID_BEGIN
    }
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

  private enum class Duration {
    TRANSIENT, ONGOING
  }

  enum class Kind(val duration: Duration, @StringRes val titleStringId: Int,
                  @DrawableRes val icon: Int, val reqId: Int, val sortKey: String) {
    DOWNLOADING(Duration.ONGOING, R.string.downloading_story,
        R.drawable.ic_file_download_black_24dp, DOWNLOAD_NOTIFICATION_ID, "A"),
    UPDATING(Duration.ONGOING, R.string.updating_story,
        R.drawable.ic_update_black_24dp, UPDATE_NOTIFICATION_ID, "B"),
    DONE_UPDATING(Duration.TRANSIENT, R.string.updated_stories,
        R.drawable.ic_done_all_black_24dp, DONE_UPDATING_NOTIFICATION_ID, "D"),
    DONE_DOWNLOADING(Duration.TRANSIENT, R.string.downloaded_stories,
        R.drawable.ic_done_all_black_24dp, DONE_DOWNLOADING_NOTIFICATION_ID, "C")
  }

  fun create(content: String): NotificationCompat.Builder {
    val builder = NotificationCompat.Builder(context, Notifications.DOWNLOAD_CHANNEL)
        .setSmallIcon(kind.icon)
        .setContentTitle(context.resources.getString(kind.titleStringId))
        .setContentText(content)
        .setSortKey(kind.sortKey)
        .setOngoing(kind.duration == Duration.ONGOING)
        .setChannelId(DOWNLOAD_CHANNEL)
    val intent = Intent(context, StoryListActivity::class.java)
    val stack = TaskStackBuilder.create(context)
    stack.addParentStack(StoryListActivity::class.java)
    stack.addNextIntent(intent)
    val pendingIntent = stack.getPendingIntent(
        NOTIF_PENDING_INTENT_REQ_CODE, PendingIntent.FLAG_UPDATE_CURRENT)
    builder.setContentIntent(pendingIntent)
    return builder
  }

  fun show(content: String) {
    notificationManager.notify(kind.reqId, create(content).build())
  }

  fun show(b: NotificationCompat.Builder) {
    notificationManager.notify(kind.reqId, b.build())
  }

  fun cancel() {
    Log.i("Notifications", "killed $kind")
    notificationManager.cancel(kind.reqId)
  }
}
