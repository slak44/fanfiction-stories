package slak.fanfictionstories.utility

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.support.v4.view.ViewCompat
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI

/**
 * Like [android.widget.TextView], but with faster, asynchronous layout creation, meant for large
 * blocks of static text.
 */
class FastTextView : View {
  companion object {
    private const val TAG = "FastTextView"
  }

  constructor(ctx: Context) : super(ctx)
  constructor(ctx: Context, set: AttributeSet) : super(ctx, set)
  constructor(ctx: Context, set: AttributeSet, defStyle: Int) : super(ctx, set, defStyle)

  /**
   * This view's layout. Is null unless [setText] was called.
   *
   * Warning: accessing this property while [setText] runs is unwise, due to the possibility of a
   * race condition.
   * @see setText
   * @see setOnTextChangeListener
   */
  var staticLayout: StaticLayout? = null
    private set
  private var textPaint: TextPaint? = null

  private var onTextChange: (Spanned) -> Unit = {}
  /**
   * @param listener called when the text changes, more precisely, when the [staticLayout] changes
   */
  fun setOnTextChangeListener(listener: (Spanned) -> Unit) {
    onTextChange = listener
  }

  /**
   * Lays out the given [Spanned], and creates [staticLayout]. We use [async2] so that layout
   * creation (the most expensive operation when there's lots of text) does not block the UI.
   */
  fun setText(s: Spanned, theme: Resources.Theme) = async2(CommonPool) {
    if (!ViewCompat.isLaidOut(this@FastTextView)) {
      Log.w(TAG, "Forcing layout, setText was called before we were laid out")
      async2(UI) {
        this@FastTextView.forceLayout()
      }.await()
    }

    textPaint = Prefs.textPaint(theme)

    staticLayout = StaticLayout(s, textPaint,
        width, Layout.Alignment.ALIGN_NORMAL, 1F, 0F, false)

    async2(UI) {
      this@FastTextView.layoutParams.height = staticLayout!!.height
      this@FastTextView.invalidate()
    }.await()

    onTextChange(s)
  }

  /**
   * Should be equivalent to [android.widget.TextView.getLineHeight].
   *
   * Formula is `Math.round(textPaint.getFontMetrics(null) * spacingMult + spacingAdd)`, but here
   * `spacingMult` is 1F and `spacingAdd` is 0
   */
  val lineHeight: Int get() = textPaint!!.getFontMetricsInt(null)

  /**
   * Serializes the current scroll state into a double, where the integer portion is the offset of
   * the first character of the first fully visible line, and the decimal portion is the percentage
   * of a line that is visible above it.
   *
   * Adapted from: https://stackoverflow.com/a/14387971
   * @returns the serialized double to be used with [scrollYFromScrollState]
   */
  fun scrollStateFromScrollY(scrollY: Int): Double {
    val layout = staticLayout!!
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
    val layout = staticLayout!!
    val offset = state.toInt()
    val above = ((state - offset) * lineHeight).toInt()
    val line = layout.getLineForOffset(offset)
    return (if (line == 0) -layout.topPadding else layout.getLineTop(line)) - above
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    canvas.save()
    staticLayout?.draw(canvas) ?: Log.d(TAG, "Drawing view without layout")
    canvas.restore()
  }
}