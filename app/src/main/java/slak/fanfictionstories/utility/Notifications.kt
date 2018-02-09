package slak.fanfictionstories.utility

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.support.annotation.DrawableRes
import android.support.annotation.StringRes
import android.support.v4.app.NotificationCompat
import android.support.v4.app.TaskStackBuilder
import android.util.Log
import slak.fanfictionstories.R
import slak.fanfictionstories.activities.StoryListActivity

object Notifications {
  const val DOWNLOAD_CHANNEL = "download_channel"
  const val NOTIF_PENDING_INTENT_REQ_CODE = 0xD00D

  const val OTHER_NOTIFICATION_ID = 0xFF0000
  const val DOWNLOAD_NOTIFICATION_ID = 0xDA010AD
  const val UPDATE_NOTIFICATION_ID = 0x04DA7E
  const val DONE_UPDATING_NOTIFICATION_ID = 0x77704DA
  const val DONE_DOWNLOADING_NOTIFICATION_ID = 0x77704DB

  const val DOWNLOADED_STORIES_ID_BEGIN = 0xFF0000
  const val UPDATED_STORIES_REQ_ID_BEGIN = 0xFFF000
  const val NOTIFICATIONS_DOWNLOADED_STORIES_GROUP = "stories_list_downloaded"
  const val NOTIFICATIONS_UPDATED_STORIES_GROUP = "stories_list_updated"

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
        R.drawable.ic_done_all_black_24dp, DONE_DOWNLOADING_NOTIFICATION_ID, "C"),
    OTHER(Duration.ONGOING, R.string.accessing_data,
        R.drawable.ic_cloud_download_black_24dp, OTHER_NOTIFICATION_ID, "E")
  }

  private var downloadedIds = DOWNLOADED_STORIES_ID_BEGIN
  fun downloadedStory(titleOfStory: String) {
    // Show group
    show(Kind.DONE_DOWNLOADING, Notifications
        .create(Kind.DONE_DOWNLOADING, "")
        .setGroupSummary(true)
        .setGroup(NOTIFICATIONS_DOWNLOADED_STORIES_GROUP))
    // Show actual download notif
    val notif = create(Kind.DONE_DOWNLOADING, titleOfStory)
        .setContentTitle(Static.res.getString(R.string.downloaded_story))
        .setGroup(NOTIFICATIONS_DOWNLOADED_STORIES_GROUP)
        .build()
    Static.notifManager.notify(downloadedIds++, notif)
  }

  private var updatedIds = UPDATED_STORIES_REQ_ID_BEGIN
  fun updatedStories(titles: List<String>) {
    if (titles.isEmpty()) return
    // Show group
    show(Kind.DONE_UPDATING, create(Kind.DONE_UPDATING,
        Static.res.getString(R.string.x_stories_updated, titles.size))
        .setGroupSummary(true)
        .setGroup(NOTIFICATIONS_UPDATED_STORIES_GROUP))
    // Show actual title notifs
    titles.forEach {
      val notif = create(Kind.DONE_UPDATING, it)
          .setContentTitle(Static.res.getString(R.string.updated_story))
          .setGroup(NOTIFICATIONS_UPDATED_STORIES_GROUP)
          .build()
      Static.notifManager.notify(updatedIds++, notif)
    }
    updatedIds = UPDATED_STORIES_REQ_ID_BEGIN
  }

  init {
    @SuppressLint("NewAPI")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val title = Static.res.getString(R.string.download_notification_channel)
      val channel =
          NotificationChannel(DOWNLOAD_CHANNEL, title, NotificationManager.IMPORTANCE_DEFAULT)
      Static.notifManager.createNotificationChannel(channel)
    }
  }

  fun create(kind: Kind, content: String): NotificationCompat.Builder {
    val builder = NotificationCompat.Builder(Static.currentCtx, DOWNLOAD_CHANNEL)
        .setSmallIcon(kind.icon)
        .setContentTitle(Static.res.getString(kind.titleStringId))
        .setContentText(content)
        .setSortKey(kind.sortKey)
        .setOngoing(kind.duration == Duration.ONGOING)
        .setChannelId(DOWNLOAD_CHANNEL)
    val intent = Intent(Static.currentCtx, StoryListActivity::class.java)
    val stack = TaskStackBuilder.create(Static.currentCtx)
    stack.addParentStack(StoryListActivity::class.java)
    stack.addNextIntent(intent)
    val pendingIntent = stack.getPendingIntent(
        NOTIF_PENDING_INTENT_REQ_CODE, PendingIntent.FLAG_UPDATE_CURRENT)
    builder.setContentIntent(pendingIntent)
    return builder
  }

  fun show(kind: Kind, content: String) {
    Static.notifManager.notify(kind.reqId, create(kind, content).build())
  }

  fun show(kind: Kind, @StringRes id: Int) {
    Static.notifManager.notify(kind.reqId, create(kind, Static.res.getString(id)).build())
  }

  fun show(kind: Kind, @StringRes id: Int, vararg format: Any) {
    Static.notifManager.notify(kind.reqId, create(kind, Static.res.getString(id, *format)).build())
  }

  fun show(kind: Kind, b: NotificationCompat.Builder) {
    Static.notifManager.notify(kind.reqId, b.build())
  }

  fun cancel(kind: Kind) {
    Log.i("Notifications", "killed $kind")
    Static.notifManager.cancel(kind.reqId)
  }
}
