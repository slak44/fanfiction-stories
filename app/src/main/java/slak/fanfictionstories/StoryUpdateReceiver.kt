package slak.fanfictionstories

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.icu.util.Calendar
import android.net.ConnectivityManager
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.util.concurrent.TimeUnit

fun initAlarm(context: Context) {
  val alarmIntent = Intent(context, StoryUpdateReceiver::class.java)
  val pendingIntent = PendingIntent.getBroadcast(context,
      0, alarmIntent, PendingIntent.FLAG_CANCEL_CURRENT)
  val calendar = Calendar.getInstance()
  calendar.timeInMillis = System.currentTimeMillis()
  val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  alarm.cancel(pendingIntent)
  // 10 seconds when debugging
  val interval = if (BuildConfig.DEBUG) 1000L * 10 else AlarmManager.INTERVAL_DAY
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
    checkNetworkState(cm)
  }

  private fun checkNetworkState(cm: ConnectivityManager): Job = launch(CommonPool) {
    val activeNetwork = cm.activeNetworkInfo
    if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting) {
      // No connection; wait
      println("no connection") // FIXME set notification to 'no connection'
      delay(5, TimeUnit.SECONDS)
      checkNetworkState(cm)
      return@launch
    } else if (activeNetwork.isConnectedOrConnecting && !activeNetwork.isConnected) {
      // We're connecting; wait
      delay(3, TimeUnit.SECONDS)
      println("connecting") // FIXME update notification
      checkNetworkState(cm)
      return@launch
    } else {
      // We have connection
      update()
    }
  }

  private fun update() {
    // FIXME actually do the updatings
    println("done updates")
  }

}
