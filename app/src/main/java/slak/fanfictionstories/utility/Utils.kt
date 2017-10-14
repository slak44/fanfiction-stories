package slak.fanfictionstories.utility

import android.app.AlertDialog
import android.content.Context
import android.support.annotation.StringRes
import android.util.Log
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import slak.fanfictionstories.activities.MainActivity
import slak.fanfictionstories.R
import slak.fanfictionstories.fetchers.Fetcher
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Wraps [async], except it also rethrows exceptions synchronously.
 */
fun <T> async2(
    context: CoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T
): Deferred<T> {
  val c = async(context, start, block)
  c.invokeOnCompletion { e -> if (e != null) throw e }
  return c
}

fun errorDialog(ctx: Context, @StringRes title: Int, @StringRes msg: Int) {
  errorDialog(ctx, ctx.resources.getString(title), ctx.resources.getString(msg))
}

fun errorDialog(ctx: Context, title: String, msg: String) = launch(UI) {
  AlertDialog.Builder(ctx)
      .setTitle(title)
      .setMessage(msg)
      .setPositiveButton(R.string.got_it, { dialogInterface, _ ->
        // User acknowledged error
        dialogInterface.dismiss()
      }).create().show()
}

/**
 * Suspends until there is an active connection.
 *
 * Shows notifications about connection status.
 */
fun waitForNetwork(n: Notifications) = async2(CommonPool) {
  while (true) {
    val activeNetwork = MainActivity.cm.activeNetworkInfo
    // FIXME figure out network status even when app is not focused
    if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting) {
      // No connection; wait
      n.show(MainActivity.res.getString(R.string.waiting_for_connection))
      Log.e("waitForNetwork", "No connection")
      delay(Fetcher.CONNECTION_MISSING_DELAY_SECONDS, TimeUnit.SECONDS)
    } else if (activeNetwork.isConnectedOrConnecting && !activeNetwork.isConnected) {
      // We're connecting; wait
      n.show(MainActivity.res.getString(R.string.waiting_for_connection))
      Log.e("waitForNetwork", "Connecting...")
      delay(Fetcher.CONNECTION_WAIT_DELAY_SECONDS, TimeUnit.SECONDS)
    } else {
      break
    }
  }
}