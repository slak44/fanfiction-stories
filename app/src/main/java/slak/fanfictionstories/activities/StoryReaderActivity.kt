package slak.fanfictionstories.activities

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.design.widget.CoordinatorLayout
import android.support.v4.view.AsyncLayoutInflater
import android.support.v4.view.ViewCompat
import android.support.v4.widget.NestedScrollView
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.*
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.*
import android.widget.TextView
import kotlinx.android.synthetic.main.activity_story_reader.*
import kotlinx.android.synthetic.main.content_story_reader.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.consumeEach
import org.jetbrains.anko.db.LongParser
import org.jetbrains.anko.db.parseSingle
import org.jetbrains.anko.db.select
import org.jetbrains.anko.db.update
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.storyDir
import slak.fanfictionstories.utility.HrSpan
import slak.fanfictionstories.utility.async2
import slak.fanfictionstories.utility.database
import slak.fanfictionstories.utility.iconTint
import java.io.File

//private class RecyclerView2 : RecyclerView {
//  constructor(ctx: Context) : super(ctx)
//  constructor(ctx: Context, set: AttributeSet) : super(ctx, set)
//  constructor(ctx: Context, set: AttributeSet, defStyle: Int) : super(ctx, set, defStyle)

//  val dm = DisplayMetrics()
//
//  init {
//    (context as AppCompatActivity).windowManager.defaultDisplay.getMetrics(dm)
//  }
//
//  override fun onMeasure(widthSpec: Int, heightSpec: Int) {
////    println((context as AppCompatActivity).window.decorView.width)
//    val spec = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
//    super.onMeasure(spec, heightSpec)
//  }
//}


//private class LinearLayoutManager2(val context: StoryReaderActivity) : LinearLayoutManager(context) {
//  override fun onMeasure(recycler: RecyclerView.Recycler?, state: RecyclerView.State?, widthSpec: Int, heightSpec: Int) {
//
//    super.onMeasure(recycler, state, spec, heightSpec)
//  }
//}

// FIXME rename to FastTextView
private class NiceTextView : View {
  constructor(ctx: Context) : super(ctx)
  constructor(ctx: Context, set: AttributeSet) : super(ctx, set)
  constructor(ctx: Context, set: AttributeSet, defStyle: Int) : super(ctx, set, defStyle)

//  var initialWidth: Int? = null
  private var staticLayout: StaticLayout? = null

  fun setText(s: Spanned) = async2(CommonPool) {
    // FIXME hardcoded textpaint
    val tp = TextPaint()
    tp.color = resources.getColor(android.R.color.secondary_text_dark)
    tp.typeface = Typeface.DEFAULT
    tp.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14F, resources.displayMetrics)
    tp.isAntiAlias = true

    if (!ViewCompat.isLaidOut(this@NiceTextView)) {
      println("not laid out?")
      async2(UI) {
        this@NiceTextView.forceLayout()
      }.await()
    }

    staticLayout = StaticLayout(s, tp,
        width, Layout.Alignment.ALIGN_NORMAL, 1F, 0F, false)

    async2(UI) {
      this@NiceTextView.layoutParams.height = staticLayout!!.height
      this@NiceTextView.invalidate()
    }.await()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    canvas.save()
//    println(staticLayout)
    staticLayout?.draw(canvas)
    canvas.restore()
  }

//  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
//    setMeasuredDimension(widthMeasureSpec, heightMeasureSpec)
//  }
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
//  private var adapter: ParagraphsAdapter? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_story_reader)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    // FIXME set app to resume to this

    model = intent.getParcelableExtra(INTENT_STORY_MODEL)
//    val lm = LinearLayoutManager(this)
//    lm.isSmoothScrollbarEnabled = false
////    lm.isAutoMeasureEnabled = false
//    chapterText.layoutManager = lm
//    chapterText.isNestedScrollingEnabled = false
//    adapter = ParagraphsAdapter(this)
//    chapterText.adapter = adapter
//
//    chapterText.initialWidth = chapterText.width

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
//    launch(UI) { adapter?.clear() }.join()
//    val textStream = Channel<Spanned>(1000)
    val text: String = readChapter(model.storyIdRaw, chapterToRead).await()

    async2(UI) {
      chapterText.forceLayout()
    }.await()

    val html =
        Html.fromHtml(
            text, Html.FROM_HTML_MODE_LEGACY, null, tagHandlerFactory(chapterText.width))

    chapterText.setText(html).await()

//    val tp = TextPaint()
//    tp.color = this@StoryReaderActivity
//        .resources.getColor(android.R.color.secondary_text_dark, this@StoryReaderActivity.theme)
//    tp.typeface = Typeface.DEFAULT
//    tp.textSize = TypedValue.applyDimension(
//        TypedValue.COMPLEX_UNIT_SP, 14F, this@StoryReaderActivity.resources.displayMetrics)
//
//    tp.isAntiAlias = true
//
//    println("INITIAL W ${nestedScroller.width}")
//    val staticLayout = StaticLayout(html, tp,
//        nestedScroller.width, Layout.Alignment.ALIGN_NORMAL, 1F, 0F, false)

//    chapterText.layoutParams = ViewGroup.LayoutParams(scrollingLayout.width, 1)

//    launch(UI) {
//      chapterText.text = html.toString()
//    }

//    AsyncLayoutInflater(this@StoryReaderActivity)
//        .inflate(R.layout.component_story_paragraph, scrollingLayout) { view: View, _, parent ->
//          (view as TextView).text = html
//          // Index 0 is title
//          parent.addView(view, 1)
//    }

