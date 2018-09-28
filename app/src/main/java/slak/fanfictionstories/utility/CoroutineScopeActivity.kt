package slak.fanfictionstories.utility

import kotlinx.coroutines.experimental.CoroutineName
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.Job
import kotlin.coroutines.experimental.CoroutineContext

open class CoroutineScopeActivity : ActivityWithStatic(), CoroutineScope {
  override val coroutineContext: CoroutineContext
    get() = parentJob + CoroutineName(localClassName) + Dispatchers.Default

  private val parentJob = Job()

  override fun onDestroy() {
    super.onDestroy()
    parentJob.cancel()
  }
}
