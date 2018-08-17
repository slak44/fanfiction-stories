package slak.fanfictionstories

import android.app.Application
import android.util.Log
import com.jakewharton.threetenabp.AndroidThreeTen
import slak.fanfictionstories.fetchers.*
import slak.fanfictionstories.utility.Static

/** Runs a whole bunch of static initializers in [onCreate]. */
@Suppress("unused")
class StoriesApplication : Application() {
  companion object {
    private const val TAG = "StoriesApplication"
  }

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "Initializing")
    // Init static access stuff
    Static.init(this)
    // Init time/date lib
    AndroidThreeTen.init(this)
    // Try not to let exceptions crash the app
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      Log.e("UNCAUGHT DEFAULT", thread.toString(), throwable)
    }
    // Init the caches from disk (all async)
    categoryCache.deserialize()
    storyCache.deserialize()
    canonListCache.deserialize()
    authorCache.deserialize()
    reviewCache.deserialize()
    // Schedule first update job
    scheduleUpdate()
  }
}
