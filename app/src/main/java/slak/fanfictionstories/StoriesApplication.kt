package slak.fanfictionstories

import android.app.Application
import android.util.Log
import com.jakewharton.threetenabp.AndroidThreeTen
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.fetchers.*
import slak.fanfictionstories.utility.ScheduleResult
import slak.fanfictionstories.utility.Static
import java.util.concurrent.TimeUnit

/** Runs a whole bunch of static initializers in [onCreate]. */
@Suppress("unused")
class StoriesApplication : Application() {
  companion object {
    private const val TAG = "StoriesApplication"
    fun scheduleInitUpdate(): Job = launch(CommonPool) {
      val areJobsPending = Static.jobScheduler.allPendingJobs.size > 0
      if (areJobsPending) return@launch
      if (scheduleInitialUpdateJob() == ScheduleResult.FAILURE) {
        Log.e(TAG, "Failed to schedule initial job")
        delay(5, TimeUnit.MINUTES)
        scheduleInitUpdate()
      }
    }
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
    // Schedule initial update job if no update job exists
    scheduleInitUpdate()
  }
}
