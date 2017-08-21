package slak.fanfictionstories

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.icu.util.Calendar
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch

fun initAlarm(context: Context) {
  val alarmIntent = Intent(context, StoryUpdateReceiver::class.java)
  val pendingIntent = PendingIntent.getBroadcast(context,
      0, alarmIntent, PendingIntent.FLAG_CANCEL_CURRENT)
  val calendar = Calendar.getInstance()
  // FIXME use TimePickerDialog for setting update time in db, and just fetch it here
  calendar.timeInMillis = System.currentTimeMillis()
  val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  alarm.cancel(pendingIntent)
  // 60 seconds when debugging
  val interval = if (BuildConfig.DEBUG) 1000L * 60 else AlarmManager.INTERVAL_DAY
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
  override fun onReceive(context: Context, intent: Intent) {
    // FIXME show updating notification
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    checkNetworkState(context, cm, { ctx: Context ->
      update(ctx)
    })
  }

  private fun update(context: Context) = launch(CommonPool) {
    Log.i("StoryUpdateReceiver", "Updating")
    val storyModels = context.database.getStories().await()
    // We can launch all of them at once since there can only be one holding the download lock,
    // so we won't assblast their site with requests
    storyModels.forEach { model -> launch(CommonPool) {
      val fetcher = StoryFetcher(model.storyIdRaw, context)
      fetcher.fetchMetadata().await()
      fetcher.update(model)
    } }
  }

}
