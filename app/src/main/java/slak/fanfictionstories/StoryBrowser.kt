package slak.fanfictionstories

import android.content.Intent
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import kotlinx.android.synthetic.main.activity_browse_category.*
import kotlinx.android.synthetic.main.activity_canon_story_list.*
import kotlinx.android.synthetic.main.activity_select_category.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import kotlin.properties.Delegates

val CATEGORIES: Array<String> = MainActivity.res.getStringArray(R.array.categories)
val URL_COMPONENTS: Array<String> =
    MainActivity.res.getStringArray(R.array.categories_url_components)

private val CATEGORIES_IDX_EXTRA_ID = "category_idx"

class SelectCategoryActivity : AppCompatActivity() {
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
private val CANON_URL_EXTRA_ID = "canon_url"

class BrowseCategoryActivity : AppCompatActivity() {
  private var categoryIdx: Int by Delegates.notNull()
  private lateinit var canons: List<Canon>
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_browse_category)
    setSupportActionBar(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    categoryIdx = intent.extras.getInt(CATEGORIES_IDX_EXTRA_ID)
    title = CATEGORIES[categoryIdx]
    launch(CommonPool) {
      canons = getCanonsForCategory(this@BrowseCategoryActivity, categoryIdx).await()
      val adapter = ArrayAdapter<String>(
          this@BrowseCategoryActivity, android.R.layout.simple_list_item_1)
      adapter.addAll(canons.map { "${it.title} - ${it.stories}" })
      launch(UI) { inCategoryList.adapter = adapter }
    }
    inCategoryList.setOnItemClickListener { _, _, idx, _ ->
      val intent = Intent(this, CanonStoryListActivity::class.java)
      intent.putExtra(CANON_URL_EXTRA_ID, canons[idx].url)
      intent.putExtra(CANON_TITLE_EXTRA_ID, canons[idx].title)
      startActivity(intent)
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_browse_category, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
    R.id.clearCache -> {
      CategoryCache.clear(categoryIdx)
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

// FIXME make this a tabbed activity, each tab being a page
class CanonStoryListActivity : AppCompatActivity() {
  private lateinit var adapter: StoryAdapter

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_canon_story_list)
    setSupportActionBar(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    title = intent.extras.getString(CANON_TITLE_EXTRA_ID)

    canonStoryListView.layoutManager = LinearLayoutManager(this)
    StoryCardView.createRightSwipeHelper(canonStoryListView, { intent, _ ->
      startActivity(intent)
    })
    launch(CommonPool) {
      adapter = StoryAdapter(this@CanonStoryListActivity)
      // FIXME get stories and init the data
      // adapter.initData(???)
      launch(UI) { canonStoryListView.adapter = adapter }
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_canon_story_list, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
    else -> super.onOptionsItemSelected(item)
  }
}

