package slak.fanfictionstories.activities

import android.os.Bundle
import android.os.Parcelable
import android.support.annotation.AnyThread
import android.support.annotation.UiThread
import android.support.v4.view.ViewCompat
import android.support.v4.widget.NestedScrollView
import android.support.v7.app.AlertDialog
import android.text.Html
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import kotlinx.android.synthetic.main.activity_story_reader.*
import kotlinx.android.synthetic.main.activity_story_reader_content.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.sync.Mutex
import org.jetbrains.anko.contentView
import org.jetbrains.anko.db.DoubleParser
import org.jetbrains.anko.db.select
import org.jsoup.Jsoup
import slak.fanfictionstories.*
import slak.fanfictionstories.Notifications.Companion.defaultIntent
import slak.fanfictionstories.fetchers.FetcherUtils.parseStoryModel
import slak.fanfictionstories.fetchers.extractChapterText
import slak.fanfictionstories.fetchers.fetchAndWriteStory
import slak.fanfictionstories.fetchers.fetchChapter
import slak.fanfictionstories.fetchers.updateStory
import slak.fanfictionstories.utility.*
import java.io.File

/** Shows a chapter of a story for reading. */
class StoryReaderActivity : LoadingActivity() {
  companion object {
    private const val TAG = "StoryReaderActivity"
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

    if (savedInstanceState != null) {
      onRestoreInstanceState(savedInstanceState)
      restoreScrollStatus()
      Log.v(TAG, "Restore model in onCreate: $model")
    } else {
      model = intent.getParcelableExtra(INTENT_STORY_MODEL) ?: return
      currentChapter =
          if (model.progress.currentChapter == 0L) 1L else model.progress.currentChapter
      Log.v(TAG, "Intent extra model: $model")
    }

    prevChapterBtn.setOnClickListener { initTextWithLoading(--currentChapter) }
    nextChapterBtn.setOnClickListener { initTextWithLoading(++currentChapter) }
    selectChapterBtn.setOnClickListener { showChapterSelectDialog() }

    // Save story for the resume button
    // Even for transient stories, because entering the reader means the story became remote
    Prefs.use { it.putLong(Prefs.RESUME_STORY_ID, model.storyId) }

    // Long titles require _even more_ space than CollapsibleToolbar already gives
    // The 35 character limit is completely arbitrary
    if (model.title.length > 35) {
      appBar.layoutParams.height = resources.px(R.dimen.app_bar_large_text_height)
    }

    title = model.title

    launch(CommonPool) {
      if (model.status != StoryStatus.LOCAL) {
        // If the story is in the db, use it
        model = database.storyById(model.storyId).await().orElse(model)
      }

      if (model.status == StoryStatus.LOCAL) {
        // Local stories load fast enough that we do not need this loader
        launch(UI) { hideLoading() }
        // Update last time the story was read
        database.updateInStory(model.storyId, "lastReadTime" to System.currentTimeMillis())
      }

      initTextWithLoading(currentChapter).join()
      restoreScrollStatus().join()
    }
  }

