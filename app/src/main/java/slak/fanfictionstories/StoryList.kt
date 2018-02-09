package slak.fanfictionstories

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.support.v4.content.ContextCompat.startActivity
import android.support.v7.widget.CardView
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.android.synthetic.main.story_component.view.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.activities.AuthorActivity
import slak.fanfictionstories.activities.StoryReaderActivity
import slak.fanfictionstories.fetchers.getFullStory
import slak.fanfictionstories.utility.*
import kotlin.coroutines.experimental.buildSequence

class StoryCardView : CardView {
  constructor(context: Context) : super(context)
  constructor(context: Context, set: AttributeSet) : super(context, set)
  constructor(context: Context, set: AttributeSet, defStyle: Int) : super(context, set, defStyle)

  var currentModel: StoryModel? = null

  companion object {
    fun createRightSwipeHelper(
        recyclerView: RecyclerView,
        openStoryReader: (intent: Intent, id: Long) -> Unit
    ): ItemTouchHelper {
      lateinit var swipeStory: ItemTouchHelper
      swipeStory = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recycler: RecyclerView?, vh: RecyclerView.ViewHolder?): Int
            = makeMovementFlags(0, ItemTouchHelper.RIGHT)

        override fun onMove(recycler: RecyclerView?, viewHolder: RecyclerView.ViewHolder?,
                            target: RecyclerView.ViewHolder?): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
          val intent = Intent(recyclerView.context, StoryReaderActivity::class.java)
          val cardView = viewHolder.itemView as StoryCardView
          intent.putExtra(StoryReaderActivity.INTENT_STORY_MODEL, cardView.currentModel!!)
          openStoryReader(intent, cardView.currentModel!!.storyIdRaw)
          // After the reader was opened, reset the translation by reattaching
          // We do this because we might go back from the reader to this activity and
          // it has to look properly
          launch(UI) {
            delay(500)
            swipeStory.attachToRecyclerView(null)
            swipeStory.attachToRecyclerView(recyclerView)
          }
        }
      })
      swipeStory.attachToRecyclerView(recyclerView)
      return swipeStory
    }

    const val DEFAULT_ELEVATION = 7F
    const val CLICK_ELEVATION = 20F
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
    categoryText.visibility = if (model.categoryRaw.isEmpty()) View.GONE else View.VISIBLE
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

    if (model.status == StoryStatus.LOCAL) addBtn.visibility = View.GONE
    if (model.status == StoryStatus.TRANSIENT) removeBtn.visibility = View.GONE

    // Reset card UI (including the measured size) to default
    storyDetails.visibility = View.GONE
    divider.visibility = View.GONE
    btnBar.visibility = View.GONE
    cardElevation = DEFAULT_ELEVATION
    addBtn.isEnabled = true
    val unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    measure(unspec, unspec)
  }

  fun setChildrenListeners(model: StoryModel, holder: RecyclerView.ViewHolder, adapter: StoryAdapter) {
    // Disable touching on the progress seek bar
    storyProgress.setOnTouchListener { _, _ -> true }
    storyMainContent.setOnClickListener {
      cardElevation = if (cardElevation == DEFAULT_ELEVATION) CLICK_ELEVATION else DEFAULT_ELEVATION
      if (storyDetails.visibility == View.GONE) {
        storyDetails.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE
        btnBar.visibility = View.VISIBLE
      } else {
        storyDetails.visibility = View.GONE
        divider.visibility = View.GONE
        btnBar.visibility = View.GONE
      }
    }
    removeBtn.setOnClickListener {
      // Even though we have a model, fetch it from db to make sure there are no inconsistencies
      val dbModel = context.database.storyById(model.storyIdRaw)
      if (!dbModel.isPresent) {
        errorDialog(R.string.storyid_does_not_exist, R.string.storyid_does_not_exist_tip)
        return@setOnClickListener
      }
      // Hide card
      adapter.hideStory(holder.adapterPosition, model)
      undoableAction(holder.itemView, R.string.removed_story, {
        adapter.undoHideStory(model)
      }) {
        deleteLocalStory(context, model.storyIdRaw).join()
        context.database.writableDatabase
            .delete("stories", "storyId = ?", arrayOf(model.storyIdRaw.toString()))
      }
    }
    addBtn.setOnClickListener {
      addBtn.isEnabled = false
      addBtn.text = str(R.string.adding___)
      launch(UI) {
        val newModel = getFullStory(this@StoryCardView.context, model.storyIdRaw).await()
        if (newModel.isPresent) {
          addBtn.visibility = View.GONE
        } else {
          addBtn.visibility = View.VISIBLE
          addBtn.isEnabled = true
          addBtn.text = str(R.string.add)
        }
      }
    }
    authorBtn.setOnClickListener {
      val intent = Intent(context, AuthorActivity::class.java)
      intent.putExtra(AuthorActivity.INTENT_AUTHOR_ID, model.authorIdRaw)
      intent.putExtra(AuthorActivity.INTENT_AUTHOR_NAME, model.authorRaw)
      startActivity(context, intent, null)
    }
  }
}

