package slak.fanfictionstories.activities

import android.os.Bundle
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.startActivity
import slak.fanfictionstories.*
import slak.fanfictionstories.data.Prefs
import slak.fanfictionstories.data.database
import slak.fanfictionstories.fetchers.*
import slak.fanfictionstories.utility.ActivityWithStatic
import slak.fanfictionstories.utility.Empty
import slak.fanfictionstories.utility.str

/** The main menu. Allows navigation to other sections of the app. */
class MainActivity : ActivityWithStatic() {
  companion object {
    private const val TAG = "MainActivity"
  }

  private var resumeModel: StoryModel? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(toolbar)

    storyListBtn.setOnClickListener { startActivity<StoryListActivity>() }
    storyBrowseBtn.setOnClickListener { startActivity<SelectCategoryActivity>() }
    favoriteCanonsBtn.setOnClickListener { startActivity<FavoriteCanonsActivity>() }

    // Using a RecyclerView is a massive hack so we don't reimplement createStorySwipeHelper()
    // START HACK
    storyContainer.layoutManager = object : LinearLayoutManager(this) {
      // The entire layout is also wrapped in a [ScrollView] so we don't care about overflow
      override fun canScrollVertically(): Boolean = false
    }
    storyContainer.adapter = object : RecyclerView.Adapter<StoryViewHolder>() {
      override fun getItemCount(): Int = 1
      override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoryViewHolder {
        return StoryViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.component_story, parent, false) as StoryCardView)
      }

      override fun onBindViewHolder(holder: StoryViewHolder, position: Int) {
        if (resumeModel == null) return
        holder.view.loadFromModel(resumeModel!!)
        holder.view.bindRemoveBtn(resumeModel!!, Empty())
      }
    }
    storyContainer.createStorySwipeHelper()
    // END HACK

    if (BuildConfig.DEBUG) injectDebugButtons(this)
  }

  override fun onResume() {
    super.onResume()
    if (Prefs.resumeStoryId == Prefs.NO_RESUME_STORY) {
      storyContainer.visibility = View.GONE
      resumeStoryText.text = str(R.string.nothing_to_resume)
      return
    }
    launch(UI) {
      resumeModel = database.storyById(Prefs.resumeStoryId).await().orNull()
      storyContainer.visibility = View.VISIBLE
      storyContainer.adapter!!.notifyItemChanged(0)
      resumeStoryText.text = str(R.string.resume_story)
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.actionSettings -> startActivity<SettingsActivity>()
      R.id.clearAllCaches -> {
        Log.d(TAG, "Clearing all caches")
        categoryCache.clear()
        storyCache.clear()
        canonListCache.clear()
        authorCache.clear()
        reviewCache.clear()
      }
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}
