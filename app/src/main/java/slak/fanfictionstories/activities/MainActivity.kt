package slak.fanfictionstories.activities

import android.os.Bundle
import android.util.Log
import android.view.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import org.jetbrains.anko.startActivity
import slak.fanfictionstories.*
import slak.fanfictionstories.data.Prefs
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.*
import slak.fanfictionstories.databinding.ActivityMainBinding
import slak.fanfictionstories.utility.CoroutineScopeActivity
import slak.fanfictionstories.utility.Empty
import slak.fanfictionstories.utility.str

/** The main menu. Allows navigation to other sections of the app. */
class MainActivity : CoroutineScopeActivity() {
  private lateinit var binding: ActivityMainBinding

  private var resumeModel: StoryModel? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    setSupportActionBar(binding.toolbar)

    binding.storyListBtn.setOnClickListener { startActivity<StoryListActivity>() }
    binding.storyBrowseBtn.setOnClickListener { startActivity<SelectCategoryActivity>() }
    binding.favoriteCanonsBtn.setOnClickListener { startActivity<FavoriteCanonsActivity>() }
    binding.storyQueueBtn.setOnClickListener { startActivity<StoryQueueActivity>() }

    // Using a RecyclerView is a hack so we don't reimplement createStorySwipeHelper()
    // START HACK
    binding.storyContainer.layoutManager = object : LinearLayoutManager(this) {
      // The entire layout is also wrapped in a [ScrollView] so we don't care about overflow
      override fun canScrollVertically(): Boolean = false
    }
    binding.storyContainer.adapter = object : RecyclerView.Adapter<StoryViewHolder>() {
      override fun getItemCount(): Int = 1
      override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoryViewHolder {
        return StoryViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.component_story, parent, false) as StoryCardView)
      }

      override fun onBindViewHolder(holder: StoryViewHolder, position: Int) {
        if (resumeModel == null) return
        holder.view.loadFromModel(resumeModel!!, this@MainActivity)
        holder.view.bindRemoveBtn(resumeModel!!, this@MainActivity, Empty())
      }
    }
    binding.storyContainer.createStorySwipeHelper()
    // END HACK

    if (BuildConfig.DEBUG) injectDebugButtons(this, binding)
  }

  override fun onResume() {
    super.onResume()
    if (Prefs.resumeStoryId == Prefs.NO_RESUME_STORY) {
      binding.storyContainer.visibility = View.GONE
      binding.resumeStoryText.text = str(R.string.nothing_to_resume)
      return
    }
    launch(Main) {
      resumeModel = database.storyById(Prefs.resumeStoryId).await().orNull()
      binding.storyContainer.visibility = View.VISIBLE
      binding.storyContainer.adapter!!.notifyItemChanged(0)
      binding.resumeStoryText.text = str(R.string.resume_story)
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

  companion object {
    private const val TAG = "MainActivity"
  }
}
