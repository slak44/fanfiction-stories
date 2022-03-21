package slak.fanfictionstories.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import androidx.activity.viewModels
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import slak.fanfictionstories.*
import slak.fanfictionstories.StoryListItem.GroupTitle
import slak.fanfictionstories.StoryListItem.StoryCardData
import slak.fanfictionstories.data.Prefs
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.*
import slak.fanfictionstories.databinding.ActivityCanonStoryListBinding
import slak.fanfictionstories.databinding.DialogFfnetFilterBinding
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
  override lateinit var loading: ProgressBar

  private val viewModel: CanonListViewModel by viewModels {
    ViewModelFactory(intent.getParcelableExtra(INTENT_LINK_DATA)!!)
  }

  private lateinit var binding: ActivityCanonStoryListBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityCanonStoryListBinding.inflate(layoutInflater)
    setContentView(binding.root)
    setSupportActionBar(binding.toolbar)
    setLoadingView(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    Static.wvViewModel.addWebView(binding.rootLayout)

    binding.canonStoryListView.adapter = StoryAdapter(viewModel)
    val layoutManager = LinearLayoutManager(this)
    binding.canonStoryListView.layoutManager = layoutManager
    binding.canonStoryListView.createStorySwipeHelper()
    infinitePageScroll(binding.canonStoryListView, layoutManager) {
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

    val binding = DialogFfnetFilterBinding.inflate(layoutInflater, null, false)

    val strNone = str(R.string.none)
    val strAny = str(R.string.any)

    with(viewModel.filters) {
      val genresEdit = resources.getStringArray(R.array.genres).toMutableList()
      genresEdit[0] = strNone
      binding.notGenre.setEntries(genresEdit)

      binding.sort.onSelect { _, pos -> sort = Sort.values()[pos] }
      binding.timeRange.onSelect { _, pos -> timeRange = TimeRange.values()[pos] }
      binding.genre1.onSelect { _, pos -> genre1 = Genre.values()[pos] }
      binding.genre2.onSelect { _, pos -> genre2 = Genre.values()[pos] }
      binding.rating.onSelect { _, pos -> rating = Rating.values()[pos] }
      binding.status.onSelect { _, pos -> status = Status.values()[pos] }
      binding.length.onSelect { _, pos -> wordCount = WordCount.values()[pos] }
      binding.notGenre.onSelect { _, pos -> genreWithout = Genre.values()[pos].opt() }
      binding.language.onSelect { _, pos ->
        lang = Language.values()[pos]
        Prefs.preferredLanguage = lang
      }

      binding.sort.setSelection(Sort.values().indexOf(sort))
      binding.timeRange.setSelection(TimeRange.values().indexOf(timeRange))
      binding.genre1.setSelection(Genre.values().indexOf(genre1))
      binding.genre2.setSelection(Genre.values().indexOf(genre2))
      binding.rating.setSelection(Rating.values().indexOf(rating))
      binding.language.setSelection(Language.values().indexOf(lang))
      binding.status.setSelection(Status.values().indexOf(status))
      binding.length.setSelection(WordCount.values().indexOf(wordCount))
      genreWithout.ifPresent { binding.notGenre.setSelection(Genre.values().indexOf(it)) }

      with(viewModel.metadata) {
        charList.ifPresent { list ->
          val charNameList = list.mapTo(mutableListOf()) { it.name }
          charNameList[0] = strAny
          binding.char1.setEntries(charNameList)
          binding.char2.setEntries(charNameList)
          binding.char3.setEntries(charNameList)
          binding.char4.setEntries(charNameList)
          charNameList[0] = strNone
          binding.notChar1.setEntries(charNameList)
          binding.notChar2.setEntries(charNameList)
          binding.char1.onSelect { _, pos -> char1Id = charList.get()[pos].id }
          binding.char2.onSelect { _, pos -> char2Id = charList.get()[pos].id }
          binding.char3.onSelect { _, pos -> char3Id = charList.get()[pos].id }
          binding.char4.onSelect { _, pos -> char4Id = charList.get()[pos].id }
          binding.notChar1.onSelect { _, pos -> char1Without = charList.get()[pos].id.opt() }
          binding.notChar2.onSelect { _, pos -> char2Without = charList.get()[pos].id.opt() }
          binding.char1.setSelection(charList.get().indexOfFirst { it.id == char1Id })
          binding.char2.setSelection(charList.get().indexOfFirst { it.id == char2Id })
          binding.char3.setSelection(charList.get().indexOfFirst { it.id == char3Id })
          binding.char4.setSelection(charList.get().indexOfFirst { it.id == char4Id })
          char1Without.ifPresent { binding.notChar1.setSelection(charNameList.indexOf(it)) }
          char2Without.ifPresent { binding.notChar2.setSelection(charNameList.indexOf(it)) }
        }
      }
      val charSpinnerState = if (viewModel.metadata.charList is Empty) View.GONE else View.VISIBLE
      listOf(binding.char1, binding.char2, binding.char3, binding.char4, binding.notChar1,
          binding.notChar2, binding.char1Text, binding.char2Text, binding.char3Text, binding.char4Text,
          binding.notChar1Text, binding.notChar2Text).forEach {
        it.visibility = charSpinnerState
      }

      viewModel.metadata.worldList.ifPresent { wl ->
        val worldNameList = wl.mapTo(mutableListOf()) { it.name }

        worldNameList[0] = strAny
        binding.world.setEntries(worldNameList)

        worldNameList[0] = strNone
        binding.notWorld.setEntries(worldNameList)

        binding.world.onSelect { _, pos -> worldId = wl[pos].id }
        binding.world.setSelection(wl.indexOfFirst { it.id == worldId })

        binding.notWorld.onSelect { _, pos -> worldWithout = wl[pos].name.opt() }

        worldWithout.ifPresent {
          binding.notWorld.setSelection(worldNameList.indexOf(it))
        }
      }
      val worldSpinnerState = if (viewModel.metadata.worldList is Empty) View.GONE else View.VISIBLE
      binding.world.visibility = worldSpinnerState
      binding.notWorld.visibility = worldSpinnerState
      binding.worldText.visibility = worldSpinnerState
      binding.notWorldText.visibility = worldSpinnerState
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
        .setView(binding.root)
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
            Snackbar.make(binding.root, R.string.unfavorited, Snackbar.LENGTH_SHORT).show()
          } else {
            Snackbar.make(binding.root, str(R.string.favorited_x, title.toString()), Snackbar.LENGTH_SHORT).show()
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
