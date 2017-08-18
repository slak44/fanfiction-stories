package slak.fanfictionstories

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.text.Html
import android.text.style.ReplacementSpan
import kotlinx.android.synthetic.main.activity_story_reader.*
import kotlinx.android.synthetic.main.content_story_reader.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.db.update
import java.io.File

class HrSpan(val heightPx: Int, val width: Int) : ReplacementSpan() {
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_story_reader)
    setSupportActionBar(toolbar)
    fab.setOnClickListener { view ->
      Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
          .setAction("Action", null).show()
    }
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    val model = intent.getParcelableExtra<StoryModel>(INTENT_STORY_MODEL)

    title = model.title

    val chapterToRead = if (model.currentChapter == 0) 1 else model.currentChapter
    launch(CommonPool) {
      val text = readChapter(model.storyidRaw, chapterToRead).await()
      launch(UI) {
        // Legacy mode puts more space between <p>, makes it easier to read
        chapterText.text =
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY, null, getTagHandler(chapterText.width))
        // FIXME reinstate scroll
      }
      database.use {
        update("stories", "currentChapter" to chapterToRead)
            .whereSimple("storyid = ?", model.storyidRaw.toString())
      }
    }
  }

  private fun readChapter(storyid: Long, chapter: Int): Deferred<String> = async(CommonPool) {
    val storyDir = storyDir(this@StoryReaderActivity, storyid)
    if (!storyDir.isPresent) throw IllegalStateException("Cannot read $storyid dir")
    if (!storyDir.get().exists()) {
      // FIXME download it
      return@async ""
    }
    val chapterHtml = File(storyDir.get(), "$chapter.html")
    if (!chapterHtml.exists()) {
      throw NoSuchFileException(chapterHtml, null, "Cannot read $storyid/$chapter.html")
    }
    return@async chapterHtml.readText()
  }

  // FIXME add scroll listener to record scroll state in db
}
