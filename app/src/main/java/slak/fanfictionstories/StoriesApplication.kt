package slak.fanfictionstories

import android.app.Application
import android.util.Log
import com.jakewharton.threetenabp.AndroidThreeTen
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.fetchers.authorCache
import slak.fanfictionstories.fetchers.canonListCache
import slak.fanfictionstories.fetchers.categoryCache
import slak.fanfictionstories.fetchers.storyCache
import slak.fanfictionstories.utility.Static
import java.util.concurrent.TimeUnit

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
    Thread.setDefaultUncaughtExceptionHandler {
      thread, throwable -> Log.e("UNCAUGHT DEFAULT", thread.toString(), throwable)
    }
    // Init the caches from disk (all async)
    categoryCache.deserialize()
    storyCache.deserialize()
    canonListCache.deserialize()
    authorCache.deserialize()
    // Schedule initial update job if no update job exists
    scheduleInitUpdate()
  }

  private fun scheduleInitUpdate(): Job = launch(CommonPool) {
    val areJobsPending = Static.jobScheduler.allPendingJobs.size > 0
    if (areJobsPending) return@launch
    if (scheduleInitialUpdateJob() == JobSchedulerResult.FAILURE) {
      Log.e(TAG, "Failed to schedule initial job")
      delay(5, TimeUnit.MINUTES)
      scheduleInitUpdate()
    }
  }
}
