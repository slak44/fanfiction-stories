package slak.fanfictionstories.activities

import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import kotlinx.android.synthetic.main.activity_story_list.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.fetchAndWriteStory
import slak.fanfictionstories.utility.*

/** The list of stories the user has started reading, or has downloaded. */
class StoryListActivity : ActivityWithStatic(), ReaderResumable by ReaderResumer() {
  private lateinit var viewModel: StoryListViewModel

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    viewModel = ViewModelProviders.of(this)[StoryListViewModel::class.java]

    setContentView(R.layout.activity_story_list)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    storyListView.layoutManager = LinearLayoutManager(this)
    storyListView.createStorySwipeHelper { enteredReader(it.storyId) }
    viewModel.getCounts().observe(this) {
      toolbar.subtitle = str(R.string.x_stories_y_filtered, it.first, it.second)
    }
    viewModel.getStoryCount().observe(this) {
      if (it == 0) nothingHere.visibility = View.VISIBLE
      else nothingHere.visibility = View.GONE
    }
    storyListView.adapter = StoryAdapter(viewModel)
    if (viewModel.itemCount() == 0) viewModel.triggerDatabaseLoad()
  }

  override fun onResume() {
    super.onResume()
    updateOnResume(viewModel)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    saveInstanceState(outState)
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    restoreInstanceState(savedInstanceState)
  }

  private fun addByIdDialog() {
    AlertDialog.Builder(this)
        .setTitle(R.string.story_by_id_title)
        .setView(R.layout.dialog_add_story_view)
        .setPositiveButton(R.string.add, { dialog, _ ->
          val editText = (dialog as AlertDialog).findViewById<EditText>(R.id.dialogStoryId)!!
          val idText = editText.text.toString()
          val list = idText.split(",").map {
            try {
              it.toLong()
            } catch (nfe: NumberFormatException) {
              Snackbar.make(storyListView, str(R.string.text_is_not_id, it), Snackbar.LENGTH_LONG)
              -1L
            }
          }.filter { it != -1L }
          dialog.dismiss()
          launch(CommonPool) {
            val models = list.map { fetchAndWriteStory(it).await() }
            val modelsFetched = models.count { it !is Empty }
            if (modelsFetched > 0) viewModel.triggerDatabaseLoad()
          }
        })
        .show()
  }

  private fun statisticsDialog() = launch(UI) {
    val stories = database.getStories().await()
    var totalWords = 0L
    var passedApprox = 0L
    val totalStories = stories.size
    var storiesRead = 0
    var storiesNotStarted = 0
    stories.forEach {
      totalWords += it.fragment.wordCount
      passedApprox += it.wordsProgressedApprox()
      when {
        it.progressAsPercentage() > 98.0 -> storiesRead++
        it.progressAsPercentage() < 2.0 -> storiesNotStarted++
      }
    }
    val storiesInProgress = totalStories - storiesRead - storiesNotStarted
    AlertDialog.Builder(this@StoryListActivity)
        .setTitle(R.string.statistics)
        .setMessage(str(
            R.string.statistics_template,
            autoSuffixNumber(totalWords),
            autoSuffixNumber(passedApprox), passedApprox * 100.0 / totalWords,
            totalStories,
            storiesRead, storiesRead * 100.0 / totalStories,
            storiesInProgress, storiesInProgress * 100.0 / totalStories,
            storiesNotStarted, storiesNotStarted * 100.0 / totalStories
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
        groupByDialog(this, Prefs.storyListGroupStrategy) {
          Prefs.storyListGroupStrategy = it
          viewModel.triggerDatabaseLoad()
        }
      }
      R.id.sort -> {
        orderByDialog(this, Prefs.storyListOrderStrategy, Prefs.storyListOrderDirection) { str, dir ->
          Prefs.storyListOrderDirection = dir
          Prefs.storyListOrderStrategy = str
          viewModel.triggerDatabaseLoad()
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
