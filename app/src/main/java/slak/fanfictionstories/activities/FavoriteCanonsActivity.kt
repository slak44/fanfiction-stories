package slak.fanfictionstories.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import slak.fanfictionstories.R
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.CategoryLink
import slak.fanfictionstories.databinding.ActivityFavoriteCanonsBinding
import slak.fanfictionstories.databinding.ComponentFavoriteCanonBinding
import slak.fanfictionstories.utility.*

class FavoriteCanonsActivity : CoroutineScopeActivity() {
  private lateinit var binding: ActivityFavoriteCanonsBinding

  private inner class CanonAdapter(
      private val linkList: MutableList<CategoryLink>
  ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private inner class CanonViewHolder(view: View) : RecyclerView.ViewHolder(view)

    var removeSnackbar: Snackbar? = null
      private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
      return CanonViewHolder(LayoutInflater.from(parent.context)
          .inflate(R.layout.component_favorite_canon, parent, false))
    }

    override fun getItemCount(): Int = linkList.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
      val binding = ComponentFavoriteCanonBinding.bind(holder.itemView)

      binding.root.setOnClickListener {
        startActivity<CanonStoryListActivity>(INTENT_LINK_DATA to linkList[position])
      }
      binding.canonTitle.text = linkList[position].text
      binding.storyCountText.text = str(R.string.x_stories, linkList[position].storyCount)
      binding.removeBtn.setOnClickListener {
        removeSnackbar?.dismiss()
        val removed = linkList.removeAt(position)
        notifyItemRemoved(position)
        updateNoFavoritesText()
        removeSnackbar = undoableAction(binding.root, R.string.removed_favorite_snack, { _ ->
          linkList.add(position, removed)
          notifyItemInserted(position)
          updateNoFavoritesText()
        }) { Static.database.removeFavoriteCanon(removed).await() }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityFavoriteCanonsBinding.inflate(layoutInflater)
    setContentView(binding.root)
    setSupportActionBar(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    Static.wvViewModel.addWebView(binding.rootLayout)

    setTitle(R.string.favorite_canons)
    launch(Main) {
      binding.canonListRecycler.adapter = CanonAdapter(database.getFavoriteCanons().await().toMutableList())
      updateNoFavoritesText()
      binding.canonListRecycler.layoutManager = LinearLayoutManager(this@FavoriteCanonsActivity)
      binding.canonListRecycler.addItemDecoration(
          DividerItemDecoration(this@FavoriteCanonsActivity, LinearLayoutManager.VERTICAL))
    }
  }

  @UiThread
  private fun updateNoFavoritesText() {
    binding.noFavoritesText.visibility =
        if (binding.canonListRecycler.adapter!!.itemCount == 0) View.VISIBLE
        else View.GONE
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }

  override fun onBackPressed() {
    (binding.canonListRecycler.adapter as CanonAdapter).removeSnackbar?.dismiss()
    super.onBackPressed()
  }
}
