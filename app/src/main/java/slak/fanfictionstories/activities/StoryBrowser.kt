package slak.fanfictionstories.activities

import android.content.Intent
import android.os.Bundle
import android.support.annotation.IdRes
import android.support.design.widget.Snackbar
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import either.Left
import either.Right
import kotlinx.android.synthetic.main.activity_browse_category.*
import kotlinx.android.synthetic.main.activity_canon_story_list.*
import kotlinx.android.synthetic.main.activity_select_category.*
import kotlinx.android.synthetic.main.dialog_ffnet_filter.*
import kotlinx.android.synthetic.main.dialog_ffnet_filter.view.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryAdapter
import slak.fanfictionstories.StoryCardView
import slak.fanfictionstories.Canon
import slak.fanfictionstories.fetchers.*
import slak.fanfictionstories.utility.async2
import slak.fanfictionstories.utility.iconTint
import java.util.*
import kotlin.properties.Delegates

val CATEGORIES by lazy { Static.res!!.getStringArray(R.array.categories) }
val URL_COMPONENTS by lazy { Static.res!!.getStringArray(R.array.categories_url_components) }

private val CATEGORIES_IDX_EXTRA_ID = "category_idx"

class SelectCategoryActivity : ActivityWithStatic() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_select_category)
    setSupportActionBar(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
    adapter.addAll(CATEGORIES.toList())
    categoriesList.adapter = adapter
    categoriesList.setOnItemClickListener { _, _, idx: Int, _ ->
      val intent = Intent(this, BrowseCategoryActivity::class.java)
      intent.putExtra(CATEGORIES_IDX_EXTRA_ID, idx)
      startActivity(intent)
    }
  }
}

private val CANON_TITLE_EXTRA_ID = "canon_title"
private val SRC_CATEGORY_EXTRA_ID = "category_title"
private val CANON_URL_EXTRA_ID = "canon_url"

class BrowseCategoryActivity : ActivityWithStatic() {
  private var categoryIdx: Int by Delegates.notNull()
  private lateinit var canons: List<Canon>
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_browse_category)
    setSupportActionBar(findViewById(R.id.toolbar))
    // FIXME: 'up' button is missing the intent extras, which used to NPE below, now it just bugs out
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    categoryIdx = intent.extras?.getInt(CATEGORIES_IDX_EXTRA_ID) ?: return
    title = CATEGORIES[categoryIdx]
    launch(CommonPool) {
      canons = CategoryFetcher(this@BrowseCategoryActivity).get(categoryIdx).await()
      val adapter = ArrayAdapter<String>(
          this@BrowseCategoryActivity, android.R.layout.simple_list_item_1)
      adapter.addAll(canons.map { "${it.title} - ${it.stories}" })
      launch(UI) { inCategoryList.adapter = adapter }
    }
    inCategoryList.setOnItemClickListener { _, _, idx, _ ->
      val intent = Intent(this, CanonStoryListActivity::class.java)
      intent.putExtra(CANON_URL_EXTRA_ID, canons[idx].url)
      intent.putExtra(CANON_TITLE_EXTRA_ID, canons[idx].title)
      intent.putExtra(SRC_CATEGORY_EXTRA_ID, CATEGORIES[categoryIdx])
      startActivity(intent)
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_browse_category, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
    R.id.clearCache -> {
      CategoryFetcher.Cache.clear(categoryIdx)
      Snackbar.make(
          findViewById(android.R.id.content)!!,
          resources.getString(R.string.cleared_from_cache, CATEGORIES[categoryIdx]),
          Snackbar.LENGTH_SHORT
      ).show()
      true
    }
    else -> super.onOptionsItemSelected(item)
  }
}

