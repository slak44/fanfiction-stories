package slak.fanfictionstories.utility

import android.app.Activity
import android.app.AlertDialog
import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.content.Intent
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.IBinder
import android.support.annotation.ColorRes
import android.support.annotation.DimenRes
import android.support.annotation.StringRes
import android.support.design.widget.Snackbar
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.style.ReplacementSpan
import android.util.Log
import android.util.SparseBooleanArray
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.sync.Mutex
import org.jetbrains.anko.intentFor
import org.jetbrains.anko.startActivity
import slak.fanfictionstories.Notifications
import slak.fanfictionstories.R
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Create an error dialog with a title, message and a dismiss button.
 * @see AlertDialog
 */
fun errorDialog(title: String, msg: String) = launch(UI) {
  AlertDialog.Builder(Static.currentCtx)
      .setTitle(title)
      .setMessage(msg)
      .setPositiveButton(R.string.got_it) { dialogInterface, _ ->
        // User acknowledged error
        dialogInterface.dismiss()
      }.create().show()
}

/** Same as [errorDialog], but with [StringRes] texts. */
fun errorDialog(@StringRes title: Int, @StringRes msg: Int) = errorDialog(str(title), str(msg))

private const val NETWORK_WAIT_DELAY_MS = 500L
private const val NET_TAG = "waitForNetwork"

/**
 * Suspends until there is an active connection.
 *
 * Shows notifications about connection status.
 */
fun waitForNetwork() = async2(CommonPool) {
  while (true) {
    val activeNetwork = Static.cm.activeNetworkInfo
    if (activeNetwork == null || !activeNetwork.isConnected) {
      // No connection; wait
      Notifications.NETWORK.show(Notifications.defaultIntent(), R.string.waiting_for_connection)
      Log.w(NET_TAG, "No connection")
      delay(NETWORK_WAIT_DELAY_MS, TimeUnit.MILLISECONDS)
    } else {
      // We're connected!
      Notifications.NETWORK.cancel()
      Log.v(NET_TAG, "We have a connection")
      break
    }
  }
}

private const val RATE_LIMIT_MS = 300L
private const val URL_TAG = "patientlyFetchURL"

/**
 * Fetches the resource at the specified url, patiently.
 *
 * Waits for the network using [waitForNetwork], then waits for the rate limit [RATE_LIMIT_MS].
 *
 * If the download fails, call the [onError] callback, wait for the rate limit again, and then call
 * this function recursively.
 */
fun patientlyFetchURL(url: String,
                      onError: (t: Throwable) -> Unit): Deferred<String> = async2(CommonPool) {
  waitForNetwork().await()
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

/** Emulates android:iconTint. Must be called in onPrepareOptionsMenu for each icon. */
fun MenuItem.iconTint(@ColorRes colorRes: Int, theme: Resources.Theme) {
  val color = Static.res.getColor(colorRes, theme)
  val drawable = this.icon
  drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
  this.icon = drawable
}

enum class Direction { LEFT, TOP, RIGHT, BOTTOM }

/** Tints a drawable. No-op if the specified drawable is null. */
fun TextView.drawableTint(@ColorRes colorRes: Int, theme: Resources.Theme, which: Direction) {
  val color = Static.res.getColor(colorRes, theme)
  val drawable = compoundDrawables[which.ordinal] ?: return
  drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
  when (which) {
    Direction.LEFT -> setCompoundDrawablesWithIntrinsicBounds(drawable, compoundDrawables[1],
        compoundDrawables[2], compoundDrawables[3])
    Direction.TOP -> setCompoundDrawablesWithIntrinsicBounds(compoundDrawables[0], drawable,
        compoundDrawables[2], compoundDrawables[3])
    Direction.RIGHT -> setCompoundDrawablesWithIntrinsicBounds(compoundDrawables[0],
        compoundDrawables[1], drawable, compoundDrawables[3])
    Direction.BOTTOM -> setCompoundDrawablesWithIntrinsicBounds(compoundDrawables[0],
        compoundDrawables[1], compoundDrawables[2], drawable)
  }
}

/** A [ReplacementSpan] that tries to emulate a <hr> element. */
class HrSpan(private val heightPx: Int, private val width: Int) : ReplacementSpan() {
  companion object {
    private const val PLACEHOLDER = "######HRPLACEHOLDERHRPLACEHOLDERHRPLACEHOLDER######"
    val tagHandlerFactory = { widthPx: Int ->
      Html.TagHandler { opening, tag, output, _ ->
        if (tag == "hr") {
          if (opening) output.insert(output.length, PLACEHOLDER)
          else output.setSpan(HrSpan(1, widthPx),
              output.length - PLACEHOLDER.length, output.length, 0)
        }
      }
    }
  }

  override fun getSize(p0: Paint?, p1: CharSequence?, p2: Int, p3: Int,
                       p4: Paint.FontMetricsInt?): Int = 0

  override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int,
                    y: Int, bottom: Int, paint: Paint) {
    canvas.drawRect(x, top.toFloat(), (y + width).toFloat(), (top + heightPx).toFloat(), paint)
  }
}

