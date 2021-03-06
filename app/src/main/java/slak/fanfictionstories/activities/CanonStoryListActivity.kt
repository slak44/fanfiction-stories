package slak.fanfictionstories.activities

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import androidx.activity.viewModels
import kotlinx.android.synthetic.main.activity_canon_story_list.*
import kotlinx.android.synthetic.main.dialog_ffnet_filter.view.*
import kotlinx.android.synthetic.main.loading_activity_indeterminate.*
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import slak.fanfictionstories.*
import slak.fanfictionstories.StoryListItem.GroupTitle
import slak.fanfictionstories.StoryListItem.StoryCardData
import slak.fanfictionstories.data.Prefs
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.*
import slak.fanfictionstories.utility.*

/** Stores the data required for a [CanonStoryListActivity]. */
class CanonListViewModel(val parentLink: CategoryLink) : StoryListViewModel() {
  private var currentPage: Int = 1

  var isFavorite: Boolean = false
    private set

  var metadata = CanonMetadata()
    private set

  val filters = CanonFilters()

  @AnyThread
  fun toggleFavorite(): Job {
    val link = CategoryLink(
        metadata.canonTitle.orElseThrow(IllegalStateException("Actual canon title missing")),
        parentLink.urlComponent,
        parentLink.storyCount
    )
    return launch(Main) {
      isFavorite = !isFavorite
      if (isFavorite) Static.database.addFavoriteCanon(link).await()
      else Static.database.removeFavoriteCanon(link).await()
    }
  }

  fun resetPagination() {
    currentPage = 1
  }

  suspend fun getCurrentPage() = getPage(currentPage)
  suspend fun getNextPage() = getPage(++currentPage)

  private suspend fun getPage(page: Int): List<StoryListItem> {
    val canonPage = getCanonPage(parentLink, filters, page)
    metadata = canonPage.metadata
    val favouriteDef = Static.database.isCanonFavorite(parentLink)
    val storyMap = canonPage.storyList.map { it.storyId to it }.toMap()
    for (dbStory in Static.database.storiesById(storyMap.keys).await()) {
      storyMap.getValue(dbStory.storyId).progress = dbStory.progress
      storyMap.getValue(dbStory.storyId).status = dbStory.status
    }
    isFavorite = favouriteDef.await()
    val storyData = storyMap.values.map { StoryCardData(it) }
    if (storyData.isEmpty()) return emptyList()
    return listOf(GroupTitle(str(R.string.page_x, page)), *storyData.toTypedArray())
  }
}

/** A list of stories within a canon. */
class CanonStoryListActivity : CoroutineScopeActivity(), IHasLoadingBar {
  override val loading: ProgressBar
    get() = activityProgressBar

  private val viewModel: CanonListViewModel by viewModels {
    ViewModelFactory(intent.getParcelableExtra(INTENT_LINK_DATA)!!)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_canon_story_list)
    setSupportActionBar(toolbar)
    setLoadingView(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    canonStoryListView.adapter = StoryAdapter(viewModel)
    val layoutManager = LinearLayoutManager(this)
    canonStoryListView.layoutManager = layoutManager
    canonStoryListView.createStorySwipeHelper()
    infinitePageScroll(canonStoryListView, layoutManager) {
      viewModel.addSuspendingItems { viewModel.getNextPage() }
    }

    if (Prefs.filterLanguage()) viewModel.filters.lang = Prefs.preferredLanguage

    setAppbarText()

    if (savedInstanceState == null) {
      triggerLoadUI().invokeOnCompletion { invalidateOptionsMenu() }
    } else {
      onRestoreInstanceState(savedInstanceState)
      hideLoading()
    }

    viewModel.defaultStoryListObserver.register()
  }

  override fun onDestroy() {
    super.onDestroy()
    viewModel.defaultStoryListObserver.unregister()
  }

  @AnyThread
  private fun triggerLoadUI() = launch(Main) {
    showLoading()
    viewModel.addItems(viewModel.getCurrentPage())
    viewModel.metadata.canonTitle.ifPresent {
      database.updateFavoriteCanon(
          CategoryLink(it, viewModel.parentLink.urlComponent, viewModel.parentLink.storyCount)).await()
    }
    setAppbarText()
    hideLoading()
  }

  @UiThread
  private fun setAppbarText() {
    title = viewModel.metadata.canonTitle.orElse(str(R.string.loading))
    viewModel.metadata.unfilteredStoryCount.ifPresent {
      supportActionBar?.subtitle = str(R.string.x_stories, it)
    }
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    menu.findItem(R.id.favorite).icon = resources.getDrawable(
        if (viewModel.isFavorite) R.drawable.ic_favorite_black_24dp
        else R.drawable.ic_favorite_border_black_24dp, theme)
    menu.findItem(R.id.filter).iconTint(R.color.white, theme)
    menu.findItem(R.id.favorite).iconTint(R.color.white, theme)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_canon_story_list, menu)
    return true
  }

  private var dialogOpened: Boolean = false
  @SuppressLint("InflateParams")
  private fun openFilterDialog() {
    // Don't open more than one dialog
    if (dialogOpened) return
    dialogOpened = true
    val layout = LayoutInflater.from(this)
        .inflate(R.layout.dialog_ffnet_filter, null, false)

    val strNone = str(R.string.none)
    val strAny = str(R.string.any)

    with(viewModel.filters) {
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
        Prefs.preferredLanguage = lang
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

      with(viewModel.metadata) {
        charList.ifPresent { list ->
          val charNameList = list.mapTo(mutableListOf()) { it.name }
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
      }
      val charSpinnerState = if (viewModel.metadata.charList is Empty) View.GONE else View.VISIBLE
      listOf(layout.char1, layout.char2, layout.char3, layout.char4, layout.notChar1,
          layout.notChar2, layout.char1Text, layout.char2Text, layout.char3Text, layout.char4Text,
          layout.notChar1Text, layout.notChar2Text).forEach {
        it.visibility = charSpinnerState
      }

      viewModel.metadata.worldList.ifPresent { wl ->
        val worldNameList = wl.mapTo(mutableListOf()) { it.name }

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
      val worldSpinnerState = if (viewModel.metadata.worldList is Empty) View.GONE else View.VISIBLE
      layout.world.visibility = worldSpinnerState
      layout.notWorld.visibility = worldSpinnerState
      layout.worldText.visibility = worldSpinnerState
      layout.notWorldText.visibility = worldSpinnerState
    }

    AlertDialog.Builder(this)
        .setTitle(R.string.filter_by)
        .setOnDismissListener { dialogOpened = false }
        .setPositiveButton(R.string.native_filter_btn) { dialog, _ ->
          viewModel.clearData()
          viewModel.resetPagination()
          triggerLoadUI()
          dialog.dismiss()
        }
        .setView(layout)
        .show()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.filter -> viewModel.metadata.pageCount.ifPresent { openFilterDialog() }
      R.id.favorite -> {
        if (viewModel.metadata.pageCount is Empty) return true
        viewModel.toggleFavorite().invokeOnCompletion {
          // It's already toggled at this point
          if (!viewModel.isFavorite) {
            Snackbar.make(this@CanonStoryListActivity.root,
                R.string.unfavorited, Snackbar.LENGTH_SHORT).show()
          } else {
            Snackbar.make(this@CanonStoryListActivity.root,
                str(R.string.favorited_x, title.toString()), Snackbar.LENGTH_SHORT).show()
          }
          invalidateOptionsMenu()
        }
      }
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}
