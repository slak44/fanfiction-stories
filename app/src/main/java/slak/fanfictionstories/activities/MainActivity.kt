package slak.fanfictionstories.activities

import android.os.Bundle
import android.os.Parcelable
import android.text.Html
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.runBlocking
import org.jetbrains.anko.startActivity
import slak.fanfictionstories.BuildConfig
import slak.fanfictionstories.R
import slak.fanfictionstories.fetchers.*
import slak.fanfictionstories.injectDebugButtons
import slak.fanfictionstories.utility.*

/** The main menu. Allows navigation to other sections of the app. */
class MainActivity : ActivityWithStatic() {
  companion object {
    private const val TAG = "MainActivity"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(toolbar)

    storyListButton.setOnClickListener { startActivity<StoryListActivity>() }
    storyBrowseButton.setOnClickListener { startActivity<SelectCategoryActivity>() }

    if (BuildConfig.DEBUG) injectDebugButtons(this)
  }

  override fun onResume() {
    super.onResume()
    val storyId = Static.prefs.getLong(Prefs.RESUME_STORY_ID, -1)
    if (storyId == -1L) {
      resumeButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
      resumeButton.text = str(R.string.nothing_to_resume)
      resumeButton.setOnClickListener {}
      return
    }
    val model = runBlocking { database.storyById(storyId).await() }.orElse { return@onResume }
    resumeButton.text = Html.fromHtml(str(R.string.resume_story, model.title, model.author,
        model.progress.currentChapter, model.fragment.chapterCount), Html.FROM_HTML_MODE_COMPACT)
    resumeButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_restore_black_24dp, 0, 0, 0)
    resumeButton.drawableTint(R.color.white, theme, Direction.LEFT)
    resumeButton.setOnClickListener {
      startActivity<StoryReaderActivity>(
          StoryReaderActivity.INTENT_STORY_MODEL to model as Parcelable)
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
