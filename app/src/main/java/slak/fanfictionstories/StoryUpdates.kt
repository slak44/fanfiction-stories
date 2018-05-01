package slak.fanfictionstories

/**
 * Updates can be forced via adb for testing:
 * Initial: `adb shell cmd jobscheduler run -f slak.fanfictionstories 909729`
 * Periodic: `adb shell cmd jobscheduler run -f slak.fanfictionstories 662035`
 */

import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.support.v4.app.NotificationCompat
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import org.threeten.bp.Duration
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime
import slak.fanfictionstories.fetchers.updateStory
import slak.fanfictionstories.utility.*
import java.util.concurrent.TimeUnit

private const val UPDATE_INIT_DELAY_JOB_INFO_ID = 0xDE1A1
private const val UPDATE_JOB_INFO_ID = 0xA1A13
private val updateComponent = ComponentName(Static.currentCtx, UpdateService::class.java)

/**
 * Schedule a job that waits until the specified update time, so it can do the update and launch the
 * periodic update job.
 */
fun scheduleInitialUpdateJob(): ScheduleResult {
  val now = ZonedDateTime.now(ZoneId.systemDefault())
  val target = Prefs.autoUpdateMoment()
  // If it's negative, we're past that moment today, so schedule it for tomorrow
  if (Duration.between(now, target).isNegative) target.plusDays(1)
  val builder = JobInfo.Builder(UPDATE_INIT_DELAY_JOB_INFO_ID, updateComponent)
      .setMinimumLatency(Duration.between(now, target).toMillis())
      .setOverrideDeadline(Duration.between(now, target).toMillis() + 1)
      .setBackoffCriteria(1000, BackoffPolicy.LINEAR)
      .setRequiredNetworkType(Prefs.autoUpdateReqNetType())
      .setPersisted(true)
  return ScheduleResult.from(Static.jobScheduler.schedule(builder.build()))
}

/** Schedule the periodic update job. */
fun schedulePeriodicUpdateJob(): ScheduleResult {
  val builder = JobInfo.Builder(UPDATE_JOB_INFO_ID, updateComponent)
      .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.MINUTES.toMillis(100))
      .setBackoffCriteria(1000, BackoffPolicy.LINEAR)
      .setRequiredNetworkType(Prefs.autoUpdateReqNetType())
      .setPersisted(true)

  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    builder.setRequiresBatteryNotLow(true)
  }
  return ScheduleResult.from(Static.jobScheduler.schedule(builder.build()))
}

/** The service invoked periodically to update the local stories. */
class UpdateService : JobService() {
  companion object {
    private const val TAG = "UpdateService"
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return Service.START_NOT_STICKY
  }

  private var coroutineJob: Job? = null

  override fun onStartJob(params: JobParameters): Boolean {
    if (params.jobId == UPDATE_INIT_DELAY_JOB_INFO_ID) {
      if (schedulePeriodicUpdateJob() == ScheduleResult.FAILURE) {
        Log.e(TAG, "Failed to schedule repeating job")
      }
    }
    Log.i(TAG, "Update job started")
    coroutineJob = launch(CommonPool) {
      val storyModels = applicationContext.database.getLocalStories().await()
      val updatedStories = orderStories(storyModels.toMutableList(),
          Prefs.storyListOrderStrategy,
          Prefs.storyListOrderDirection).mapIndexedNotNull { idx, model ->
        val str = str(R.string.checking_story,
            model.title, idx + 1, storyModels.size, (idx + 1) * 100F / storyModels.size)
        Notifications.show(Notifications.Kind.UPDATING,
            Notifications.create(Notifications.Kind.UPDATING, str, Notifications.defaultIntent())
                .setStyle(NotificationCompat.BigTextStyle().bigText(str))
                .setProgress(storyModels.size, idx + 1, false))
        val newModel = updateStory(model).await()
        Log.v(TAG, "Story ${model.storyId} was update performed: ${newModel !is Empty}")
        return@mapIndexedNotNull newModel.orNull()
      }
      Notifications.cancel(Notifications.Kind.UPDATING)
      Notifications.updatedStories(updatedStories)
      jobFinished(params, false)
    }
    return true
  }

  override fun onStopJob(params: JobParameters?): Boolean {
    Log.w(TAG, "Update job was cancelled")
    coroutineJob?.cancel()
    Notifications.cancel(Notifications.Kind.UPDATING)
    coroutineJob = null
    return true
  }
}
