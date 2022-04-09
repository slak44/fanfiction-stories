package slak.fanfictionstories.data.fetchers

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities.*
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import slak.fanfictionstories.Notifications
import slak.fanfictionstories.R
import slak.fanfictionstories.utility.Static
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val NET_TAG = "waitForNetwork"

/**
 * Suspends until there is an active connection.
 *
 * Shows notifications about connection status.
 */
suspend fun waitForNetwork() = suspendCoroutine<Unit> { cont ->
  val req = NetworkRequest.Builder()
      .addTransportType(TRANSPORT_WIFI)
      .addTransportType(TRANSPORT_CELLULAR)
      .addTransportType(TRANSPORT_ETHERNET)
      .build()
  val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      Notifications.NETWORK.cancel()
      Log.v(NET_TAG, "We have a connection")
      Static.cm.unregisterNetworkCallback(this)
      cont.resume(Unit)
    }

    override fun onUnavailable() {
      Notifications.NETWORK.show(Notifications.defaultIntent(), R.string.waiting_for_connection)
      Log.w(NET_TAG, "No connection")
    }
  }
  Static.cm.registerNetworkCallback(req, callback)
}

const val RATE_LIMIT_MS = 1500L
const val URL_TAG = "patientlyFetchURL"
const val RETRY_COUNT = 5

val networkContext = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

private var currentRetries = RETRY_COUNT

/**
 * Fetches the resource at the specified url, patiently. This function's calls run on the same thread.
 *
 * Waits for the network using [waitForNetwork], then waits for the rate limit [RATE_LIMIT_MS].
 *
 * If the download fails, call the [onError] callback, wait for the rate limit again, and then call this function
 * recursively. As a result, [onError] is called for every failed download retry.
 */
suspend fun patientlyFetchURLBytes(
    url: String,
    onError: (t: Throwable) -> Unit
): ByteArray? = withContext(networkContext) {
  waitForNetwork()
  delay(RATE_LIMIT_MS)
  // We do want to block this thread
  @Suppress("BlockingMethodInNonBlockingContext")
  return@withContext try {
    val conn = URL(url).openConnection() as HttpsURLConnection
    if (conn.errorStream != null) {
      Log.d(URL_TAG, conn.responseCode.toString())
      Log.e(URL_TAG, conn.errorStream.readBytes().decodeToString())
    }
    val text = conn.inputStream.readBytes()
    Notifications.ERROR.cancel()
    text
  } catch (t: Throwable) {
    // Something happened; retry
    Log.e(URL_TAG, "Failed to fetch url ($url)", t)
    onError(t)

    if (currentRetries == 0) {
      Log.e(URL_TAG, "Retry count $RETRY_COUNT exceeded for $url")
      currentRetries = RETRY_COUNT
      return@withContext null
    } else {
      currentRetries--
    }

    patientlyFetchURLBytes(url, onError)
  }
}
