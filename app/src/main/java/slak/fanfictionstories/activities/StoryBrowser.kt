package slak.fanfictionstories.activities

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import org.jetbrains.anko.contentView
import slak.fanfictionstories.R
import slak.fanfictionstories.data.fetchers.CategoryLink
import slak.fanfictionstories.data.fetchers.categoryCache
import slak.fanfictionstories.data.fetchers.fetchCategoryData
import slak.fanfictionstories.databinding.ActivityBrowseCategoryBinding
import slak.fanfictionstories.databinding.ActivitySelectCategoryBinding
import slak.fanfictionstories.utility.*

val categories: Array<String> by lazy { Static.res.getStringArray(R.array.categories) }
val categoryUrl: Array<String> by lazy {
  Static.res.getStringArray(R.array.categories_url_components)
}

const val INTENT_LINK_DATA = "link_data_cat_browser"

/** Allows selecting one of ffnet's categories. */
class SelectCategoryActivity : ActivityWithStatic() {
  private lateinit var binding: ActivitySelectCategoryBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivitySelectCategoryBinding.inflate(layoutInflater)
    setContentView(binding.root)
    setSupportActionBar(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    Static.wvViewModel.addWebView(binding.rootLayout)

    val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
    adapter.addAll(categories.toList())
    binding.categoriesList.adapter = adapter
    binding.categoriesList.setOnItemClickListener { _, _, idx: Int, _ ->
      val urlComponent = (if (binding.useCrossover.isChecked) "crossovers/" else "") + categoryUrl[idx] + "/"
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
  private lateinit var binding: ActivityBrowseCategoryBinding

  override lateinit var loading: ProgressBar

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityBrowseCategoryBinding.inflate(layoutInflater)
    setContentView(R.layout.activity_browse_category)
    setSupportActionBar(findViewById(R.id.toolbar))
    setLoadingView(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    Static.wvViewModel.addWebView(binding.rootLayout)

    val parentLink = intent.extras?.getParcelable<CategoryLink>(INTENT_LINK_DATA) ?: return
    title = parentLink.displayName
    showLoading()
    launch(Main) {
      val links = fetchCategoryData(parentLink.urlComponent)
      val adapter = ArrayAdapter<String>(this@BrowseCategoryActivity, android.R.layout.simple_list_item_1)
      adapter.addAll(links.map { "${it.text} - ${it.storyCount}" })
      binding.inCategoryList.adapter = adapter
      binding.inCategoryList.setOnItemClickListener { _, _, idx, _ ->
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
