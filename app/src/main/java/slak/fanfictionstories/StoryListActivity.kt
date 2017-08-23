package slak.fanfictionstories

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.CardView
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.activity_story_list.*
import kotlinx.android.synthetic.main.content_story_list.*
import kotlinx.android.synthetic.main.story_component.view.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import org.jetbrains.anko.db.parseSingle
import org.jetbrains.anko.db.select
import java.util.*

class StoryCardView : CardView {
  constructor(context: Context) : super(context)
  constructor(context: Context, set: AttributeSet) : super(context, set)
  constructor(context: Context, set: AttributeSet, defStyle: Int) : super(context, set, defStyle)

  var currentModel: StoryModel? = null

  companion object {
    fun createRightSwipeHelper(recyclerView: RecyclerView, a: StoryListActivity): ItemTouchHelper {
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
          val intent = Intent(recyclerView.context, StoryReaderActivity::class.java)
          val cardView = viewHolder.itemView as StoryCardView
          intent.putExtra(StoryReaderActivity.INTENT_STORY_MODEL, cardView.currentModel!!)
          a.openStoryReader(intent, cardView.storyId.get())
          // After the reader was opened, reset the translation by reattaching
          // We do this because we might go back from the reader to this activity and
          // it has to look properly
          launch(UI) {
            delay(2500)
            swipeStory!!.attachToRecyclerView(null)
            swipeStory!!.attachToRecyclerView(recyclerView)
          }
        }
      })
      swipeStory.attachToRecyclerView(recyclerView)
      return swipeStory
    }
  }

  var storyId: Optional<Long> = Optional.empty()

  override fun onCreateDrawableState(extraSpace: Int): IntArray {
    // Disable touching on the progress seek bar
    storyProgress.setOnTouchListener { _, _ -> true }
    storyMainContent.setOnClickListener {
      // FIXME do we want this elevation like this?
      cardElevation = if (cardElevation == 7F) 20F else 7F
      // This gets animated automatically
      if (storyDetails.visibility == View.GONE) storyDetails.visibility = View.VISIBLE
      else storyDetails.visibility = View.GONE
    }
    addBtn.setOnClickListener {
      addBtn.isEnabled = false
      addBtn.text = context.resources.getString(R.string.adding___)
      if (!storyId.isPresent)
        throw IllegalStateException("StoryCardView clicked, but data not filled by model")
      val n = Notifications(this@StoryCardView.context, Notifications.Kind.DOWNLOADING)
      launch(CommonPool) {
        getFullStory(this@StoryCardView.context, storyId.get(), n).await()
        n.cancel()
      }
    }
    return super.onCreateDrawableState(extraSpace)
  }

  fun loadFromModel(model: StoryModel) {
    currentModel = model
    // Unexpanded view
    titleText.text = model.title
    authorText.text = model.author
    canonText.text = model.canon
    wordsText.text = model.words
    storyProgress.progress = Math.round(model.progress).toInt()
    isCompletedText.visibility = if (model.isCompleted) View.VISIBLE else View.INVISIBLE
    // Detail view
    languageText.text = model.language
    ratingText.text = model.rating
    summaryText.text = model.summary
    chaptersText.text = model.chapters
    genresText.text = model.genres
    charactersText.text = model.characters
    categoryText.text = model.category
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
    storyIdText.text = model.storyId

    storyId = Optional.of(model.storyIdRaw)
    if (model.status == StoryStatus.LOCAL) addBtn.visibility = View.INVISIBLE
  }
}

class StoryAdapter private constructor(val context: Context) : RecyclerView.Adapter<StoryAdapter.ViewHolder>() {
  class ViewHolder(val view: StoryCardView) : RecyclerView.ViewHolder(view)

  companion object {
    fun create(context: Context): Deferred<StoryAdapter> = async(CommonPool) {
      val adapter = StoryAdapter(context)
      adapter.initData().await()
      return@async adapter
    }
  }

  lateinit var data: MutableList<StoryModel>

  fun initData(): Deferred<Unit> = async(CommonPool) {
    data = this@StoryAdapter.context.database.getStories().await().toMutableList()
    launch(UI) {
      notifyDataSetChanged()
      notifyItemRangeChanged(0, itemCount)
    }
    return@async
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    holder.view.loadFromModel(data[position])
  }

  private var addedOrder: Long = 0

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val holder = ViewHolder(LayoutInflater.from(parent.context)
        .inflate(R.layout.story_component, parent, false) as StoryCardView)
    holder.view.alpha = 0f
    val fadeIn = ObjectAnimator.ofFloat(holder.view, "alpha", 0.3f, 1f)
    fadeIn.startDelay = Math.min(addedOrder * 50, 250)
    fadeIn.start()
    addedOrder++
    return holder
  }

  override fun getItemCount(): Int = data.size
  override fun getItemId(position: Int): Long = data[position]._id.get()
}

class StoryListActivity : AppCompatActivity() {
  private var adapter: StoryAdapter? = null
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_story_list)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    storyListView.layoutManager = LinearLayoutManager(this)
    StoryCardView.createRightSwipeHelper(storyListView, this)
    launch(CommonPool) {
      adapter = StoryAdapter.create(this@StoryListActivity).await()
      launch(UI) {
        storyListView.adapter = adapter
        if (adapter!!.itemCount == 0) nothingHere.visibility = View.VISIBLE
      }
    }
  }

  private var lastStoryId: Optional<Long> = Optional.empty()

  fun openStoryReader(intent: Intent, storyId: Long) {
    lastStoryId = Optional.of(storyId)
    startActivity(intent)
  }

  override fun onResume() {
    super.onResume()
    launch(CommonPool) {
      if (lastStoryId.isPresent && adapter != null) database.use {
        val newModel = select("stories")
            .whereSimple("storyId = ?", lastStoryId.get().toString())
            .exec { parseSingle(StoryModel.dbParser) }
        val idx = adapter!!.data.indexOfFirst { it.storyIdRaw == lastStoryId.get() }
        adapter!!.data[idx] = newModel
        launch(UI) {
          adapter!!.notifyDataSetChanged()
          adapter!!.notifyItemChanged(idx)
        }
      }
    }
  }
}
