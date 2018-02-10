package slak.fanfictionstories

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.icu.util.Calendar
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.fetchers.fetchStoryModel
import slak.fanfictionstories.fetchers.updateStory
import slak.fanfictionstories.utility.Notifications
import slak.fanfictionstories.utility.async2
import slak.fanfictionstories.utility.database

// FIXME: this entire file needs to be rethought and refactored

const val UPDATE_ALARM_PENDING_INTENT_REQ_CODE = 0xA1A12

fun initAlarm(context: Context) {
  val alarmIntent = Intent(context, StoryUpdateReceiver::class.java)
  val pendingIntent = PendingIntent.getBroadcast(context,
      UPDATE_ALARM_PENDING_INTENT_REQ_CODE, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT)
  val calendar = Calendar.getInstance()
  // FIXME use TimePickerDialog for setting update time in db, and just fetch it here
  calendar.timeInMillis = System.currentTimeMillis()
  val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  alarm.cancel(pendingIntent)
  // 60 seconds when debugging
  // val interval = if (BuildConfig.DEBUG) 1000L * 60 else AlarmManager.INTERVAL_DAY
  // Debugging this is not necessary most of the time
  val interval = AlarmManager.INTERVAL_DAY
  alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP,
      calendar.timeInMillis, interval, pendingIntent)
}

class BootBroadcastReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    initAlarm(context)
  }
}

class StoryUpdateReceiver : BroadcastReceiver() {
  private var updatedStories: MutableList<StoryModel> = mutableListOf()
  override fun onReceive(context: Context, intent: Intent) {
    launch(CommonPool) {
      update(context).await()
      Notifications.updatedStories(updatedStories.map { Pair(it.storyId, it.title) })
    }
  }

  private fun update(context: Context) = async2(CommonPool) {
    Log.i("StoryUpdateReceiver", "Updating")
    updatedStories = mutableListOf()
    val storyModels = context.database.getLocalStories().await()
    storyModels.forEach { model ->
      val updated = updateStory(fetchStoryModel(model.storyId).await()).await()
      if (updated) updatedStories.add(model)
    }
    Notifications.cancel(Notifications.Kind.UPDATING)
  }

}
