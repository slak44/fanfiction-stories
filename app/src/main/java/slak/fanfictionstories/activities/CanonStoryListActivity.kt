package slak.fanfictionstories.activities

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import kotlinx.android.synthetic.main.activity_canon_story_list.*
import kotlinx.android.synthetic.main.dialog_ffnet_filter.view.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.*
import slak.fanfictionstories.StoryListItem.GroupTitle
import slak.fanfictionstories.StoryListItem.StoryCardData
import slak.fanfictionstories.fetchers.*
import slak.fanfictionstories.utility.*

class CanonListViewModel : StoryListViewModel() {
  val fetcher: CanonFetcher by lazy {
    CanonFetcher(CanonFetcher.Details(intent!!.extras.getParcelable(INTENT_LINK_DATA)))
  }
  private var currentPage: MutableLiveData<Int> = MutableLiveData()

  fun getPage(): LiveData<Int> = currentPage

  init {
    currentPage.it = 1
  }

  private fun getPage(page: Int): Deferred<List<StoryListItem>> = async2(CommonPool) {
    val pageData = fetcher.get(page).await().map {
      val model = Static.database.storyById(it.storyId).await()
          .orElse(null) ?: return@map StoryCardData(it)
      it.progress = model.progress
      it.status = model.status
      return@map StoryCardData(it)
    }
    if (pageData.isEmpty()) return@async2 listOf<StoryListItem>()
    return@async2 listOf(GroupTitle(str(R.string.page_x, page)), *pageData.toTypedArray())
  }

  fun resetPagination() {
    currentPage.it = 1
  }

  fun getCurrentPage() = getPage(currentPage.it)
  fun getNextPage() = async2(UI) { getPage(++currentPage.it).await() }
}

class CanonStoryListActivity : LoadingActivity(), ReaderResumable by ReaderResumer() {
  private lateinit var viewModel: CanonListViewModel

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    viewModel = obtainViewModel()

    setContentView(R.layout.activity_canon_story_list)
    setSupportActionBar(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    canonStoryListView.adapter = StoryAdapter(viewModel)
    val layoutManager = LinearLayoutManager(this)
    canonStoryListView.layoutManager = layoutManager
    canonStoryListView.createStorySwipeHelper { enteredReader(it.storyId) }
    infinitePageScroll(canonStoryListView, layoutManager) {
      viewModel.addDeferredData(viewModel.getNextPage())
    }

    viewModel.fetcher.details.lang = Prefs.filterLanguage()

    setAppbarText()

    if (savedInstanceState == null) {
      triggerLoadUI()
    } else {
      onRestoreInstanceState(savedInstanceState)
      hideLoading()
    }
  }

  private fun triggerLoadUI() = launch(UI) {
    showLoading()
    viewModel.addData(viewModel.getCurrentPage().await())
    setAppbarText()
    hideLoading()
  }

  private fun setAppbarText() {
    title = viewModel.fetcher.canonTitle.orElse(str(R.string.loading___))
    viewModel.fetcher.unfilteredStories.ifPresent {
      supportActionBar?.subtitle = str(R.string.x_stories, it)
    }
  }

