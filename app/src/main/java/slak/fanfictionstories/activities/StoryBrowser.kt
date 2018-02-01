package slak.fanfictionstories.activities

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import either.Either
import either.Left
import either.Right
import either.fold
import kotlinx.android.synthetic.main.activity_browse_category.*
import kotlinx.android.synthetic.main.activity_canon_story_list.*
import kotlinx.android.synthetic.main.activity_select_category.*
import kotlinx.android.synthetic.main.dialog_ffnet_filter.view.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.contentView
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.*
import slak.fanfictionstories.utility.*
import java.util.*

val categories: Array<String> by lazy { Static.res.getStringArray(R.array.categories) }
val categoryUrl: Array<String> by lazy {
  Static.res.getStringArray(R.array.categories_url_components)
}

class SelectCategoryActivity : ActivityWithStatic() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_select_category)
    setSupportActionBar(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
    adapter.addAll(categories.toList())
    categoriesList.adapter = adapter
    categoriesList.setOnItemClickListener { _, _, idx: Int, _ ->
      val urlComponent = (if (useCrossover.isChecked) "crossovers/" else "") + categoryUrl[idx] + "/"
      val intent = Intent(this, BrowseCategoryActivity::class.java)
      intent.putExtra(INTENT_LINK_DATA, CategoryLink(categories[idx], urlComponent, "") as Parcelable)
      startActivity(intent)
    }
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}

private const val INTENT_LINK_DATA = "link_data_cat_browser"

class BrowseCategoryActivity : ActivityWithStatic() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_browse_category)
    setSupportActionBar(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    val parentLink = intent.extras?.getParcelable<CategoryLink>(INTENT_LINK_DATA) ?: return
    title = parentLink.displayName
    launch(CommonPool) {
      val links = fetchCategoryData(this@BrowseCategoryActivity, parentLink.urlComponent).await()
      val adapter = ArrayAdapter<String>(
          this@BrowseCategoryActivity, android.R.layout.simple_list_item_1)
      adapter.addAll(links.map { "${it.text} - ${it.storyCount}" })
      launch(UI) {
        inCategoryList.adapter = adapter
        inCategoryList.setOnItemClickListener { _, _, idx, _ ->
          val target =
              if (links[idx].isTargetCategory()) BrowseCategoryActivity::class.java
              else CanonStoryListActivity::class.java
          val intent = Intent(this@BrowseCategoryActivity, target)
          intent.putExtra(INTENT_LINK_DATA, links[idx] as Parcelable)
          startActivity(intent)
        }
      }
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_browse_category, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.clearCache -> {
        undoableAction(contentView!!, getString(R.string.cleared_from_cache)) {
          categoryCache.clear()
        }
      }
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}

class CanonStoryListActivity : ActivityWithStatic() {
  companion object {
    private const val CURRENT_PAGE_RESTORE = "current_page"
    private const val FETCHER_RESTORE = "canon_fetcher"
    private const val ADAPTER_DATA_RESTORE = "adapter_data"
  }

  private lateinit var adapter: StoryAdapter
  private lateinit var fetcher: CanonFetcher
  private var currentPage = 1

  private var hasSaved = false

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

    setTitle(R.string.loading___)

    if (!hasSaved) {
      fetcher = CanonFetcher(CanonFetcher.Details(parentLink))
      addPage(1)
    }

    StoryCardView.createRightSwipeHelper(canonStoryListView, { intent, _ ->
      this@CanonStoryListActivity.startActivity(intent)
    })

    infinitePageScroll(canonStoryListView, layoutManager) {
      addPage(++currentPage)
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    hasSaved = true
    super.onSaveInstanceState(outState)
    outState.putInt(CURRENT_PAGE_RESTORE, currentPage)
    outState.putParcelable(FETCHER_RESTORE, fetcher)
    outState.putParcelableArray(ADAPTER_DATA_RESTORE, adapter.getData().map {
      it.fold({ EitherWrapper(it, null) }, { EitherWrapper(null, it) })
    }.toTypedArray())
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    currentPage = savedInstanceState.getInt(CURRENT_PAGE_RESTORE)
    fetcher = savedInstanceState.getParcelable(FETCHER_RESTORE)
    val data = savedInstanceState.getParcelableArray(ADAPTER_DATA_RESTORE)
    @Suppress("unchecked_cast")
    adapter.addData(data.map {
      it as EitherWrapper<StoryModel, String>
      return@map if (it.l == null) Right(it.r) else Left(it.l)
    } as List<Either<StoryModel, String>>)
  }

  private fun addPage(page: Int) = launch(UI) {
    if (!userStories.isPresent) userStories = database.getStories().await().opt()
    val pageData = fetcher.get(page, this@CanonStoryListActivity).await().map {
      val model = userStories.get().find { st -> st.storyIdRaw == it.storyIdRaw } ?: return@map it
      it.src["scrollProgress"] = model.src["scrollProgress"] as Double
      it.src["scrollAbsolute"] = model.src["scrollAbsolute"] as Double
      it.src["currentChapter"] = model.src["currentChapter"] as Long
      return@map StoryModel(it.src, it._id.isPresent)
    }
    if (pageData.isEmpty()) return@launch
    adapter.addData(Right(resources.getString(R.string.page_x, page)))
    adapter.addData(pageData.map { Left(it) })
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
          addPage(1)
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
