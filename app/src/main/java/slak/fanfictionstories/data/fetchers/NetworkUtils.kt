package slak.fanfictionstories.data.fetchers

import android.util.Log
import kotlinx.coroutines.experimental.*
import slak.fanfictionstories.Notifications
import slak.fanfictionstories.R
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.async2
import java.net.URL
import java.util.concurrent.TimeUnit

private const val NETWORK_WAIT_DELAY_MS = 500L
private const val NET_TAG = "waitForNetwork"

/**
 * Suspends until there is an active connection.
 *
 * Shows notifications about connection status.
 */
private tailrec suspend fun waitForNetwork() {
  val activeNetwork = Static.cm.activeNetworkInfo
  return if (activeNetwork == null || !activeNetwork.isConnected) {
    // No connection; wait
    Notifications.NETWORK.show(Notifications.defaultIntent(), R.string.waiting_for_connection)
    Log.w(NET_TAG, "No connection")
    delay(NETWORK_WAIT_DELAY_MS, TimeUnit.MILLISECONDS)
    waitForNetwork()
  } else {
    // We're connected!
    Notifications.NETWORK.cancel()
    Log.v(NET_TAG, "We have a connection")
    Unit
  }
}

private const val RATE_LIMIT_MS = 300L
private const val URL_TAG = "patientlyFetchURL"
private val networkContext = newSingleThreadContext("NetworkThread")

/**
 * Fetches the resource at the specified url, patiently. This function's calls run on the same thread.
 *
 * Waits for the network using [waitForNetwork], then waits for the rate limit [RATE_LIMIT_MS].
 *
 * If the download fails, call the [onError] callback, wait for the rate limit again, and then call
 * this function recursively.
 */
fun CoroutineScope.patientlyFetchURL(url: String,
                                     onError: (t: Throwable) -> Unit): Deferred<String> = async2(networkContext) {
  waitForNetwork()
  delay(RATE_LIMIT_MS)
  return@async2 try {
    val text = URL(url).readText()
    Notifications.ERROR.cancel()
    text
  } catch (t: Throwable) {
    // Something happened; retry
    onError(t)
    Log.e(URL_TAG, "Failed to fetch url ($url)", t)
    patientlyFetchURL(url, onError).await()
  }
}
