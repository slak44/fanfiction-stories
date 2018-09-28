package slak.fanfictionstories.activities

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import kotlinx.android.synthetic.main.activity_browse_category.*
import kotlinx.android.synthetic.main.activity_select_category.*
import kotlinx.android.synthetic.main.loading_activity_indeterminate.*
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.contentView
import slak.fanfictionstories.R
import slak.fanfictionstories.data.fetchers.CategoryLink
import slak.fanfictionstories.data.fetchers.categoryCache
import slak.fanfictionstories.data.fetchers.fetchCategoryData
import slak.fanfictionstories.utility.*

val categories: Array<String> by lazy { Static.res.getStringArray(R.array.categories) }
val categoryUrl: Array<String> by lazy {
  Static.res.getStringArray(R.array.categories_url_components)
}

const val INTENT_LINK_DATA = "link_data_cat_browser"

/** Allows selecting one of ffnet's categories. */
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
      startActivity<BrowseCategoryActivity>(
          INTENT_LINK_DATA to CategoryLink(categories[idx], urlComponent, "") as Parcelable)
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

/** Navigate a category. Can represent links to canons, or links to further categories when working with crossovers. */
class BrowseCategoryActivity : CoroutineScopeActivity(), IHasLoadingBar {
  override val loading: ProgressBar
    get() = activityProgressBar

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_browse_category)
    setSupportActionBar(findViewById(R.id.toolbar))
    setLoadingView(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    val parentLink = intent.extras?.getParcelable<CategoryLink>(INTENT_LINK_DATA) ?: return
    title = parentLink.displayName
    showLoading()
    launch(Dispatchers.Default) {
      val links = fetchCategoryData(parentLink.urlComponent).await()
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
        hideLoading()
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
        undoableAction(contentView!!, str(R.string.cleared_from_cache)) {
          categoryCache.clear()
        }
      }
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}
