package slak.fanfictionstories.activities

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
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
    CanonFetcher(intent!!.extras.getParcelable(INTENT_LINK_DATA))
  }
  private var currentPage: MutableLiveData<Int> = MutableLiveData()

  fun getPage(): LiveData<Int> = currentPage

  init {
    currentPage.it = 1
  }

  private fun getPage(page: Int): Deferred<List<StoryListItem>> = async2(CommonPool) {
    val pageData = fetcher.get(page).await().map {
      val model = Static.database.storyById(it.storyId).await()
          .orNull() ?: return@map StoryCardData(it)
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
      viewModel.addDeferredItems(viewModel.getNextPage())
    }

    if (Prefs.filterLanguage()) viewModel.fetcher.lang = Prefs.preferredLanguage()

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
    viewModel.addItems(viewModel.getCurrentPage().await())
    setAppbarText()
    hideLoading()
  }

  private fun setAppbarText() {
    title = viewModel.fetcher.canonTitle.orElse(str(R.string.loading___))
    viewModel.fetcher.unfilteredStoryCount.ifPresent {
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

    with(viewModel.fetcher) {
      val genresEdit = resources.getStringArray(R.array.genres).toMutableList()
      genresEdit[0] = strNone
      layout.notGenre.setEntries(genresEdit)

      layout.sort.onSelect { _, pos -> sort = Sort.values()[pos] }
      layout.timeRange.onSelect { _, pos -> timeRange = TimeRange.values()[pos] }
      layout.genre1.onSelect { _, pos -> genre1 = Genre.values()[pos] }
      layout.genre2.onSelect { _, pos -> genre2 = Genre.values()[pos] }
      layout.rating.onSelect { _, pos -> rating = Rating.values()[pos] }
      layout.status.onSelect { _, pos -> status = Status.values()[pos] }
      layout.length.onSelect { _, pos -> wordCount = WordCount.values()[pos] }
      layout.notGenre.onSelect { _, pos -> genreWithout = Genre.values()[pos].opt() }
      layout.language.onSelect { _, pos ->
        lang = Language.values()[pos]
        Prefs.use { it.putInt(Prefs.REMEMBER_LANG_ID, pos) }
      }

      layout.sort.setSelection(Sort.values().indexOf(sort))
      layout.timeRange.setSelection(TimeRange.values().indexOf(timeRange))
      layout.genre1.setSelection(Genre.values().indexOf(genre1))
      layout.genre2.setSelection(Genre.values().indexOf(genre2))
      layout.rating.setSelection(Rating.values().indexOf(rating))
      layout.language.setSelection(Language.values().indexOf(lang))
      layout.status.setSelection(Status.values().indexOf(status))
      layout.length.setSelection(WordCount.values().indexOf(wordCount))
      genreWithout.ifPresent { layout.notGenre.setSelection(Genre.values().indexOf(it)) }

      charList.ifPresent {
        val charNameList = it.map { it.name }.toMutableList()
        charNameList[0] = strAny
        layout.char1.setEntries(charNameList)
        layout.char2.setEntries(charNameList)
        layout.char3.setEntries(charNameList)
        layout.char4.setEntries(charNameList)
        charNameList[0] = strNone
        layout.notChar1.setEntries(charNameList)
        layout.notChar2.setEntries(charNameList)
        layout.char1.onSelect { _, pos -> char1Id = charList.get()[pos].id }
        layout.char2.onSelect { _, pos -> char2Id = charList.get()[pos].id }
        layout.char3.onSelect { _, pos -> char3Id = charList.get()[pos].id }
        layout.char4.onSelect { _, pos -> char4Id = charList.get()[pos].id }
        layout.notChar1.onSelect { _, pos -> char1Without = charList.get()[pos].id.opt() }
        layout.notChar2.onSelect { _, pos -> char2Without = charList.get()[pos].id.opt() }
        layout.char1.setSelection(charList.get().indexOfFirst { it.id == char1Id })
        layout.char2.setSelection(charList.get().indexOfFirst { it.id == char2Id })
        layout.char3.setSelection(charList.get().indexOfFirst { it.id == char3Id })
        layout.char4.setSelection(charList.get().indexOfFirst { it.id == char4Id })
        char1Without.ifPresent { layout.notChar1.setSelection(charNameList.indexOf(it)) }
        char2Without.ifPresent { layout.notChar2.setSelection(charNameList.indexOf(it)) }
      }
      val charSpinnerState = if (charList is Empty) View.GONE else View.VISIBLE
      listOf(layout.char1, layout.char2, layout.char3, layout.char4, layout.notChar1,
          layout.notChar2, layout.char1Text, layout.char2Text, layout.char3Text, layout.char4Text,
          layout.notChar1Text, layout.notChar2Text).forEach {
        it.visibility = charSpinnerState
      }

      worldList.ifPresent { wl ->
        val worldNameList = wl.map { it.name }.toMutableList()

        worldNameList[0] = strAny
        layout.world.setEntries(worldNameList)

        worldNameList[0] = strNone
        layout.notWorld.setEntries(worldNameList)

        layout.world.onSelect { _, pos -> worldId = wl[pos].id }
        layout.world.setSelection(wl.indexOfFirst { it.id == worldId })

        layout.notWorld.onSelect { _, pos -> worldWithout = wl[pos].name.opt() }

        worldWithout.ifPresent {
          layout.notWorld.setSelection(worldNameList.indexOf(it))
        }
      }
      val worldSpinnerState = if (worldList is Empty) View.GONE else View.VISIBLE
      layout.world.visibility = worldSpinnerState
      layout.notWorld.visibility = worldSpinnerState
      layout.worldText.visibility = worldSpinnerState
      layout.notWorldText.visibility = worldSpinnerState
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
      R.id.filter -> viewModel.fetcher.pageCount.ifPresent { openFilterDialog() }
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}
