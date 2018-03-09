package slak.fanfictionstories.utility

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import android.support.annotation.DrawableRes
import android.support.annotation.StringRes
import android.support.v4.app.NotificationCompat
import android.support.v4.app.TaskStackBuilder
import android.util.Log
import kotlinx.coroutines.experimental.runBlocking
import org.jetbrains.anko.intentFor
import slak.fanfictionstories.R
import slak.fanfictionstories.activities.StoryListActivity
import slak.fanfictionstories.activities.StoryReaderActivity

object Notifications {
  const val DOWNLOAD_CHANNEL = "download_channel"

  const val ERROR_NOTIFICATION_ID = 1
  const val DOWNLOAD_NOTIFICATION_ID = 2
  const val UPDATE_NOTIFICATION_ID = 3
  const val DONE_UPDATING_NOTIFICATION_ID = 4
  const val DONE_DOWNLOADING_NOTIFICATION_ID = 5
  const val NETWORK_NOTIFICATION_ID = 6

  const val DOWNLOADED_STORIES_ID_BEGIN = 0xA000000
  const val UPDATED_STORIES_ID_BEGIN = 0xB000000
  const val PENDING_INTENT_ID_BEGIN = 0xC000000

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
    NETWORK(Duration.ONGOING, R.string.network_not_available,
        R.drawable.ic_signal_wifi_off_black_24dp, NETWORK_NOTIFICATION_ID, "E"),
    ERROR(Duration.ONGOING, R.string.accessing_data,
        R.drawable.ic_cloud_download_black_24dp, ERROR_NOTIFICATION_ID, "F")
  }

  private var downloadedIds = DOWNLOADED_STORIES_ID_BEGIN
  fun downloadedStory(titleOfStory: String, storyId: Long) {
    // Show group
    show(Kind.DONE_DOWNLOADING,
        create(Kind.DONE_DOWNLOADING, str(R.string.downloaded_stories), defaultIntent())
        .setGroupSummary(true)
        .setGroup(NOTIFICATIONS_DOWNLOADED_STORIES_GROUP))
    // Show actual download notif
    val notif = create(Kind.DONE_DOWNLOADING, titleOfStory, readerIntent(storyId))
        .setContentTitle(str(R.string.downloaded_story))
        .setGroup(NOTIFICATIONS_DOWNLOADED_STORIES_GROUP)
        .build()
    Static.notifManager.notify(++downloadedIds, notif)
  }

  private var updatedIds = UPDATED_STORIES_ID_BEGIN
  fun updatedStories(stories: List<Pair<Long, String>>) {
    if (stories.isEmpty()) {
      show(Kind.DONE_UPDATING, defaultIntent(), R.string.no_updates_found)
      return
    }
    // Show group
    show(Kind.DONE_UPDATING,
        create(Kind.DONE_UPDATING, str(R.string.x_stories_updated, stories.size), defaultIntent())
        .setGroupSummary(true)
        .setGroup(NOTIFICATIONS_UPDATED_STORIES_GROUP))
    // Show actual title notifs
    stories.forEach {
      val notif = create(Kind.DONE_UPDATING, it.second, readerIntent(it.first))
          .setContentTitle(str(R.string.updated_story))
          .setGroup(NOTIFICATIONS_UPDATED_STORIES_GROUP)
          .build()
      Static.notifManager.notify(++updatedIds, notif)
    }
    updatedIds = UPDATED_STORIES_ID_BEGIN
  }

  init {
    @SuppressLint("NewAPI")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
          DOWNLOAD_CHANNEL,
          str(R.string.download_notification_channel),
          NotificationManager.IMPORTANCE_DEFAULT)
      Static.notifManager.createNotificationChannel(channel)
    }
  }

  fun defaultIntent() = Static.currentCtx.intentFor<StoryListActivity>()
  fun readerIntent(storyId: Long): Intent {
    val model = runBlocking { Static.database.storyById(storyId).await() }
        .orElseThrow(IllegalStateException("Story not found in db"))
    return intentFor<StoryReaderActivity>(
        StoryReaderActivity.INTENT_STORY_MODEL to model as Parcelable)
  }

  private var reqIdCounter = PENDING_INTENT_ID_BEGIN
  fun create(kind: Kind, content: String, target: Intent): NotificationCompat.Builder {
    val builder = NotificationCompat.Builder(Static.currentCtx, DOWNLOAD_CHANNEL)
        .setSmallIcon(kind.icon)
        .setContentTitle(str(kind.titleStringId))
        .setContentText(content)
        .setSortKey(kind.sortKey)
        .setOngoing(kind.duration == Duration.ONGOING)
        .setChannelId(DOWNLOAD_CHANNEL)
    val stack = TaskStackBuilder.create(Static.currentCtx)
    stack.addParentStack(target.component)
    stack.addNextIntent(target)
    val pendingIntent = stack.getPendingIntent(++reqIdCounter, PendingIntent.FLAG_UPDATE_CURRENT)
    builder.setContentIntent(pendingIntent)
    return builder
  }

  fun show(kind: Kind, target: Intent, content: String) {
    Static.notifManager.notify(kind.reqId, create(kind, content, target).build())
  }

  fun show(kind: Kind, target: Intent, @StringRes id: Int) {
    Static.notifManager.notify(kind.reqId, create(kind, str(id), target).build())
  }

  fun show(kind: Kind, target: Intent, @StringRes id: Int, vararg format: Any) {
    Static.notifManager.notify(kind.reqId, create(kind, str(id, *format), target).build())
  }

  fun show(kind: Kind, b: NotificationCompat.Builder) {
    Static.notifManager.notify(kind.reqId, b.build())
  }

  fun cancel(kind: Kind) {
    Log.i("Notifications", "killed $kind")
    Static.notifManager.cancel(kind.reqId)
  }
}