  override fun onResume() {
    super.onResume()
    restoreScrollStatus()
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

  @AnyThread
  private fun restoreScrollStatus() = launch(UI) {
    // onResume and others get called before onCreate when the activity is first instantiated
    // Which means we would NPE when the text is inevitably not there
    // The status gets restored in onCreate after text creation in that case, so we just return here
    if (chapterText.staticLayout == null) return@launch
    val scrollAbs = database.useAsync {
      select("stories", "scrollAbsolute")
          .whereSimple("storyId = ?", model.storyId.toString())
          .parseOpt(DoubleParser)
    }.await() ?: return@launch
    val y = chapterText.scrollYFromScrollState(scrollAbs)
    if (y > resources.px(R.dimen.app_bar_height)) appBar.setExpanded(false)
    nestedScroller.scrollTo(0, y)
  }

  private fun showChapterSelectDialog() {
    AlertDialog.Builder(this@StoryReaderActivity)
        .setTitle(R.string.select_chapter)
        .setSingleChoiceItems(model.chapterTitles().mapIndexed { idx, chapterTitle ->
          "${idx + 1}. $chapterTitle"
        }.toTypedArray(), (currentChapter - 1).toInt()) { dialog, which: Int ->
          dialog.dismiss()
          // This means 'go to same chapter', so do nothing
          if (currentChapter == which + 1L) return@setSingleChoiceItems
          currentChapter = which + 1L
          initTextWithLoading(currentChapter)
        }.show()
  }

  @AnyThread
  private fun initTextWithLoading(chapterToRead: Long) = launch(UI) {
    // Show loading things and disable chapter switching
    if (model.status != StoryStatus.LOCAL) showLoading()
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

  @AnyThread
  private fun initText(chapterToRead: Long) = async2(CommonPool) {
    val text: String = readChapter(model.storyId, chapterToRead).await()
    val chapterWordCount = autoSuffixNumber(text.split(" ").size)

    launch(UI) {
      chapterWordCountText.text = str(R.string.x_words, chapterWordCount)
      currentChapterText.text = str(R.string.chapter_progress, chapterToRead, model.fragment.chapterCount)
      val avgWordCount: Double = model.fragment.wordCount.toDouble() / model.fragment.chapterCount
      val wordsPassedEstimate: Double = (chapterToRead - 1) * avgWordCount
      approxWordCountRemainText.text = str(R.string.approx_x_words_left,
          autoSuffixNumber(model.fragment.wordCount - wordsPassedEstimate.toLong()))
      // This data is more or less useless with only one chapter, so we hide it
      val extraDataVisibility = if (model.fragment.chapterCount == 1L) View.GONE else View.VISIBLE
      chapterWordCountText.visibility = extraDataVisibility
      currentChapterText.visibility = extraDataVisibility
      approxWordCountRemainText.visibility = extraDataVisibility

      // Set chapter's title (chapterToRead is 1-indexed)
      if (model.fragment.chapterCount > 1) {
        chapterTitleText.text = model.chapterTitles()[chapterToRead.toInt() - 1]
      }
      // Don't show it if there is no title (otherwise there are leftover margins/padding)
      chapterTitleText.visibility = if (chapterTitleText.text == "") View.GONE else View.VISIBLE
    }

    // We use the width for the <hr> elements
    if (!ViewCompat.isLaidOut(chapterText)) launch(UI) { chapterText.forceLayout() }.join()

    if (chapterText.width == 0) Log.w(TAG, "chapterText.width is 0!")

    val html = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY,
        null, HrSpan.tagHandlerFactory(chapterText.width))

    chapterText.setText(html, theme).await()

    updateUiAfterFetchingText(chapterToRead)
    database.updateInStory(model.storyId, "currentChapter" to chapterToRead).await()
  }

  @UiThread
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

  private val scrollSaver = object {
    private var percentageScrolledVal = 0.0
    private var scrollAbsoluteVal = 0.0
    private var saveLock = Mutex()
    private var hasChanged = false

    @AnyThread
    fun notifyChanged(percentageScrolled: Double, scrollAbsolute: Double) {
      percentageScrolledVal = percentageScrolled
      scrollAbsoluteVal = scrollAbsolute
      val managedToLock = saveLock.tryLock()
      hasChanged = true
      if (!managedToLock) return
      launch(CommonPool) {
        while (hasChanged) {
          hasChanged = false
          database.updateInStory(model.storyId,
              "scrollProgress" to percentageScrolledVal,
              "scrollAbsolute" to scrollAbsoluteVal).join()
        }
        // If this method is called right *here* on another thread, that call will be swallowed
        saveLock.unlock()
      }
    }
  }

  @AnyThread
  private fun updateUiAfterFetchingText(chapterToRead: Long) = launch(UI) {
    setChangeChapterButtonStates(chapterToRead)

    // Start at the top, regardless of where we were when we ran this function
    nestedScroller.scrollTo(0, 0)

    // If the text is so short the scroller doesn't need to scroll, max out progress right away
    if (nestedScroller.height > chapterText.staticLayout!!.height) {
      scrollSaver.notifyChanged(100.0, 99999999.0)
    }

    // Record scroll status
    nestedScroller.setOnScrollChangeListener { scroller, _, scrollY: Int, _, _ ->
      val rawPercentage = scrollY * 100.0 / (scrollingLayout.measuredHeight - scroller.bottom)
      // Make sure that values >100 get clamped to 100
      val percentageScrolled = Math.min(rawPercentage, 100.0)
      val scrollAbs = chapterText.scrollStateFromScrollY(scrollY)
      scrollSaver.notifyChanged(percentageScrolled, scrollAbs)
    }
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    menu.findItem(R.id.goToTop).iconTint(R.color.white, theme)
    menu.findItem(R.id.goToBottom).iconTint(R.color.white, theme)
    menu.findItem(R.id.nextChapter).isEnabled = nextChapterBtn.isEnabled
    menu.findItem(R.id.prevChapter).isEnabled = prevChapterBtn.isEnabled
    menu.findItem(R.id.selectChapter).isEnabled = selectChapterBtn.isEnabled
    menu.findItem(R.id.downloadLocal).isVisible = model.status != StoryStatus.LOCAL
    menu.findItem(R.id.checkForUpdate).isVisible = model.status == StoryStatus.LOCAL
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_story_reader, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.goToTop -> {
        // Without this hacky call to fling, we don't scroll at all
        nestedScroller.fling(0)
        nestedScroller.fullScroll(NestedScrollView.FOCUS_UP)
        appBar.setExpanded(true)
      }
      R.id.goToBottom -> {
        nestedScroller.fullScroll(NestedScrollView.FOCUS_DOWN)
        appBar.setExpanded(false)
      }
      R.id.selectChapter -> showChapterSelectDialog()
      R.id.nextChapter -> nextChapterBtn.callOnClick()
      R.id.prevChapter -> prevChapterBtn.callOnClick()
      R.id.storyReviews -> {
        startActivity<ReviewsActivity>(
            ReviewsActivity.INTENT_STORY_MODEL to model as Parcelable,
            ReviewsActivity.INTENT_TARGET_CHAPTER to currentChapter.toInt())
      }
      R.id.viewAuthor -> {
        startActivity<AuthorActivity>(
            AuthorActivity.INTENT_AUTHOR_ID to model.authorId,
            AuthorActivity.INTENT_AUTHOR_NAME to model.author)
      }
      R.id.downloadLocal -> launch(CommonPool) {
        model = fetchAndWriteStory(model.storyId).await().orElse(model)
      }
      R.id.checkForUpdate -> launch(CommonPool) {
        Notifications.UPDATING.show(defaultIntent(), R.string.checking_one_story, model.title)
        val newModel = updateStory(model).await()
        if (newModel !is Empty) {
          model = newModel.get()
          Notifications.updatedStories(listOf(model))
        } else {
          Notifications.UPDATING.cancel()
          Notifications.updatedStories(emptyList())
        }
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
      database.upsertStory(model).await()
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
