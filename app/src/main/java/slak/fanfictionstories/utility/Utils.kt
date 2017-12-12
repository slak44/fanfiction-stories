package slak.fanfictionstories.utility

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.Parcelable
import android.support.annotation.ColorRes
import android.support.annotation.StringRes
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import kotlinx.android.parcel.Parcelize
import kotlinx.android.parcel.RawValue
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import slak.fanfictionstories.R
import slak.fanfictionstories.fetchers.Fetcher
import slak.fanfictionstories.fetchers.Fetcher.DOWNLOAD_MUTEX
import slak.fanfictionstories.fetchers.Fetcher.RATE_LIMIT_MILLISECONDS
import java.net.URL
import java.util.*
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

/**
 * Create an error dialog with a title, message and a dismiss button.
 * @see AlertDialog
 */
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
 * Same as [errorDialog], but with [StringRes] texts.
 */
fun errorDialog(ctx: Context, @StringRes title: Int, @StringRes msg: Int) {
  errorDialog(ctx, ctx.resources.getString(title), ctx.resources.getString(msg))
}

/**
 * Suspends until there is an active connection.
 *
 * Shows notifications about connection status.
 */
fun waitForNetwork(n: Notifications) = async2(CommonPool) {
  while (true) {
    val activeNetwork = Static.cm.activeNetworkInfo
    // FIXME figure out network status even when app is not focused
    if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting) {
      // No connection; wait
      n.show(Static.res.getString(R.string.waiting_for_connection))
      Log.e("waitForNetwork", "No connection")
      delay(Fetcher.CONNECTION_MISSING_DELAY_SECONDS, TimeUnit.SECONDS)
    } else if (activeNetwork.isConnectedOrConnecting && !activeNetwork.isConnected) {
      // We're connecting; wait
      n.show(Static.res.getString(R.string.waiting_for_connection))
      Log.e("waitForNetwork", "Connecting...")
      delay(Fetcher.CONNECTION_WAIT_DELAY_SECONDS, TimeUnit.SECONDS)
    } else {
      break
    }
  }
}

/**
 * Fetches the resource at the specified url, patiently.
 *
 * Waits for the download lock [Fetcher.DOWNLOAD_MUTEX].
 * Waits for the network using [waitForNetwork].
 * Waits for the rate limit [Fetcher.RATE_LIMIT_MILLISECONDS].
 *
 * If the download fails, call the error callback, wait for the rate limit again, and then call this
 * function recursively.
 */
fun patientlyFetchURL(url: String, n: Notifications,
                      onError: (t: Throwable) -> Unit): Deferred<String> =
    async2(CommonPool) { DOWNLOAD_MUTEX.withLock {
  waitForNetwork(n).await()
  delay(RATE_LIMIT_MILLISECONDS)
  try {
    return@withLock URL(url).readText()
  } catch (t: Throwable) {
    // Something happened; retry
    onError(t)
    delay(RATE_LIMIT_MILLISECONDS)
    return@withLock patientlyFetchURL(url, n, onError).await()
  }
} }

/**
 * Emulates android:iconTint. Must be called in onPrepareOptionsMenu for each icon.
 */
fun MenuItem.iconTint(@ColorRes colorRes: Int, theme: Resources.Theme) {
  val color = Static.res.getColor(colorRes, theme)
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
  val color = Static.res.getColor(colorRes, theme)
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

/**
 * Pretty wrapper for [AdapterView.OnItemSelectedListener] in the common case where only
 * onItemSelected needs to be overridden
 */
fun Spinner.onSelect(block: (spinner: Spinner, position: Int) -> Unit) {
  this.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
    override fun onNothingSelected(parent: AdapterView<*>?) {}
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
      block(this@onSelect, position)
    }
  }
}

/**
 * Set [Spinner] entries using [android.R.layout.simple_spinner_dropdown_item] and a [ArrayAdapter].
 */
fun <T> Spinner.setEntries(entries: List<T>) {
  val adapter = ArrayAdapter<T>(context, android.R.layout.simple_spinner_dropdown_item)
  adapter.addAll(entries)
  this.adapter = adapter
}