/**
 * Pretty wrapper for [AdapterView.OnItemSelectedListener] in the common case where only
 * [AdapterView.OnItemSelectedListener.onItemSelected] needs to be overridden.
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

/**
 * Provide infinite scrolling for a [RecyclerView] with a [LinearLayoutManager], using the provided
 * function to add more content when at the end. Attaches a [RecyclerView.OnScrollListener] to the
 * recycler.
 */
fun infinitePageScroll(recycler: RecyclerView, lm: LinearLayoutManager, addPage: () -> Job) {
  recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
    private val addPageLock = Mutex()
    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
      // We only want scroll downs
      if (dy <= 0) return
      val visibleItemCount = lm.childCount
      val totalItemCount = lm.itemCount
      val pastVisibleItems = lm.findFirstVisibleItemPosition()
      if ((visibleItemCount + pastVisibleItems) >= totalItemCount - 1) {
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

/**
 * Shows a snack with an undo button. If the undo button wasn't pressed, execute the provided
 * action in a coroutine.
 * @param view snackbar target
 */
fun undoableAction(view: View, snackText: String,
                   onUndo: (View) -> Unit = {}, action: suspend () -> Unit): Snackbar {
  val snack = Snackbar.make(view, snackText, Snackbar.LENGTH_LONG)
  snack.setAction(R.string.undo, onUndo)
  snack.addCallback(object : Snackbar.Callback() {
    override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
      // The user clicking undo does not trigger this
      if (event != DISMISS_EVENT_ACTION) launch(CommonPool) { action() }
    }
  })
  snack.show()
  return snack
}

/** @see undoableAction */
fun undoableAction(view: View, @StringRes snackText: Int,
                   onUndo: (View) -> Unit = {}, action: suspend () -> Unit): Snackbar {
  return undoableAction(view, str(snackText), onUndo, action)
}

/**
 * Enables property access syntax for [SparseBooleanArray]. Forwards to [SparseBooleanArray.put].
 */
operator fun SparseBooleanArray.set(key: Int, value: Boolean) = put(key, value)

/** Sugar for [Static]'s [Resources.getString]. */
fun str(@StringRes i: Int): String = Static.res.getString(i)

/** Sugar for [Static]'s [Resources.getString] with a format string. */
fun str(@StringRes i: Int, vararg format: Any?): String = Static.res.getString(i, *format)

/** Sugar for [Resources.getDimensionPixelSize]. */
fun Resources.px(@DimenRes d: Int): Int = getDimensionPixelSize(d)

/** Sugar for [MarginLayoutParams.setMargins]. */
fun MarginLayoutParams.margins(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
  setMargins(left, top, right, bottom)
}

/** Call [org.jetbrains.anko.startActivity] from anywhere using [Static.currentActivity]. */
inline fun <reified T : Activity> startActivity(vararg params: Pair<String, Any?>) {
  Static.currentActivity!!.startActivity<T>(*params)
}

/** Call [org.jetbrains.anko.intentFor] from anywhere using [Static.currentCtx]. */
inline fun <reified T : Any> intentFor(vararg params: Pair<String, Any?>): Intent {
  return Static.currentCtx.intentFor<T>(*params)
}

/** Convenience access property for non-nullable types. */
var <T> MutableLiveData<T>.it: T
  get() = value!!
  set(newVal) {
    value = newVal
  }

/** Convenience access property for non-nullable types. */
val <T> LiveData<T>.it: T
  get() = value!!

/** Sugar over [LiveData.observe] for non-nullable types. */
fun <T> LiveData<T>.observe(owner: LifecycleOwner, observer: (T) -> Unit) {
  observe(owner, android.arch.lifecycle.Observer { observer(it!!) })
}

/**
 * Iterate over the lines of a text layout (each on-screen line).
 * @param block code to execute on each line. Return true to break the iteration.
 */
fun Layout.iterateDisplayedLines(block: (lineIdx: Int, lineRange: IntRange) -> Boolean) {
  var lineIdx = 0
  var lineStart = 0
  while (lineIdx < lineCount) {
    val lineEnd = getLineEnd(lineIdx)
    val `break` = block(lineIdx, lineStart..lineEnd)
    if (`break`) break
    lineStart = lineEnd
    lineIdx++
  }
}

/** Remove all spans from a [Spannable] depending on their type. */
fun <T> Spannable.removeAllSpans(kind: Class<T>) {
  getSpans(0, length, kind).forEach { removeSpan(it) }
}

/** Hides the on-screen keyboard. */
fun hideSoftKeyboard(windowToken: IBinder) {
  Static.imm.hideSoftInputFromWindow(windowToken, 0)
}
