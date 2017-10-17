package slak.fanfictionstories.activities

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Bundle
import android.support.annotation.ColorRes
import android.support.v4.view.ViewCompat
import android.support.v4.widget.NestedScrollView
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.text.*
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.TextView
import kotlinx.android.synthetic.main.activity_story_reader.*
import kotlinx.android.synthetic.main.content_story_reader.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import org.jetbrains.anko.db.LongParser
import org.jetbrains.anko.db.parseSingle
import org.jetbrains.anko.db.select
import org.jetbrains.anko.db.update
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.storyDir
import slak.fanfictionstories.utility.*
import java.io.File

private class FastTextView : View {
  companion object {
    const val TAG = "FastTextView"
  }

  constructor(ctx: Context) : super(ctx)
  constructor(ctx: Context, set: AttributeSet) : super(ctx, set)
  constructor(ctx: Context, set: AttributeSet, defStyle: Int) : super(ctx, set, defStyle)

  private var staticLayout: StaticLayout? = null

  fun setText(s: Spanned) = async2(CommonPool) {
    // FIXME hardcoded textpaint
    val tp = TextPaint()
    tp.color = resources.getColor(android.R.color.secondary_text_dark)
    tp.typeface = Typeface.DEFAULT
    tp.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14F, resources.displayMetrics)
    tp.isAntiAlias = true

    if (!ViewCompat.isLaidOut(this@FastTextView)) {
      Log.w(TAG, "forcing layout, setText was called before we were laid out")
      async2(UI) {
        this@FastTextView.forceLayout()
      }.await()
    }

    staticLayout = StaticLayout(s, tp,
        width, Layout.Alignment.ALIGN_NORMAL, 1F, 0F, false)

    async2(UI) {
      this@FastTextView.layoutParams.height = staticLayout!!.height
      this@FastTextView.invalidate()
    }.await()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    canvas.save()
    staticLayout?.draw(canvas)
    canvas.restore()
  }
}

class StoryReaderActivity : AppCompatActivity() {

  companion object {
    const val INTENT_STORY_MODEL = "bundle"
    private val PLACEHOLDER = "######HRPLACEHOLDERHRPLACEHOLDERHRPLACEHOLDER######"
    private val tagHandlerFactory = { widthPx: Int -> Html.TagHandler { opening, tag, output, _ ->
      if (tag == "hr") {
        if (opening) output.insert(output.length, PLACEHOLDER)
        else output.setSpan(HrSpan(1, widthPx),
            output.length - PLACEHOLDER.length, output.length, 0)
      }
    } }
  }

