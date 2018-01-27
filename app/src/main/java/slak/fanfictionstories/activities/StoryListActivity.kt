package slak.fanfictionstories.activities

import android.os.Bundle
import android.os.Parcelable
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import either.Left
import kotlinx.android.synthetic.main.activity_story_list.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.getFullStory
import slak.fanfictionstories.utility.*

class StoryListActivity : ActivityWithStatic() {
  private lateinit var adapter: StoryAdapter
  companion object {
    private const val SCROLL_STATE = "recycler_scroll_state"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_story_list)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    storyListView.layoutManager = LinearLayoutManager(this)
    StoryCardView.createRightSwipeHelper(storyListView, { intent, _ -> startActivity(intent) })
    adapter = StoryAdapter(this@StoryListActivity)
    adapter.onSizeChange = { storyCount, filteredCount ->
      toolbar.subtitle =
          resources.getString(R.string.x_stories_y_filtered, storyCount, filteredCount)
    }
    storyListView.adapter = adapter
    initializeAdapter()
  }

  private fun initializeAdapter() = launch(UI) {
    val stories = database.getStories().await()
    if (stories.isEmpty()) nothingHere.visibility = View.VISIBLE
    else nothingHere.visibility = View.GONE
    adapter.arrangeStories(stories, Prefs.arrangement())
  }

  private var layoutState: Parcelable? = null

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable(SCROLL_STATE, storyListView.layoutManager.onSaveInstanceState())
  }

  override fun onPause() {
    super.onPause()
    layoutState = storyListView.layoutManager.onSaveInstanceState()
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    layoutState = savedInstanceState.getParcelable(SCROLL_STATE)
  }

  override fun onResume() {
    super.onResume()
    initializeAdapter().invokeOnCompletion {
      storyListView.layoutManager.onRestoreInstanceState(layoutState)
    }
  }

  private fun addByIdDialog() {
    AlertDialog.Builder(this)
        .setTitle(R.string.story_by_id_title)
        .setView(R.layout.dialog_add_story_view)
        .setPositiveButton(R.string.add, { dialog, _ ->
          val editText = (dialog as AlertDialog).findViewById<EditText>(R.id.dialogStoryId)!!
          val id = editText.text.toString().toLong()
          dialog.dismiss()
          val n = Notifications(this@StoryListActivity, Notifications.Kind.DOWNLOADING)
          launch(CommonPool) {
            val model = getFullStory(this@StoryListActivity, id, n).await()
            n.cancel()
            model.ifPresent { launch(UI) { adapter.addData(Left(it)) } }
          }
        })
        .show()
  }

  private fun statisticsDialog() {
    val stories = runBlocking { database.getStories().await() }
    var totalWords = 0
    var passedApprox = 0
    val totalStories = stories.size
    var storiesRead = 0
    var storiesNotStarted = 0
    stories.forEach {
      totalWords += it.wordCount
      passedApprox += it.wordsProgressedApprox
      when {
        it.progress > 98.0 -> storiesRead++
        it.progress < 2.0 -> storiesNotStarted++
      }
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.statistics)
        .setMessage(resources.getString(
            R.string.statistics_template,
            totalWords,
            passedApprox,
            totalStories,
            storiesRead,
            totalStories - storiesRead - storiesNotStarted,
            storiesNotStarted
        ))
        .show()
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    val toTint = arrayOf(
        menu.findItem(R.id.filter),
        menu.findItem(R.id.sort),
        menu.findItem(R.id.group)
    )
    for (item in toTint) item.iconTint(R.color.white, theme)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_story_list, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.group -> {
        groupByDialog(this, Prefs.groupStrategy) {
          Prefs.groupStrategy = it
          initializeAdapter()
        }
      }
      R.id.sort -> {
        orderByDialog(this, Prefs.orderStrategy, Prefs.orderDirection) { str, dir ->
          Prefs.orderDirection = dir
          Prefs.orderStrategy = str
          initializeAdapter()
        }
      }
      R.id.addById -> addByIdDialog()
      R.id.statistics -> statisticsDialog()
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}