class StoryGroupTitle : TextView {
  constructor(context: Context) : super(context)
  constructor(context: Context, set: AttributeSet) : super(context, set)
  constructor(context: Context, set: AttributeSet, defStyle: Int) : super(context, set, defStyle)

  companion object {
    private val borderHeight =
        Static.res.getDimensionPixelSize(R.dimen.story_list_title_divider_height)
    private val bottomMargin = Static.res.getDimensionPixelSize(R.dimen.story_list_margin)
  }

  init {
    setPadding(0, 0, 0,
        resources.getDimensionPixelSize(R.dimen.story_list_title_underline_margin))
  }

  private val border: Paint by lazy {
    val border = Paint()
    border.style = Paint.Style.STROKE
    border.color = Static.res.getColor(android.R.color.secondary_text_dark, this.context.theme)
    border.strokeWidth = borderHeight.toFloat()
    border
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    (layoutParams as ViewGroup.MarginLayoutParams).setMargins(0, 0, 0, bottomMargin)
    requestLayout()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val width = (parent as RecyclerView).measuredWidth.toFloat()
    canvas.drawLine(0F, measuredHeight.toFloat(), width, measuredHeight.toFloat(), border)
  }
}

private val counter = buildSequence {
  var c = 0
  while (true) yield(c++)
}
class Loading {
  val id = counter.take(1).first()
}
typealias StoryAdapterItem = Either3<StoryModel, String, Loading>
class StoryAdapter(val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  init { setHasStableIds(true) }

  class StoryViewHolder(val view: StoryCardView) : RecyclerView.ViewHolder(view)
  class TitleViewHolder(val view: StoryGroupTitle) : RecyclerView.ViewHolder(view)
  class ProgressBarHolder(val view: ProgressBar) : RecyclerView.ViewHolder(view)

  /**
   * Stores working data (stories and group titles).
   */
  private val data: MutableList<StoryAdapterItem> = mutableListOf()

  /**
   * Get an immutable copy of [data], for serialization purposes.
   */
  fun getData(): List<StoryAdapterItem> = data.toList()

  /**
   * Clear adapter data, including any pending items.
   * @see data
   * @see pendingItems
   */
  fun clearData() {
    val dataSize = data.size
    data.clear()
    notifyItemRangeRemoved(0, dataSize)
    pendingItems.clear()
    onSizeChange(0, 0)
  }

  fun addData(item: StoryAdapterItem) {
    data.add(item)
    notifyItemInserted(data.size - 1)
    if (item is T1) {
      storyCount++
      onSizeChange(storyCount, filteredCount)
    }
  }

  fun addData(items: List<StoryAdapterItem>) {
    data.addAll(items)
    notifyItemRangeInserted(data.size, items.size)
    val newStories = items.count { it is T1 }
    if (newStories > 0) {
      storyCount += newStories
      onSizeChange(storyCount, filteredCount)
    }
  }

  fun addDeferredData(deferredList: Deferred<List<StoryAdapterItem>>) = launch(UI) {
    addData(T3(Loading()))
    val loaderIdx = data.size - 1
    addData(deferredList.await())
    data.removeAt(loaderIdx)
    notifyItemRemoved(loaderIdx)
  }

  /**
   * Pending items are items that have been hidden with [hideStory], and can be reinstated using
   * [undoHideStory]. This maps a [StoryModel] to its current adapter position.
   */
  private val pendingItems = mutableMapOf<StoryModel, Int>()

  /**
   * Remove the story from the adapter and keep track of its data.
   * @param position adapter position of story view
   * @see pendingItems
   * @see undoHideStory
   */
  fun hideStory(position: Int, model: StoryModel) {
    if (!data.contains(T1(model))) throw IllegalArgumentException("Model not part of the adapter")
    pendingItems[model] = position
    data.removeAt(position)
    notifyItemRemoved(position)
    storyCount--
    onSizeChange(storyCount, filteredCount)
  }

  /**
   * Reverses the effects of [hideStory].
   * @param model a model that was previously passed to [hideStory]
   * @see pendingItems
   * @see hideStory
   */
  fun undoHideStory(model: StoryModel) {
    val pos = pendingItems[model] ?: throw IllegalArgumentException("This model was never hidden")
    data.add(pos, T1(model))
    pendingItems.remove(model)
    notifyItemInserted(pos)
    storyCount++
    onSizeChange(storyCount, filteredCount)
  }

  /**
   * Called when the story count changes.
   */
  var onSizeChange: (storyCount: Int, filteredCount: Int) -> Unit = { _, _ -> }

  /**
   * How many stories have been filtered in the latest [arrangeStories] call.
   */
  private var filteredCount = 0
  /**
   * How many stories were passed to the latest [arrangeStories] call.
   */
  private var storyCount = 0

  /**
   * Filter, group, sort [stories] according to the [arrangement], and put the results in [data].
   */
  fun arrangeStories(stories: List<StoryModel>, arrangement: Arrangement) {
    // Ignore currently pending stories, the user might have rearranged before the db was updated
    val storiesNotPending = stories.filter { story ->
      pendingItems.keys.find { it.storyIdRaw == story.storyIdRaw } == null
    }
    clearData()
    val toData = storiesNotPending.filter { true }.toMutableList() // FIXME filter
    groupStories(toData, arrangement.groupStrategy).toSortedMap().forEach {
      val ordered = orderStories(it.value, arrangement.orderStrategy, arrangement.orderDirection)
      addData(listOf(T2(it.key), *ordered.map { T1(it) }.toTypedArray()))
    }
    filteredCount = storiesNotPending.size - toData.size
    storyCount = storiesNotPending.size
    onSizeChange(storyCount, filteredCount)
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    data[position].fold(
        { model ->
          val view = (holder as StoryViewHolder).view
          view.loadFromModel(model)
          view.setChildrenListeners(model, holder, this)
        },
        { title -> (holder as TitleViewHolder).view.text = title },
        { loading -> (holder as ProgressBarHolder).view.id = loading.id }
    )
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val inflater = LayoutInflater.from(parent.context)
    val view: View = when (viewType) {
      0 -> inflater.inflate(R.layout.story_component, parent, false)
      1 -> StoryGroupTitle(context)
      2 -> inflater.inflate(R.layout.loading_circle_indeterminate, parent, false)
      else -> throw IllegalStateException("getItemViewType out of sync with onCreateViewHolder")
    }
    view.alpha = 0F
    view.isDrawingCacheEnabled = true
    val fadeIn = ObjectAnimator.ofFloat(view, "alpha", 0.3F, 1F)
    fadeIn.startDelay = 50
    fadeIn.start()
    return when (viewType) {
      0 -> StoryViewHolder(view as StoryCardView)
      1 -> TitleViewHolder(view as StoryGroupTitle)
      2 -> ProgressBarHolder(view as ProgressBar)
      else -> throw IllegalStateException("getItemViewType out of sync with onCreateViewHolder")
    }
  }

  override fun getItemCount(): Int = data.size
  override fun getItemId(position: Int): Long = data[position].fold(
      { model -> model.storyIdRaw + (1 shl 15) },
      { title -> title.hashCode().toLong() + (2 shl 15) },
      { loading -> loading.id.toLong() + (3 shl 15) }
  )
  override fun getItemViewType(position: Int): Int = data[position].fold({ 0 }, { 1 }, { 2 })
}