  private lateinit var model: StoryModel
  private var currentChapter: Int = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_story_reader)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    // FIXME set app to resume to this

    model = intent.getParcelableExtra(INTENT_STORY_MODEL)

    title = model.title
    currentChapter = if (model.currentChapter == 0) 1 else model.currentChapter
    initText(currentChapter)

    prevChapterBtn.setOnClickListener {
      currentChapter--
      initText(currentChapter)
    }
    nextChapterBtn.setOnClickListener {
      currentChapter++
      initText(currentChapter)
    }
    selectChapterBtn.setOnClickListener { showChapterSelectDialog() }
  }

  private fun showChapterSelectDialog() {
    AlertDialog.Builder(this@StoryReaderActivity)
        .setTitle(R.string.select_chapter)
        .setItems(model.chapterTitles.toTypedArray(), { dialog, which: Int ->
          dialog.dismiss()
          // This means 'go to same chapter', so do nothing
          if (currentChapter == which + 1) return@setItems
          currentChapter = which + 1
          initText(currentChapter)
        }).show()
  }

  private fun initText(chapterToRead: Int) = launch(CommonPool) {
    val text: String = readChapter(model.storyIdRaw, chapterToRead).await()

    async2(UI) {
      chapterText.forceLayout()
    }.await()

    val html =
        Html.fromHtml(
            text, Html.FROM_HTML_MODE_LEGACY, null, tagHandlerFactory(chapterText.width))

    chapterText.setText(html).await()

    updateUiAfterFetchingText(chapterToRead)
    database.use {
      update("stories", "currentChapter" to chapterToRead)
          .whereSimple("storyId = ?", model.storyIdRaw.toString()).exec()
    }
  }

  private fun getColorFor(textView: TextView): Int {
    return if (textView.isEnabled) android.R.color.white
    else android.R.color.tertiary_text_light
  }

  private fun updateUiAfterFetchingText(chapterToRead: Int) = launch(UI) {
    // Disable buttons if there is nowhere for them to go
    prevChapterBtn.isEnabled = chapterToRead != 1
    nextChapterBtn.isEnabled = chapterToRead != model.chapterCount
    selectChapterBtn.isEnabled = model.chapterCount > 1

    // Tint button icons dark if the buttons are disabled, white if not
    prevChapterBtn.drawableTint(getColorFor(prevChapterBtn), theme, Direction.LEFT)
    nextChapterBtn.drawableTint(getColorFor(nextChapterBtn), theme, Direction.RIGHT)
    selectChapterBtn.drawableTint(getColorFor(selectChapterBtn), theme, Direction.LEFT)

    // Handle the next/prev button states in the appbar
    invalidateOptionsMenu()

    // Set chapter's title (chapters are 1-indexed)
    chapterTitleText.text = model.chapterTitles[chapterToRead - 1]
    // Don't show it if there is no title (otherwise there are leftover margins/padding)
    chapterTitleText.visibility =
        if (model.chapterTitles[chapterToRead - 1] == "") View.GONE else View.VISIBLE

    // Start at the top, regardless of where we were when we ran this function
    nestedScroller.scrollTo(0, 0)

    // Record scroll status
    nestedScroller.setOnScrollChangeListener { scroller, _, scrollY: Int, _, _ ->
      val rawPercentage = scrollY * 100.0 / (scrollingLayout.measuredHeight - scroller.bottom)
      // Make sure that values >100 get clamped to 100
      val percentageScrolled = Math.min(rawPercentage, 100.0)
      launch(CommonPool) { database.use {
        update("stories",
            "scrollProgress" to percentageScrolled, "scrollAbsolute" to scrollY)
            .whereSimple("storyid = ?", model.storyIdRaw.toString()).exec()
      } }
    }

    // Restore scroll status
    database.use {
      val absoluteScroll = select("stories", "scrollAbsolute")
          .whereSimple("storyId = ?", model.storyIdRaw.toString())
          .exec { parseSingle(LongParser) }
      launch(UI) {
        if (absoluteScroll > resources.getDimensionPixelSize(R.dimen.app_bar_height))
          appBar.setExpanded(false)
        nestedScroller.scrollTo(0, absoluteScroll.toInt())
      }
    }
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    val toTint = arrayOf(
        menu.findItem(R.id.goToTop),
        menu.findItem(R.id.goToBottom),
        menu.findItem(R.id.selectChapter)
    )
    for (item in toTint) item.iconTint(android.R.color.white, theme)
    menu.findItem(R.id.nextChapter).isEnabled = nextChapterBtn.isEnabled
    menu.findItem(R.id.prevChapter).isEnabled = prevChapterBtn.isEnabled
    menu.findItem(R.id.selectChapter).isEnabled = selectChapterBtn.isEnabled
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_story_reader, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
    R.id.goToTop -> {
      nestedScroller.scrollTo(0, 0)
      true
    }
    R.id.goToBottom -> {
      // FIXME this scrolls to the bottom of the **text**, not the bottom of the entire nested scroller
      nestedScroller.fullScroll(NestedScrollView.FOCUS_DOWN)
      true
    }
    R.id.selectChapter -> {
      showChapterSelectDialog()
      true
    }
    R.id.nextChapter -> {
      nextChapterBtn.callOnClick()
      true
    }
    R.id.prevChapter -> {
      prevChapterBtn.callOnClick()
      true
    }
    else -> super.onOptionsItemSelected(item)
  }

  private fun readChapter(storyId: Long, chapter: Int): Deferred<String> = async2(CommonPool) {
    val storyDir = storyDir(this@StoryReaderActivity, storyId)
    if (!storyDir.isPresent) throw IllegalStateException("Cannot read $storyId dir")
    if (!storyDir.get().exists()) {
      // FIXME download it
      return@async2 ""
    }
    val chapterHtml = File(storyDir.get(), "$chapter.html")
    if (!chapterHtml.exists()) {
      throw NoSuchFileException(chapterHtml, null, "Cannot read $storyId/$chapter.html")
    }
    return@async2 chapterHtml.readText()
  }
}
