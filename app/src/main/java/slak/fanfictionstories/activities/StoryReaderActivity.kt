package slak.fanfictionstories.activities

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.support.annotation.AnyThread
import android.support.annotation.UiThread
import android.support.v4.view.ViewCompat
import android.support.v4.widget.NestedScrollView
import android.support.v7.app.AlertDialog
import android.text.Html
import android.text.Spanned
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_story_reader.*
import kotlinx.android.synthetic.main.activity_story_reader_content.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.sync.Mutex
import org.jetbrains.anko.contentView
import org.jetbrains.anko.db.DoubleParser
import org.jetbrains.anko.db.select
import org.jsoup.Jsoup
import slak.fanfictionstories.Notifications
import slak.fanfictionstories.Notifications.Companion.defaultIntent
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.StoryStatus
import slak.fanfictionstories.activities.ReaderViewModel.ChapterEvent.*
import slak.fanfictionstories.activities.ReaderViewModel.Companion.UNINITIALIZED_CHAPTER
import slak.fanfictionstories.data.*
import slak.fanfictionstories.data.fetchers.*
import slak.fanfictionstories.data.fetchers.FetcherUtils.parseStoryModel
import slak.fanfictionstories.utility.*

/** Handles the data required to display story chapters. */
class ReaderViewModel(sModel: StoryModel) : ViewModel() {
  var storyModel: StoryModel
    private set

  var currentChapter: Long = UNINITIALIZED_CHAPTER
    private set

  lateinit var chapterHtml: String
    private set

  enum class ChapterEvent {
    CHAPTER_LOAD_STARTED,
    CHAPTER_FIRST_LOAD,
    CHAPTER_CHANGED,
    CHAPTER_RELOADED
  }
  private var _chapterEvents = MutableLiveData<ChapterEvent>()
  val chapterEvents: LiveData<ChapterEvent> get() = _chapterEvents

  var searchMatches = listOf<Area>()
    private set

  var searchCurrentMatchIdx: Int = 0

  companion object {
    const val UNINITIALIZED_CHAPTER = -454L
  }

  init {
    storyModel = sModel
  }

  /** Update the [searchMatches] according to the new search string. */
  @AnyThread
  fun searchInChapter(chapterSpanned: Spanned, searchQuery: String) {
    searchCurrentMatchIdx = 0
    if (searchQuery.isEmpty()) searchMatches = listOf()
    var startIdx = chapterSpanned.indexOf(searchQuery)
    val results = mutableListOf<Area>()
    while (startIdx != -1) {
      val p = Area(startIdx, searchQuery.length)
      results.add(p)
      startIdx = chapterSpanned.indexOf(searchQuery, p.endPosition)
    }
    searchMatches = results
  }

  /** Load a particular chapter. */
  @AnyThread
  fun changeChapter(chapterToRead: Long) = launch(UI) {
    _chapterEvents.it = CHAPTER_LOAD_STARTED
    if (chapterToRead != currentChapter) chapterHtml = getChapterHtml(chapterToRead).await()
    _chapterEvents.it = when {
      currentChapter == UNINITIALIZED_CHAPTER -> CHAPTER_FIRST_LOAD
      chapterToRead == currentChapter -> CHAPTER_RELOADED
      else -> CHAPTER_CHANGED
    }
    currentChapter = chapterToRead
  }

  @AnyThread
  private fun getChapterHtml(chapterToRead: Long): Deferred<String> = async2(CommonPool) {
    readChapter(storyModel.storyId, chapterToRead).orElse {
      val chapterHtmlText = fetchChapter(storyModel.storyId, chapterToRead).await()
      val text = extractChapterText(Jsoup.parse(chapterHtmlText))
      writeChapter(storyModel.storyId, chapterToRead, text)
      // Get the model too if we need it
      if (storyModel.status == StoryStatus.TRANSIENT) {
        storyModel = parseStoryModel(chapterHtmlText, storyModel.storyId)
        Static.database.upsertStory(storyModel).await()
      }
      // If all chapters are on disk, set to local
      if (chapterCount(storyModel.storyId) == storyModel.fragment.chapterCount.toInt()) {
        storyModel.status = StoryStatus.LOCAL
        Static.database.updateInStory(storyModel.storyId, "status" to "local")
      }
      return@orElse text
    }
  }

