package slak.fanfictionstories.utility

import android.app.job.JobInfo
import android.app.job.JobScheduler

/** Wraps some ints in [JobScheduler]. */
enum class ScheduleResult {
  SUCCESS, FAILURE;

  companion object {
    fun from(intResult: Int): ScheduleResult = when (intResult) {
      JobScheduler.RESULT_SUCCESS -> SUCCESS
      JobScheduler.RESULT_FAILURE -> FAILURE
      else -> throw IllegalArgumentException("No such JobScheduler result type")
    }
  }
}

/** Wraps some ints in [JobInfo]. */
enum class NetworkType(val jobInfoVal: Int) {
  ANY(JobInfo.NETWORK_TYPE_ANY),
  METERED(JobInfo.NETWORK_TYPE_METERED),
  NONE(JobInfo.NETWORK_TYPE_NONE),
  NOT_ROAMING(JobInfo.NETWORK_TYPE_NOT_ROAMING),
  UNMETERED(JobInfo.NETWORK_TYPE_UNMETERED);
}

/** Convenience extension to use the [NetworkType] enum instead of named ints. */
fun JobInfo.Builder.setRequiredNetworkType(type: NetworkType): JobInfo.Builder {
  return this.setRequiredNetworkType(type.jobInfoVal)
}

/** Wraps some ints in [JobInfo]. */
enum class BackoffPolicy(val jobInfoVal: Int) {
  LINEAR(JobInfo.BACKOFF_POLICY_LINEAR), EXPONENTIAL(JobInfo.BACKOFF_POLICY_EXPONENTIAL)
}

/** Convenience extension to use the [BackoffPolicy] enum instead of named ints. */
fun JobInfo.Builder.setBackoffCriteria(initialBackoffMillis: Long,
                                       policy: BackoffPolicy): JobInfo.Builder {
  return this.setBackoffCriteria(initialBackoffMillis, policy.jobInfoVal)
}
