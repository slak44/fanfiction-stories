package slak.fanfictionstories.activities

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.support.v4.widget.NestedScrollView
import android.support.v7.app.AlertDialog
import android.text.Html
import android.view.Menu
import android.view.MenuItem
import android.view.View
import kotlinx.android.synthetic.main.activity_story_reader.*
import kotlinx.android.synthetic.main.content_story_reader.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.contentView
import org.jetbrains.anko.db.DoubleParser
import org.jetbrains.anko.db.select
import org.jsoup.Jsoup
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.FetcherUtils.parseStoryModel
import slak.fanfictionstories.fetchers.extractChapterText
import slak.fanfictionstories.fetchers.fetchAndWriteStory
import slak.fanfictionstories.fetchers.fetchChapter
import slak.fanfictionstories.utility.*
import java.io.File

class StoryReaderActivity : LoadingActivity() {
  companion object {
    const val INTENT_STORY_MODEL = "bundle"
    private const val RESTORE_STORY_MODEL = "story_model"
    private const val RESTORE_CURRENT_CHAPTER = "current_chapter"
  }

  private lateinit var model: StoryModel
  private var currentChapter: Long = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_story_reader)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    model = intent.getParcelableExtra(INTENT_STORY_MODEL) ?: return
    if (model.status == StoryStatus.TRANSIENT) {
      // If it's in the db, use it, else set keep the transient one
      model = database.storyById(model.storyId).orElse(model)
    }

    // Save story for the resume button, but not for transient stories
    if (model.status != StoryStatus.TRANSIENT)
      Prefs.use { it.putLong(Prefs.RESUME_STORY_ID, model.storyId) }

    title = model.title
    currentChapter = if (model.progress.currentChapter == 0L) 1L else model.progress.currentChapter

    chapterText.setOnTextChangeListener {
      restoreScrollStatus()
    }

    launch(UI) {
      initTextWithLoading(currentChapter).join()
      restoreScrollStatus()
    }

    prevChapterBtn.setOnClickListener {
      currentChapter--
      initTextWithLoading(currentChapter)
    }
    nextChapterBtn.setOnClickListener {
      currentChapter++
      initTextWithLoading(currentChapter)
    }
    selectChapterBtn.setOnClickListener { showChapterSelectDialog() }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable(RESTORE_STORY_MODEL, model)
    outState.putLong(RESTORE_CURRENT_CHAPTER, currentChapter)
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    model = savedInstanceState.getParcelable(RESTORE_STORY_MODEL)
    currentChapter = savedInstanceState.getLong(RESTORE_CURRENT_CHAPTER)
  }

  private fun restoreScrollStatus() = launch(UI) {
    val absoluteScroll = database.readableDatabase.select("stories", "scrollAbsolute")
        .whereSimple("storyId = ?", model.storyId.toString())
        .parseOpt(DoubleParser) ?: return@launch
    val y = chapterText.scrollYFromScrollState(absoluteScroll)
    if (y > resources.getDimensionPixelSize(R.dimen.app_bar_height)) appBar.setExpanded(false)
    nestedScroller.scrollTo(0, y)
  }

  private fun showChapterSelectDialog() {
    AlertDialog.Builder(this@StoryReaderActivity)
        .setTitle(R.string.select_chapter)
        .setItems(model.chapterTitles().mapIndexed {
          idx, chapterTitle -> "${idx + 1}. $chapterTitle"
        }.toTypedArray(), { dialog, which: Int ->
          dialog.dismiss()
          // This means 'go to same chapter', so do nothing
          if (currentChapter == which + 1L) return@setItems
          currentChapter = which + 1L
          initTextWithLoading(currentChapter)
        }).show()
  }

  private fun initTextWithLoading(chapterToRead: Long) = launch(UI) {
    // Show loading things and disable chapter switching
    showLoading()
    btnBarLoader.visibility = View.VISIBLE
    navButtons.visibility = View.GONE
    prevChapterBtn.isEnabled = false
    nextChapterBtn.isEnabled = false
    selectChapterBtn.isEnabled = false
    invalidateOptionsMenu()
    // Load chapter
    initText(chapterToRead).await()
    // Hide loading things
    // Chapter switching is re-enabled by initText
    hideLoading()
    btnBarLoader.visibility = View.GONE
    navButtons.visibility = View.VISIBLE
  }

  private fun initText(chapterToRead: Long) = async2(CommonPool) {
    val text: String = readChapter(model.storyId, chapterToRead).await()
    val chapterWordCount = autoSuffixNumber(text.split(" ").size)

    async2(UI) {
      chapterWordCountText.text = str(R.string.x_words, chapterWordCount)
      currentChapterText.text = str(R.string.chapter_progress, chapterToRead, model.fragment.chapterCount)
      // This data is more or less useless with only one chapter, so we hide it
      val extraDataVisibility = if (model.fragment.chapterCount == 1L) View.GONE else View.VISIBLE
      chapterWordCountText.visibility = extraDataVisibility
      currentChapterText.visibility = extraDataVisibility

      chapterText.forceLayout()
    }.await()

    val html = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY,
        null, HrSpan.tagHandlerFactory(chapterText.width))

    chapterText.setText(html, theme).await()

    updateUiAfterFetchingText(chapterToRead)
    database.updateInStory(model.storyId, "currentChapter" to chapterToRead)
  }

  private fun setChangeChapterButtonStates(chapterToRead: Long) {
    // Disable buttons if there is nowhere for them to go
    prevChapterBtn.isEnabled = chapterToRead != 1L
    nextChapterBtn.isEnabled = chapterToRead != model.fragment.chapterCount
    selectChapterBtn.isEnabled = model.fragment.chapterCount > 1L

    // Tint button icons grey if the buttons are disabled, white if not
    fun getColorFor(view: View) = if (view.isEnabled) R.color.white else R.color.textDisabled

    prevChapterBtn.drawableTint(getColorFor(prevChapterBtn), theme, Direction.LEFT)
    nextChapterBtn.drawableTint(getColorFor(nextChapterBtn), theme, Direction.RIGHT)
    selectChapterBtn.drawableTint(getColorFor(selectChapterBtn), theme, Direction.LEFT)

    // Handle the next/prev button states/colors in the appbar
    invalidateOptionsMenu()
  }

  private fun updateUiAfterFetchingText(chapterToRead: Long) = launch(UI) {
    setChangeChapterButtonStates(chapterToRead)

    // Set chapter's title (chapterToRead is 1-indexed)
    chapterTitleText.text = model.chapterTitles()[chapterToRead.toInt() - 1]
    // Don't show it if there is no title (otherwise there are leftover margins/padding)
    chapterTitleText.visibility =
        if (model.chapterTitles()[chapterToRead.toInt() - 1] == "") View.GONE else View.VISIBLE

    // Start at the top, regardless of where we were when we ran this function
    nestedScroller.scrollTo(0, 0)

    // Record scroll status
    nestedScroller.setOnScrollChangeListener { scroller, _, scrollY: Int, _, _ ->
      val rawPercentage = scrollY * 100.0 / (scrollingLayout.measuredHeight - scroller.bottom)
      // Make sure that values >100 get clamped to 100
      val percentageScrolled = Math.min(rawPercentage, 100.0)

      database.updateInStory(model.storyId,
          "scrollProgress" to percentageScrolled,
          "scrollAbsolute" to chapterText.scrollStateFromScrollY(scrollY))
    }
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    val toTint = arrayOf(
        menu.findItem(R.id.goToTop),
        menu.findItem(R.id.goToBottom)
    )
    for (item in toTint) item.iconTint(R.color.white, theme)
    menu.findItem(R.id.nextChapter).isEnabled = nextChapterBtn.isEnabled
    menu.findItem(R.id.prevChapter).isEnabled = prevChapterBtn.isEnabled
    menu.findItem(R.id.selectChapter).isEnabled = selectChapterBtn.isEnabled
    val isNotLocal = model.status != StoryStatus.LOCAL
    menu.findItem(R.id.downloadLocal).isVisible = isNotLocal
    menu.findItem(R.id.downloadLocal).isEnabled = isNotLocal
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
        intent.putExtra(ReviewsActivity.INTENT_STORY_MODEL, model as Parcelable)
        intent.putExtra(ReviewsActivity.INTENT_TARGET_CHAPTER, currentChapter)
        startActivity(intent)
      }
      R.id.viewAuthor -> {
        val intent = Intent(this, AuthorActivity::class.java)
        intent.putExtra(AuthorActivity.INTENT_AUTHOR_ID, model.authorId)
        intent.putExtra(AuthorActivity.INTENT_AUTHOR_NAME, model.author)
        startActivity(intent)
      }
      R.id.downloadLocal -> launch(CommonPool) {
        model = fetchAndWriteStory(model.storyId).await().orElse(model)
      }
      R.id.deleteLocal -> undoableAction(contentView!!, R.string.data_deleted) {
        deleteLocalStory(this, model.storyId)
        database.updateInStory(model.storyId, "status" to "remote")
        model.status = StoryStatus.REMOTE
      }
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }

  private fun downloadChapter(storyId: Long,
                              chapter: Long, target: File): Deferred<String> = async2(CommonPool) {
    val chapterHtmlText = fetchChapter(storyId, chapter).await()
    val text = extractChapterText(Jsoup.parse(chapterHtmlText))
    target.printWriter().use { it.print(text) }
    if (model.status == StoryStatus.TRANSIENT) {
      model = parseStoryModel(chapterHtmlText, storyId)
      database.upsertStory(model)
    }
    return@async2 text
  }
  private fun readChapter(storyId: Long, chapter: Long): Deferred<String> = async2(CommonPool) {
    val storyDir = storyDir(this@StoryReaderActivity, storyId)
        .orElseThrow(IllegalStateException("Cannot read $storyId dir"))
    if (!storyDir.exists()) storyDir.mkdirs()
    val chapterFile = File(storyDir, "$chapter.html")
    if (!chapterFile.exists()) {
      val text = downloadChapter(storyId, chapter, chapterFile).await()
      launch(CommonPool) {
        // If all chapters are on disk, set to local
        if (storyDir.list().size == model.fragment.chapterCount.toInt()) {
          model.status = StoryStatus.LOCAL
          database.updateInStory(storyId, "status" to "local")
        }
      }
      return@async2 text
    } else {
      return@async2 chapterFile.readText()
    }
  }
}