  /**
   * If the story already exists in the database, fetch it and replace the current [StoryModel].
   * This ensures that the [StoryStatus.TRANSIENT] models get replaced with real ones.
   */
  @AnyThread
  fun tryLoadingModelFromDatabase() = launch(CommonPool) {
    // If the story is in the db, use it
    storyModel = Static.database.storyById(storyModel.storyId).await().orElse(storyModel)
  }

  /** @see fetchAndWriteStory */
  @AnyThread
  fun downloadStoryLocally() = launch(CommonPool) {
    storyModel = fetchAndWriteStory(storyModel.storyId).await().orElse {
      Toast.makeText(Static.currentActivity, R.string.story_not_found, Toast.LENGTH_LONG).show()
      return@launch
    }
  }

  /** @see slak.fanfictionstories.data.fetchers.updateStory */
  @AnyThread
  fun updateStory() = launch(CommonPool) {
    Notifications.UPDATING.show(defaultIntent(), R.string.checking_one_story, storyModel.title)
    val newModel = updateStory(storyModel).await()
    if (newModel !is Empty) {
      storyModel = newModel.get()
      Notifications.updatedStories(listOf(storyModel))
    } else {
      Notifications.UPDATING.cancel()
      Notifications.updatedStories(emptyList())
    }
  }
}

/** Shows a chapter of a story for reading. */
class StoryReaderActivity : LoadingActivity(), SearchableActivity {
  companion object {
    private const val TAG = "StoryReaderActivity"
    const val INTENT_STORY_MODEL = "bundle"
    private const val TAG_SEARCH_FRAGMENT = "search"
  }

  private lateinit var viewModel: ReaderViewModel
  private lateinit var searchUI: SearchUIFragment

  /**
   * Gets the data required for the [ViewModel]. It will load from the intent URI (clicked link to
   * story), or from the intent extras (in-app navigation).
   * @returns the initial chapter to view
   */
  @UiThread
  private suspend fun obtainModel(): Long = when {
    intent.action == Intent.ACTION_VIEW -> {
      val pathSegments = intent.data?.pathSegments
          ?: throw IllegalArgumentException("Intent data is empty")
      if (pathSegments.size > 3) title = pathSegments[3]
      val model = fetchStoryModel(pathSegments[1].toLong()).await().orElse {
        Toast.makeText(this, R.string.story_not_found, Toast.LENGTH_LONG).show()
        return UNINITIALIZED_CHAPTER
      }
      val currentChapter = pathSegments[2].toLong()
      viewModel = obtainViewModel(model)
      Static.database.upsertModel(model).join()
      Log.v(TAG, "Model from link: $intent, resolved model: $model")
      currentChapter
    }
    else -> {
      val model = intent.getParcelableExtra<StoryModel>(INTENT_STORY_MODEL)
          ?: throw IllegalArgumentException("Story model missing from extras")
      val currentChapter =
          if (model.progress.currentChapter == 0L) 1L else model.progress.currentChapter
      viewModel = obtainViewModel(model)
      Log.v(TAG, "Model from intent extra: $model")
      currentChapter
    }
  }

