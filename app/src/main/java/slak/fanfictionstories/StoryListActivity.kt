package slak.fanfictionstories

import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import kotlinx.android.synthetic.main.activity_story_list.*
import kotlinx.android.synthetic.main.content_story_list.*
import kotlinx.android.synthetic.main.dialog_add_story_view.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import org.jetbrains.anko.db.parseSingle
import org.jetbrains.anko.db.select
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
      // FIXME read the stored strategy from somewhere
      adapter = StoryAdapter.create(this@StoryListActivity, GroupStrategy.NONE).await()
      launch(UI) { storyListView.adapter = adapter }
    }
  }

  override fun onResume() {
    super.onResume()
    if (adapter != null && adapter!!.itemCount == 0) nothingHere.visibility = View.VISIBLE
    launch(CommonPool) {
      if (lastStoryId.isPresent && adapter != null) database.use {
        val newModel = select("stories")
            .whereSimple("storyId = ?", lastStoryId.get().toString())
            .exec { parseSingle(StoryModel.dbParser) }
        val idx = adapter!!.stories.indexOfFirst { it.storyIdRaw == lastStoryId.get() }
        adapter!!.stories[idx] = newModel
        adapter!!.initData()
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
              adapter!!.initData()
            }
          }
        })
        .show()
  }

  private fun groupByDialog() {
    AlertDialog.Builder(this)
        .setTitle(R.string.group_by)
        .setItems(GroupStrategy.values().map { it.toUIString() }.toTypedArray(), { dialog, which ->
          dialog.dismiss()
          // FIXME store the chosen group strategy somewhere
          adapter!!.groupStrategy = GroupStrategy.values()[which]
          adapter!!.initData()
        }).show()
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
    // Inflate the menu; this adds items to the action bar if it is present.
    menuInflater.inflate(R.menu.menu_story_list, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    return when (item.itemId) {
      R.id.addById -> {
        addByIdDialog()
        return true
      }
      R.id.group -> {
        groupByDialog()
        return true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }
}
