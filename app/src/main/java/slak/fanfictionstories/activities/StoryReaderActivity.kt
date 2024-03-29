package slak.fanfictionstories.activities

import android.content.Intent
import android.content.res.TypedArray
import android.os.Bundle
import android.os.Parcelable
import android.text.Html
import android.text.Spanned
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.viewModels
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.commit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.anko.contentView
import org.jetbrains.anko.db.DoubleParser
import org.jetbrains.anko.db.select
import org.jetbrains.anko.longToast
import org.jetbrains.anko.startActivity
import org.jsoup.Jsoup
import slak.fanfictionstories.*
import slak.fanfictionstories.Notifications.Companion.defaultIntent
import slak.fanfictionstories.activities.ReaderViewModel.ChapterEvent.*
import slak.fanfictionstories.activities.ReaderViewModel.Companion.UNINITIALIZED_CHAPTER
import slak.fanfictionstories.data.*
import slak.fanfictionstories.data.fetchers.*
import slak.fanfictionstories.data.fetchers.ParserUtils.parseStoryModel
import slak.fanfictionstories.databinding.ActivityStoryReaderBinding
import slak.fanfictionstories.utility.*
import kotlin.coroutines.CoroutineContext

/** Handles the data required to display story chapters. */
class ReaderViewModel(sModel: StoryModel) : ViewModel(), CoroutineScope {
  override val coroutineContext: CoroutineContext
    get() = Dispatchers.Default

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

  private val _chapterEvents = MutableLiveData<ChapterEvent>()
  val chapterEvents: LiveData<ChapterEvent> get() = _chapterEvents

  var searchQuery = ""
    private set

  var searchMatches = listOf<Area>()
    private set

  var searchCurrentMatchIdx: Int = 0

  companion object {
    const val UNINITIALIZED_CHAPTER = -454L
    private const val TAG = "ReaderViewModel"
  }

  init {
    storyModel = sModel
  }

