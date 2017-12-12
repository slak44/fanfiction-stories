package slak.fanfictionstories.activities

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Bundle
import android.support.v4.app.NavUtils
import android.support.v4.view.ViewCompat
import android.support.v4.widget.NestedScrollView
import android.support.v7.app.AlertDialog
import android.text.*
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import kotlinx.android.synthetic.main.activity_story_reader.*
import kotlinx.android.synthetic.main.content_story_reader.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.db.*
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.StoryStatus
import slak.fanfictionstories.fetchers.Fetcher.parseMetadata
import slak.fanfictionstories.fetchers.StoryFetcher
import slak.fanfictionstories.storyDir
import slak.fanfictionstories.utility.*
import java.io.File

private class FastTextView : View {
  companion object {
    private const val TAG = "FastTextView"
  }

  constructor(ctx: Context) : super(ctx)
  constructor(ctx: Context, set: AttributeSet) : super(ctx, set)
  constructor(ctx: Context, set: AttributeSet, defStyle: Int) : super(ctx, set, defStyle)

  var staticLayout: StaticLayout? = null
    private set

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

class StoryReaderActivity : ActivityWithStatic() {
  companion object {
    const val INTENT_STORY_MODEL = "bundle"
    private const val RESTORE_STORY_MODEL = "story_model"
    private const val PLACEHOLDER = "######HRPLACEHOLDERHRPLACEHOLDERHRPLACEHOLDER######"
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

    model = intent.getParcelableExtra(INTENT_STORY_MODEL) ?: return
    if (model.status == StoryStatus.TRANSIENT) {
      // If it's in the db, use it, else set keep the transient one
      model = database.storyById(model.storyIdRaw).orElse(model)
    }

    // Save story for the resume button, but only for local stories
    if (model.status == StoryStatus.LOCAL)
      usePrefs { it.putLong(Prefs.RESUME_STORY_ID, model.storyIdRaw) }

    title = model.title
    currentChapter = if (model.currentChapter == 0) 1 else model.currentChapter

    launch(UI) {
      initText(currentChapter).await()
      restoreScrollStatus()
    }

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

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable(RESTORE_STORY_MODEL, model)
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    model = savedInstanceState.getParcelable(RESTORE_STORY_MODEL)
    restoreScrollStatus()
  }

  private fun restoreScrollStatus() {
    database.use {
      val absoluteScroll = select("stories", "scrollAbsolute")
          .whereSimple("storyId = ?", model.storyIdRaw.toString())
          .parseOpt(DoubleParser) ?: return@use
      launch(UI) {
        if (absoluteScroll > resources.getDimensionPixelSize(R.dimen.app_bar_height))
          appBar.setExpanded(false)
        val offset = absoluteScroll.toInt()
        // FIXME hardcoded font size (it's not even that), that's actually supposed to be the line height
        val above = (absoluteScroll - offset) * 15F
        val layout = chapterText.staticLayout!!
        val line = layout.getLineForOffset(offset)
        val y = (if (line == 0) -layout.topPadding else layout.getLineTop(line)) - above
        nestedScroller.scrollTo(0, y.toInt())
      }
    }
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

  private fun initText(chapterToRead: Int) = async2(CommonPool) {
    val text: String = readChapter(model.storyIdRaw, chapterToRead).await()
    val chapterWordCount = autoSuffixNumber(text.split(Regex("\\w+")).size)

    async2(UI) {
      chapterWordCountText.text = resources.getString(R.string.x_words, chapterWordCount)
      currentChapterText.text = resources.getString(
          R.string.chapter_progress, chapterToRead, model.chapterCount)
      // This data is more or less useless with only one chapter, so we hide it
      val extraDataVisibility = if (model.chapterCount == 1) View.GONE else View.VISIBLE
      chapterWordCountText.visibility = extraDataVisibility
      currentChapterText.visibility = extraDataVisibility

      chapterText.forceLayout()
    }.await()

    val html = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY,
        null, tagHandlerFactory(chapterText.width))

    chapterText.setText(html).await()

    updateUiAfterFetchingText(chapterToRead)
    database.updateInStory(model.storyIdRaw, "currentChapter" to chapterToRead)
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
      val layout = chapterText.staticLayout!!
      val topPadding = -layout.topPadding
      val res = if (scrollY <= topPadding) {
        // FIXME hardcoded font size (it's not even that), that's actually supposed to be the line height
        (topPadding - scrollY) / 15F
      } else {
        val line = layout.getLineForVertical(scrollY - 1) + 1
        val offset = layout.getLineStart(line)
        val above = layout.getLineTop(line) - scrollY
        // FIXME hardcoded font size (it's not even that), that's actually supposed to be the line height
        offset + above / 15F
      }

      val rawPercentage = scrollY * 100.0 / (scrollingLayout.measuredHeight - scroller.bottom)
      // Make sure that values >100 get clamped to 100
      val percentageScrolled = Math.min(rawPercentage, 100.0)

      database.updateInStory(model.storyIdRaw,
          "scrollProgress" to percentageScrolled, "scrollAbsolute" to res)
    }
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    val toTint = arrayOf(
        menu.findItem(R.id.goToTop),
        menu.findItem(R.id.goToBottom)
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

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.goToTop -> nestedScroller.scrollTo(0, 0)
      R.id.goToBottom -> nestedScroller.fullScroll(NestedScrollView.FOCUS_DOWN)
      R.id.selectChapter -> showChapterSelectDialog()
      R.id.nextChapter -> nextChapterBtn.callOnClick()
      R.id.prevChapter -> prevChapterBtn.callOnClick()
      R.id.storyReviews -> {
        val intent = Intent(this, ReviewsActivity::class.java)
        intent.putExtra(ReviewsActivity.INTENT_STORY_MODEL, model)
        intent.putExtra(ReviewsActivity.INTENT_TARGET_CHAPTER, currentChapter)
        startActivity(intent)
      }
      android.R.id.home -> NavUtils.navigateUpFromSameTask(this)
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }

  private var fetcher: StoryFetcher? = null
  private fun readChapter(storyId: Long, chapter: Int): Deferred<String> = async2(CommonPool) {
    if (fetcher == null) fetcher = StoryFetcher(storyId, this@StoryReaderActivity)
    val storyDir = storyDir(this@StoryReaderActivity, storyId)
        .orElseThrow(IllegalStateException("Cannot read $storyId dir"))
    if (!storyDir.exists()) storyDir.mkdirs()
    val chapterFile = File(storyDir, "$chapter.html")
    if (!chapterFile.exists()) {
      // FIXME show loading thingy, this may not be fast
      val n = Notifications(this@StoryReaderActivity, Notifications.Kind.DOWNLOADING)
      val chapterHtmlText = fetcher!!.fetchChapter(chapter, n).await()
      val text = fetcher!!.parseChapter(chapterHtmlText)
      chapterFile.printWriter().use { it.print(text) }
      if (model.status == StoryStatus.TRANSIENT) {
        model = StoryModel(parseMetadata(chapterHtmlText, storyId), fromDb = false)
        model.status = StoryStatus.REMOTE
        fetcher!!.setMetadata(model)
        database.use { insertOrThrow("stories", *model.toKvPairs()) }
      }
      return@async2 text
    } else {
      return@async2 chapterFile.readText()
    }
  }
}
