package slak.fanfictionstories

/**
 * Updates can be forced via adb for testing:
 * `adb shell cmd jobscheduler run -f slak.fanfictionstories 909729`
 */

import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import android.util.Log
import kotlinx.coroutines.*
import org.threeten.bp.Duration
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime
import slak.fanfictionstories.Notifications.Companion.defaultIntent
import slak.fanfictionstories.data.Prefs
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.updateStory
import slak.fanfictionstories.utility.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

private const val TAG = "StoryUpdates"
private const val UPDATE_JOB_INFO_ID = 0xDE1A1
private val updateComponent = ComponentName(Static.currentCtx, UpdateService::class.java)

/**
 * Schedule a job that waits until the specified update time to run the update and schedule the next
 * update job. The update has a +-30 minute window to complete.
 */
private fun scheduleUpdateJob(initTarget: ZonedDateTime): ScheduleResult {
  val now = ZonedDateTime.now(ZoneId.systemDefault())
  // If we're past the target today, schedule it for tomorrow
  val target =
      if (now.isAfter(initTarget)) initTarget.plusDays(1)
      else initTarget
  val timeUntilUpdate = Duration.between(now, target)
  val lowerBound = timeUntilUpdate.minusMinutes(30)
  val upperBound = timeUntilUpdate.plusMinutes(30)
  val builder = JobInfo.Builder(UPDATE_JOB_INFO_ID, updateComponent)
      .setMinimumLatency(lowerBound.toMillis())
      .setOverrideDeadline(upperBound.toMillis())
      .setBackoffCriteria(1000, BackoffPolicy.LINEAR)
      .setRequiredNetworkType(Prefs.autoUpdateReqNetType())
      .setPersisted(true)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setRequiresBatteryNotLow(true)
  return ScheduleResult.from(Static.jobScheduler.schedule(builder.build()))
}

/** Schedule update job if one doesn't exists. */
fun scheduleUpdate(): Job = GlobalScope.launch(Dispatchers.Default) {
  val areJobsPending = Static.jobScheduler.allPendingJobs.size > 0
  if (areJobsPending) return@launch
  if (scheduleUpdateJob(Prefs.autoUpdateMoment()) == ScheduleResult.FAILURE) {
    Log.e(TAG, "Failed to schedule initial job")
    delay(TimeUnit.MINUTES.toMillis(5))
    scheduleUpdate()
  }
}

/** The service invoked periodically to update the local stories. */
class UpdateService : JobService(), CoroutineScope {
  companion object {
    private const val TAG = "UpdateService"
  }

  private var coroutine: Job? = null
  override val coroutineContext: CoroutineContext
    get() = Dispatchers.Default

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return Service.START_NOT_STICKY
  }

  override fun onStartJob(params: JobParameters): Boolean {
    Log.i(TAG, "Update job started")
    coroutine = launch {
      val storyModels = applicationContext.database.getLocalStories().await()
      val storiesToUpdate = orderStories(storyModels.toMutableList(),
          Prefs.storyListOrderStrategy, Prefs.storyListOrderDirection)
      val idxDelta = Prefs.updateResumeIndex.orElse(0)
      val startTime = System.currentTimeMillis()
      val updatedStories = storiesToUpdate.subList(Prefs.updateResumeIndex.orElse(0), storyModels.size)
          .mapIndexedNotNull { idx, model ->
            val realIdx = idxDelta + idx
            val str = str(R.string.checking_story,
                model.title, realIdx + 1, storyModels.size, (realIdx + 1) * 100F / storyModels.size)
            Notifications.UPDATING.show(defaultIntent(), str) {
              setWhen(startTime)
              setStyle(NotificationCompat.BigTextStyle().bigText(str))
              setProgress(storyModels.size, realIdx + 1, false)
            }
            val newModel = updateStory(model)
            Log.v(TAG, "Story ${model.storyId} was update performed: ${newModel !is Empty}")
            Prefs.updateResumeIndex = realIdx.opt()
            return@mapIndexedNotNull newModel.orNull()
          }
      Prefs.updateResumeIndex = Empty()
      Notifications.UPDATING.cancel()
      Notifications.updatedStories(updatedStories)
      scheduleUpdateJob(Prefs.autoUpdateMoment().plusDays(1))
      jobFinished(params, false)
    }
    return true
  }

  override fun onStopJob(params: JobParameters?): Boolean {
    Log.w(TAG, "Update job was cancelled")
    coroutine?.cancel()
    Notifications.UPDATING.cancel()
    coroutine = null
    return true
  }
}
