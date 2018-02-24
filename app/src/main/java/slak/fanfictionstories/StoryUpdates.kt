package slak.fanfictionstories

import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import org.threeten.bp.Duration
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime
import slak.fanfictionstories.fetchers.fetchStoryModel
import slak.fanfictionstories.fetchers.updateStory
import slak.fanfictionstories.utility.Notifications
import slak.fanfictionstories.utility.Prefs
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.database
import java.util.concurrent.TimeUnit

// Updates can be forced via adb for testing:
// Initial: `adb shell cmd jobscheduler run -f slak.fanfictionstories 909729`
// Periodic: `adb shell cmd jobscheduler run -f slak.fanfictionstories 662035`

private const val UPDATE_INIT_DELAY_JOB_INFO_ID = 0xDE1A1
private const val UPDATE_JOB_INFO_ID = 0xA1A13
private val updateComponent = ComponentName(Static.currentCtx, UpdateService::class.java)

enum class JobSchedulerResult {
  SUCCESS, FAILURE;
  companion object {
    fun from(intResult: Int): JobSchedulerResult = when (intResult) {
      JobScheduler.RESULT_SUCCESS -> SUCCESS
      JobScheduler.RESULT_FAILURE -> FAILURE
      else -> throw IllegalArgumentException("No such JobScheduler result type")
    }
  }
}

enum class NetworkType(val jobInfoVal: Int) {
  ANY(JobInfo.NETWORK_TYPE_ANY),
  METERED(JobInfo.NETWORK_TYPE_METERED),
  NONE(JobInfo.NETWORK_TYPE_NONE),
  NOT_ROAMING(JobInfo.NETWORK_TYPE_NOT_ROAMING),
  UNMETERED(JobInfo.NETWORK_TYPE_UNMETERED);
}

/**
 * Convenience extension to use the [NetworkType] enum instead of named ints.
 */
fun JobInfo.Builder.setRequiredNetworkType(type: NetworkType): JobInfo.Builder {
  return this.setRequiredNetworkType(type.jobInfoVal)
}

enum class BackoffPolicy(val jobInfoVal: Int) {
  LINEAR(JobInfo.BACKOFF_POLICY_LINEAR), EXPONENTIAL(JobInfo.BACKOFF_POLICY_EXPONENTIAL)
}

/**
 * Convenience extension to use the [BackoffPolicy] enum instead of named ints.
 */
fun JobInfo.Builder.setBackoffCriteria(initialBackoffMillis: Long,
                                       policy: BackoffPolicy): JobInfo.Builder {
  return this.setBackoffCriteria(initialBackoffMillis, policy.jobInfoVal)
}

/**
 * Schedule a job that waits until the specified update time, so it can do the update and launch the
 * periodic update job.
 */
fun scheduleInitialUpdateJob(): JobSchedulerResult {
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
  return JobSchedulerResult.from(Static.jobScheduler.schedule(builder.build()))
}

/**
 * Schedule the periodic update job.
 */
fun schedulePeriodicUpdateJob(): JobSchedulerResult {
  val builder = JobInfo.Builder(UPDATE_JOB_INFO_ID, updateComponent)
      .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.MINUTES.toMillis(100))
      .setBackoffCriteria(1000, BackoffPolicy.LINEAR)
      .setRequiredNetworkType(Prefs.autoUpdateReqNetType())
      .setPersisted(true)

  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    builder.setRequiresBatteryNotLow(true)
  }
  return JobSchedulerResult.from(Static.jobScheduler.schedule(builder.build()))
}

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
      if (schedulePeriodicUpdateJob() == JobSchedulerResult.FAILURE) {
        Log.e(TAG, "Failed to schedule repeating job")
      }
    }
    Log.i(TAG, "Update job started")
    coroutineJob = launch(CommonPool) {
      val updatedStories = mutableListOf<StoryModel>()
      val storyModels = applicationContext.database.getLocalStories().await()
      storyModels.forEach { model ->
        val updated = updateStory(fetchStoryModel(model.storyId).await()).await()
        if (updated) updatedStories.add(model)
      }
      Notifications.cancel(Notifications.Kind.UPDATING)
      Notifications.updatedStories(updatedStories.map { Pair(it.storyId, it.title) })
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
