package slak.fanfictionstories.utility

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.support.annotation.AnyThread
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withContext
import slak.fanfictionstories.data.Prefs

/**
 * Like [android.widget.TextView], but with faster, asynchronous layout creation, meant for large
 * blocks of static text.
 */
class FastTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
  /**
   * The backing string for the [textLayout]. Its content is immutable (use [setText] for that), but
   * markup can be freely added/removed. Is null unless [setText] was called.
   *
   * Warning: accessing this property *while* [setText] runs is unwise, due to the possibility of a
   * race condition.
   */
  var spannable: SpannableString? = null
    private set

  /**
   * The layout used to lay out and draw the text. Is null unless [setText] was called.
   *
   * Warning: accessing this property *while* [setText] runs is unwise, due to the possibility of a
   * race condition.
   * @see setText
   */
  var textLayout: StaticLayout? = null
    private set

  /**
   * A [TextPaint] instance is created with data fetched from preferences, but we keep a reference
   * to calculate some text properties.
   * @see lineHeight
   */
  private var textPaint: TextPaint? = null

  /**
   * Lays out the given [SpannableString], and creates [textLayout]. We use a coroutine so that
   * layout creation (the most expensive operation when there's lots of text) does not block the UI.
   */
  @AnyThread
  fun setText(s: SpannableString, theme: Resources.Theme) = launch(CommonPool) {
    if (width == 0) Log.e(TAG, "Creating StaticLayout with 0 width!")

    spannable = s
    textPaint = obtainTextPaint(theme)
    textLayout =
        StaticLayout.Builder.obtain(spannable!!, 0, spannable!!.length, textPaint!!, width)
            .setBreakStrategy(Prefs.textBreakStrategy())
            .build()

    withContext(UI) {
      this@FastTextView.layoutParams.height = textLayout!!.height
      this@FastTextView.requestLayout()
      this@FastTextView.invalidate()
    }
  }

  /** @see setText */
  @AnyThread
  fun setText(s: CharSequence, theme: Resources.Theme) = setText(SpannableString(s), theme)

  /**
   * Should be equivalent to [android.widget.TextView.getLineHeight].
   *
   * Formula is `Math.round(textPaint.getFontMetrics(null) * spacingMult + spacingAdd)`, but here
   * `spacingMult` is 1F and `spacingAdd` is 0 (defaults)
   */
  val lineHeight: Int get() = textPaint!!.getFontMetricsInt(null)

  /**
   * Serializes the current scroll state into a double, where the integer portion is the offset of
   * the first character of the first fully visible line, and the decimal portion is the percentage
   * of a line that is visible above it.
   *
   * Adapted from: https://stackoverflow.com/a/14387971
   * @returns the serialized double to be used with [scrollYFromScrollState]
   * @see scrollYFromScrollState
   */
  fun scrollStateFromScrollY(scrollY: Int): Double {
    val layout = textLayout!!
    val topPadding = -layout.topPadding
    return if (scrollY <= topPadding) {
      (topPadding - scrollY) / lineHeight.toDouble()
    } else {
      val line = layout.getLineForVertical(scrollY - 1) + 1
      val offset = layout.getLineStart(line)
      val above = layout.getLineTop(line) - scrollY
      offset + (above / lineHeight).toDouble()
    }
  }

  /**
   * Deserializes scroll state into a pixel amount to be scrolled.
   *
   * Adapted from: https://stackoverflow.com/a/14387971
   * @returns a y value to pass to [View.scrollTo]
   * @see scrollStateFromScrollY
   */
  fun scrollYFromScrollState(state: Double): Int {
    val layout = textLayout!!
    val offset = state.toInt()
    val above = ((state - offset) * lineHeight).toInt()
    val line = layout.getLineForOffset(offset)
    return (if (line == 0) -layout.topPadding else layout.getLineTop(line)) - above
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    canvas.save()
    textLayout?.draw(canvas) ?: Log.d(TAG, "Drawing view without layout")
    canvas.restore()
  }

  companion object {
    private const val TAG = "FastTextView"

    private var textPaintCache: TextPaint? = null
    private var fontNameCache: String? = null

    /**
     * Create a [TextPaint] instance for drawing the chapter text.
     * We try to return the same instance if its params haven't changed, because
     * [android.graphics.Paint] uses native calls, and these are (annoyingly) slow.
     */
    private fun obtainTextPaint(theme: Resources.Theme): TextPaint {
      val color = Prefs.textColor(theme)
      val size = TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_SP, Prefs.textSize(), Static.res.displayMetrics)
      val antialias = Prefs.textAntiAlias()
      val fontName = Prefs.textFontName()
      if (textPaintCache != null &&
          textPaintCache!!.textSize == size &&
          textPaintCache!!.color == color &&
          textPaintCache!!.isAntiAlias == antialias &&
          fontNameCache == fontName) {
        Log.v(TAG, "Reusing TextPaint instance $textPaintCache")
        return textPaintCache!!
      }
      val tp = TextPaint()
      tp.color = color
      tp.typeface = Prefs.textFont()
      tp.textSize = size
      tp.isAntiAlias = antialias
      fontNameCache = fontName
      textPaintCache = tp
      return tp
    }
  }
}