private val suffixes = TreeMap(mapOf(
    1_000L to "K",
    1_000_000L to "M",
    1_000_000_000L to "G",
    1_000_000_000_000L to "T",
    1_000_000_000_000_000L to "P",
    1_000_000_000_000_000_000L to "E"
))
/**
 * Truncate a number and append the respective suffix.
 *
 * Example: 12345 -> 12K
 * Original Java Source: https://stackoverflow.com/a/30661479/3329467
 */
fun autoSuffixNumber(value: Long): String {
  // Long.MIN_VALUE == -Long.MIN_VALUE so we need an adjustment here
  if (value == Long.MIN_VALUE) return autoSuffixNumber(Long.MIN_VALUE + 1)
  if (value < 0) return "-" + autoSuffixNumber(-value)
  if (value < 1000) return value.toString() // deal with easy case

  val e = suffixes.floorEntry(value)
  val divideBy = e.key
  val suffix = e.value

  val truncated = value / (divideBy!! / 10) // the number part of the output times 10
  val hasDecimal = truncated < 100 && truncated / 10.0 != (truncated / 10).toDouble()
  return if (hasDecimal) "${truncated / 10.0}$suffix" else "${truncated / 10}$suffix"
}

/**
 * @see autoSuffixNumber(Long)
 */
fun autoSuffixNumber(value: Int): String = autoSuffixNumber(value.toLong())

/**
 * Like [usePrefs], but uses [SharedPreferences.Editor.commit] instead of apply.
 * @see usePrefs
 */
@SuppressLint("ApplySharedPref")
fun usePrefsImmediate(block: (SharedPreferences.Editor) -> Unit) {
  val editor = Static.prefs.edit()
  block(editor)
  editor.commit()
}

/**
 * Wraps [SharedPreferences]'s edit-change-apply boilerplate.
 */
fun usePrefs(block: (SharedPreferences.Editor) -> Unit) {
  val editor = Static.prefs.edit()
  block(editor)
  editor.apply()
}

@Parcelize @SuppressLint("ParcelCreator")
data class EitherWrapper<out T1, out T2>(val l: @RawValue T1?, val r: @RawValue T2?) : Parcelable

/**
 * Shorthand for `if (obj == null) Optional.empty() else Optional.of(obj)`
 * @see Optional.of
 * @see Optional.empty
 */
fun <T> T?.opt(): Optional<T> = if (this == null) Optional.empty() else Optional.of(this)

/**
 * A prettier, `inline` version of [Optional.orElseGet].
 */
inline fun <T> Optional<T>.orElse(block: () -> T): T = if (isPresent) this.get() else block()

/**
 * `inline` version of [Optional.ifPresent]. Unfortunately, since the signatures are similar enough,
 * this version must be named `ifPresent2`.
 */
inline fun <T> Optional<T>.ifPresent2(block: (T) -> Unit) {
  if (isPresent) block(this.get())
}

/**
 * Sugar for the default Optional.orElseThrow.
 */
fun <T> Optional<T>.orElseThrow(th: Throwable): T = if (isPresent) this.get() else throw th

/**
 * Provide infinite scrolling for a [RecyclerView] with a [LinearLayoutManager], using the provided
 * function to add more content when at the end. Attaches a [RecyclerView.OnScrollListener] to the
 * recycler.
 */
fun infinitePageScroll(recycler: RecyclerView, lm: LinearLayoutManager, addPage: () -> Job) {
  recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
    private val addPageLock = Mutex()
    override fun onScrolled(recyclerView: RecyclerView?, dx: Int, dy: Int) {
      // We only want scroll downs
      if (dy <= 0) return
      val visibleItemCount = lm.childCount
      val totalItemCount = lm.itemCount
      val pastVisibleItems = lm.findFirstVisibleItemPosition()
      if ((visibleItemCount + pastVisibleItems) >= totalItemCount) {
        // There are lots of scroll events, so use a lock to make sure we don't overdo it
        if (addPageLock.isLocked) return
        launch(CommonPool) {
          addPageLock.lock()
          addPage().join()
          addPageLock.unlock()
        }
      }
    }
  })
}