  override fun onResume() {
    super.onResume()
    updateOnResume(viewModel)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    saveInstanceState(outState)
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    restoreInstanceState(savedInstanceState)
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    menu.findItem(R.id.filter).iconTint(R.color.white, theme)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_canon_story_list, menu)
    return true
  }

  private var dialogOpened: Boolean = false
  private fun openFilterDialog() {
    // Don't open more than one dialog
    if (dialogOpened) return
    dialogOpened = true
    val layout = LayoutInflater.from(this)
        .inflate(R.layout.dialog_ffnet_filter, null, false)

    val strNone = str(R.string.none)
    val strAny = str(R.string.any)

    with(layout) {
      val genresEdit = resources.getStringArray(R.array.genres).toMutableList()
      genresEdit[0] = strNone
      notGenre.setEntries(genresEdit)

      val charNameList = viewModel.fetcher.charList.map { it.name }.toMutableList()
      charNameList[0] = strAny
      char1.setEntries(charNameList)
      char2.setEntries(charNameList)
      char3.setEntries(charNameList)
      char4.setEntries(charNameList)
      charNameList[0] = strNone
      notChar1.setEntries(charNameList)
      notChar2.setEntries(charNameList)

      sort.onSelect { _, pos -> viewModel.fetcher.details.sort = Sort.values()[pos] }
      timeRange.onSelect { _, pos -> viewModel.fetcher.details.timeRange = TimeRange.values()[pos] }
      genre1.onSelect { _, pos -> viewModel.fetcher.details.genre1 = Genre.values()[pos] }
      genre2.onSelect { _, pos -> viewModel.fetcher.details.genre2 = Genre.values()[pos] }
      rating.onSelect { _, pos -> viewModel.fetcher.details.rating = Rating.values()[pos] }
      status.onSelect { _, pos -> viewModel.fetcher.details.status = Status.values()[pos] }
      length.onSelect { _, pos -> viewModel.fetcher.details.wordCount = WordCount.values()[pos] }
      char1.onSelect { _, pos -> viewModel.fetcher.details.char1Id = viewModel.fetcher.charList[pos].id }
      char2.onSelect { _, pos -> viewModel.fetcher.details.char2Id = viewModel.fetcher.charList[pos].id }
      char3.onSelect { _, pos -> viewModel.fetcher.details.char3Id = viewModel.fetcher.charList[pos].id }
      char4.onSelect { _, pos -> viewModel.fetcher.details.char4Id = viewModel.fetcher.charList[pos].id }
      notChar1.onSelect { _, pos -> viewModel.fetcher.details.char1Without = viewModel.fetcher.charList[pos].id }
      notChar2.onSelect { _, pos -> viewModel.fetcher.details.char2Without = viewModel.fetcher.charList[pos].id }
      notGenre.onSelect { _, pos -> viewModel.fetcher.details.genreWithout = Genre.values()[pos] }
      language.onSelect { _, pos ->
        viewModel.fetcher.details.lang = Language.values()[pos]
        Prefs.use { it.putInt(Prefs.REMEMBER_LANG_ID, pos) }
      }

      sort.setSelection(Sort.values().indexOf(viewModel.fetcher.details.sort))
      timeRange.setSelection(TimeRange.values().indexOf(viewModel.fetcher.details.timeRange))
      genre1.setSelection(Genre.values().indexOf(viewModel.fetcher.details.genre1))
      genre2.setSelection(Genre.values().indexOf(viewModel.fetcher.details.genre2))
      rating.setSelection(Rating.values().indexOf(viewModel.fetcher.details.rating))
      language.setSelection(Language.values().indexOf(viewModel.fetcher.details.lang))
      status.setSelection(Status.values().indexOf(viewModel.fetcher.details.status))
      length.setSelection(WordCount.values().indexOf(viewModel.fetcher.details.wordCount))
      char1.setSelection(viewModel.fetcher.charList.indexOfFirst { it.id == viewModel.fetcher.details.char1Id })
      char2.setSelection(viewModel.fetcher.charList.indexOfFirst { it.id == viewModel.fetcher.details.char2Id })
      char3.setSelection(viewModel.fetcher.charList.indexOfFirst { it.id == viewModel.fetcher.details.char3Id })
      char4.setSelection(viewModel.fetcher.charList.indexOfFirst { it.id == viewModel.fetcher.details.char4Id })

      viewModel.fetcher.worldList.ifPresent { wl ->
        val worldNameList = wl.map { it.name }.toMutableList()

        worldNameList[0] = strAny
        world.setEntries(worldNameList)

        worldNameList[0] = strNone
        notWorld.setEntries(worldNameList)

        world.onSelect { _, pos -> viewModel.fetcher.details.worldId = wl[pos].id }
        world.setSelection(wl.indexOfFirst { it.id == viewModel.fetcher.details.worldId })

        notWorld.onSelect { _, pos -> viewModel.fetcher.details.worldWithout = wl[pos].name }
        if (viewModel.fetcher.details.worldWithout != null) {
          notWorld.setSelection(worldNameList.indexOf(viewModel.fetcher.details.worldWithout!!))
        }
      }
      val worldSpinnerState = if (viewModel.fetcher.worldList.isPresent) View.VISIBLE else View.GONE
      world.visibility = worldSpinnerState
      notWorld.visibility = worldSpinnerState
      worldText.visibility = worldSpinnerState
      notWorldText.visibility = worldSpinnerState

      if (viewModel.fetcher.details.genreWithout != null) {
        notGenre.setSelection(Genre.values().indexOf(viewModel.fetcher.details.genreWithout!!))
      }
      if (viewModel.fetcher.details.char1Without != null) {
        notChar1.setSelection(charNameList.indexOf(viewModel.fetcher.details.char1Without!!))
      }
      if (viewModel.fetcher.details.char2Without != null) {
        notChar2.setSelection(charNameList.indexOf(viewModel.fetcher.details.char2Without!!))
      }
    }

    AlertDialog.Builder(this)
        .setTitle(R.string.filter_by)
        .setOnDismissListener { dialogOpened = false }
        .setPositiveButton(R.string.native_filter_btn, { dialog, _ ->
          viewModel.clearData()
          viewModel.resetPagination()
          triggerLoadUI()
          dialog.dismiss()
        })
        .setView(layout)
        .show()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.filter -> if (viewModel.fetcher.charList.isNotEmpty()) openFilterDialog()
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}