  /** Initializes the [SearchUIFragment] fragment. */
  @UiThread
  private fun initSearch() {
    val oldHighlighter = supportFragmentManager
        .findFragmentByTag(TAG_SEARCH_FRAGMENT) as? SearchUIFragment
    if (oldHighlighter != null) {
      searchUI = oldHighlighter
    } else {
      searchUI = SearchUIFragment()
      supportFragmentManager.beginTransaction()
          .add(R.id.rootLayout, searchUI, TAG_SEARCH_FRAGMENT).commit()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_story_reader)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    launch(UI) {
      val initialChapter = obtainModel()
      // No story there, leave activity
      if (initialChapter == UNINITIALIZED_CHAPTER) {
        finish()
        return@launch
      }

      viewModel.chapterEvents.observe(this@StoryReaderActivity) {
        when (it) {
          CHAPTER_LOAD_STARTED -> {
            // Show loading things and disable chapter switching
            if (viewModel.storyModel.status != StoryStatus.LOCAL) showLoading()
            btnBarLoader.visibility = View.VISIBLE
            navButtons.visibility = View.GONE
            prevChapterBtn.isEnabled = false
            nextChapterBtn.isEnabled = false
            selectChapterBtn.isEnabled = false
            invalidateOptionsMenu()
          }
          CHAPTER_CHANGED -> onChapterLoadFinished(false)
          CHAPTER_FIRST_LOAD, CHAPTER_RELOADED -> {
            onChapterLoadFinished(true).invokeOnCompletion { err ->
              if (err == null) searchUI.restoreState()
            }
          }
        }
      }

      // Setup chapter switching buttons
      prevChapterBtn.setOnClickListener { viewModel.changeChapter(viewModel.currentChapter - 1) }
      nextChapterBtn.setOnClickListener { viewModel.changeChapter(viewModel.currentChapter + 1) }
      selectChapterBtn.setOnClickListener { showChapterSelectDialog() }

      // Save story for the resume button
      // Even for transient stories, because entering the reader means the story became remote
      Prefs.resumeStoryId = viewModel.storyModel.storyId

      // Long titles require _even more_ space than CollapsibleToolbar already gives
      // The 35 character limit is completely arbitrary
      if (viewModel.storyModel.title.length > 35) {
        appBar.layoutParams.height = resources.px(R.dimen.app_bar_large_text_height)
      }

      toolbarLayout.title = viewModel.storyModel.title

      if (viewModel.storyModel.status != StoryStatus.LOCAL) {
        viewModel.tryLoadingModelFromDatabase().join()
      }
      viewModel.changeChapter(initialChapter).join()
      initSearch()
    }
  }

  override fun onPause() {
    super.onPause()
    if (viewModel.storyModel.status == StoryStatus.LOCAL) {
      // Update last time the story was read
      database.updateInStory(viewModel.storyModel.storyId,
          "lastReadTime" to System.currentTimeMillis())
    }
  }

  @UiThread
  private fun setChapterMetaText() = with(viewModel.storyModel) {
    val chapterWordCount = autoSuffixNumber(viewModel.chapterHtml.split(" ").size)
    chapterWordCountText.text = str(R.string.x_words, chapterWordCount)
    currentChapterText.text = str(R.string.chapter_progress,
        viewModel.currentChapter, fragment.chapterCount)
    val avgWordCount: Double = fragment.wordCount.toDouble() / fragment.chapterCount
    val wordsPassedEstimate: Double = (viewModel.currentChapter - 1) * avgWordCount
    approxWordCountRemainText.text = str(R.string.approx_x_words_left,
        autoSuffixNumber(fragment.wordCount - wordsPassedEstimate.toLong()))
    // This data is more or less useless with only one chapter, so we hide it
    val extraDataVisibility = if (fragment.chapterCount == 1L) View.GONE else View.VISIBLE
    chapterWordCountText.visibility = extraDataVisibility
    currentChapterText.visibility = extraDataVisibility
    approxWordCountRemainText.visibility = extraDataVisibility

    // Set chapter's title (chapterToRead is 1-indexed)
    if (fragment.chapterCount > 1) {
      chapterTitleText.text = chapterTitles()[viewModel.currentChapter.toInt() - 1]
    }
    // Don't show it if there is no title (otherwise there are leftover margins/padding)
    chapterTitleText.visibility = if (chapterTitleText.text == "") View.GONE else View.VISIBLE
  }

