package slak.fanfictionstories.activities

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
import slak.fanfictionstories.fetchers.*
import slak.fanfictionstories.utility.*
import java.util.*

class CanonStoryListActivity : LoadingActivity() {
  companion object {
    private const val CURRENT_PAGE_RESTORE = "current_page"
    private const val FETCHER_RESTORE = "canon_fetcher"
    private const val ADAPTER_DATA_RESTORE = "adapter_data"
  }

  private lateinit var adapter: StoryAdapter
  private lateinit var fetcher: CanonFetcher
  private var currentPage = 1

  private var userStories: Optional<List<StoryModel>> = Optional.empty()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_canon_story_list)
    setSupportActionBar(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    val parentLink: CategoryLink = intent.extras.getParcelable(INTENT_LINK_DATA)

    adapter = StoryAdapter(this@CanonStoryListActivity)
    canonStoryListView.adapter = adapter
    val layoutManager = LinearLayoutManager(this)
    canonStoryListView.layoutManager = layoutManager

    if (savedInstanceState == null) {
      setTitle(R.string.loading___)
      fetcher = CanonFetcher(CanonFetcher.Details(parentLink))
      launch(UI) {
        showLoading()
        adapter.addData(getPage(1).await())
        setAppbarText()
        hideLoading()
      }
    } else {
      onRestoreInstanceState(savedInstanceState)
    }

    StoryCardView.createRightSwipeHelper(canonStoryListView, { intent, _ -> startActivity(intent) })

    infinitePageScroll(canonStoryListView, layoutManager) {
      adapter.addDeferredData(getPage(++currentPage))
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putInt(CURRENT_PAGE_RESTORE, currentPage)
    outState.putParcelable(FETCHER_RESTORE, fetcher)
    outState.putParcelableArray(ADAPTER_DATA_RESTORE, adapter.getData().toTypedArray())
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    currentPage = savedInstanceState.getInt(CURRENT_PAGE_RESTORE)
    fetcher = savedInstanceState.getParcelable(FETCHER_RESTORE)
    val data = savedInstanceState.getParcelableArray(ADAPTER_DATA_RESTORE)
    @Suppress("unchecked_cast")
    adapter.addData((data as Array<StoryAdapterItem>).toList())
    setAppbarText()
    hideLoading()
  }

  private fun getPage(page: Int): Deferred<List<StoryAdapterItem>> = async2(CommonPool) {
    if (!userStories.isPresent) userStories = database.getStories().await().opt()
    val pageData = fetcher.get(page).await().map {
      val model = userStories.get().find { st -> st.storyIdRaw == it.storyIdRaw } ?: return@map T1(it)
      it.src["scrollProgress"] = model.src["scrollProgress"] as Double
      it.src["scrollAbsolute"] = model.src["scrollAbsolute"] as Double
      it.src["currentChapter"] = model.src["currentChapter"] as Long
      return@map T1(StoryModel(it.src))
    }
    if (pageData.isEmpty()) return@async2 listOf<StoryAdapterItem>()
    return@async2 listOf<StoryAdapterItem>(
        T2(resources.getString(R.string.page_x, page)), *pageData.toTypedArray())
  }

  private fun setAppbarText() {
    title = fetcher.canonTitle.get()
    supportActionBar?.subtitle =
        resources.getString(R.string.x_stories, fetcher.unfilteredStories.get())
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    val toTint = arrayOf(
        menu.findItem(R.id.filter)
    )
    for (item in toTint) item.iconTint(R.color.white, theme)
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

    val strNone = resources.getString(R.string.none)
    val strAny = resources.getString(R.string.any)

    with(layout) {
      val genresEdit = resources.getStringArray(R.array.genres).toMutableList()
      genresEdit[0] = strNone
      notGenre.setEntries(genresEdit)

      val charNameList = fetcher.charList.map { it.name }.toMutableList()
      charNameList[0] = strAny
      char1.setEntries(charNameList)
      char2.setEntries(charNameList)
      char3.setEntries(charNameList)
      char4.setEntries(charNameList)
      charNameList[0] = strNone
      notChar1.setEntries(charNameList)
      notChar2.setEntries(charNameList)

      sort.onSelect { _, pos -> fetcher.details.sort = Sort.values()[pos] }
      timeRange.onSelect { _, pos -> fetcher.details.timeRange = TimeRange.values()[pos] }
      genre1.onSelect { _, pos -> fetcher.details.genre1 = Genre.values()[pos] }
      genre2.onSelect { _, pos -> fetcher.details.genre2 = Genre.values()[pos] }
      rating.onSelect { _, pos -> fetcher.details.rating = Rating.values()[pos] }
      language.onSelect { _, pos -> fetcher.details.lang = Language.values()[pos] }
      status.onSelect { _, pos -> fetcher.details.status = Status.values()[pos] }
      length.onSelect { _, pos -> fetcher.details.wordCount = WordCount.values()[pos] }
      char1.onSelect { _, pos -> fetcher.details.char1Id = fetcher.charList[pos].id }
      char2.onSelect { _, pos -> fetcher.details.char2Id = fetcher.charList[pos].id }
      char3.onSelect { _, pos -> fetcher.details.char3Id = fetcher.charList[pos].id }
      char4.onSelect { _, pos -> fetcher.details.char4Id = fetcher.charList[pos].id }
      notChar1.onSelect { _, pos -> fetcher.details.char1Without = fetcher.charList[pos].id }
      notChar2.onSelect { _, pos -> fetcher.details.char2Without = fetcher.charList[pos].id }
      notGenre.onSelect { _, pos -> fetcher.details.genreWithout = Genre.values()[pos] }

      sort.setSelection(Sort.values().indexOf(fetcher.details.sort))
      timeRange.setSelection(TimeRange.values().indexOf(fetcher.details.timeRange))
      genre1.setSelection(Genre.values().indexOf(fetcher.details.genre1))
      genre2.setSelection(Genre.values().indexOf(fetcher.details.genre2))
      rating.setSelection(Rating.values().indexOf(fetcher.details.rating))
      language.setSelection(Language.values().indexOf(fetcher.details.lang))
      status.setSelection(Status.values().indexOf(fetcher.details.status))
      length.setSelection(WordCount.values().indexOf(fetcher.details.wordCount))
      char1.setSelection(fetcher.charList.indexOfFirst { it.id == fetcher.details.char1Id})
      char2.setSelection(fetcher.charList.indexOfFirst { it.id == fetcher.details.char2Id})
      char3.setSelection(fetcher.charList.indexOfFirst { it.id == fetcher.details.char3Id})
      char4.setSelection(fetcher.charList.indexOfFirst { it.id == fetcher.details.char4Id})

      fetcher.worldList.ifPresent { wl ->
        val worldNameList = wl.map { it.name }.toMutableList()

        worldNameList[0] = strAny
        world.setEntries(worldNameList)

        worldNameList[0] = strNone
        notWorld.setEntries(worldNameList)

        world.onSelect { _, pos -> fetcher.details.worldId = wl[pos].id }
        world.setSelection(wl.indexOfFirst { it.id == fetcher.details.worldId})

        notWorld.onSelect { _, pos -> fetcher.details.worldWithout = wl[pos].name }
        if (fetcher.details.worldWithout != null) {
          notWorld.setSelection(worldNameList.indexOf(fetcher.details.worldWithout!!))
        }
      }
      val worldSpinnerState = if (fetcher.worldList.isPresent) View.VISIBLE else View.GONE
      world.visibility = worldSpinnerState
      notWorld.visibility = worldSpinnerState
      worldText.visibility = worldSpinnerState
      notWorldText.visibility = worldSpinnerState

      if (fetcher.details.genreWithout != null) {
        notGenre.setSelection(Genre.values().indexOf(fetcher.details.genreWithout!!))
      }
      if (fetcher.details.char1Without != null) {
        notChar1.setSelection(charNameList.indexOf(fetcher.details.char1Without!!))
      }
      if (fetcher.details.char2Without != null) {
        notChar2.setSelection(charNameList.indexOf(fetcher.details.char2Without!!))
      }
    }

    AlertDialog.Builder(this)
        .setTitle(R.string.filter_by)
        .setOnDismissListener {
          dialogOpened = false
        }
        .setPositiveButton(R.string.native_filter_btn, { dialog, _ ->
          adapter.clearData()
          currentPage = 1
          launch(UI) {
            showLoading()
            adapter.addData(getPage(1).await())
            hideLoading()
          }
          dialog.dismiss()
        })
        .setView(layout)
        .show()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.filter -> if (fetcher.charList.isNotEmpty()) openFilterDialog()
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}