//    async2(CommonPool) {
//      val paragraphs = text.split(Regex("</p>"))
//      paragraphs.forEachIndexed { index, s ->
//        val txt = if (index == paragraphs.size - 1) s else s + "</p>"
//        // Legacy mode puts more space between <p>, makes it easier to read
//        textStream.send(Html.fromHtml(
//            txt, Html.FROM_HTML_MODE_LEGACY, null, tagHandlerFactory(chapterText.width)))
//      }
//      textStream.close()
//    }
//    async2(CommonPool) {
//      val buffer = mutableListOf<Spanned>()
//      var a: Int = 0
//      textStream.consumeEach { p ->
//        launch(UI) { adapter!!.addParagraph(p) }
//
////        buffer.add(p)
////        if (buffer.size == 30) {
////          launch(UI) {
////            adapter.addParagraphs(buffer)
////            buffer.clear()
////          }.join()
////          delay(50)
////        }
//
////        adapter.data.add(p)
////        a++
////        if (a % 20 == 0) launch(UI) {
////          adapter.notifyItemRangeInserted(a - 20, 20)
////        }.join()
//      }
////      launch(UI) {
////        adapter.notifyItemRangeInserted(a - a % 20, a % 20)
////      }.join()
//
////      val childCount = chapterText.getChildCount()
////      for (i in 0 until childCount) {
////        val child = chapterText.getChildAt(i)
////        val lp = child!!.getLayoutParams()
////        if (lp.width < 0 && lp.height < 0) {
////          println("SOUND ZE ALARMS")
////        }
////      }
//
//    }
    updateUiAfterFetchingText(chapterToRead)
    database.use {
      update("stories", "currentChapter" to chapterToRead)
          .whereSimple("storyId = ?", model.storyIdRaw.toString()).exec()
    }
  }

  private fun updateUiAfterFetchingText(chapterToRead: Int) = launch(UI) {
    // Disable buttons if there is nowhere for them to go
    prevChapterBtn.isEnabled = chapterToRead != 1
    nextChapterBtn.isEnabled = chapterToRead != model.chapterCount

    // Handle the next/prev button states in the appbar
    invalidateOptionsMenu()

//    println("${chapterText.width}, ${chapterText.height} STATIC, ${chapterText.staticLayout?.width}, ${chapterText.staticLayout?.height}")
//    chapterText.staticLayout = staticLayout
//
//    println("${chapterText.width}, ${chapterText.height} STATIC, ${chapterText.staticLayout!!.width}, ${chapterText.staticLayout!!.height}")
//    chapterText.layoutParams.height = chapterText.staticLayout!!.height
//    chapterText.staticLayout!!.increaseWidthTo(chapterText.width)
//    chapterText.layoutParams.width = chapterText.initialWidth!!
//    println("${chapterText.width}, ${chapterText.height} STATIC, ${chapterText.staticLayout!!.width}, ${chapterText.staticLayout!!.height}")
//    chapterText.measure(View.MeasureSpec.makeMeasureSpec(chapterText.width, View.MeasureSpec.EXACTLY),
//        View.MeasureSpec.makeMeasureSpec(chapterText.staticLayout!!.height, View.MeasureSpec.EXACTLY))
//    println("${chapterText.width}, ${chapterText.height} STATIC, ${chapterText.staticLayout!!.width}, ${chapterText.staticLayout!!.height}")
//    chapterText.invalidate()
//    println("${chapterText.width}, ${chapterText.height} STATIC, ${chapterText.staticLayout!!.width}, ${chapterText.staticLayout!!.height}")
//    chapterText.text = html

    // Set chapter's title (chapters are 1-indexed)
    chapterTitleText.text = model.chapterTitles[chapterToRead - 1]
    // Don't show it if there is no title (otherwise there are leftover margins/padding)
    chapterTitleText.visibility =
        if (model.chapterTitles[chapterToRead - 1] == "") View.GONE else View.VISIBLE

    // Start at the top, regardless of where we were when we ran this function
    nestedScroller.scrollTo(0, 0)
//    chapterText.scrollTo(0, 0)

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

//private class ParagraphsAdapter(val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
//  class TextViewHolder(val view: TextView) : RecyclerView.ViewHolder(view)
//
//  var data: MutableList<Spanned> = mutableListOf()
//
//  fun clear() {
//    val dataSize = data.size
//    data.clear()
//    notifyItemRangeRemoved(0, dataSize)
//  }
//
//  fun addParagraph(s: Spanned) {
//    data.add(s)
//    notifyItemInserted(data.size - 1)
//  }
//
//  fun addParagraphs(s: MutableList<Spanned>) {
//    val dataSize = data.size
//    data.addAll(s)
//    notifyItemRangeInserted(dataSize, s.size)
//  }
//
//  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
//    val tv = (holder as TextViewHolder).view
//    tv.text = data[position]
//    tv.layoutParams = ViewGroup.LayoutParams(
//        ViewGroup.LayoutParams.MATCH_PARENT,
//        ViewGroup.LayoutParams.WRAP_CONTENT)
//  }
//
//  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
//    return TextViewHolder(TextView(context))
//  }
//
//  private var id: Long = 0
//
//  override fun getItemCount(): Int = data.size
//  override fun getItemId(position: Int): Long = id++
//  override fun getItemViewType(position: Int): Int = 0
//}
