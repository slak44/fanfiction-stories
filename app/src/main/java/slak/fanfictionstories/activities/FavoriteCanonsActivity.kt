package slak.fanfictionstories.activities

import android.os.Bundle
import android.support.annotation.UiThread
import android.support.constraint.ConstraintLayout
import android.support.design.widget.Snackbar
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.activity_favorite_canons.*
import kotlinx.android.synthetic.main.component_favorite_canon.view.*
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.R
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.CategoryLink
import slak.fanfictionstories.utility.*
import kotlin.coroutines.experimental.CoroutineContext

class FavoriteCanonsActivity : CoroutineScopeActivity() {
  // FIXME turn this class into an object, it's just inconvenient like this
  private class CanonAdapter(
      private val activity: FavoriteCanonsActivity,
      links: List<CategoryLink>
  ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    class CanonViewHolder(view: View) : RecyclerView.ViewHolder(view)

    private val linkList: MutableList<CategoryLink> = links.toMutableList()

    var removeSnackbar: Snackbar? = null
      private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
      return CanonViewHolder(LayoutInflater.from(parent.context)
          .inflate(R.layout.component_favorite_canon, parent, false))
    }

    override fun getItemCount(): Int = linkList.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder,
                                  position: Int) = with(holder.itemView as ConstraintLayout) {
      setOnClickListener {
        startActivity<CanonStoryListActivity>(INTENT_LINK_DATA to linkList[position])
      }
      canonTitle.text = linkList[position].text
      storyCountText.text = str(R.string.x_stories, linkList[position].storyCount)
      removeBtn.setOnClickListener {
        removeSnackbar?.dismiss()
        val removed = linkList.removeAt(position)
        // FIXME useless coroutine, we're already on the UI thread
        activity.launch(UI) {
          notifyItemRemoved(position)
          activity.updateNoFavoritesText()
        }
        removeSnackbar = undoableAction(this, R.string.removed_favorite_snack, { _ ->
          linkList.add(position, removed)
          // FIXME useless coroutine, we're already on the UI thread
          activity.launch(UI) {
            notifyItemInserted(position)
            activity.updateNoFavoritesText()
          }
        }) { Static.database.removeFavoriteCanon(removed).await() }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_favorite_canons)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    setTitle(R.string.favorite_canons)
    // FIXME possibly useless coroutine, we're already on the UI thread, just have to handle getFavoriteCanons
    launch(UI) {
      canonListRecycler.adapter =
          CanonAdapter(this@FavoriteCanonsActivity, database.getFavoriteCanons().await())
      updateNoFavoritesText()
      canonListRecycler.layoutManager = LinearLayoutManager(this@FavoriteCanonsActivity)
      canonListRecycler.addItemDecoration(
          DividerItemDecoration(this@FavoriteCanonsActivity, LinearLayoutManager.VERTICAL))
    }
  }

  @UiThread
  private fun updateNoFavoritesText() {
    noFavoritesText.visibility =
        if (canonListRecycler.adapter!!.itemCount == 0) View.VISIBLE
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
    (canonListRecycler.adapter as CanonAdapter).removeSnackbar?.dismiss()
    super.onBackPressed()
  }
}
