package slak.fanfictionstories.activities

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.constraint.ConstraintLayout
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.activity_favorite_canons.*
import kotlinx.android.synthetic.main.favorite_canon_component.view.*
import slak.fanfictionstories.R
import slak.fanfictionstories.fetchers.CategoryLink
import slak.fanfictionstories.utility.startActivity

class FavoriteCanonsActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_favorite_canons)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    setTitle(R.string.favorite_canons)
    // FIXME get list
    canonListRecycler.adapter = CanonAdapter(emptyList())
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}

private class CanonAdapter(links: List<CategoryLink>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  class CanonViewHolder(view: View) : RecyclerView.ViewHolder(view)

  private val linkList: MutableList<CategoryLink> = links.toMutableList()

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    return CanonViewHolder(LayoutInflater.from(parent.context)
        .inflate(R.layout.favorite_canon_component, parent, false))
  }

  override fun getItemCount(): Int = linkList.size

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    with(holder.itemView as ConstraintLayout) {
      canonTitle.text = linkList[position].text
      storyCountText.text = linkList[position].storyCount
      setOnClickListener {
        startActivity<CanonStoryListActivity>(INTENT_LINK_DATA to linkList[position])
      }
    }
  }
}