class CanonStoryListActivity : ActivityWithStatic() {
  private lateinit var adapter: StoryAdapter
  private lateinit var layoutManager: LinearLayoutManager
  private lateinit var fetcher: CanonFetcher
  private var currentPage = 1
  private val addPageLock = Mutex()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_canon_story_list)
    setSupportActionBar(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    val title = intent.extras.getString(CANON_TITLE_EXTRA_ID)
    val urlComp = intent.extras.getString(CANON_URL_EXTRA_ID)
    val srcCategory = intent.extras.getString(SRC_CATEGORY_EXTRA_ID)

    fetcher = CanonFetcher(this@CanonStoryListActivity,
        CanonFetcher.Details(urlComp, title, srcCategory))
    adapter = StoryAdapter(this@CanonStoryListActivity)
    canonStoryListView.adapter = adapter
    layoutManager = LinearLayoutManager(this)
    canonStoryListView.layoutManager = layoutManager

    this.title = title

    StoryCardView.createRightSwipeHelper(canonStoryListView, { intent, _ ->
      startActivity(intent)
    })

    canonStoryListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
      override fun onScrolled(recyclerView: RecyclerView?, dx: Int, dy: Int) {
        // We only want scroll downs
        if (dy <= 0) return
        val visibleItemCount = layoutManager.childCount
        val totalItemCount = layoutManager.itemCount
        val pastVisiblesItems = layoutManager.findFirstVisibleItemPosition()
        if ((visibleItemCount + pastVisiblesItems) >= totalItemCount) {
          // There are lots of scroll events, so use a lock to make sure we don't overdo it
          if (addPageLock.isLocked) return
          launch(CommonPool) {
            addPageLock.lock()
            addPage(++currentPage).await()
            addPageLock.unlock()
          }
        }
      }
    })

    launch(CommonPool) {
      addPage(1).await()
    }
  }

  private fun addPage(page: Int) = async2(UI) {
    // Add page title
    adapter.addData(Right(resources.getString(R.string.page_x, page)))
    // Add stories
    adapter.addData(fetcher.get(page).await().map { Left(it) })
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    val toTint = arrayOf(
        menu.findItem(R.id.filter)
    )
    for (item in toTint) item.iconTint(android.R.color.white, theme)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_canon_story_list, menu)
    return true
  }

  private fun spinnerOnSelect(layout: View, @IdRes id: Int,
                              block: (spinner: Spinner, position: Int) -> Unit) {
    val spinner = layout.findViewById<Spinner>(id) as Spinner
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onNothingSelected(parent: AdapterView<*>?) {}
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        block(spinner, position)
      }
    }
  }

  private fun spinnerSetEntries(layout: View, @IdRes id: Int, entries: List<String>) {
    val spinner = layout.findViewById<Spinner>(id) as Spinner
    val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item)
    adapter.addAll(entries)
    spinner.adapter = adapter
  }

  private fun spinnerSelect(layout: View, @IdRes id: Int, position: Int) {
    val spinner = layout.findViewById<Spinner>(id) as Spinner
    spinner.setSelection(position)
  }

  private fun openFilterDialog() {
    val layout = LayoutInflater.from(this)
        .inflate(R.layout.dialog_ffnet_filter, null, false)

    val strNone = resources.getString(R.string.none)
    val strAny = resources.getString(R.string.any)

    val genresEdit = resources.getStringArray(R.array.genres).toMutableList()
    genresEdit[0] = strNone
    spinnerSetEntries(layout, R.id.notGenre, genresEdit)

    val charNameList = fetcher.charList.map { it.name }.toMutableList()
    charNameList[0] = strAny
    spinnerSetEntries(layout, R.id.char1, charNameList)
    spinnerSetEntries(layout, R.id.char2, charNameList)
    spinnerSetEntries(layout, R.id.char3, charNameList)
    spinnerSetEntries(layout, R.id.char4, charNameList)
    charNameList[0] = strNone
    spinnerSetEntries(layout, R.id.notChar1, charNameList)
    spinnerSetEntries(layout, R.id.notChar2, charNameList)

    if (fetcher.worldList.isPresent) {
      val wl = fetcher.worldList.get()
      val worldNameList = wl.map { it.name }.toMutableList()

      worldNameList[0] = strAny
      spinnerSetEntries(layout, R.id.world, worldNameList)

      worldNameList[0] = strNone
      spinnerSetEntries(layout, R.id.notWorld, worldNameList)

      spinnerOnSelect(layout, R.id.world) { _, pos -> fetcher.details.worldId = wl[pos].id }
      spinnerSelect(layout, R.id.world, wl.indexOfFirst { it.id == fetcher.details.worldId})

      spinnerOnSelect(layout, R.id.notWorld) { _, pos -> fetcher.details.worldWithout = Optional.of(wl[pos].name) }
      if (fetcher.details.worldWithout.isPresent) {
        spinnerSelect(layout, R.id.notWorld, worldNameList.indexOf(fetcher.details.worldWithout.get()))
      }
    }
    val worldSpinnerState = if (fetcher.worldList.isPresent) View.VISIBLE else View.GONE
    (layout.findViewById<Spinner>(R.id.world) as Spinner).visibility = worldSpinnerState
    (layout.findViewById<Spinner>(R.id.notWorld) as Spinner).visibility = worldSpinnerState
    (layout.findViewById<TextView>(R.id.worldText) as TextView).visibility = worldSpinnerState
    (layout.findViewById<TextView>(R.id.notWorldText) as TextView).visibility = worldSpinnerState

    spinnerOnSelect(layout, R.id.sort) { _, pos -> fetcher.details.sort = Sort.values()[pos] }
    spinnerOnSelect(layout, R.id.timeRange) { _, pos -> fetcher.details.timeRange = TimeRange.values()[pos] }
    spinnerOnSelect(layout, R.id.genre1) { _, pos -> fetcher.details.genre1 = Genre.values()[pos] }
    spinnerOnSelect(layout, R.id.genre2) { _, pos -> fetcher.details.genre2 = Genre.values()[pos] }
    spinnerOnSelect(layout, R.id.rating) { _, pos -> fetcher.details.rating = Rating.values()[pos] }
    spinnerOnSelect(layout, R.id.language) { _, pos -> fetcher.details.lang = Language.values()[pos] }
    spinnerOnSelect(layout, R.id.status) { _, pos -> fetcher.details.status = Status.values()[pos] }
    spinnerOnSelect(layout, R.id.length) { _, pos -> fetcher.details.wordCount = WordCount.values()[pos] }
    spinnerOnSelect(layout, R.id.char1) { _, pos -> fetcher.details.char1Id = fetcher.charList[pos].id }
    spinnerOnSelect(layout, R.id.char2) { _, pos -> fetcher.details.char2Id = fetcher.charList[pos].id }
    spinnerOnSelect(layout, R.id.char3) { _, pos -> fetcher.details.char3Id = fetcher.charList[pos].id }
    spinnerOnSelect(layout, R.id.char4) { _, pos -> fetcher.details.char4Id = fetcher.charList[pos].id }
    spinnerOnSelect(layout, R.id.notChar1) { _, pos -> fetcher.details.char1Without = Optional.of(fetcher.charList[pos].id) }
    spinnerOnSelect(layout, R.id.notChar2) { _, pos -> fetcher.details.char2Without = Optional.of(fetcher.charList[pos].id) }
    spinnerOnSelect(layout, R.id.notGenre) { _, pos -> fetcher.details.genreWithout = Optional.of(Genre.values()[pos]) }

    spinnerSelect(layout, R.id.sort, Sort.values().indexOf(fetcher.details.sort))
    spinnerSelect(layout, R.id.timeRange, TimeRange.values().indexOf(fetcher.details.timeRange))
    spinnerSelect(layout, R.id.genre1, Genre.values().indexOf(fetcher.details.genre1))
    spinnerSelect(layout, R.id.genre2, Genre.values().indexOf(fetcher.details.genre2))
    spinnerSelect(layout, R.id.rating, Rating.values().indexOf(fetcher.details.rating))
    spinnerSelect(layout, R.id.language, Language.values().indexOf(fetcher.details.lang))
    spinnerSelect(layout, R.id.status, Status.values().indexOf(fetcher.details.status))
    spinnerSelect(layout, R.id.length, WordCount.values().indexOf(fetcher.details.wordCount))
    spinnerSelect(layout, R.id.char1, fetcher.charList.indexOfFirst { it.id == fetcher.details.char1Id})
    spinnerSelect(layout, R.id.char2, fetcher.charList.indexOfFirst { it.id == fetcher.details.char2Id})
    spinnerSelect(layout, R.id.char3, fetcher.charList.indexOfFirst { it.id == fetcher.details.char3Id})
    spinnerSelect(layout, R.id.char4, fetcher.charList.indexOfFirst { it.id == fetcher.details.char4Id})

    if (fetcher.details.genreWithout.isPresent) {
      spinnerSelect(layout, R.id.notGenre,
          Genre.values().indexOf(fetcher.details.genreWithout.get()))
    }
    if (fetcher.details.char1Without.isPresent) {
      spinnerSelect(layout, R.id.notChar1,
          charNameList.indexOf(fetcher.details.char1Without.get()))
    }
    if (fetcher.details.char2Without.isPresent) {
      spinnerSelect(layout, R.id.notChar2,
          charNameList.indexOf(fetcher.details.char2Without.get()))
    }

    AlertDialog.Builder(this)
        .setTitle(R.string.filter_by)
        .setPositiveButton(R.string.native_filter_btn, { dialog, _ ->
          adapter.clear()
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
      else -> super.onOptionsItemSelected(item)
    }
    return true
  }
}
