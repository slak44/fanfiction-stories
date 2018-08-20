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
import slak.fanfictionstories.data.Prefs
import slak.fanfictionstories.data.database
import slak.fanfictionstories.fetchers.fetchAndWriteStory
import slak.fanfictionstories.utility.*

/** The list of stories the user has started reading, or has downloaded. */
class StoryListActivity :
    ViewModelWorkaroundLoadingActivity<StoryListViewModel>(StoryListViewModel::class),
    IStoryEventObserver {
  override fun onStoriesChanged(t: StoryChangeEvent) {
    launch(UI) {
      if (t.kind === StoryEventKind.New) {
        viewModel.triggerDatabaseLoad()
        return@launch
      }
      t.models.forEach {
        val idx = viewModel.indexOfStoryId(it.storyId)
        if (idx == -1) return@forEach
        when (t.kind) {
          is StoryEventKind.Changed -> viewModel.updateStoryModel(idx, it)
          is StoryEventKind.Removed -> viewModel.hideStory(it)
        }
      }
    }
  }

  private lateinit var layoutManager: LinearLayoutManager

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    viewModel = ViewModelProviders.of(this)[StoryListViewModel::class.java]

    setContentView(R.layout.activity_story_list)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    layoutManager = LinearLayoutManager(this)
    storyListView.layoutManager = layoutManager
    storyListView.createStorySwipeHelper()
    storyListScroller.setRecyclerView(storyListView)
    storyListView.addOnScrollListener(storyListScroller.onScrollListener)
    viewModel.getCounts().observe(this) {
      if (it.first == StoryListViewModel.UNINITIALIZED) return@observe
      toolbar.subtitle = str(R.string.x_stories_y_filtered, it.first, it.second)
    }
    viewModel.getStoryCount().observe(this) {
      if (it == 0 && !viewModel.hasPending()) nothingHere.visibility = View.VISIBLE
      else nothingHere.visibility = View.GONE
    }
    storyListView.adapter = StoryAdapter(viewModel)
    if (viewModel.isEmpty()) {
      viewModel.triggerDatabaseLoad().invokeOnCompletion { hideLoading() }
    } else {
      hideLoading()
    }
    register()
  }

  override fun onDestroy() {
    super.onDestroy()
    unregister()
    storyListView.removeOnScrollListener(storyListScroller.onScrollListener)
  }

  private fun addByIdDialog() {
    AlertDialog.Builder(this)
        .setTitle(R.string.story_by_id_title)
        .setView(R.layout.dialog_add_story_view)
        .setPositiveButton(R.string.add) { dialog, _ ->
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
        }
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
            autoSuffixNumber(totalWords / totalStories),
            totalStories,
            storiesRead, storiesRead * 100.0 / totalStories,
            storiesInProgress, storiesInProgress * 100.0 / totalStories,
            storiesNotStarted, storiesNotStarted * 100.0 / totalStories
        ))
        .show()
  }

  private fun jumpToDialog() {
    // List of (position of title, title text) pairs
    val items = viewModel.mapIndexedNotNull { idx, item ->
      if (item !is StoryListItem.GroupTitle) return@mapIndexedNotNull null
      else Pair(idx, item.title)
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.jump_to)
        .setItems(items.map { it.second }.toTypedArray()) { _, which ->
          layoutManager.scrollToPositionWithOffset(items[which].first, 0)
        }
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
      R.id.jumpTo -> jumpToDialog()
      R.id.collapseAll -> {
        viewModel.filter { it is StoryListItem.GroupTitle && !it.isCollapsed }.forEach {
          it as StoryListItem.GroupTitle
          it.collapse(viewModel)
          val vh = storyListView.findViewHolderForItemId(it.id)
          val titleView = vh?.itemView ?: return@forEach
          titleView as GroupTitleView
          titleView.setDrawable(it.isCollapsed)
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
