package slak.fanfictionstories.activities

import android.os.Bundle
import android.support.v7.widget.LinearLayoutManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import kotlinx.android.synthetic.main.activity_story_queue.*
import kotlinx.android.synthetic.main.activity_story_queue.toolbar
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import slak.fanfictionstories.*
import slak.fanfictionstories.data.database
import slak.fanfictionstories.utility.*

class StoryQueueActivity : CoroutineScopeActivity() {
  private lateinit var viewModel: StoryListViewModel

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    viewModel = obtainViewModel()

    setContentView(R.layout.activity_story_queue)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    val layoutManager = LinearLayoutManager(this)
    storyQueueList.layoutManager = layoutManager
    storyQueueList.createStorySwipeHelper()

    storyQueueList.adapter = StoryAdapter(viewModel)
    if (viewModel.isEmpty()) {
      viewModel.addSuspendingItems {
        val ids = database.getStoryQueue()
        if (ids.isEmpty()) setupEmptyQueueText()
        database
            .storiesById(ids.map { it.first })
            .await()
            .sortedBy { model -> ids.first { it.first == model.storyId }.second }
            .map { StoryListItem.StoryCardData(it) }
      }
    }

    viewModel.defaultStoryListObserver.register()
  }

  private fun setupEmptyQueueText() {
    val ss = SpannableString(str(R.string.queue_empty))
    val dw = getDrawable(R.drawable.ic_more_vert_black_24dp)!!
    val sz = queueEmpty.textSize.toInt().coerceAtLeast(resources.px(R.dimen.story_queue_empty_icon_size))
    dw.setBounds(0, 0, sz, sz)
    dw.setTint(getColor(R.color.white))
    ss.setSpan(ImageSpan(dw, ImageSpan.ALIGN_BOTTOM), ss.length - 3, ss.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    queueEmpty.text = ss
    queueEmpty.visibility = View.VISIBLE
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    menu.findItem(R.id.clearQueue).iconTint(R.color.white, theme)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_story_queue, menu)
    return true
  }

  override fun onBackPressed() {
    viewModel.clearData()
    super.onBackPressed()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.clearQueue -> launch(Main) {
        database.clearQueue()
        viewModel.clearData()
        setupEmptyQueueText()
      }
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}
