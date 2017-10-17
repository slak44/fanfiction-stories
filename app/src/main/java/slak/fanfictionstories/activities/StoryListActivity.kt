package slak.fanfictionstories.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Switch
import kotlinx.android.synthetic.main.activity_story_list.*
import kotlinx.android.synthetic.main.content_story_list.*
import kotlinx.android.synthetic.main.dialog_add_story_view.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.db.parseSingle
import org.jetbrains.anko.db.select
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.getFullStory
import slak.fanfictionstories.utility.Notifications
import slak.fanfictionstories.utility.database
import slak.fanfictionstories.utility.iconTint
import java.util.*

class StoryListActivity : AppCompatActivity() {
  private var adapter: StoryAdapter? = null
  private var lastStoryId: Optional<Long> = Optional.empty()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_story_list)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    storyListView.layoutManager = LinearLayoutManager(this)
    StoryCardView.createRightSwipeHelper(storyListView, { intent, storyId ->
      lastStoryId = Optional.of(storyId)
      startActivity(intent)
    })
    launch(CommonPool) {
      // FIXME read the stored strategy from somewhere and set it
      adapter = StoryAdapter(this@StoryListActivity)
      adapter!!.initDataFromDb().await()
      launch(UI) { storyListView.adapter = adapter }
    }
  }

  override fun onResume() {
    super.onResume()
    if (adapter != null && adapter!!.stories.size == 0) nothingHere.visibility = View.VISIBLE
    launch(CommonPool) {
      if (lastStoryId.isPresent && adapter != null) database.use {
        val newModel = select("stories")
            .whereSimple("storyId = ?", lastStoryId.get().toString())
            .exec { parseSingle(StoryModel.dbParser) }
        val idx = adapter!!.stories.indexOfFirst { it.storyIdRaw == lastStoryId.get() }
        adapter!!.stories[idx] = newModel
        adapter!!.initDataFromDb()
      }
    }
  }

  private fun addByIdDialog() {
    AlertDialog.Builder(this)
        .setTitle(R.string.story_by_id_title)
        .setView(R.layout.dialog_add_story_view)
        .setPositiveButton(R.string.add, { dialog, _ ->
          dialog.dismiss()
          val id = dialogStoryId.text.toString().toLong()
          val n = Notifications(this@StoryListActivity, Notifications.Kind.DOWNLOADING)
          launch(CommonPool) {
            val model = getFullStory(this@StoryListActivity, id, n).await()
            n.cancel()
            if (model.isPresent) {
              adapter!!.stories.add(model.get())
              adapter!!.initDataFromDb()
            }
          }
        })
        .show()
  }

  private fun groupByDialog() {
    // FIXME get current strategy and put a tick next to the respective item
    AlertDialog.Builder(this)
        .setTitle(R.string.group_by)
        .setItems(GroupStrategy.values().map { it.toUIString() }.toTypedArray(), { dialog, which ->
          dialog.dismiss()
          // FIXME store the chosen group strategy somewhere
          adapter!!.groupStrategy = GroupStrategy.values()[which]
          adapter!!.initDataFromDb()
        }).show()
  }

  @SuppressLint("InflateParams")
  private fun orderByDialog() {
    // FIXME get current strategy + direction and put a tick next to the respective item
    val layout = LayoutInflater.from(this)
        .inflate(R.layout.dialog_group_by_switch, null, false)
    val switch = layout.findViewById<Switch>(R.id.reverseOrderSw) as Switch
    AlertDialog.Builder(this)
        .setTitle(R.string.sort_by)
        .setView(layout)
        .setItems(OrderStrategy.values().map { it.toUIString() }.toTypedArray(), { dialog, which ->
          dialog.dismiss()
          // FIXME store the chosen order strategy somewhere
          adapter!!.orderStrategy = OrderStrategy.values()[which]
          adapter!!.orderDirection =
              if (switch.isChecked) OrderDirection.ASC else OrderDirection.DESC
          adapter!!.initDataFromDb()
        })
        .show()
  }

  private fun statisticsDialog() {
    var totalWords = 0
    var passedApprox = 0
    val totalStories = adapter!!.stories.size
    var storiesRead = 0
    var storiesNotStarted = 0
    adapter!!.stories.forEach {
      totalWords += it.wordCount
      println(it.wordsProgressedApprox)
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
