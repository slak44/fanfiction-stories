package slak.fanfictionstories.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Parcelable
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Switch
import either.Left
import either.fold
import kotlinx.android.synthetic.main.activity_story_list.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.db.select
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.getFullStory
import slak.fanfictionstories.utility.*
import slak.fanfictionstories.utility.Prefs.LIST_GROUP_STRATEGY
import slak.fanfictionstories.utility.Prefs.LIST_ORDER_IS_REVERSE
import slak.fanfictionstories.utility.Prefs.LIST_ORDER_STRATEGY
import java.util.*

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
    adapter.groupStrategy = GroupStrategy[Static.prefs.getInt(LIST_GROUP_STRATEGY, GroupStrategy.NONE.ordinal)]
    adapter.orderStrategy = OrderStrategy[Static.prefs.getInt(LIST_ORDER_STRATEGY, OrderStrategy.TITLE_ALPHABETIC.ordinal)]
    adapter.orderDirection = if (Static.prefs.getInt(LIST_ORDER_IS_REVERSE, 1) == 1)
      OrderDirection.ASC else OrderDirection.DESC
    storyListView.adapter = adapter
    launch(CommonPool) {
      adapter.setStories(database.getStories().await().toMutableList())
      launch(UI) {
        arrangeStories()
        if (adapter.getStories().isEmpty()) nothingHere.visibility = View.VISIBLE
      }
    }
  }

  /**
   * Wrap [StoryAdapter.arrangeStories] to also set the subtitle on this activity.
   */
  private fun arrangeStories() {
    adapter.arrangeStories()
    toolbar.subtitle = resources.getString(R.string.x_stories_y_filtered,
        adapter.storyCount, adapter.filteredCount)
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
    launch(UI) {
      adapter.setStories(database.getStories().await().toMutableList())
      arrangeStories()
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

  private fun groupByDialog() {
    val strategy = Static.prefs.getInt(LIST_GROUP_STRATEGY, GroupStrategy.NONE.ordinal)
    AlertDialog.Builder(this)
        .setTitle(R.string.group_by)
        .setSingleChoiceItems(GroupStrategy.uiItems(), strategy, { dialog, which ->
          dialog.dismiss()
          usePrefs { it.putInt(LIST_GROUP_STRATEGY, which) }
          adapter.groupStrategy = GroupStrategy.values()[which]
          arrangeStories()
        }).show()
  }

  @SuppressLint("InflateParams")
  private fun orderByDialog() {
    val strategy = Static.prefs.getInt(LIST_ORDER_STRATEGY, OrderStrategy.TITLE_ALPHABETIC.ordinal)
    val isReverse = Static.prefs.getInt(LIST_ORDER_IS_REVERSE, 0)
    val layout = LayoutInflater.from(this)
        .inflate(R.layout.dialog_order_by_switch, null, false)
    val switch = layout.findViewById<Switch>(R.id.reverseOrderSw) as Switch
    if (isReverse == 1) switch.toggle()
    AlertDialog.Builder(this)
        .setTitle(R.string.sort_by)
        .setView(layout)
        .setSingleChoiceItems(OrderStrategy.uiItems(), strategy, { dialog, which ->
          dialog.dismiss()
          usePrefs {
            it.putInt(LIST_ORDER_STRATEGY, which)
            it.putInt(LIST_ORDER_IS_REVERSE, if (switch.isChecked) 1 else 0)
          }
          adapter.orderStrategy = OrderStrategy.values()[which]
          adapter.orderDirection =
              if (switch.isChecked) OrderDirection.ASC else OrderDirection.DESC
          arrangeStories()
        })
        .show()
  }

  private fun statisticsDialog() {
    var totalWords = 0
    var passedApprox = 0
    val totalStories = adapter.getStories().size
    var storiesRead = 0
    var storiesNotStarted = 0
    adapter.getStories().forEach {
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
    for (item in toTint) item.iconTint(android.R.color.white, theme)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_story_list, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
    R.id.addById -> {
      addByIdDialog()
      true
    }
    R.id.group -> {
      groupByDialog()
      true
    }
    R.id.sort -> {
      orderByDialog()
      true
    }
    R.id.statistics -> {
      statisticsDialog()
      true
    }
    else -> super.onOptionsItemSelected(item)
  }
}
