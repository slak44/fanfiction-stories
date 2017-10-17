package slak.fanfictionstories.utility

import android.app.AlertDialog
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.support.annotation.ColorRes
import android.support.annotation.StringRes
import android.text.style.ReplacementSpan
import android.util.Log
import android.view.MenuItem
import android.widget.TextView
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import slak.fanfictionstories.R
import slak.fanfictionstories.activities.MainActivity
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

/**
 * Emulates android:iconTint. Must be called in onPrepareOptionsMenu for each icon.
 */
fun MenuItem.iconTint(@ColorRes colorRes: Int, theme: Resources.Theme) {
  val color = MainActivity.res.getColor(colorRes, theme)
  val drawable = this.icon
  drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
  this.icon = drawable
}

enum class Direction {
  LEFT, TOP, RIGHT, BOTTOM
}

/**
 * Tints a drawable. No-op if the specified drawable is null.
 */
fun TextView.drawableTint(@ColorRes colorRes: Int, theme: Resources.Theme, which: Direction) {
  val color = MainActivity.res.getColor(colorRes, theme)
  val drawable = this.compoundDrawables[which.ordinal] ?: return
  drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
  when (which) {
    Direction.LEFT -> this.setCompoundDrawablesWithIntrinsicBounds(drawable,
        this.compoundDrawables[1], this.compoundDrawables[2], this.compoundDrawables[3])
    Direction.TOP -> this.setCompoundDrawablesWithIntrinsicBounds(this.compoundDrawables[0],
        drawable, this.compoundDrawables[2], this.compoundDrawables[3])
    Direction.RIGHT -> this.setCompoundDrawablesWithIntrinsicBounds(this.compoundDrawables[0],
        this.compoundDrawables[1], drawable, this.compoundDrawables[3])
    Direction.BOTTOM -> this.setCompoundDrawablesWithIntrinsicBounds(this.compoundDrawables[0],
        this.compoundDrawables[1], this.compoundDrawables[2], drawable)
  }
}

class HrSpan(private val heightPx: Int, private val width: Int) : ReplacementSpan() {
  override fun getSize(p0: Paint?, p1: CharSequence?, p2: Int, p3: Int,
                       p4: Paint.FontMetricsInt?): Int {
    return 0
  }

  override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int,
                    y: Int, bottom: Int, paint: Paint) {
    canvas.drawRect(x, top.toFloat(), (y + width).toFloat(), (top + heightPx).toFloat(), paint)
  }
}