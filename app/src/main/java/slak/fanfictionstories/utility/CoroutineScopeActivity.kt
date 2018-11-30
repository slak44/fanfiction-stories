package slak.fanfictionstories.utility

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

open class CoroutineScopeActivity : ActivityWithStatic(), CoroutineScope {
  override val coroutineContext: CoroutineContext
    get() = parentJob + CoroutineName(localClassName) + Dispatchers.Default

  private val parentJob = Job()

  override fun onDestroy() {
    super.onDestroy()
    parentJob.cancel()
  }
}