  @UiThread
  private suspend fun restoreScrollStatus() {
    if (chapterText.textLayout == null) {
      Log.w(TAG, "Cannot restore scroll status because layout is null")
      return
    }
    val scrollAbs = database.useAsync {
      select("stories", "scrollAbsolute")
          .whereSimple("storyId = ?", viewModel.storyModel.storyId.toString())
          .parseOpt(DoubleParser)
    }.await() ?: return
    chapterScroll(chapterText.scrollYFromScrollState(scrollAbs))
  }

  @UiThread
  private fun parseChapterHTML(): Spanned {
    return Html.fromHtml(viewModel.chapterHtml, Html.FROM_HTML_MODE_LEGACY,
        null, HrSpan.tagHandlerFactory(chapterText.width))
  }

  @AnyThread
  private fun onChapterLoadFinished(restoreScroll: Boolean) = launch(UI) {
    setChapterMetaText()

    // We use the width for the <hr> elements
    if (!ViewCompat.isLaidOut(chapterText)) chapterText.requestLayout()
    if (chapterText.width == 0) Log.w(TAG, "chapterText.width is 0!")

    // FIXME move into CommonPool if it proves too slow
    chapterText.setText(parseChapterHTML(), theme).join()

    // Scroll to where we left off if we just entered, or at the beginning for further navigation
    if (restoreScroll) restoreScrollStatus()
    else chapterScroll(0)

    database.updateInStory(viewModel.storyModel.storyId,
        "currentChapter" to viewModel.currentChapter).await()

    // Disable buttons if there is nowhere for them to go
    prevChapterBtn.isEnabled = viewModel.currentChapter != 1L
    nextChapterBtn.isEnabled =
        viewModel.currentChapter != viewModel.storyModel.fragment.chapterCount
    selectChapterBtn.isEnabled = viewModel.storyModel.fragment.chapterCount > 1L

    // Tint button icons grey if the buttons are disabled, white if not
    fun getColorFor(view: View) = if (view.isEnabled) R.color.white else R.color.textDisabled
    prevChapterBtn.drawableTint(getColorFor(prevChapterBtn), theme, Direction.LEFT)
    nextChapterBtn.drawableTint(getColorFor(nextChapterBtn), theme, Direction.RIGHT)
    selectChapterBtn.drawableTint(getColorFor(selectChapterBtn), theme, Direction.LEFT)

    // Handle the next/prev button states/colors in the appbar
    invalidateOptionsMenu()

    // If the text is so short the scroller doesn't need to scroll, max out progress right away
    if (nestedScroller.height > chapterText.textLayout!!.height) {
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

    // Hide loading things
    hideLoading()
    btnBarLoader.visibility = View.GONE
    navButtons.visibility = View.VISIBLE
  }

  @UiThread
  private fun chapterScroll(y: Int) {
    appBar.setExpanded(y <= resources.px(R.dimen.app_bar_height))
    nestedScroller.scrollTo(0, y)
  }

  /** Encapsulates the scroll state saving logic. */
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
          database.updateInStory(viewModel.storyModel.storyId,
              "scrollProgress" to percentageScrolledVal,
              "scrollAbsolute" to scrollAbsoluteVal).join()
        }
        // If this method is called right *here* on another thread, that call will be swallowed
        saveLock.unlock()
      }
    }
  }

  @UiThread
  private fun showChapterSelectDialog() {
    AlertDialog.Builder(this)
        .setTitle(R.string.select_chapter)
        .setSingleChoiceItems(viewModel.storyModel.chapterTitles().mapIndexed { idx, chapterTitle ->
          "${idx + 1}. $chapterTitle"
        }.toTypedArray(), (viewModel.currentChapter - 1).toInt()) { dialog, which: Int ->
          dialog.dismiss()
          // This means 'go to same chapter', so do nothing
          if (viewModel.currentChapter == which + 1L) return@setSingleChoiceItems
          viewModel.changeChapter(which + 1L)
        }.show()
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    if (isLoading()) return false
    menu.findItem(R.id.goToTop).iconTint(R.color.white, theme)
    menu.findItem(R.id.goToBottom).iconTint(R.color.white, theme)
    menu.findItem(R.id.nextChapter).isEnabled = nextChapterBtn.isEnabled
    menu.findItem(R.id.prevChapter).isEnabled = prevChapterBtn.isEnabled
    menu.findItem(R.id.selectChapter).isEnabled = selectChapterBtn.isEnabled
    menu.findItem(R.id.downloadLocal).isVisible = viewModel.storyModel.status != StoryStatus.LOCAL
    menu.findItem(R.id.checkForUpdate).isVisible = viewModel.storyModel.status == StoryStatus.LOCAL
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
      R.id.searchChapter -> searchUI.show()
      R.id.storyReviews -> {
        startActivity<ReviewsActivity>(
            ReviewsActivity.INTENT_STORY_MODEL to viewModel.storyModel as Parcelable,
            ReviewsActivity.INTENT_TARGET_CHAPTER to viewModel.currentChapter.toInt())
      }
      R.id.viewAuthor -> {
        startActivity<AuthorActivity>(
            AuthorActivity.INTENT_AUTHOR_ID to viewModel.storyModel.authorId,
            AuthorActivity.INTENT_AUTHOR_NAME to viewModel.storyModel.author)
      }
      R.id.downloadLocal -> viewModel.downloadStoryLocally()
      R.id.checkForUpdate -> viewModel.updateStory()
      R.id.deleteLocal -> undoableAction(contentView!!, R.string.data_deleted) {
        deleteStory(viewModel.storyModel.storyId)
        database.updateInStory(viewModel.storyModel.storyId, "status" to "remote")
        viewModel.storyModel.status = StoryStatus.REMOTE
      }
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }

  override fun getMatchCount(): Int = viewModel.searchMatches.size
  override fun getCurrentHighlight(): Int = viewModel.searchCurrentMatchIdx

  override fun navigateToHighlight(idx: Int) {
    chapterText.textLayout?.iterateDisplayedLines { lineIdx, lineRange ->
      if (viewModel.searchMatches[idx].startPosition in lineRange) {
        val baseline = chapterText.textLayout!!.getLineBounds(lineIdx, null)
        chapterScroll(baseline)
        return@iterateDisplayedLines true
      }
      return@iterateDisplayedLines false
    }
  }

  override fun setSearchQuery(query: String) = viewModel.searchInChapter(parseChapterHTML(), query)

  override fun updateCurrentHighlight(idx: Int) {
    viewModel.searchCurrentMatchIdx = idx
    highlightMatches()
  }

  private inner class SearchHighlightSpan(isCurrent: Boolean) : BackgroundColorSpan(
      getColor(if (isCurrent) R.color.textHighlightCurrent else R.color.textHighlightDefault))
  override fun highlightMatches() {
    if (chapterText.spannable == null) throw IllegalStateException("Can't highlight missing text")
    viewModel.searchMatches.forEach {
      chapterText.spannable!!.setSpan(SearchHighlightSpan(false),
          it.startPosition, it.endPosition, SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (viewModel.searchMatches.isNotEmpty()) {
      val curr = viewModel.searchMatches[viewModel.searchCurrentMatchIdx]
      chapterText.spannable!!.setSpan(SearchHighlightSpan(true),
          curr.startPosition, curr.endPosition, SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    chapterText.invalidate()
  }

  override fun clearHighlights() {
    chapterText.spannable?.removeAllSpans(SearchHighlightSpan::class.java)
    chapterText.invalidate()
  }
}
