package slak.fanfictionstories

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

class BootBroadcastReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    val serviceIntent = Intent(context, StoryUpdateService::class.java)
    context.startService(serviceIntent)
  }
}

class StoryUpdateService : Service() {
  override fun onCreate() {
    super.onCreate()
    // TODO
  }

  override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    return Service.START_STICKY
  }

  override fun onBind(intent: Intent): IBinder {
    return Binder()
  }

  override fun onDestroy() {
    super.onDestroy()
    Log.i("StoryUpdateService", "Dying")
  }
}
