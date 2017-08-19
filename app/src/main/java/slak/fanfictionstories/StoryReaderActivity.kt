package slak.fanfictionstories

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.Bundle
import android.support.v4.widget.NestedScrollView
import android.support.v7.app.AppCompatActivity
import android.text.Html
import android.text.style.ReplacementSpan
import android.view.*
import kotlinx.android.synthetic.main.activity_story_reader.*
import kotlinx.android.synthetic.main.content_story_reader.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.db.LongParser
import org.jetbrains.anko.db.parseSingle
import org.jetbrains.anko.db.select
import org.jetbrains.anko.db.update
import java.io.File

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

class StoryReaderActivity : AppCompatActivity() {

  companion object {
    const val INTENT_STORY_MODEL = "bundle"
    private val PLACEHOLDER = "######HRPLACEHOLDERHRPLACEHOLDERHRPLACEHOLDER######"
    private val getTagHandler = { widthPx: Int -> Html.TagHandler { opening, tag, output, _ ->
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
  }

  private fun initText(chapterToRead: Int) = launch(CommonPool) {
    // FIXME maybe throw a spinny loader here until the text shows up
    val text: String = readChapter(model.storyIdRaw, chapterToRead).await()
    updateUiAfterFetchingText(text, chapterToRead)
    database.use {
      update("stories", "currentChapter" to chapterToRead)
          .whereSimple("storyId = ?", model.storyIdRaw.toString()).exec()
    }
  }

  private fun updateUiAfterFetchingText(text: String, chapterToRead: Int) = launch(UI) {
    // Disable buttons if there is nowhere for them to go
    prevChapterBtn.isEnabled = chapterToRead != 1
    nextChapterBtn.isEnabled = chapterToRead != model.chapterCount

    // Legacy mode puts more space between <p>, makes it easier to read
    chapterText.text = Html.fromHtml(
        text, Html.FROM_HTML_MODE_LEGACY, null, getTagHandler(chapterText.width))

    // Set chapter's title (chapters are 1-indexed)
    chapterTitleText.text = model.chapterTitles[chapterToRead - 1]
    // Don't show it if there is no title
    chapterTitleText.visibility =
        if (model.chapterTitles[chapterToRead - 1] == "") View.GONE else View.VISIBLE

    // Start at the top, regardless of where we were when we ran this function
    nestedScroller.scrollTo(0, 0)

    // This measurement is not really guaranteed to happen: because this function gets called
    // from onCreate, the height might not be calculated, so there is technically a race
    // condition between this coroutine and whoever calculates the height, but since fetching and
    // parsing the chapter takes more time than that, we're not going to have issues
    val heightMeasureSpec =
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    val widthMeasureSpec =
        View.MeasureSpec.makeMeasureSpec(nestedScroller.width, View.MeasureSpec.AT_MOST)
    scrollingLayout.measure(widthMeasureSpec, heightMeasureSpec)

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
    val white = resources.getColor(android.R.color.white, theme)
    val top = resources.getDrawable(R.drawable.ic_arrow_upward_black_24dp, theme)
    top.setColorFilter(white, PorterDuff.Mode.SRC_IN)
    val bot = resources.getDrawable(R.drawable.ic_arrow_downward_black_24dp, theme)
    bot.setColorFilter(white, PorterDuff.Mode.SRC_IN)
    menu.findItem(R.id.goToTop).icon = top
    menu.findItem(R.id.goToBottom).icon = bot
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    // Inflate the menu; this adds items to the action bar if it is present.
    menuInflater.inflate(R.menu.menu_story_reader, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    return when (item.itemId) {
      R.id.goToTop -> {
        nestedScroller.scrollTo(0, 0)
        return false
      }
      R.id.goToBottom -> {
        nestedScroller.fullScroll(NestedScrollView.FOCUS_DOWN)
        return false
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  private fun readChapter(storyId: Long, chapter: Int): Deferred<String> = async(CommonPool) {
    val storyDir = storyDir(this@StoryReaderActivity, storyId)
    if (!storyDir.isPresent) throw IllegalStateException("Cannot read $storyId dir")
    if (!storyDir.get().exists()) {
      // FIXME download it
      return@async ""
    }
    val chapterHtml = File(storyDir.get(), "$chapter.html")
    if (!chapterHtml.exists()) {
      throw NoSuchFileException(chapterHtml, null, "Cannot read $storyId/$chapter.html")
    }
    return@async chapterHtml.readText()
  }
}
