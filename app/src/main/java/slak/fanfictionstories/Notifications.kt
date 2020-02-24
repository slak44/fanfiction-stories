package slak.fanfictionstories

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import android.util.Log
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.anko.intentFor
import slak.fanfictionstories.activities.StoryListActivity
import slak.fanfictionstories.activities.StoryReaderActivity
import slak.fanfictionstories.utility.*
import slak.fanfictionstories.utility.Optional
import java.util.*

enum class Notifications(
    private val duration: Duration,
    @StringRes val titleStringId: Int,
    @DrawableRes val icon: Int,
    @StringRes val channelTitleId: Int,
    @StringRes val channelDescId: Int,
    val group: Optional<String> = Empty()
) {
  DOWNLOADING(
      Duration.ONGOING,
      R.string.downloading_story,
      R.drawable.ic_file_download_black_24dp,
      R.string.channel_title_download,
      R.string.channel_desc_download),
  UPDATING(
      Duration.ONGOING,
      R.string.updating_story,
      R.drawable.ic_update_black_24dp,
      R.string.channel_title_update,
      R.string.channel_desc_update),
  DONE_UPDATING(
      Duration.TRANSIENT,
      R.string.updated_story,
      R.drawable.ic_done_all_black_24dp,
      R.string.channel_title_done_updating,
      R.string.channel_desc_done_updating,
      "stories_list_updated".opt()),
  DONE_DOWNLOADING(
      Duration.TRANSIENT,
      R.string.downloaded_story,
      R.drawable.ic_done_all_black_24dp,
      R.string.channel_title_done_downloading,
      R.string.channel_desc_done_downloading,
      "stories_list_downloaded".opt()),
  NETWORK(
      Duration.ONGOING,
      R.string.network_not_available,
      R.drawable.ic_signal_wifi_off_black_24dp,
      R.string.channel_title_network,
      R.string.channel_desc_network),
  ERROR(
      Duration.ONGOING,
      R.string.accessing_data,
      R.drawable.ic_cloud_download_black_24dp,
      R.string.channel_title_error,
      R.string.channel_desc_error);

  init {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) GlobalScope.launch(Main) {
      for (value in values()) {
        val channel = NotificationChannel(
            value.channelKey,
            str(value.channelTitleId),
            NotificationManager.IMPORTANCE_LOW)
        channel.description = str(value.channelDescId)
        Static.notifManager.createNotificationChannel(channel)
      }
    }
  }

  private val reqId = ordinal
  private val channelKey = "${name.toLowerCase(Locale.ROOT)}_channel"

  private var reqIdCounter = 0xC000000
  fun create(target: Intent, content: String): NotificationCompat.Builder {
    val builder = NotificationCompat.Builder(Static.currentCtx, channelKey)
        .setSmallIcon(icon)
        .setContentTitle(str(titleStringId))
        .setContentText(content)
        .setOngoing(duration == Duration.ONGOING)
        .setChannelId(channelKey)
    if (duration == Duration.ONGOING) {
      builder
          .setShowWhen(true)
          .setOnlyAlertOnce(true)
    }
    group.ifPresent { builder.setGroup(it) }
    val stack = TaskStackBuilder.create(Static.currentCtx)
    stack.addParentStack(target.component)
    stack.addNextIntent(target)
    val pendingIntent = stack.getPendingIntent(++reqIdCounter, PendingIntent.FLAG_UPDATE_CURRENT)
    builder.setContentIntent(pendingIntent)
    return builder
  }

  fun create(@StringRes content: Int, target: Intent) = create(target, str(content))

  fun show(target: Intent, content: String) {
    Static.notifManager.notify(reqId, create(target, content).build())
  }

  fun show(target: Intent, @StringRes id: Int) {
    Static.notifManager.notify(reqId, create(id, target).build())
  }

  fun show(target: Intent, @StringRes id: Int, vararg format: Any) {
    Static.notifManager.notify(reqId, create(target, str(id, *format)).build())
  }

  fun show(target: Intent,
           content: String,
           builder: NotificationCompat.Builder.() -> NotificationCompat.Builder) {
    Static.notifManager.notify(reqId, builder(create(target, content)).build())
  }

  fun show(target: Intent,
           @StringRes content: Int,
           builder: NotificationCompat.Builder.() -> NotificationCompat.Builder) {
    show(target, str(content), builder)
  }

  fun cancel() {
    Log.v("Notifications", "killed $this")
    Static.notifManager.cancel(reqId)
  }

  companion object {
    private enum class Duration { TRANSIENT, ONGOING }

    private var downloadedIds = 0xA000000
    fun downloadedStory(model: StoryModel) {
      // Show group
      DONE_DOWNLOADING.show(defaultIntent(), R.string.downloaded_stories) {
        setGroupSummary(true)
        setContentTitle(str(R.string.downloaded_stories))
      }
      // Show actual download notifications
      val notif = DONE_DOWNLOADING.create(readerIntent(model), model.title).build()
      Static.notifManager.notify(++downloadedIds, notif)
    }

    private const val UPDATED_STORIES_ID_BEGIN = 0xB000000
    private var updatedIds = UPDATED_STORIES_ID_BEGIN
    fun updatedStories(stories: List<StoryModel>) {
      if (stories.isEmpty()) {
        DONE_UPDATING.show(defaultIntent(), R.string.no_updates_found)
        return
      }
      // Show group
      DONE_UPDATING.show(defaultIntent(), str(R.string.x_stories_updated, stories.size)) {
        setGroupSummary(true)
        setContentTitle(str(R.string.updated_stories))
      }
      // Show actual title notifications
      stories.forEach {
        val notif = DONE_UPDATING.create(readerIntent(it), it.title).build()
        Static.notifManager.notify(++updatedIds, notif)
      }
      updatedIds = UPDATED_STORIES_ID_BEGIN
    }

    fun defaultIntent() = Static.currentCtx.intentFor<StoryListActivity>()
    fun readerIntent(model: StoryModel) = intentFor<StoryReaderActivity>(
        StoryReaderActivity.INTENT_STORY_MODEL to model as Parcelable)
  }
}