  /** Update the [searchMatches] according to the new search string. */
  @AnyThread
  fun searchInChapter(chapterSpanned: Spanned, searchQuery: String) {
    this.searchQuery = searchQuery
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
  fun changeChapter(chapterToRead: Long) = launch(Main) {
    _chapterEvents.it = CHAPTER_LOAD_STARTED
    if (chapterToRead != currentChapter) {
      val html = getChapterHtml(chapterToRead)

      if (html == null) {
        _chapterEvents.it = CHAPTER_RELOADED
        return@launch
      }

      chapterHtml = html
    }
    _chapterEvents.it = when {
      currentChapter == UNINITIALIZED_CHAPTER -> CHAPTER_FIRST_LOAD
      chapterToRead == currentChapter -> CHAPTER_RELOADED
      else -> CHAPTER_CHANGED
    }
    Log.v(TAG, "toRead: $chapterToRead, current: $currentChapter | ${_chapterEvents.it}")
    currentChapter = chapterToRead
  }

  @Suppress("RedundantNullableReturnType") // This inspection is wrong
  @AnyThread
  private suspend fun getChapterHtml(chapterToRead: Long): String? {
    // If the story is remote and it gets updated, the downloaded chapters may be outdated, so delete and re-download
    if (storyModel.status == StoryStatus.REMOTE && storyModel.fragment.updateTime > storyModel.lastReadTime ?: 0) {
      deleteStory(storyModel.storyId)
    }
    return readChapter(storyModel.storyId, chapterToRead).orElse {
      val chapterHtmlText = fetchChapter(storyModel.storyId, chapterToRead) ?: return@getChapterHtml null

      val text = extractChapterText(Jsoup.parse(chapterHtmlText))
      writeChapter(storyModel.storyId, chapterToRead, text)
      // Get the model too if we need it
      if (storyModel.status != StoryStatus.LOCAL) {
        storyModel = parseStoryModel(chapterHtmlText, storyModel.storyId, storyModel)
        Static.database.upsertModel(storyModel)
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
  fun tryLoadingModelFromDatabase() = launch(Dispatchers.Default) {
    // If the story is in the db, use it
    storyModel = Static.database.storyById(storyModel.storyId).await().orElse(storyModel)
  }

  /** @see fetchAndWriteStory */
  @AnyThread
  fun downloadStoryLocally() = launch(Dispatchers.Default) {
    storyModel = fetchAndWriteStory(storyModel.storyId).orElse {
      Static.currentActivity!!.longToast(R.string.story_not_found)
      return@launch
    }
  }

  /** @see slak.fanfictionstories.data.fetchers.updateStory */
  @AnyThread
  fun updateStory() = launch(Dispatchers.Default) {
    Notifications.UPDATING.show(defaultIntent(), R.string.checking_one_story, storyModel.title)
    val newModel = updateStory(storyModel)
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
class StoryReaderActivity : CoroutineScopeActivity(), ISearchableActivity, IHasLoadingBar {
  private lateinit var binding: ActivityStoryReaderBinding

  private lateinit var storyModel: StoryModel
  private val viewModel: ReaderViewModel by viewModels { ViewModelFactory(storyModel) }
  private lateinit var searchUI: SearchUIFragment

  override lateinit var loading: ProgressBar

  /**
   * Gets the data required for the [ViewModel]. It will load from the intent URI (clicked link to story), or from the
   * intent extras (in-app navigation).
   * @returns the initial chapter to view, or [UNINITIALIZED_CHAPTER] if we are responding to a link, and the linked
   * story doesn't exist
   */
  @UiThread
  private suspend fun obtainModel(): Long = when (intent.action) {
    // Responding to links
    Intent.ACTION_VIEW -> {
      val pathSegments = intent.data?.pathSegments ?: throw IllegalArgumentException("Intent data is empty")
      if (pathSegments.size > 3) title = pathSegments[3]
      storyModel = fetchStoryModel(pathSegments[1].toLong()).orElse {
        longToast(R.string.story_not_found)
        return UNINITIALIZED_CHAPTER
      }
      val currentChapter = pathSegments[2].toLong()
      Static.database.upsertModel(storyModel)
      Log.v(TAG, "Model from link: $intent, resolved model: $storyModel")
      currentChapter
    }
    // In-app navigation
    else -> {
      storyModel = intent.getParcelableExtra(INTENT_STORY_MODEL)
          ?: throw IllegalArgumentException("Story model missing from extras")
      val currentChapter = if (storyModel.progress.currentChapter == 0L) 1L else storyModel.progress.currentChapter
      Log.v(TAG, "Model from intent extra: $storyModel")
      currentChapter
    }
  }

  /** Initializes the [SearchUIFragment] fragment. */
  @UiThread
  private fun initSearch() {
    val oldHighlighter = supportFragmentManager.findFragmentByTag(TAG_SEARCH_FRAGMENT) as? SearchUIFragment
    if (oldHighlighter != null) {
      searchUI = oldHighlighter
    } else {
      searchUI = SearchUIFragment()
      supportFragmentManager.commit {
        add(R.id.rootLayout, searchUI, TAG_SEARCH_FRAGMENT)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityStoryReaderBinding.inflate(layoutInflater)
    setContentView(binding.root)
    setSupportActionBar(binding.toolbar)
    setLoadingView(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    Static.wvViewModel.addWebView(binding.rootLayout)

    launch(Main) {
      val initialChapter = obtainModel()
      // No story there, leave activity
      if (initialChapter == UNINITIALIZED_CHAPTER) {
        finish()
        return@launch
      }

      // Setup chapter switching buttons
      binding.readerContent.prevChapterBtn.setOnClickListener { viewModel.changeChapter(viewModel.currentChapter - 1) }
      binding.readerContent.nextChapterBtn.setOnClickListener {
        if (binding.readerContent.nextChapterBtn.text == str(R.string.next)) {
          viewModel.changeChapter(viewModel.currentChapter + 1)
          return@setOnClickListener
        }
        val model = runBlocking {
          val queue = database.getStoryQueue()
          val currentIdxInQueue = queue.first { it.first == viewModel.storyModel.storyId }.second
          val nextStory = queue.first { it.second == currentIdxInQueue + 1 }
          database.storyById(nextStory.first).await()
              .orElseThrow(IllegalStateException("Story queue inconsistent"))
        }
        val intent = Intent(this@StoryReaderActivity, StoryReaderActivity::class.java)
        intent.putExtra(INTENT_STORY_MODEL, model as Parcelable)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
      }
      binding.readerContent.selectChapterBtn.setOnClickListener { showChapterSelectDialog() }

      // Save story for the resume button
      // Even for transient stories, because entering the reader means the story became remote
      Prefs.resumeStoryId = viewModel.storyModel.storyId

      binding.toolbarLayout.post {
        setToolbarHeight()
      }

      // Set title, make sure it is in the right position
      binding.toolbarLayout.title = viewModel.storyModel.title
      binding.toolbarLayout.expandedTitleMarginTop = getActionBarSize()

      if (viewModel.storyModel.status != StoryStatus.LOCAL) {
        viewModel.tryLoadingModelFromDatabase().join()
      }

      if (viewModel.currentChapter == UNINITIALIZED_CHAPTER) viewModel.changeChapter(initialChapter).join()
      else viewModel.changeChapter(viewModel.currentChapter).join()
      initSearch()

      viewModel.chapterEvents.observe(this@StoryReaderActivity, ::handleChapterEvent)
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable(INTENT_STORY_MODEL, storyModel)
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    storyModel = savedInstanceState.getParcelable(INTENT_STORY_MODEL)!!
  }

  override fun onPause() {
    super.onPause()
    if (viewModel.storyModel.status == StoryStatus.LOCAL) {
      // Update last time the story was read
      database.updateInStory(viewModel.storyModel.storyId, "lastReadTime" to System.currentTimeMillis())
    }
  }

  @UiThread
  private fun setToolbarHeight() {
    // Magic for dealing with text height
    // Let's pretend we're not using reflection for this
    val toolbarLayout = binding.toolbarLayout
    val toolbarField = binding.toolbarLayout::class.java.declaredFields.first { it.name == "toolbar" }
    toolbarField.isAccessible = true
    val titleTextView = ((toolbarField.get(toolbarLayout) as ViewGroup).getChildAt(0) as AppCompatTextView)
    val fakeWidth = titleTextView.textSize * viewModel.storyModel.title.length
    val textMargin = resources.px(R.dimen.text_margin)
    val textHeight = (fakeWidth / (binding.toolbarLayout.width / 2)).coerceAtLeast(1F) * titleTextView.lineHeight
    binding.appBar.updateLayoutParams {
      height = (resources.px(R.dimen.app_bar_height_base) + textHeight + textMargin).toInt()
    }
  }

  @UiThread
  private fun getActionBarSize(): Int {
    val ta: TypedArray = obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
    val toolBarHeight = ta.getDimensionPixelSize(0, -1)
    ta.recycle()
    return toolBarHeight
  }

  @UiThread
  private fun setChapterMetaText() = with(viewModel.storyModel) {
    val chapterWordCount = autoSuffixNumber(viewModel.chapterHtml.split(" ").size)
    binding.readerContent.chapterWordCountText.text = str(R.string.x_words, chapterWordCount)
    binding.readerContent.currentChapterText.text = str(R.string.chapter_progress,
        viewModel.currentChapter, fragment.chapterCount)
    val avgWordCount: Double = fragment.wordCount.toDouble() / fragment.chapterCount
    val wordsPassedEstimate: Double = (viewModel.currentChapter - 1) * avgWordCount
    binding.readerContent.approxWordCountRemainText.text = str(R.string.approx_x_words_left,
        autoSuffixNumber(fragment.wordCount - wordsPassedEstimate.toLong()))
    // This data is more or less useless with only one chapter, so we hide it
    val extraDataVisibility = if (fragment.chapterCount == 1L) View.GONE else View.VISIBLE
    binding.readerContent.chapterWordCountText.visibility = extraDataVisibility
    binding.readerContent.currentChapterText.visibility = extraDataVisibility
    binding.readerContent.approxWordCountRemainText.visibility = extraDataVisibility

    // Set chapter's title (chapterToRead is 1-indexed)
    if (fragment.chapterCount > 1) {
      binding.readerContent.chapterTitleText.text = chapterTitles()[viewModel.currentChapter.toInt() - 1]
    }
    // Don't show it if there is no title (otherwise there are leftover margins/padding)
    binding.readerContent.chapterTitleText.visibility =
        if (binding.readerContent.chapterTitleText.text == "") View.GONE else View.VISIBLE
  }

  @UiThread
  private suspend fun restoreScrollStatus() {
    if (binding.readerContent.chapterText.textLayout == null) {
      Log.w(TAG, "Cannot restore scroll status because layout is null")
      return
    }
    val scrollAbs = database.useAsync {
      select("stories", "scrollAbsolute")
          .whereSimple("storyId = ?", viewModel.storyModel.storyId.toString())
          .parseOpt(DoubleParser)
    }.await()
    if (scrollAbs == null) {
      Log.w(TAG, "Absolute scroll position is null")
      return
    }
    chapterScroll(binding.readerContent.chapterText.scrollYFromScrollState(scrollAbs))
  }

  @UiThread
  private fun parseChapterHTML(): Spanned {
    return Html.fromHtml(viewModel.chapterHtml, Html.FROM_HTML_MODE_LEGACY,
        null, HrSpan.tagHandlerFactory(binding.readerContent.chapterText.width))
  }

  /** React to [ReaderViewModel.ChapterEvent]s from the model. */
  @UiThread
  private fun handleChapterEvent(it: ReaderViewModel.ChapterEvent): Unit = when (it) {
    CHAPTER_LOAD_STARTED -> {
      // Show loading things and disable chapter switching
      if (viewModel.storyModel.status != StoryStatus.LOCAL) showLoading()
      binding.readerContent.btnBarLoader.visibility = View.VISIBLE
      binding.readerContent.navButtons.visibility = View.GONE
      binding.readerContent.prevChapterBtn.isEnabled = false
      binding.readerContent.nextChapterBtn.isEnabled = false
      binding.readerContent.selectChapterBtn.isEnabled = false
      invalidateOptionsMenu()
    }
    CHAPTER_FIRST_LOAD -> {
      onChapterLoadFinished(true)
      Unit
    }
    CHAPTER_CHANGED -> {
      onChapterLoadFinished(false)
      Unit
    }
    CHAPTER_RELOADED -> {
      onChapterLoadFinished(true).invokeOnCompletion { err ->
        if (err != null) return@invokeOnCompletion
        val isVisible = searchUI.restoreState()
        binding.rootLayout.post {
          clearHighlights()
          highlightMatches()
          adjustNavForSearch(removeMargin = !isVisible)
        }
      }
      Unit
    }
  }

  @UiThread
  private suspend fun updateNextButtonText() {
    if (viewModel.currentChapter == viewModel.storyModel.fragment.chapterCount) {
      val queue = database.getStoryQueue()
      val currentInQueue = queue.firstOrNull { it.first == viewModel.storyModel.storyId }?.second
      if (currentInQueue == null || currentInQueue.toInt() == queue.size - 1) {
        binding.readerContent.nextChapterBtn.isEnabled = false
        return
      }
      binding.readerContent.nextChapterBtn.text = str(R.string.next_chapter_with_queue)
      binding.readerContent.nextChapterBtn.isEnabled = true
    } else {
      binding.readerContent.nextChapterBtn.text = str(R.string.next)
      binding.readerContent.nextChapterBtn.isEnabled = true
    }
  }

  @AnyThread
  private fun onChapterLoadFinished(restoreScroll: Boolean) = launch(Main) {
    // We use the width for the <hr> elements, so the layout should be done
    if (!ViewCompat.isLaidOut(binding.readerContent.chapterText)) binding.readerContent.chapterText.requestLayout()

    val chapterSpanned = parseChapterHTML()
    binding.readerContent.chapterText.setText(chapterSpanned, theme)
    if (viewModel.searchMatches.isNotEmpty()) viewModel.searchInChapter(chapterSpanned, viewModel.searchQuery)

    setChapterMetaText()

    // Scroll to where we left off if we just entered, or at the beginning for further navigation
    if (restoreScroll) restoreScrollStatus()
    else chapterScroll(0)

    database.updateInStory(viewModel.storyModel.storyId,
        "currentChapter" to viewModel.currentChapter).await()

    // Disable buttons if there is nowhere for them to go
    binding.readerContent.prevChapterBtn.isEnabled = viewModel.currentChapter != 1L
    updateNextButtonText()
    binding.readerContent.selectChapterBtn.isEnabled = viewModel.storyModel.fragment.chapterCount > 1L

    // Tint button icons grey if the buttons are disabled, white if not
    fun getColorFor(view: View) = if (view.isEnabled) R.color.white else R.color.textDisabled
    binding.readerContent.prevChapterBtn.drawableTint(
        getColorFor(binding.readerContent.prevChapterBtn),
        theme,
        Direction.LEFT
    )
    binding.readerContent.nextChapterBtn.drawableTint(
        getColorFor(binding.readerContent.nextChapterBtn),
        theme,
        Direction.RIGHT
    )
    binding.readerContent.selectChapterBtn.drawableTint(
        getColorFor(binding.readerContent.selectChapterBtn),
        theme,
        Direction.LEFT
    )

    // Handle the next/prev button states/colors in the appbar
    invalidateOptionsMenu()

    // If the text is so short the scroller doesn't need to scroll, max out progress right away
    if (binding.readerContent.nestedScroller.height > binding.readerContent.chapterText.textLayout!!.height) {
      scrollSaver.notifyChanged(100.0, 99999999.0)
    }

    // Record scroll status
    binding.readerContent.nestedScroller.setOnScrollChangeListener { scroller, _, scrollY: Int, _, _ ->
      val rawPercentage = scrollY * 100.0 / (binding.readerContent.scrollingLayout.measuredHeight - scroller.bottom)
      // Make sure that values >100 get clamped to 100
      val percentageScrolled = rawPercentage.coerceAtMost(100.0)
      val scrollAbs = binding.readerContent.chapterText.scrollStateFromScrollY(scrollY)
      scrollSaver.notifyChanged(percentageScrolled, scrollAbs)
    }

    // Hide loading things
    hideLoading()
    binding.readerContent.btnBarLoader.visibility = View.GONE
    binding.readerContent.navButtons.visibility = View.VISIBLE
  }

  @UiThread
  private fun chapterScroll(y: Int) {
    binding.appBar.setExpanded(y <= resources.px(R.dimen.app_bar_height))
    binding.readerContent.nestedScroller.post {
      binding.readerContent.nestedScroller.scrollTo(0, y)
    }
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
      launch(Dispatchers.Default) {
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
    menu.findItem(R.id.nextChapter).isEnabled = binding.readerContent.nextChapterBtn.isEnabled
    menu.findItem(R.id.nextChapter).title =
        if (binding.readerContent.nextChapterBtn.text == str(R.string.next)) str(R.string.next_chapter)
        else str(R.string.next_chapter_with_queue)
    menu.findItem(R.id.prevChapter).isEnabled = binding.readerContent.prevChapterBtn.isEnabled
    menu.findItem(R.id.selectChapter).isEnabled = binding.readerContent.selectChapterBtn.isEnabled
    menu.findItem(R.id.downloadLocal).isVisible = viewModel.storyModel.status != StoryStatus.LOCAL
    menu.findItem(R.id.checkForUpdate).isVisible = viewModel.storyModel.status == StoryStatus.LOCAL
    launch(Main) {
      val isInQueue = database.getStoryQueue().firstOrNull { it.first == viewModel.storyModel.storyId }
      val item = menu.findItem(R.id.toggleQueue)
      if (isInQueue != null) {
        item.isChecked = true
        item.title = str(R.string.remove_from_queue)
      } else {
        item.isChecked = false
        item.title = str(R.string.add_to_queue)
      }
    }
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
        binding.readerContent.nestedScroller.fling(0)
        binding.readerContent.nestedScroller.fullScroll(NestedScrollView.FOCUS_UP)
        binding.appBar.setExpanded(true)
      }
      R.id.goToBottom -> {
        binding.readerContent.nestedScroller.fullScroll(NestedScrollView.FOCUS_DOWN)
        binding.appBar.setExpanded(false)
      }
      R.id.selectChapter -> showChapterSelectDialog()
      R.id.nextChapter -> binding.readerContent.nextChapterBtn.callOnClick()
      R.id.prevChapter -> binding.readerContent.prevChapterBtn.callOnClick()
      R.id.toggleQueue -> launch(Main) {
        if (!item.isChecked) {
          val wasAdded = database.addToQueue(viewModel.storyModel.storyId)
          val str =
              if (wasAdded) str(R.string.added_story_from_queue, viewModel.storyModel.title)
              else str(R.string.already_in_queue)
          Snackbar.make(binding.readerContent.chapterText, str, Snackbar.LENGTH_LONG).show()
          item.title = str(R.string.remove_from_queue)
        } else {
          database.removeFromQueue(viewModel.storyModel.storyId)
          val str = str(R.string.removed_story_from_queue, viewModel.storyModel.title)
          Snackbar.make(binding.readerContent.chapterText, str, Snackbar.LENGTH_LONG).show()
          item.title = str(R.string.add_to_queue)
        }
      }
      R.id.setMarkerColor -> launch(Main) {
        val color = Static.database.getMarker(viewModel.storyModel.storyId).await().toInt()
        createMarkerColorDialog(color) {
          database.setMarker(viewModel.storyModel.storyId, it)
        }
      }
      R.id.searchChapter -> {
        searchUI.show()
        binding.rootLayout.post {
          adjustNavForSearch(removeMargin = false)
        }
      }
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
    binding.readerContent.chapterText.textLayout?.iterateDisplayedLines { lineIdx, lineRange ->
      if (viewModel.searchMatches[idx].startPosition in lineRange) {
        val baseline = binding.readerContent.chapterText.textLayout!!.getLineBounds(lineIdx, null)
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
    checkNotNull(binding.readerContent.chapterText.spannable) { "Can't highlight missing text" }
    viewModel.searchMatches.forEach {
      binding.readerContent.chapterText.spannable!!.setSpan(SearchHighlightSpan(false),
          it.startPosition, it.endPosition, SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (viewModel.searchMatches.isNotEmpty()) {
      val curr = viewModel.searchMatches[viewModel.searchCurrentMatchIdx]
      binding.readerContent.chapterText.spannable!!.setSpan(SearchHighlightSpan(true),
          curr.startPosition, curr.endPosition, SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    binding.readerContent.chapterText.invalidate()
  }

  override fun clearHighlights() {
    binding.readerContent.chapterText.spannable?.removeAllSpans(SearchHighlightSpan::class.java)
    binding.readerContent.chapterText.invalidate()
  }

  private fun adjustNavForSearch(removeMargin: Boolean) {
    val params = binding.readerContent.navButtons.layoutParams as LinearLayout.LayoutParams
    params.bottomMargin += searchUI.requireView().measuredHeight * (if (removeMargin) -1 else 1)
    binding.readerContent.navButtons.layoutParams = params
  }

  override fun onHide() {
    adjustNavForSearch(removeMargin = true)
  }

  companion object {
    private const val TAG = "StoryReaderActivity"
    const val INTENT_STORY_MODEL = "bundle"
    private const val TAG_SEARCH_FRAGMENT = "search"
  }
}
