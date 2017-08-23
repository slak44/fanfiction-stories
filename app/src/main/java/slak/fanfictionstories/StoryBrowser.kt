package slak.fanfictionstories

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.widget.ArrayAdapter
import kotlinx.android.synthetic.main.activity_browse_category.*
import kotlinx.android.synthetic.main.activity_select_category.*

private val CATEGORIES = MainActivity.res.getStringArray(R.array.categories)
private val URL_COMPONENTS = MainActivity.res.getStringArray(R.array.categories_url_components)

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

class BrowseCategoryActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_browse_category)
    setSupportActionBar(findViewById(R.id.toolbar))
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    val categoryIdx = intent.extras.getInt(CATEGORIES_IDX_EXTRA_ID)
    title = CATEGORIES[categoryIdx]
    // FIXME get data lol
    inCategoryList.setOnItemClickListener { _, _, idx, _ ->
      // FIXME enter
    }
  }
}

