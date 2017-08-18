package slak.fanfictionstories

import android.content.Context
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.CardView
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.activity_story_list.*
import kotlinx.android.synthetic.main.content_story_list.*
import kotlinx.android.synthetic.main.story_component.view.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import android.animation.ObjectAnimator
import android.support.v7.widget.helper.ItemTouchHelper
import android.util.Log
import java.util.*

class StoryCardView : CardView {
  constructor(context: Context) : super(context)
  constructor(context: Context, set: AttributeSet) : super(context, set)
  constructor(context: Context, set: AttributeSet, defStyle: Int) : super(context, set, defStyle)

  companion object {
    fun createRightSwipeHelper(recyclerView: RecyclerView): ItemTouchHelper {
      var swipeStory: ItemTouchHelper? = null
      swipeStory = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recycler: RecyclerView?,
                                      viewHolder: RecyclerView.ViewHolder?): Int {
          return makeMovementFlags(0, ItemTouchHelper.RIGHT)
        }

        override fun onMove(recycler: RecyclerView?, viewHolder: RecyclerView.ViewHolder?,
                            target: RecyclerView.ViewHolder?): Boolean {
          return false
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
          Log.d("StoryListActivity", "swiped thing right")
          // FIXME open story reader here
          // After the reader was opened, reset the translation by reattaching
          // We do this because we might go back from the reader to this activity and
          // it has to look properly
          swipeStory!!.attachToRecyclerView(null)
          swipeStory!!.attachToRecyclerView(recyclerView)
        }
      })
      swipeStory.attachToRecyclerView(recyclerView)
      return swipeStory
    }
  }

  var storyid: Optional<Long> = Optional.empty()

  override fun onCreateDrawableState(extraSpace: Int): IntArray {
    // Disable touching on the progress seek bar
    storyProgress.setOnTouchListener { _, _ -> true }
    storyMainContent.setOnClickListener {
      // FIXME do we want this elevation like this?
      if (cardElevation == 7F) cardElevation = 20F
      else cardElevation = 7F
      // This gets animated automatically
      if (storyDetails.visibility == View.GONE) storyDetails.visibility = View.VISIBLE
      else storyDetails.visibility = View.GONE
    }
    addBtn.setOnClickListener {
      addBtn.isEnabled = false
      addBtn.text = context.resources.getString(R.string.adding___)
      // FIXME update downloading notification here
      if (!storyid.isPresent)
        throw IllegalStateException("StoryCardView clicked, but data not filled by model")
      StoryFetcher(storyid.get(), context)
    }
    return super.onCreateDrawableState(extraSpace)
  }

  fun loadFromModel(model: StoryModel) {
    // Unexpanded view
    titleText.text = model.title
    authorText.text = model.author
    canonText.text = model.canon
    wordsText.text = model.words
    storyProgress.progress = model.progress
    isCompletedText.visibility = if (model.isCompleted) View.VISIBLE else View.INVISIBLE
    // Detail view
    languageText.text = model.language
    ratingText.text = model.rating
    summaryText.text = model.summary
    chaptersText.text = model.chapters
    genresText.text = model.genres
    charactersText.text = model.characters
    updateDateText.text = model.updateDate
    if (model.updateDateSeconds == 0L) {
      // Do this instead of View.GONE or View.INVISIBLE because we want
      // its margins, but not its height
      updateDateText.height = 0
      updateDateText.requestLayout()
    }
    publishDateText.text = model.publishDate
    reviewsText.text = model.reviews
    favoritesText.text = model.favorites
    followsText.text = model.follows
    storyidText.text = model.storyid

    storyid = Optional.of(model.storyidRaw)
    if (model.status == StoryStatus.LOCAL) addBtn.visibility = View.INVISIBLE
  }
}

class StoryAdapter private constructor (val context: Context) : RecyclerView.Adapter<StoryAdapter.ViewHolder>() {
  class ViewHolder(val view: StoryCardView) : RecyclerView.ViewHolder(view)

  companion object {
    fun create(context: Context): Deferred<StoryAdapter> = async(CommonPool) {
      val adapter = StoryAdapter(context)
      adapter.data = context.database.getStories(context).await()
      return@async adapter
    }
  }

  lateinit var data: List<StoryModel>

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    holder.view.loadFromModel(data[position])
  }

  private var addedOrder: Long = 0

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val holder = ViewHolder(LayoutInflater.from(parent.context)
        .inflate(R.layout.story_component, parent, false) as StoryCardView)
    holder.view.alpha = 0f
    val fadeIn = ObjectAnimator.ofFloat(holder.view, "alpha", 0.3f, 1f)
    fadeIn.startDelay = addedOrder * 50
    fadeIn.start()
    addedOrder++
    return holder
  }

  override fun getItemCount(): Int = data.size
  override fun getItemId(position: Int): Long = data[position]._id.get()
}

class StoryListActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_story_list)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    storyListView.layoutManager = LinearLayoutManager(this)
    StoryCardView.createRightSwipeHelper(storyListView)
    launch(CommonPool) {
      val adapter = StoryAdapter.create(this@StoryListActivity).await()
      launch(UI) {
        storyListView.adapter = adapter
        if (adapter.itemCount == 0) nothingHere.visibility = View.VISIBLE
      }
    }
  }
}
